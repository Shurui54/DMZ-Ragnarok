package net.shurui.shuruisutilities.world.space;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import net.shurui.dev.sdu.api.ModulePresence;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Refuses to start core alone on a world that already holds Space data.
 *
 * <p>The Space feature registers its entity types (planet citizens and traders, the saiyan garrisons and
 * defenders, the Super dragon balls) in the {@code dmz_ragnarok} namespace from the {@code dmz_ragnarok_space}
 * module. With that module absent (core loaded on its own, no Space jar and not the fat jar) those entity
 * types are never registered.
 *
 * <p><b>What Forge 1.20.1 actually does here, verified on a dedicated server (2026-09-23):</b> it does NOT
 * stop the boot. During world LOAD (before any server lifecycle event) Forge's missing-mapping handling logs,
 * per absent id, {@code Registry minecraft:entity_type: Found a missing id from the world
 * dmz_ragnarok:planet_defender} (and planet_garrison_defender, planet_garrison_saiyan, planet_saiyan_citizen,
 * planet_saiyan_trader, super_ball), then reports {@code There are N missing entries in this save} and
 * {@code A world backup will be automatically created}, keeps every missing id reserved in the level.dat
 * registry snapshot (so the id is NOT dropped and a later boot WITH Space restores it), rewrites level.dat with
 * that reconciled snapshot, and proceeds to start. Because the entity TYPE itself is not registered, vanilla
 * cannot deserialize those entities: as each chunk holding one loads it DROPS the entity and, on the next save
 * of that chunk, rewrites it without them. So the loss is silent and per-chunk, not a boot failure. This guard
 * turns that silent per-chunk loss into a deliberate, up-front choice.
 *
 * <p>Runs at the same lifecycle point as {@link PlanetRegionSeeder} (from
 * {@code ShuruisUtilities.serverPreInit}, on {@code ServerAboutToStartEvent}), and BEFORE the seeder. It halts
 * BEFORE any chunk is loaded, so no chunk with a Space entity is ever read or rewritten: the region, entity,
 * data and SavedData files stay byte-for-byte identical (verified: all 295 such files unchanged before and
 * after a refused boot). The guard writes nothing itself and lets no suite code (seeder, border seeder, data
 * save) run. The ONE file it cannot keep identical is level.dat: Forge already rewrote it during LOAD, in the
 * missing-mapping reconciliation above, which is BEFORE any server event, so no event-based guard can prevent
 * it. That rewrite is non-destructive to Space content (it preserves the Space entity ids as reserved
 * missing-mappings), and Forge also makes its own world backup at that point.
 *
 * <p>To stop the boot it terminates the JVM with {@link Runtime#halt(int)} after logging the reason, rather
 * than throwing: a thrown exception lets vanilla's {@code runServer} finally-block run {@code stopServer()},
 * which SAVES the world again (verified: a thrown refusal triggered a second level.dat save on shutdown). Halt
 * runs no shutdown hooks and no finally blocks, so it is the minimal-write way to refuse. It is the same
 * "refuse startup" intent the suite's dedicated-server key check historically had, made strict about not
 * writing further. A distinct {@link SpaceRemovalRefusedException} is still thrown as an unreachable fallback
 * in case {@code halt} is ever a no-op (e.g. a security manager), so the server still cannot proceed.
 *
 * <p>A fresh world, or a world that never had Space data, boots normally with core alone: {@link #checkOrRefuse}
 * finds no Space SavedData and no Space region files and returns. An operator who accepts losing the Space
 * entities starts once with {@code -Ddmzr.allowSpaceRemoval=true} and the guard steps aside with a warning.
 */
public final class SpaceAbsenceGuard
{
    private SpaceAbsenceGuard()
    {
    }

    /** Operator override: accept that Space entities will be dropped, and boot core alone anyway. */
    public static final String OVERRIDE_PROPERTY = "dmzr.allowSpaceRemoval";

    // The five per-world SavedData files the Space feature writes, WITHOUT the ".dat" suffix. Vanilla stores
    // these under <world>/data/<name>.dat. The names are load-bearing (they key the files in the save) and must
    // match the NAME constants on the Space SavedData classes exactly.
    private static final String[] SPACE_SAVED_DATA = {
        "shuruisutilities_generated_planets",
        "shuruisutilities_super_planets",
        "shuruisutilities_planet_garrison",
        "shuruisutilities_apophis_planets",
        "shuruisutilities_asteroid_stamps",
    };

    // The Space dimensions whose region folders signal that Space content exists on disk. Ids come from
    // SpaceKeys so a future dimension rename moves the guard with them. planet_surface and planet_vegeta hold
    // the generated planets / Beerus / Vegeta builds; space is the travel void. All three live under
    // <world>/dimensions/<namespace>/<path>/region.
    private static final ResourceLocation[] SPACE_DIMENSIONS = {
        SpaceKeys.SPACE_ID,
        SpaceKeys.SURFACE_ID,
        SpaceKeys.PLANET_VEGETA_ID,
    };

    /**
     * Refuses startup when core is running without the Space module on a world that already holds Space data.
     * A no-op when Space is present, when the override property is set, or when the world has no Space data.
     *
     * <p>When it must refuse it terminates the JVM with {@link Runtime#halt(int)} (see the class note) and does
     * not return; {@link SpaceRemovalRefusedException} is thrown only as an unreachable fallback.
     */
    public static void checkOrRefuse(MinecraftServer server)
    {
        if (ModulePresence.space())
            return; // the module is loaded (own jar or the fat jar); its entity types register, nothing is lost.

        List<String> found;
        try
        {
            found = detectSpaceData(server);
        }
        catch (Throwable t)
        {
            // Detection is a read-only best effort. If it cannot read the save we must NOT invent a refusal
            // (that would brick a world that may be perfectly fine), nor silently pass a world that may hold
            // Space data. Log loudly and let startup continue: the worst case degrades to Forge's own missing
            // registry handling, which is exactly the native behaviour this guard sits in front of.
            LoggingHandler.sulog.warn("[SpaceAbsenceGuard] could not inspect the save for Space data; skipping the guard: {}", t.toString());
            return;
        }

        if (found.isEmpty())
            return; // fresh world or a world without Space data: core alone boots normally.

        if (Boolean.getBoolean(OVERRIDE_PROPERTY))
        {
            LoggingHandler.sulog.warn("[SpaceAbsenceGuard] Space module is absent and this world holds Space data ({}), "
                    + "but -D{}=true was set: booting anyway. Entities of the unregistered Space types will be DROPPED "
                    + "as their chunks load and those chunks rewritten without them. This is irreversible.",
                String.join(", ", found), OVERRIDE_PROPERTY);
            return;
        }

        // Refuse. Nothing has been written: only the save was read above. We must keep it that way, so we
        // terminate the JVM with Runtime.halt below rather than throwing: a thrown exception here lets vanilla's
        // runServer finally-block run stopServer(), which SAVES the world data and rewrites level.dat. halt runs
        // no shutdown hooks and no finally blocks, so the shutdown save never happens.
        String detail = String.join("\n    - ", found);
        LoggingHandler.sulog.error(
            "\n"
            + "============================================================================\n"
            + " REFUSING TO START: the Space module (dmz_ragnarok_space) is NOT installed,\n"
            + " but this world already holds Space data:\n"
            + "    - {}\n"
            + "\n"
            + " Booting without Space would let vanilla DROP every planet citizen, trader,\n"
            + " garrison, defender and Super dragon ball of the unregistered Space entity\n"
            + " types as their chunks load, rewriting those chunks without them. That loss\n"
            + " is permanent, so the server has been stopped before touching the world.\n"
            + "\n"
            + " To fix, do ONE of:\n"
            + "    1. Install dmz_ragnarok_space (or run the fat dmz_ragnarok jar instead\n"
            + "       of the modular core), then start again. Nothing is lost.\n"
            + "    2. If you deliberately want to abandon all Space content, start once with\n"
            + "       -D{}=true to accept losing those entities.\n"
            + "============================================================================",
            detail, OVERRIDE_PROPERTY);

        // Also write the headline to stderr directly: Runtime.halt skips the logging framework's shutdown flush,
        // so an async log appender might not get the error line above onto disk. stderr is unbuffered enough here.
        System.err.println("[SpaceAbsenceGuard] REFUSING TO START: Space module absent but this world holds Space "
            + "data (" + String.join(", ", found) + "). Install dmz_ragnarok_space (or use the fat jar), or start "
            + "with -D" + OVERRIDE_PROPERTY + "=true to accept losing Space entities.");
        System.err.flush();
        System.out.flush();

        // Terminate NOW, before any level load or shutdown save can run. halt() never returns.
        Runtime.getRuntime().halt(70);

        // Unreachable in practice: only if halt is somehow a no-op. Still refuse, so the server cannot proceed.
        throw new SpaceRemovalRefusedException(
            "Space module (dmz_ragnarok_space) absent but this world holds Space data; refusing to start to avoid "
            + "silently dropping Space entities. Install dmz_ragnarok_space or the fat jar, or start with -D"
            + OVERRIDE_PROPERTY + "=true to accept the loss.");
    }

    /**
     * Fail-soft, read-only detection of Space data for {@link ModuleAbsenceGuard}. Returns the same
     * human-readable list as {@link #detectSpaceData(MinecraftServer)} but never throws: on any read error it
     * logs and returns empty, matching the "do not invent a refusal from a read failure" policy in
     * {@link #checkOrRefuse(MinecraftServer)}. This catches Space content that leaves NO missing registry id
     * (a world with Space SavedData or region files but no loaded Space entity), which the missing-mapping
     * recorder alone would not see.
     */
    public static List<String> detectSpaceDataFailSoft(MinecraftServer server)
    {
        try
        {
            return detectSpaceData(server);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[SpaceAbsenceGuard] could not inspect the save for Space data; skipping the Space data-file check: {}", t.toString());
            return new ArrayList<>();
        }
    }

    /**
     * Reads the save (only) for signs of Space content: any of the five Space SavedData files under
     * {@code <world>/data}, or a non-empty region folder for any Space dimension. Returns a human-readable list
     * of what was found, empty when the world holds no Space data.
     */
    private static List<String> detectSpaceData(MinecraftServer server) throws IOException
    {
        List<String> found = new ArrayList<>();

        // ROOT resolves to the world directory; the overworld's data folder lives directly under it.
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);

        Path dataDir = worldRoot.resolve("data");
        for (String name : SPACE_SAVED_DATA)
        {
            Path dat = dataDir.resolve(name + ".dat");
            if (Files.isRegularFile(dat))
                found.add("data/" + name + ".dat");
        }

        for (ResourceLocation dim : SPACE_DIMENSIONS)
        {
            // Non-overworld dimensions live under <world>/dimensions/<namespace>/<path>/, mirroring vanilla's
            // LevelStorageAccess.getDimensionPath. We build the path from the id rather than a loaded level
            // because no level is loaded yet at this lifecycle point.
            Path regionDir = worldRoot.resolve("dimensions").resolve(dim.getNamespace()).resolve(dim.getPath()).resolve("region");
            if (regionHasContent(regionDir))
                found.add("dimensions/" + dim.getNamespace() + "/" + dim.getPath() + "/region");
        }

        return found;
    }

    /** True when the region folder exists and contains at least one .mca region file. */
    private static boolean regionHasContent(Path regionDir) throws IOException
    {
        if (!Files.isDirectory(regionDir))
            return false;
        try (Stream<Path> entries = Files.list(regionDir))
        {
            return entries.anyMatch(p -> p.getFileName().toString().endsWith(".mca") && isNonEmptyFile(p));
        }
    }

    private static boolean isNonEmptyFile(Path p)
    {
        try
        {
            return Files.isRegularFile(p) && Files.size(p) > 0L;
        }
        catch (IOException e)
        {
            return false;
        }
    }

    /** Thrown to stop startup cleanly. A distinct type so the cause reads clearly in the crash report. */
    public static final class SpaceRemovalRefusedException extends RuntimeException
    {
        public SpaceRemovalRefusedException(String message)
        {
            super(message);
        }
    }
}
