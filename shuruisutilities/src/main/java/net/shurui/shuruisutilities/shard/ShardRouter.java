package net.shurui.shuruisutilities.shard;

import net.shurui.shuruisutilities.api.key.ShardHooks;

/**
 * Where an arriving player should play: a FACADE over {@link ShardHooks}. The join balancer and its login routing
 * are the key's (Sh1: {@code ShardRouterEngine}); core and the space module only ask it for a target.
 */
public final class ShardRouter
{
    private ShardRouter() {}

    /**
     * The open world a login or {@code /spawn} would be sent to, or null. Callers check {@code ShardSync.active()}
     * first. Keyless: null.
     */
    public static String pickTarget()
    {
        return ShardHooks.get().pickTarget();
    }
}
