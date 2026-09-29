package net.shurui.shuruisutilities.shard;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.api.key.ShardHooks;

/**
 * Network events (raids, tournaments) hosted on one open world and joinable from anywhere: a FACADE over
 * {@link ShardHooks}. The host directory, the per-slot election and the routing are the key's (Sh1:
 * {@code ShardEventsEngine}); the Raids and Tournaments modules call these names. Keyless nothing is networked:
 * {@link #active()} is false and every event runs locally, as on a single server.
 */
public final class ShardEvents
{
    private ShardEvents() {}

    /** A network event a routed player is on their way to join. */
    public record Pending(String kind, String defId) {}

    /** Where a network event is running now. */
    public record Host(String kind, String defId, String server, String phase, long updatedAt)
    {
        public boolean isSelf()
        {
            return server != null && server.equalsIgnoreCase(ShardConfig.get().serverId);
        }
    }

    /** Whether network events are live (the shard network is). Keyless: false. */
    public static boolean active()
    {
        return ShardSync.active();
    }

    /** Whether THIS server may host network events (an open world on a live network). Keyless: false. */
    public static boolean eligibleHost()
    {
        return ShardHooks.get().eventsEligibleHost();
    }

    /** Take (and clear) the network event a player was routed to join, if any (server thread). Keyless: null. */
    public static Pending consumePending(ServerPlayer player)
    {
        return ShardHooks.get().consumePendingEvent(player);
    }

    /**
     * Send a player to the server an event is running on. True when a hop was started (the caller must not also join
     * locally). Keyless: false, so the caller does its ordinary local join.
     */
    public static boolean routeToEvent(ServerPlayer player, String kind, String defId, String hostServer)
    {
        return ShardHooks.get().routeToEvent(player, kind, defId, hostServer);
    }

    /** Which server an event is on, from the local cache (safe on the tick). Keyless: null. */
    public static Host hostOf(String kind, String defId)
    {
        return ShardHooks.get().eventHost(kind, defId);
    }

    /** Record THIS server as the host of an event. Keyless: a no-op. */
    public static void setHost(String kind, String defId, String phase)
    {
        ShardHooks.get().setEventHost(kind, defId, phase);
    }

    /** Give up hosting an event, so every server drops it. Keyless: a no-op. */
    public static void clearHost(String kind, String defId)
    {
        ShardHooks.get().clearEventHost(kind, defId);
    }

    /**
     * Enter the election for one scheduled event slot; {@code onWon} runs on the server thread only if this server
     * wins. Keyless: a no-op (a keyless server never hosts a network event).
     */
    public static void submitElection(String kind, String defId, long slotMillis, int players, Runnable onWon)
    {
        ShardHooks.get().submitEventElection(kind, defId, slotMillis, players, onWon);
    }
}
