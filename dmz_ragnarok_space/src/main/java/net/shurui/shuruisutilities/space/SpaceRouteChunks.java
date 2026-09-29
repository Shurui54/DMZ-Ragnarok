package net.shurui.shuruisutilities.space;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.world.ForgeChunkManager;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Server-side rolling force-load window for an autopilot pod's route. At its peak the pod crosses more than a chunk per
 * tick, and the controlling client SKIPS its whole tick while its local chunk has not arrived from the server (see
 * {@code LocalPlayer.tick}), which reads as a freeze-then-lurch hitch, far worse on a dedicated server where generation
 * and chunk sending lag most. Keeping a short line of chunks AHEAD of the pod forced-loaded gives the server time to
 * generate and send them before the pod (and thus the client's own view) arrives.
 *
 * <p>The window is deliberately small and bounded: a line of at most {@link #LOOKAHEAD_CHUNKS} chunks sampled every 16
 * blocks along the travel vector from the pod outward. Sideways loading is already handled by the player's own view
 * tickets (the pilot rides the pod), so only the forward gap needs pre-warming. Each drive tick the desired set is
 * recomputed and diffed against the currently forced set: newly-entered chunks are forced, chunks that dropped behind are
 * released, so the pinned region never grows.
 *
 * <p>RELEASING IS MANDATORY. A leaked ticket permanently pins chunks and is a live-server memory and tick-time problem.
 * Every path that ends an autopilot releases: the drive's own {@code stopAutopilot}, the per-tick safety sweep in
 * {@code SpaceTravelModule} (which catches death/respawn where the persistent autopilot tag is dropped), player logout,
 * and a cross-dimension launch (the origin-dimension window is released before the space window opens). As a final
 * backstop against an abnormal shutdown mid-trip, {@code SpaceRouteTickets.registerLoadingCallback} (in core) drops every persisted ticket on world
 * load; a resuming drive simply re-forces a fresh window on its next tick.
 *
 * <p>Server thread only (the per-player tick), so the plain maps need no synchronisation.
 */
public final class SpaceRouteChunks
{
    private SpaceRouteChunks()
    {
    }

    // the SMALLEST look-ahead window: six 16-block samples reach ~96 blocks (six chunks) down the travel vector, enough
    // for the slow stock cruise, while pinning at most a handful of chunks. The window GROWS with the pod's per-tick step
    // (see update) so a faster cruise keeps the same lead time in ticks; this is just the floor. Kept small on purpose:
    // this is a pre-warm window, not a claimed region.
    private static final int LOOKAHEAD_CHUNKS = 6;

    // how many ticks of travel the window aims to cover, and the hard cap on samples so a very fast pod can never pin an
    // unbounded line of chunks. Raised alongside the hold-when-chunks-lag gate (see SpaceTravelModule.driveAutopilot): a
    // longer lead means the forward chunks are force-loaded and generated further in advance, so the hold trips less
    // often. At the default cruise (12 blocks/tick) TICKS_LEAD=48 gives ~36 samples (~576 blocks, ~2.9s of lead); the cap
    // of 48 bounds it to ~768 blocks even at the fastest allowed cruise. The hold is the actual correctness backstop; the
    // wider window just reduces how often it is needed, at the cost of a still-bounded handful more pinned chunks per
    // travelling pilot.
    private static final int TICKS_LEAD = 48;

    /**
     * How many already-generated chunks may take their force ticket in a single tick.
     *
     * <p>Even the cheap path is not free (Forge walks its ticket bookkeeping and the chunk map), and the window can
     * want a dozen chunks at once right after a launch or a course change. Spreading that over ticks keeps the drive
     * off the tick-time budget entirely; the window catches up within a few ticks and the lead is measured in
     * seconds, so nothing is lost.
     */
    private static final int MAX_FORCES_PER_TICK = 2;
    private static final int MAX_LOOKAHEAD_CHUNKS = 48;

    // the dimension each player's currently-forced window lives in, so a release always unforces in the RIGHT level even
    // after the player has since changed dimension (a landing, a death/respawn, a cross-dim launch).
    private static final Map<UUID, ResourceKey<Level>> windowDim = new HashMap<>();
    // the packed chunk positions currently forced for each player.
    private static final Map<UUID, Set<Long>> windowChunks = new HashMap<>();
    // chunks the window WANTS but has not forced yet, because they are still generating on the worker pool or were
    // held back by this tick's force budget. Tracked separately from windowChunks so a release never tries to unforce
    // a ticket that was never taken, and so the next tick knows to retry them.
    private static final Map<UUID, Set<Long>> windowPending = new HashMap<>();

    // recompute and apply the rolling window for this player given the pod's current position, unit travel direction and
    // the pod's per-tick step (blocks). The window length scales with the step so it always covers about TICKS_LEAD ticks
    // of travel, so a faster cruise never outruns the pre-warmed chunks.
    public static void update(ServerPlayer player, ServerLevel level, Vec3 podPos, Vec3 dir, double step)
    {
        UUID id = player.getUUID();
        MinecraftServer server = player.getServer();

        // if the window was in a different dimension (a cross-dim launch happened between ticks), release it there first
        // so nothing is left pinned in the origin dimension.
        ResourceKey<Level> prevDim = windowDim.get(id);
        if (prevDim != null && !prevDim.equals(level.dimension()))
        {
            releaseIn(server, id, prevDim);
        }

        // samples needed to cover TICKS_LEAD ticks of travel at this step, floored at the small default and capped so a
        // fast pod can never pin an unbounded line of chunks.
        int samples = (int) Math.ceil(Math.max(0.0, step) * TICKS_LEAD / 16.0);
        samples = Math.max(LOOKAHEAD_CHUNKS, Math.min(MAX_LOOKAHEAD_CHUNKS, samples));

        Set<Long> want = new HashSet<>();
        Vec3 unit = dir != null && dir.lengthSqr() > 1.0e-6 ? dir.normalize() : Vec3.ZERO;
        for (int i = 0; i <= samples; i++)
        {
            Vec3 s = podPos.add(unit.scale(i * 16.0));
            ChunkPos cp = new ChunkPos(BlockPos.containing(s.x, s.y, s.z));
            want.add(cp.toLong());
        }

        Set<Long> current = windowChunks.computeIfAbsent(id, k -> new HashSet<>());

        // Force any chunk newly entering the window, but NEVER pay for its generation on this thread.
        //
        // ForgeChunkManager.forceChunk asks the level for the chunk, and for a chunk that does not exist yet that is
        // getChunkBlocking: the server thread stops and drives the chunk executor until generation finishes. A live
        // profile put this ONE call at 3.5% of the entire server thread, nearly all of it inside ProtoChunk
        // construction and a ZGC page-allocation stall, because a cruising pod walks into ungenerated space every few
        // ticks. That is a tick-time stall, not just CPU: it is exactly the freeze this class exists to prevent,
        // moved from the client onto the server.
        //
        // So the window is warmed in two steps. A chunk that is ALREADY loaded is forced immediately, which is cheap
        // because Forge's internal lookup finds it. A chunk that is not gets a one-tick UNKNOWN ticket, which asks the
        // chunk system to generate it on the worker pool, and is picked up on a later tick once it has arrived. That
        // costs a few ticks of extra lead, which a pre-warm window has by definition, and nothing on the tick thread.
        Set<Long> pending = windowPending.computeIfAbsent(id, k -> new HashSet<>());
        pending.retainAll(want);
        int forcedThisTick = 0;
        for (long key : want)
        {
            if (current.contains(key))
            {
                continue;
            }
            ChunkPos cp = new ChunkPos(key);
            // getChunkNow is the only genuinely non-blocking presence check here: it returns the chunk when it is
            // already loaded to FULL and null otherwise, without ever scheduling generation or driving the main
            // thread chunk processor. getChunk(.., FULL, false) READ like a plain lookup but still ran
            // mainThreadProcessor.managedBlock on the chunk future, so a holder left mid-generation by last tick's
            // UNKNOWN ticket stalled the server thread right here (getChunkBlocking / parkNanos in the live
            // profile, 24.5% of slow ticks on smp). getChunkNow returns null off the main thread, which is fine:
            // this method is server-thread only.
            if (level.getChunkSource().getChunkNow(cp.x, cp.z) == null)
            {
                // Not generated yet. Nudge it onto the worker pool and come back for it; re-added every tick because
                // an UNKNOWN ticket lasts one tick by design.
                level.getChunkSource().addRegionTicket(TicketType.UNKNOWN, cp, 1, cp);
                pending.add(key);
                continue;
            }
            if (forcedThisTick >= MAX_FORCES_PER_TICK)
            {
                pending.add(key);
                continue;
            }
            ForgeChunkManager.forceChunk(level, chunkOwnerModId(), id, cp.x, cp.z, true, false);
            pending.remove(key);
            forcedThisTick++;
        }
        // release any chunk that dropped out behind the pod.
        for (long key : new HashSet<>(current))
        {
            if (!want.contains(key))
            {
                ChunkPos cp = new ChunkPos(key);
                ForgeChunkManager.forceChunk(level, chunkOwnerModId(), id, cp.x, cp.z, false, false);
            }
        }

        // The window records only what is genuinely forced. A chunk still waiting on generation stays out of it, so
        // the next tick retries it instead of assuming a ticket that was never taken.
        Set<Long> forced = new HashSet<>(want);
        forced.removeAll(pending);
        windowChunks.put(id, forced);
        windowDim.put(id, level.dimension());
    }

    // Is the chunk containing world (x, z) loaded to FULL status on the server right now? This is the readiness signal
    // the autopilot hold gates on before driving the pod into that chunk.
    //
    // HONESTY NOTE (this is a PROXY, not the client's true state): the pilot rides the pod, so the chunk the pod is about
    // to enter is by definition inside the pilot's view distance and WILL be sent to the pilot's client. A chunk that is
    // not even loaded full on the server therefore cannot possibly be on the client yet, so refusing to advance into it
    // is a genuine, correct necessary condition and stops the pod outrunning generation. What it does NOT prove is that
    // an already-server-loaded chunk has finished travelling the network and been integrated by the client: the server
    // cannot observe a client's received-chunk set for a chunk it force-loads (ForgeChunkManager.forceChunk keeps a chunk
    // loaded server-side but never pushes it to a client beyond view distance, and there is no per-player "already sent"
    // set exposed here). So this tracks the server's generation/loading FRONT, which is the dominant cause of the stall
    // on a dedicated server, not the last hop of network latency. See the assessment in SpaceTravelModule.
    public static boolean chunkReady(ServerLevel level, double x, double z)
    {
        ChunkPos cp = new ChunkPos(BlockPos.containing(x, 0.0, z));
        return level.getChunkSource().hasChunk(cp.x, cp.z);
    }

    // true while this player has any chunk forced by the route loader. Used by the per-tick safety sweep to release a
    // window left behind when an autopilot ended without going through stopAutopilot (chiefly death/respawn).
    public static boolean has(ServerPlayer player)
    {
        Set<Long> set = windowChunks.get(player.getUUID());
        return set != null && !set.isEmpty();
    }

    // release the whole window for a live player (the common case: the drive ended and we have the player in hand).
    public static void release(ServerPlayer player)
    {
        release(player.getServer(), player.getUUID());
    }

    // release the whole window for a player id, resolving the stored dimension against the server so the unforce lands in
    // the level the chunks were actually forced in. Idempotent: no window is a no-op.
    public static void release(MinecraftServer server, UUID id)
    {
        ResourceKey<Level> dim = windowDim.get(id);
        releaseIn(server, id, dim);
        windowChunks.remove(id);
        windowPending.remove(id);
        windowDim.remove(id);
    }

    private static void releaseIn(MinecraftServer server, UUID id, ResourceKey<Level> dim)
    {
        if (server == null || dim == null)
        {
            return;
        }
        Set<Long> set = windowChunks.get(id);
        if (set == null || set.isEmpty())
        {
            return;
        }
        ServerLevel level = server.getLevel(dim);
        if (level == null)
        {
            return;
        }
        for (long key : set)
        {
            ChunkPos cp = new ChunkPos(key);
            ForgeChunkManager.forceChunk(level, chunkOwnerModId(), id, cp.x, cp.z, false, false);
        }
    }

    // Owner id for every forced-chunk ticket this route driver creates. ForgeChunkManager validates this against the
    // set of LOADED mod containers, so it must be the container that actually ships SU: since the suite merge that is
    // the single dmz_ragnarok container, not the historical "shuruisutilities" id (now only a registry/asset namespace,
    // no longer a loaded mod). Handing Forge the old id makes it drop the callback and every ticket. Resolved from the
    // active container SU cached at construction, so it tracks the real container id and survives a future rename.
    // The owner id here MUST match the one SpaceRouteTickets registers the loading-validation callback with (both
    // resolve ShuruisUtilities.MOD_CONTAINER.getModId(), the single dmz_ragnarok container since the suite merge), or
    // Forge would validate this driver's tickets against a callback registered for a different owner.
    private static String chunkOwnerModId()
    {
        return ShuruisUtilities.MOD_CONTAINER.getModId();
    }
}
