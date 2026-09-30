package net.shurui.shuruisutilities.space;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.shurui.shuruisutilities.shard.ShardStateSync;

/**
 * The single wall-clock source that drives {@link Orbits}, resolved so every shard and every client agrees on the SAME
 * instant. This is what lets a planet's orbital position be authoritative (used for landing, autopilot, collision and the
 * star map) and yet identical everywhere, without syncing a per-body position every tick.
 *
 * <h3>Server side: the shard database clock</h3>
 * The shard hosts' own {@code System.currentTimeMillis()} run hours apart (see the shard-clock reference), so an orbit
 * driven off a host's raw wall clock would place the same planet in a different spot on each shard. The server epoch is
 * therefore {@code System.currentTimeMillis() + ShardStateSync.dbClockOffsetMillis()}, the exact idiom DisguiseState and
 * ModelState already use for cross-shard-consistent time: with the shard network live every server reads the one database
 * clock; with it off the offset is 0 and this is just the local wall clock (a single server needs no correction).
 *
 * <h3>Client side: the server's instant, pushed once</h3>
 * A client cannot read the shard database, so the server sends its current epoch in the layout sync packet
 * ({@code PacketSpaceLayoutSync}); the client records the offset between that and its own wall clock and applies it, so its
 * drawn orbit tracks the server's authoritative one within the network latency. Orbits are slow (hours per turn), so a few
 * seconds of clock skew is a few thousandths of a revolution, invisible.
 *
 * <h3>Picking the side</h3>
 * {@link #epochMillis()} takes no server handle (it is called from deep in the pure position derivation), so it decides by
 * physical side: {@link ServerLifecycleHooks#getCurrentServer()} is non-null on a dedicated server and on a singleplayer
 * host (both use the server epoch, which in singleplayer is just the local clock), and null on a remote client (which uses
 * the synced offset). On a singleplayer host the two agree at the local clock, so a body never jumps between the server
 * tick and the render frame.
 */
public final class OrbitClock
{
    private OrbitClock()
    {
    }

    // CLIENT: (server epoch at the moment the sync arrived) minus (the client's own wall clock then), so
    // localWallClock + clientOffsetMillis reproduces the server's epoch. volatile: written on the client thread by the
    // sync handler, read on the render thread.
    private static volatile long clientOffsetMillis = 0L;
    private static volatile boolean clientOffsetSet = false;

    /** CLIENT: record the server's current epoch from the layout sync, so the client's orbits track the server's. */
    public static void setServerEpoch(long serverEpochMillis)
    {
        clientOffsetMillis = serverEpochMillis - System.currentTimeMillis();
        clientOffsetSet = true;
    }

    /**
     * The SERVER's cross-shard-consistent epoch (the shard database clock when the network is live, else the local wall
     * clock). Also what the sync packet ships to clients.
     */
    public static long serverEpochMillis()
    {
        return System.currentTimeMillis() + shardOffsetMillis();
    }

    // the shard database clock offset, or 0 if the shard system is off or unavailable. Guarded so a shard API shift can
    // never break the orbit derivation: worst case orbits fall back to the local wall clock.
    private static long shardOffsetMillis()
    {
        try
        {
            return ShardStateSync.dbClockOffsetMillis();
        }
        catch (Throwable t)
        {
            return 0L;
        }
    }

    /**
     * The epoch to drive orbits with on whichever side is asking. Server (or singleplayer host): the shard-corrected
     * server epoch. Remote client: the local wall clock plus the synced offset (or the bare wall clock until the first
     * sync arrives, which just means orbits are momentarily off by the clock skew, self-correcting on the first packet).
     */
    public static long epochMillis()
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null)
        {
            return serverEpochMillis();
        }
        return clientOffsetSet ? System.currentTimeMillis() + clientOffsetMillis : System.currentTimeMillis();
    }
}
