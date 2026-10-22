package net.shurui.shuruisutilities.space;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.shurui.shuruisutilities.world.space.SpaceKeys;

/**
 * Server tick savings for the SPACE dimension only (owner decision, 2026-10-21: "let's do both for space").
 *
 * <h3>1. A lower simulation distance in space</h3>
 * Space is almost entirely empty air, yet every player in it kept a full server simulation distance of chunks ticking
 * (and, at pod speed, the ticking-ticket tracker re-propagating that whole square every few ticks). The space level's
 * {@code ServerChunkCache} is given its own, smaller simulation distance ({@link #simulationDistance}, config
 * {@code spaceSimulationDistance}, default 4) through {@code MixinSpaceSimulationDistance}, which clamps whatever the
 * server asks for (at start, and every later change, singleplayer included) for that one level. It is never RAISED above
 * the server's value. The VIEW distance gets the same treatment ({@link #viewDistance}, config {@code spaceViewDistance},
 * default 5) through the same mixin: at pod speed the full server radius meant hundreds of new chunks a second to send,
 * light and build, which is what made fast flight hitch. Bodies, suns and systems are drawn without chunks, so only
 * asteroids and other pods are limited to that radius. Every other dimension, planet surfaces included, is unchanged.
 *
 * <p>What still ticks: the player (always, from its connection), a pod it rides (the root vehicle sits on the player),
 * and every entity inside the radius (4 chunks is a 64 to 80 block square around each player). So that nothing FREEZES
 * where a client can still see it, an entity just outside the radius whose type a client still tracks there (a ki disc or
 * ki wave tracks for 64 chunks, an item 6, a pod or a DMZ fighter 5, against a ki blast's 4) is ticked by {@link
 * #onLevelTick} exactly like vanilla would, as long as it is within its tracking range (capped at the view distance) of a
 * player and its neighbouring chunks are loaded. Super dragon ball pickups and the gods are on planet SURFACES, not here.
 *
 * <h3>2. All-air space chunks skip the per-chunk tick</h3>
 * {@code ServerLevel.tickChunk} (random ticks over all 254 sections of the 4064-high column, the weather checks) and
 * {@code NaturalSpawner.spawnForChunk} are skipped for a space chunk holding no block and no block entity. The answer is
 * CACHED on the chunk ({@link Flag}) and reset by {@code MixinSpaceChunkEmptyFlag} on any block change or block entity,
 * so it is computed once per chunk, not scanned every tick (plus a staggered re-check of all-air chunks every 10 s as a
 * safety net for writes that bypass LevelChunk). Entity ticking and scheduled block / fluid ticks are a
 * different path and untouched, so nothing a player can see or touch stops: the first block placed in a chunk makes it a
 * normal ticking chunk again on the next tick.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SpaceChunkTicking
{
    private SpaceChunkTicking()
    {
    }

    /** Duck interface the chunk mixin adds to LevelChunk: 0 unknown, 1 all air, 2 has something. */
    public interface Flag
    {
        byte dmzr$emptyState();

        void dmzr$setEmptyState(byte state);
    }

    private static volatile int simulationDistance = 4;
    private static volatile int viewDistance = 5;
    private static volatile boolean skipEmpty = true;
    private static long skipped;
    private static final long RECHECK_TICKS = 200L;

    /** Config bake (PlanetSpawnModule). Re-applies to a running server. */
    public static void configure(int simDistance, int spaceViewDistance, boolean skipEmptyChunks)
    {
        simulationDistance = simDistance;
        viewDistance = spaceViewDistance;
        skipEmpty = skipEmptyChunks;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null)
        {
            server.execute(() -> apply(server));
        }
    }

    /** The simulation distance the space level may use when the server asks for {@code requested}. */
    public static int clamp(ServerLevel level, int requested)
    {
        int cap = simulationDistance;
        if (cap <= 0 || level == null || level.dimension() != SpaceKeys.SPACE)
        {
            return requested;
        }
        return Math.min(requested, cap);
    }

    /** The view distance the space level may use when the server asks for {@code requested}: never raised. */
    public static int clampView(ServerLevel level, int requested)
    {
        int cap = viewDistance;
        if (cap <= 0 || level == null || level.dimension() != SpaceKeys.SPACE)
        {
            return requested;
        }
        return Math.min(requested, Math.max(2, cap));
    }

    /** Whether the per-chunk tick (random ticks, weather, spawning) is skipped for this chunk of this level. */
    public static boolean skipChunkTick(ServerLevel level, LevelChunk chunk)
    {
        if (!skipEmpty || level.dimension() != SpaceKeys.SPACE || !(chunk instanceof Flag flag))
        {
            return false;
        }
        byte state = flag.dmzr$emptyState();
        // safety net: an all-air answer is re-checked every RECHECK_TICKS (staggered per chunk), so even a write that
        // bypassed LevelChunk.setBlockState (straight into a section) resumes ticking within seconds.
        if (state == 1 && ((level.getGameTime() + chunk.getPos().x * 31L + chunk.getPos().z * 17L) % RECHECK_TICKS) == 0)
        {
            state = 0;
        }
        if (state == 0)
        {
            state = isAllAir(chunk) ? (byte) 1 : (byte) 2;
            flag.dmzr$setEmptyState(state);
        }
        if (state == 1)
        {
            ++skipped;
            return true;
        }
        return false;
    }

    /** Chunk ticks skipped since start (server thread counter, for probes and the log). */
    public static long skippedChunkTicks()
    {
        return skipped;
    }

    private static boolean isAllAir(LevelChunk chunk)
    {
        if (!chunk.getBlockEntities().isEmpty())
        {
            return false;
        }
        for (LevelChunkSection section : chunk.getSections())
        {
            if (section != null && !section.hasOnlyAir())
            {
                return false;
            }
        }
        return true;
    }

    // ask the space level to re-run its simulation distance through the clamp, and log the result.
    private static void apply(MinecraftServer server)
    {
        ServerLevel space = server.getLevel(SpaceKeys.SPACE);
        if (space == null)
        {
            return;
        }
        int serverSim = server.getPlayerList().getSimulationDistance();
        space.getChunkSource().setSimulationDistance(serverSim);
        int serverView = server.getPlayerList().getViewDistance();
        space.getChunkSource().setViewDistance(serverView);
        LoggingHandler.sulog.info("[SpaceTicking] space simulation distance {} chunks (server {}), view distance {} "
                        + "chunks (server {}); all-air space chunks skip the chunk tick: {}.",
                clamp(space, serverSim), serverSim, clampView(space, serverView), serverView, skipEmpty);
    }

    // Keep an entity just OUTSIDE the reduced entity-ticking radius moving while a client can still see it: vanilla ticks
    // only entities in entity-ticking chunks, and with the smaller radius a ki disc (tracked for 64 chunks), an item
    // (6) or an unridden pod (5) would otherwise stop dead in view. Space holds few entities, so the walk is cheap; an
    // entity whose 3x3 chunks are not all loaded is left alone (it is past the view distance, so no client draws it).
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event)
    {
        if (!(event.level instanceof ServerLevel level) || level.dimension() != SpaceKeys.SPACE)
        {
            return;
        }
        if (event.phase == TickEvent.Phase.END)
        {
            // tick what START picked: those were outside the radius before vanilla's entity pass, so vanilla did not
            // tick them this tick (an entity vanilla ticked out of the radius is picked up next tick, never twice).
            for (int i = 0; i < EDGE.size(); ++i)
            {
                Entity entity = EDGE.get(i);
                if (!entity.isRemoved() && !entity.isPassenger())
                {
                    ++edgeTicked;
                    level.guardEntityTick(level::tickNonPassenger, entity);
                }
            }
            EDGE.clear();
            return;
        }
        EDGE.clear();
        int sim = level.getServer().getPlayerList().getSimulationDistance();
        if (clamp(level, sim) >= sim || level.players().isEmpty())
        {
            return;   // no reduction in force: vanilla already ticks everything it would.
        }
        int view = clampView(level, level.getServer().getPlayerList().getViewDistance());
        var distance = level.getChunkSource().chunkMap.getDistanceManager();
        for (Entity entity : level.getAllEntities())
        {
            if (entity.isRemoved() || entity.isPassenger() || entity instanceof ServerPlayer)
            {
                continue;
            }
            int cx = entity.chunkPosition().x;
            int cz = entity.chunkPosition().z;
            if (distance.inEntityTickingRange(entity.chunkPosition().toLong()))
            {
                continue;   // vanilla ticks it.
            }
            int reachChunks = Math.min(entity.getType().clientTrackingRange(), view);
            double reach = reachChunks * 16.0;
            boolean seen = false;
            for (ServerPlayer player : level.players())
            {
                double dx = player.getX() - entity.getX();
                double dz = player.getZ() - entity.getZ();
                if (dx * dx + dz * dz <= reach * reach)
                {
                    seen = true;
                    break;
                }
            }
            if (!seen || !neighboursLoaded(level, cx, cz))
            {
                continue;
            }
            EDGE.add(entity);
        }
    }

    private static final java.util.ArrayList<Entity> EDGE = new java.util.ArrayList<>();

    private static long edgeTicked;

    /** Entities ticked by the edge pass since start (server thread counter, for probes). */
    public static long edgeTickedEntities()
    {
        return edgeTicked;
    }

    private static boolean neighboursLoaded(ServerLevel level, int cx, int cz)
    {
        for (int dx = -1; dx <= 1; ++dx)
        {
            for (int dz = -1; dz <= 1; ++dz)
            {
                if (!level.hasChunk(cx + dx, cz + dz))
                {
                    return false;
                }
            }
        }
        return true;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event)
    {
        skipped = 0;
        edgeTicked = 0;
        apply(event.getServer());
    }

    // a space level created after start (a dynamic load) also gets the clamp.
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event)
    {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == SpaceKeys.SPACE
                && level.getServer().isReady())
        {
            level.getServer().execute(() -> apply(level.getServer()));
        }
    }
}
