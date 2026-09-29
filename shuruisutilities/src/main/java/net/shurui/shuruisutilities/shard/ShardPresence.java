package net.shurui.shuruisutilities.shard;

import java.util.List;
import java.util.UUID;

import net.shurui.shuruisutilities.api.key.ShardHooks;

/**
 * Who is online across the network, and on which server: a FACADE over {@link ShardHooks}. The presence directory
 * (its table, heartbeat and staleness rule) is the key's (Sh1: {@code ShardPresenceStore}); core keeps the two row
 * types so public and module code can name them. Keyless the network is empty: nobody is found, the count is 0.
 */
public final class ShardPresence
{
    private ShardPresence() {}

    /** Where a player is, as far as the network knows. */
    public record Located(UUID id, String name, String serverId) {}

    /**
     * Where a player is, plus the skin texture property needed to DRAW them on a server they are not on.
     * {@code texValue} / {@code texSignature} may be null (an offline-mode player, or a row written before the skin
     * columns existed), in which case the client falls back to the default skin.
     */
    public record Skinned(UUID id, String name, String serverId, String texValue, String texSignature) {}

    /** Find a player anywhere on the network by name, case insensitively (blocking). Keyless: null. */
    public static Located find(String name)
    {
        return ShardHooks.get().find(name);
    }

    /** Find a player anywhere on the network by UUID (blocking). Keyless: null. */
    public static Located findById(UUID id)
    {
        return ShardHooks.get().findById(id);
    }

    /** True when a fresh presence row exists for this player anywhere (blocking). Keyless: false. */
    public static boolean isOnlineAnywhere(UUID id)
    {
        return ShardHooks.get().isOnlineAnywhere(id);
    }

    /** How many players are online across the whole network (blocking). Keyless: 0. */
    public static int count()
    {
        return ShardHooks.get().onlineCount();
    }

    /** Everyone online across the network (blocking). Keyless: empty. */
    public static List<Located> all()
    {
        return ShardHooks.get().all();
    }

    /** Everyone online across the network, with their skin property (blocking). Keyless: empty. */
    public static List<Skinned> allWithSkins()
    {
        return ShardHooks.get().allWithSkins();
    }
}
