package net.shurui.shuruisutilities.multiworld.v2;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.multiworld.v2.genWorld.ServerWorldMultiworld;
import net.shurui.shuruisutilities.multiworld.v2.utils.PlayerInvalidRegistryLoginFix;
import net.shurui.shuruisutilities.util.events.SUModuleEvent.SUModuleServerStartingEvent;

/**
 * The multiworld ENGINE, which stays in core and runs with or without the Ragnarok Key: the saved multiworld
 * dimensions (the {@code Multiworld} DataManager folder) are created and loaded at SU server start, the named-world
 * lookup ({@code APIRegistry.namedWorldHandler}) resolves them, and the logout parking for dynamic dimensions
 * ({@link PlayerInvalidRegistryLoginFix}) runs.
 *
 * <h2>Why it is not in the key</h2>
 * Public features stand on these worlds. The Space module pre-loads every multiworld world that is a pod destination
 * ({@code PlanetSpawnModule}), the respawn handler re-registers a spawn that sits in one ({@code RespawnHandler}), the
 * smp nether portals route through them ({@code SmpWorld}, {@code MixinBaseFireBlock}, {@code MixinEntityNetherPortal})
 * and the login parking protects every {@code dmz_ragnarok:} dimension. Before S11 all of this already ran keyless,
 * because the module's server-start hook ran before the keyless teardown and the manager outlived it. The PRIVATE part,
 * the {@code MultiworldV2} module with {@code /mw} and {@code /mwtp} (create, import, delete, teleport, list) and its
 * permission nodes, lives in the key; so does the save of the world records at server stop, which never ran keyless.
 *
 * <p>Registered once from core's common setup. Its server-start hook runs at HIGH priority so the worlds exist before
 * any module's own server-start hook reads them, whichever jar that module came from.
 */
public final class MultiworldEngine
{
    // Constructing the manager puts it in front of APIRegistry.namedWorldHandler and on the Forge bus (it is a
    // ServerEventHandler), exactly as the module's static field used to.
    private static final MultiworldManager MANAGER = new MultiworldManager();

    // Registers itself on the Forge bus (ServerEventHandler); held so it is built once, with the engine.
    @SuppressWarnings("unused")
    private static final PlayerInvalidRegistryLoginFix LOGIN_FIX = new PlayerInvalidRegistryLoginFix();

    private static boolean registered;

    private MultiworldEngine()
    {
    }

    /** Register the engine's Forge handlers. Idempotent; called from {@code ShuruisUtilities.preInit}. */
    public static synchronized void register()
    {
        if (registered)
            return;
        registered = true;
        MinecraftForge.EVENT_BUS.register(MultiworldEngine.class);
    }

    public static MultiworldManager manager()
    {
        return MANAGER;
    }

    public static boolean isMultiWorld(ServerLevel world)
    {
        return world instanceof ServerWorldMultiworld
                || world.dimension().location().getNamespace().equals(Multiworld.SUNameSpace);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void serverStarting(SUModuleServerStartingEvent e)
    {
        // provider catalogues must be built BEFORE load() recreates saved worlds, else the dim/biome/generator
        // lookups resolve against empty maps. Both run at ServerStartingEvent, when the registries + level map
        // are ready.
        MANAGER.getProviderHandler().loadDimensionTypes();
        MANAGER.getProviderHandler().loadDimensionSettings();
        MANAGER.getProviderHandler().loadChunkGenerators();
        MANAGER.getProviderHandler().loadBiomeProviders();

        MANAGER.load();

        // Create <SUdir>/import/ up front so admins can drop world folders in before running /mw import.
        MultiworldManager.ensureImportDir();
    }

    // For a VOID world (shuruisutilities:void, empty superflat), place a safe platform block at 0,64,0 and set
    // world spawn on top so players can /mwtp in without falling into the void. On the server thread after the
    // level registers; re-places the block if it's gone.
    @SubscribeEvent
    public static void placeVoidPlatform(LevelEvent.Load event)
    {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        if (!(level.getChunkSource().getGenerator() instanceof FlatLevelSource flat)
                || !flat.settings().getLayers().isEmpty())
            return;
        level.getServer().execute(() -> {
            // imported void worlds already carry terrain from copied region files; a platform at 0,64,0 would
            // stomp it, so exempt them. Regular empty void worlds still get their platform. Lookup is deferred
            // to here because a runtime /mw import isn't in the manager's map until addWorld() completes, which
            // is after LevelEvent.Load is posted; present by the next tick.
            Multiworld mw = MANAGER.getMultiworldByResourceName(level.dimension().location().toString());
            if (mw != null && mw.isImportedVoid())
                return;
            BlockPos platform = new BlockPos(0, 64, 0);
            if (level.getBlockState(platform).isAir())
                level.setBlockAndUpdate(platform, Blocks.STONE.defaultBlockState());
            level.setDefaultSpawnPos(platform.above(), 0f);
        });
    }
}
