package net.shurui.shuruisutilities.client.dormancy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/**
 * Client side cache of which DragonMineZ ball sets are DORMANT, pushed by {@link
 * net.shurui.shuruisutilities.compat.dmz.network.PacketBallDormancySync}. The authoritative state is
 * {@code BallDormancyStorage} on the server (server only), so render code must never read it and reads this
 * standalone holder instead. Only {@code MixinDmzDragonBallDormantRender} consults it, to draw a dormant set's
 * balls grey and translucent.
 *
 * <p><b>Fail safe.</b> Until the packet has arrived this reports NOTHING dormant, so a single player world, an
 * older server, or any moment before the first sync leaves the balls drawn normally rather than greying them from
 * a missing packet.
 *
 * <p>State is a single volatile reference to an immutable snapshot set, written only on the client network thread's
 * enqueued work and read on the render thread, so the volatile publish is enough to keep each read consistent.
 */
public final class BallDormancyClient
{
    private BallDormancyClient() {}

    private static final Logger LOG = LogUtils.getLogger();

    // Immutable snapshot, replaced wholesale on each sync. Empty before any sync (fail closed).
    private static volatile Set<String> dormant = Set.of();

    /** Replace the cached dormant set with the server's authoritative list for this world. */
    public static void apply(List<String> sets)
    {
        dormant = sets == null ? Set.of() : new HashSet<>(sets);
        // Proof of what the client actually received, so an empty cache can be told apart from a non binding
        // render hook: if this reports dormant sets but nothing greys, the mixin did not fire.
        LOG.info("[dormancy] client received dormant set sync: {}", dormant);
    }

    /** True when the given set is dormant (draw its balls grey). Fail closed before the first sync. */
    public static boolean isDormant(String setId)
    {
        return setId != null && dormant.contains(setId);
    }

    /** Drop the cache (e.g. on disconnect) so the next session starts fail closed again. */
    public static void clear()
    {
        dormant = Set.of();
    }
}
