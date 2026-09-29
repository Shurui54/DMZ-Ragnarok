package net.shurui.shuruisutilities.shard;

import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.api.key.ShardHooks;

/**
 * Moving a player to another server through the proxy: a FACADE over {@link ShardHooks}. The hop itself (pre-hop
 * teardown, vault save and release, the proxy Connect message, the arrival record) is the key's (Sh1:
 * {@code ShardTransferEngine}). Every caller checks {@code ShardSync.active()} or {@code ShardDimensions.hosts}
 * first, so keyless nothing here is ever reached.
 */
public final class ShardTransfer
{
    private ShardTransfer() {}

    /**
     * Stand-in "target" meaning: on arrival, put this player at the destination's spawn rather than next to
     * somebody. A fixed name-based (version 3) UUID, compared by value on the far side, so it can never collide with
     * a Mojang account id. The value is part of the arrival rows other servers read: never change it.
     */
    public static final UUID SPAWN_ON_ARRIVAL =
            UUID.nameUUIDFromBytes("shuruisutilities:spawn-on-arrival".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    /** Ask the proxy to move this player to another server (main thread). Keyless: a no-op. */
    public static void connect(ServerPlayer player, String serverId)
    {
        ShardHooks.get().connect(player, serverId);
    }

    /**
     * Record that this player is on their way to {@code serverId} to be put next to {@code targetId} (or at spawn,
     * {@link #SPAWN_ON_ARRIVAL}). Written before the connect is sent. Keyless: a no-op.
     */
    public static void expect(UUID playerId, String serverId, UUID targetId)
    {
        ShardHooks.get().expectArrival(playerId, serverId, targetId);
    }
}
