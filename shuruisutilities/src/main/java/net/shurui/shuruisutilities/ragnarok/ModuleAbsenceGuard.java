package net.shurui.shuruisutilities.ragnarok;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.MissingMappingsEvent;

import net.shurui.dev.sdu.api.ModulePresence;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.shurui.shuruisutilities.world.space.SpaceAbsenceGuard;

/**
 * General "a module jar is missing from an existing world" guard for the 2.0 core-plus-modules split.
 *
 * <p><b>The problem this exists for.</b> Every module (Dungeons, Raids, Tournaments, Space) registers its blocks,
 * items, entity types and block entity types in the {@code dmz_ragnarok} namespace, but from its OWN module jar.
 * Boot the modular CORE alone (or core plus only some modules) on a world that used to run the fat jar, and
 * Forge 1.20.1 finds those ids in the world's saved registry snapshot but not in the running registries. Verified
 * on a dedicated server (2026-09-23): Forge logs {@code There are N missing entries in this save} (N was 65 on the
 * real world), makes a world backup, keeps the ids RESERVED in level.dat, and BOOTS. It does not stop. But the
 * TYPES are not registered, so vanilla cannot deserialize the content: as each chunk holding a missing entity
 * loads it DROPS that entity and rewrites the chunk without it, and every missing BLOCK becomes AIR (silent build
 * damage). So leaving any module jar out of an existing world is silent, per-chunk, irreversible data loss. This
 * guard turns it into a deliberate, up-front refusal, exactly as {@link SpaceAbsenceGuard} already did for Space.
 *
 * <p><b>How it detects the loss without loading a chunk.</b> A FORGE-bus {@link MissingMappingsEvent} listener
 * (below) fires during world LOAD, once per persisted registry that has missing entries, BEFORE any server
 * lifecycle event. For each missing id it records the ones this suite does NOT heal: an id in the merged
 * ({@code dmz_ragnarok}) or a pre-merge namespace that {@link NamespaceRemap} neither remaps (its merged form is
 * absent from the running registry, so there is no target) nor ignores (it is not a retired placeholder), and is
 * not the rgnpc path-rename owned by {@code RgNpcEntities.Remap}. Those recorded ids are precisely a removed
 * module's registrations. The recorder only READS the mappings, it never calls remap/ignore/fail, so it cannot
 * interfere with {@link NamespaceRemap} regardless of listener order; it also does its own merged-form
 * containsKey test, so it does not depend on running after the remap either.
 *
 * <p><b>Retired placeholders never trigger it.</b> The 232 removed placeholder items and records
 * ({@link NamespaceRemap#isRetired}) are expected-missing and are excluded, so a fat/all boot on any world, and a
 * core boot on a world that only carries retired ids, sees no refusal.
 *
 * <p><b>The refusal.</b> At {@link ServerAboutToStartEvent} (from {@code ShuruisUtilities.serverPreInit}, the same
 * point {@link SpaceAbsenceGuard} used) {@link #checkOrRefuse(MinecraftServer)} groups the recorded ids by the
 * module that most likely provides them and, if any exist, refuses to start with ONE message naming the modules
 * and their jars. It halts with {@link Runtime#halt(int)} (never a throw) for the SAME reason SpaceAbsenceGuard
 * does: a thrown refusal lets vanilla's runServer finally-block run stopServer(), which re-saves the world. Halt
 * runs no finally blocks, so nothing further is written. It refuses BEFORE any chunk is read, so the region,
 * entity and data files stay byte-for-byte identical (level.dat is the one file Forge already rewrote during
 * LOAD, before any event, non-destructively, with a backup).
 *
 * <p><b>Overrides.</b> {@code -Ddmzr.allowContentRemoval=true} boots anyway and accepts losing ALL the listed
 * content. The older {@code -Ddmzr.allowSpaceRemoval=true} is kept for backward compatibility: it boots only when
 * the ONLY content at risk is Space (its historical meaning), and does nothing when a non-Space module is also
 * missing, because it never authorised dropping dungeon/raid/tournament content.
 *
 * <p>Space is folded in: the recorder catches the Space entity types, and {@link #checkOrRefuse} also runs
 * {@link SpaceAbsenceGuard#detectSpaceDataFailSoft(MinecraftServer)} so a world with Space SavedData or region
 * files but no loaded Space entity (which would leave no missing registry id) is still caught.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ModuleAbsenceGuard
{
    private ModuleAbsenceGuard()
    {
    }

    /** Operator override: accept that ALL the missing modules' content will be dropped, and boot anyway. */
    public static final String OVERRIDE_PROPERTY = "dmzr.allowContentRemoval";

    /** Legacy Space-only override, kept working: boots only when the sole content at risk is Space. */
    public static final String SPACE_OVERRIDE_PROPERTY = SpaceAbsenceGuard.OVERRIDE_PROPERTY; // "dmzr.allowSpaceRemoval"

    /** The suite modules a missing id can be attributed to, plus a fall-back for anything unrecognised. */
    private enum Module
    {
        DUNGEONS("Dungeons", ModulePresence.DUNGEONS),
        RAIDS("Raids", ModulePresence.RAIDS),
        TOURNAMENTS("Tournaments", ModulePresence.TOURNAMENTS),
        SPACE("Space", ModulePresence.SPACE),
        UNKNOWN("Unknown module", null);

        final String label;
        final String jar; // the module jar / container id to install, or null for UNKNOWN

        Module(String label, String jar)
        {
            this.label = label;
            this.jar = jar;
        }
    }

    // Recorded missing ids that this suite does not heal, keyed by "registry|namespace:path" so the same path in
    // two registries (advanced_spawner is a block, an item and a block entity) is reported once per registry.
    // Concurrent because MissingMappingsEvent can fire off the worker that loads the level; read once, at
    // ServerAboutToStart, on the server thread. Insertion order is preserved for a stable message.
    private static final Map<String, MissingEntry> RECORDED = new ConcurrentHashMap<>();
    private static final List<String> RECORDED_ORDER = java.util.Collections.synchronizedList(new ArrayList<>());

    private record MissingEntry(String registrySimpleName, ResourceLocation id)
    {
    }

    // ---- Recording (world load, forge bus) ---------------------------------------------------------------

    /**
     * Records every unhealed missing id across the four persisted registries whose content a world stores.
     * SOUND_EVENTS is deliberately excluded: a missing sound is never chunk/entity data, so it is no data loss.
     * LOWEST priority so any remap/ignore other suite handlers do runs first; this listener only reads, so the
     * priority is belt-and-braces, not a correctness dependency.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onMissingMappings(MissingMappingsEvent event)
    {
        recordUnhealed(event, ForgeRegistries.Keys.BLOCKS, ForgeRegistries.BLOCKS);
        recordUnhealed(event, ForgeRegistries.Keys.ITEMS, ForgeRegistries.ITEMS);
        recordUnhealed(event, ForgeRegistries.Keys.ENTITY_TYPES, ForgeRegistries.ENTITY_TYPES);
        recordUnhealed(event, ForgeRegistries.Keys.BLOCK_ENTITY_TYPES, ForgeRegistries.BLOCK_ENTITY_TYPES);
    }

    private static <T> void recordUnhealed(MissingMappingsEvent event,
                                           ResourceKey<? extends Registry<T>> registryKey,
                                           IForgeRegistry<T> registry)
    {
        for (MissingMappingsEvent.Mapping<T> mapping : event.getAllMappings(registryKey))
        {
            ResourceLocation key = mapping.getKey();
            if (!NamespaceRemap.isOurNamespace(key))
                continue; // another mod's missing id: not ours to guard, leave it to Forge / that mod.
            if (NamespaceRemap.isRetired(key))
                continue; // a deliberately-removed placeholder: expected missing, dropped by NamespaceRemap.
            if (NamespaceRemap.isRgNpcLegacy(key))
                continue; // the ninjin_npc -> rgnpc path rename, healed by RgNpcEntities.Remap.
            // Healed by NamespaceRemap's namespace swap when the merged form is present: only a genuinely
            // absent merged id (a removed module's registration) has no target and is real data loss.
            ResourceLocation merged = ResourceLocation.fromNamespaceAndPath(LegacyIds.NEW_NAMESPACE, key.getPath());
            if (registry.containsKey(merged))
                continue;

            String simpleName = registryKey.location().getPath(); // e.g. "block", "item", "entity_type"
            String dedupe = simpleName + "|" + key;
            if (RECORDED.putIfAbsent(dedupe, new MissingEntry(simpleName, key)) == null)
                RECORDED_ORDER.add(dedupe);
        }
    }

    // ---- Refusal (ServerAboutToStart) --------------------------------------------------------------------

    /**
     * Refuses startup when this world holds registry ids or Space data from a module that is not installed.
     * A no-op when nothing is missing, or when the appropriate override property is set. When it must refuse it
     * terminates the JVM with {@link Runtime#halt(int)} and does not return.
     */
    public static void checkOrRefuse(MinecraftServer server)
    {
        // Group the recorded missing ids by the module that most likely provides them.
        Map<Module, List<MissingEntry>> byModule = new EnumMap<>(Module.class);
        synchronized (RECORDED_ORDER)
        {
            for (String dedupe : RECORDED_ORDER)
            {
                MissingEntry e = RECORDED.get(dedupe);
                if (e == null)
                    continue;
                Module m = classify(e.id());
                if (ModulePresence.loaded(moduleContainerId(m)))
                    continue; // defensive: the module is present after all (should not happen, id was missing).
                byModule.computeIfAbsent(m, k -> new ArrayList<>()).add(e);
            }
        }

        // Space also has SavedData / region evidence that leaves no missing registry id; fold it in.
        List<String> spaceDataFiles = ModulePresence.space()
                ? new ArrayList<>()
                : SpaceAbsenceGuard.detectSpaceDataFailSoft(server);

        if (byModule.isEmpty() && spaceDataFiles.isEmpty())
            return; // nothing missing: fresh world, or every needed module is present. Boot normally.

        // What modules are affected, so we can decide whether the Space-only legacy override applies.
        boolean spaceAffected = byModule.containsKey(Module.SPACE) || !spaceDataFiles.isEmpty();
        boolean nonSpaceAffected = byModule.keySet().stream().anyMatch(m -> m != Module.SPACE);
        boolean onlySpace = spaceAffected && !nonSpaceAffected;

        boolean contentOverride = Boolean.getBoolean(OVERRIDE_PROPERTY);
        boolean spaceOverride = Boolean.getBoolean(SPACE_OVERRIDE_PROPERTY);

        String report = buildReport(byModule, spaceDataFiles);

        if (contentOverride || (spaceOverride && onlySpace))
        {
            String which = contentOverride ? OVERRIDE_PROPERTY : SPACE_OVERRIDE_PROPERTY;
            LoggingHandler.sulog.warn("[ModuleAbsenceGuard] a suite module is absent and this world holds its content, "
                    + "but -D{}=true was set: booting anyway. The content below will be DROPPED as its chunks load "
                    + "(entities removed, blocks turned to AIR) and those chunks rewritten without it. This is "
                    + "irreversible.\n{}", which, report);
            return;
        }

        LoggingHandler.sulog.error(
            "\n"
            + "============================================================================\n"
            + " REFUSING TO START: this world holds content from suite module(s) that are\n"
            + " NOT installed. Booting without them would let vanilla DROP every entity of\n"
            + " the unregistered types as their chunks load, and turn every missing BLOCK\n"
            + " into AIR, rewriting those chunks without the content. That loss is permanent,\n"
            + " so the server has been stopped before touching the world.\n"
            + "\n"
            + "{}\n"
            + "\n"
            + " To fix, do ONE of:\n"
            + "    1. Install the missing module jar(s) listed above (or run the fat\n"
            + "       dmz_ragnarok jar instead of the modular core), then start again.\n"
            + "       Nothing is lost.\n"
            + "    2. If you deliberately want to abandon that content, start once with\n"
            + "       -D" + OVERRIDE_PROPERTY + "=true to accept losing it.\n"
            + "============================================================================",
            report);

        // stderr too: Runtime.halt skips the logging framework's shutdown flush, so an async appender might not
        // land the error above on disk.
        System.err.println("[ModuleAbsenceGuard] REFUSING TO START: this world holds content from uninstalled suite "
            + "module(s). Install the missing module jar(s) or the fat jar, or start with -D" + OVERRIDE_PROPERTY
            + "=true to accept losing it.\n" + report);
        System.err.flush();
        System.out.flush();

        // Terminate NOW, before any level load or shutdown save can run. halt() never returns.
        Runtime.getRuntime().halt(70);

        // Unreachable in practice: only if halt is somehow a no-op. Still refuse, so the server cannot proceed.
        throw new ContentRemovalRefusedException(
            "A suite module is absent but this world holds its content; refusing to start to avoid silently "
            + "dropping it. Install the missing module jar(s) or the fat jar, or start with -D" + OVERRIDE_PROPERTY
            + "=true to accept the loss.");
    }

    /** Builds the human-readable, module-grouped body shared by the warning and the refusal message. */
    private static String buildReport(Map<Module, List<MissingEntry>> byModule, List<String> spaceDataFiles)
    {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Module, List<MissingEntry>> entry : byModule.entrySet())
        {
            Module m = entry.getKey();
            sb.append(" ").append(m.label);
            if (m.jar != null)
                sb.append(" (install ").append(m.jar).append(")");
            sb.append(":\n");
            for (MissingEntry e : entry.getValue())
                sb.append("    - ").append(e.registrySimpleName()).append(' ').append(e.id()).append('\n');
        }
        if (!spaceDataFiles.isEmpty())
        {
            // Space data files/regions may or may not have already appeared as a SPACE registry group above; list
            // them explicitly so the operator sees the on-disk Space evidence even with no missing entity id.
            sb.append(" Space (data files) (install ").append(ModulePresence.SPACE).append("):\n");
            for (String f : spaceDataFiles)
                sb.append("    - ").append(f).append('\n');
        }
        // Trim a trailing newline for a tidy block.
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n')
            sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private static String moduleContainerId(Module m)
    {
        return m.jar != null ? m.jar : ModulePresence.CORE; // UNKNOWN maps to core, which is always present.
    }

    // ---- Module attribution ------------------------------------------------------------------------------
    // Core cannot classload a module to ask it, so the path->module map is a static table built from each
    // module's registrations (its ModBlocks / ModItems / ModEntities / ModBlockEntities). Exact ids plus the
    // dynamic-suffix prefixes (tp_gem_<amount>, z_soul_<...>, stat_gem_<amount>). Anything unrecognised is
    // UNKNOWN, which still refuses the boot, just without naming a jar.

    private static final Set<String> DUNGEONS_IDS = Set.of(
            "advanced_spawner", "crate_chest", "crate_barrel", "floor_ticket");
    private static final Set<String> RAIDS_IDS = Set.of(
            "raid_npc", "dimensional_tear", "raid_soul");
    private static final Set<String> TOURNAMENTS_IDS = Set.of(
            "tournament_npc");
    private static final Set<String> SPACE_IDS = Set.of(
            "planet_defender", "planet_garrison_defender", "planet_garrison_saiyan",
            "planet_saiyan_citizen", "planet_saiyan_trader", "super_ball");

    private static Module classify(ResourceLocation id)
    {
        String path = id.getPath();
        if (DUNGEONS_IDS.contains(path) || path.startsWith("tp_gem_"))
            return Module.DUNGEONS;
        if (RAIDS_IDS.contains(path) || path.startsWith("z_soul_"))
            return Module.RAIDS;
        if (TOURNAMENTS_IDS.contains(path) || path.startsWith("stat_gem_"))
            return Module.TOURNAMENTS;
        if (SPACE_IDS.contains(path))
            return Module.SPACE;
        return Module.UNKNOWN;
    }

    /** Thrown to stop startup cleanly. A distinct type so the cause reads clearly in the crash report. */
    public static final class ContentRemovalRefusedException extends RuntimeException
    {
        public ContentRemovalRefusedException(String message)
        {
            super(message);
        }
    }
}
