package net.shurui.dev.sdu.compat.shard;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

/**
 * Reflection bridge from sdu into shuruisutilities' cross-shard selector fan-out.
 *
 * <h2>Why reflection and not a direct call</h2>
 * sdu deliberately imports NOTHING from shuruisutilities: that layering is what lets the public content tier ship
 * and function without the admin suite. Cross-shard command fan-out lives in the admin suite
 * ({@code net.shurui.shuruisutilities.shard.ShardSelectorFanout}), so sdu reaches it the same way the {@code compat}
 * packages reach an optional mod: by name, guarded, resolved once. When the SU shard layer is absent (a public tier
 * build) or off (no vault configured), every method here is a clean no-op and the calling command behaves exactly as
 * it does on a single server. The one type that crosses the boundary is {@code ServerPlayer}, which is Minecraft's,
 * plus plain JDK types, so nothing about SU's internals leaks into sdu's compile classpath.
 */
public final class ShardFanoutBridge
{
    private ShardFanoutBridge() {}

    /** Selector scope codes, kept in step with {@code ShardSelectorFanout.Scope}'s ordinal order. */
    public static final int SCOPE_LOCAL = 0;
    public static final int SCOPE_NETWORK = 1;
    public static final int SCOPE_LOCAL_PREDICATED = 2;

    private static final Class<?> CLASS = probe();
    private static final Method M_CLASSIFY = method("classifyCode", String.class);
    private static final Method M_FANOUT = method("fanOutByName",
            ServerPlayer.class, String.class, String.class, Collection.class);

    private static Class<?> probe()
    {
        try
        {
            return Class.forName("net.shurui.shuruisutilities.shard.ShardSelectorFanout");
        }
        catch (Throwable t)
        {
            // The SU shard layer is not on the classpath (a public tier build): fan-out simply does not exist here.
            return null;
        }
    }

    private static Method method(String name, Class<?>... args)
    {
        if (CLASS == null)
            return null;
        try
        {
            return CLASS.getMethod(name, args);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /**
     * How the targets selector on the current command should be treated. {@link #SCOPE_LOCAL} whenever the shard
     * layer is off or absent, which is the unchanged single-server path.
     */
    public static int classify(String fullInput)
    {
        if (M_CLASSIFY == null)
            return SCOPE_LOCAL;
        try
        {
            return (int) M_CLASSIFY.invoke(null, fullInput);
        }
        catch (Throwable t)
        {
            return SCOPE_LOCAL;
        }
    }

    /**
     * Fan a selector-scoped command out to the other shards, targeting each remote player by name. The executed
     * remote line is {@code before + name + after}. {@code localExclude} are the players this shard already handled.
     * A no-op when the shard layer is off or absent.
     */
    public static void fanOutByName(ServerPlayer sender, String before, String after, Collection<UUID> localExclude)
    {
        if (M_FANOUT == null)
            return;
        try
        {
            M_FANOUT.invoke(null, sender, before, after, localExclude);
        }
        catch (Throwable t)
        {
            // Launching the fan-out failed; the local pass has already run, so this is a soft miss, not a command
            // failure. Deliberately swallowed: a network hiccup must never turn a working local grant into an error.
        }
    }
}
