package net.shurui.shuruisutilities.shard;

import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.api.key.ShardHooks;
import net.shurui.shuruisutilities.teleport.SpawnPoints;

/**
 * The cross-server player network (the shared player vault, login negotiation, hops): a FACADE over
 * {@link ShardHooks}. The engine is the key's (Sh1: {@code ShardSyncEngine}); about a hundred call sites across core,
 * the modules, Space and the key read these names. Keyless the network is off, exactly as it was without the key:
 * {@link #active()} is false, nobody is handing off, and a hand-off runs the move at once.
 *
 * <p>Read LAZILY: never cache {@link #active()} at construction (the key installs the hook during mod construction).
 */
public final class ShardSync
{
    private ShardSync() {}

    /** Live only with the key, and only when an operator has configured a vault ({@code shard.json}). */
    public static boolean active()
    {
        return ShardHooks.get().active();
    }

    /**
     * True while this player is being handed off to another server. Peeks, does not consume, so a logout handler
     * running ABOVE the engine's NORMAL priority can tell a hop from a genuine quit. Keyless: false.
     */
    public static boolean isHandingOff(UUID id)
    {
        return ShardHooks.get().isHandingOff(id);
    }

    /** Persist a player's vault copy now (a character slot switch just completed). Keyless: a no-op. */
    public static void persistNow(ServerPlayer player)
    {
        ShardHooks.get().persistNow(player);
    }

    /**
     * Hand a player to another server: save them, give up the lock, and only THEN run {@code afterRelease} (the
     * actual move) on the server thread. Keyless: {@code afterRelease} runs at once, as with the network off.
     */
    public static void handOff(ServerPlayer player, Runnable afterRelease)
    {
        ShardHooks.get().handOff(player, afterRelease);
    }

    /**
     * Place a player at THIS server's spawn. Public behaviour, keyless included: the resolution lives in core
     * ({@link SpawnPoints#placeAtServerSpawn}), and only its network branch needs {@link #active()}.
     */
    public static void placeAtServerSpawn(ServerPlayer p)
    {
        SpawnPoints.placeAtServerSpawn(p);
    }
}
