package net.shurui.shuruisutilities.shard;

import java.util.Collection;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.api.key.ShardHooks;

/**
 * Network-wide fan-out of a bare {@code @a} / {@code @e} selector on an admin command: a FACADE over
 * {@link ShardHooks}. The logic is the key's (Sh1: {@code ShardSelectorFanoutEngine}); keyless every command takes
 * the unchanged single-server path.
 *
 * <p>The name and the two methods sdu reaches by reflection ({@link #classifyCode(String)} and
 * {@link #fanOutByName(ServerPlayer, String, String, Collection)}, see sdu's {@code ShardFanoutBridge}) must keep
 * exactly these signatures, and {@link Scope} must keep its constant order (the bridge reads the ordinal).
 */
public final class ShardSelectorFanout
{
    private ShardSelectorFanout() {}

    /** How the targets selector on a command should be treated. */
    public enum Scope
    {
        /** No selector, a sender/position relative one, or the shard layer is off: run as a single server would. */
        LOCAL_ONLY,
        /** A bare {@code @a} / {@code @e} with the shard layer live: run local, then fan the network out. */
        NETWORK,
        /** A predicated {@code @a[..]} / {@code @e[..]} with the shard layer live: run local, and say so. */
        LOCAL_PREDICATED
    }

    /** True only when a fan-out would actually be consumed somewhere. Keyless: false. */
    public static boolean active()
    {
        return ShardHooks.get().selectorFanoutActive();
    }

    /** Classify the first {@code @} selector token in a command line. Keyless: {@link Scope#LOCAL_ONLY}. */
    public static Scope classify(String fullInput)
    {
        int code = ShardHooks.get().classifySelector(fullInput);
        Scope[] all = Scope.values();
        return code >= 0 && code < all.length ? all[code] : Scope.LOCAL_ONLY;
    }

    /** The same classification as an {@code int} ({@link Scope} ordinal), for sdu's reflective bridge. */
    public static int classifyCode(String fullInput)
    {
        return classify(fullInput).ordinal();
    }

    /** Queue a by-name copy of a selector command for every player on another server. Keyless: a no-op. */
    public static void fanOutByName(ServerPlayer sender, String before, String after, Collection<UUID> localExclude)
    {
        ShardHooks.get().fanOutByName(sender, before, after, localExclude);
    }
}
