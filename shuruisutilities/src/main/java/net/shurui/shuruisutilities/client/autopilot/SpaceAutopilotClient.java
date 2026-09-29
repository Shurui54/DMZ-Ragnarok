package net.shurui.shuruisutilities.client.autopilot;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Client-only holder for the space-pod autopilot integration state, the client half of the jitter fix. The server no
 * longer teleports the pod every tick (which destroyed interpolation via {@code absMoveTo}); instead it SYNCS the
 * autopilot target and the constant per-tick step to this controlling client through {@code PacketSpaceAutopilotSync},
 * and the client's OWN {@code SpacePodEntity.travel} (see {@code core.mixin.client.MixinDmzSpacePodTravel}) integrates
 * the pod one clamped step toward the target each tick, reporting position back through the ordinary
 * {@code ServerboundMoveVehiclePacket} path exactly like manual flight. Because the pod now moves via the client's own
 * {@code move()}, its {@code xo -> x} baseline stays intact and the cockpit and starfield interpolate on the same clock.
 *
 * <p>State is deliberately tiny and phase-agnostic: one target position, one constant step, and the dimension that
 * target lives in. The dimension guard is what makes the cross-into-space handoff safe: while the pod is still in the
 * origin dimension climbing, the synced dimension is the origin one; the instant the server teleports the pod into space
 * it stops matching until the fresh space-cruise sync arrives, so the client never integrates a fresh pod toward a stale
 * target. The server sends only on start, on a target/step change, and on stop, never per tick, so there is no
 * per-frame traffic here to read.
 *
 * <p>All fields are {@code volatile}: the packet handler writes them on the main thread (enqueued), the travel mixin
 * reads them on the main thread, and a stray read from a render hook must never see a torn value. Cleared on disconnect
 * by {@link SpaceAutopilotClientHooks} so a relog onto another server never inherits a stale course.
 */
public final class SpaceAutopilotClient
{
    private SpaceAutopilotClient()
    {
    }

    private static volatile boolean active;
    private static volatile ResourceLocation dimension;
    private static volatile double targetX;
    private static volatile double targetY;
    private static volatile double targetZ;
    private static volatile double step;

    // Apply an autopilot sync from the server. An inactive sync clears everything; an active one records the target,
    // its dimension and the constant per-tick step the client integrates at.
    public static void accept(boolean isActive, ResourceLocation dim, double x, double y, double z, double perTickStep)
    {
        dimension = dim;
        targetX = x;
        targetY = y;
        targetZ = z;
        step = perTickStep;
        // set active LAST so a reader that sees active == true always sees the matching target/step already written.
        active = isActive;
    }

    // drop all autopilot state (disconnect, or an explicit inactive sync). Leaves active false so the travel mixin
    // immediately falls back to DMZ's own travel.
    public static void clear()
    {
        active = false;
        dimension = null;
    }

    public static boolean isActive()
    {
        return active;
    }

    // true only when an autopilot is active AND its target lives in the given dimension. The travel mixin gates on this
    // so a pod that has just crossed into space is never integrated toward the previous dimension's stale target.
    public static boolean matches(ResourceLocation dim)
    {
        return active && dim != null && dim.equals(dimension);
    }

    public static Vec3 target()
    {
        return new Vec3(targetX, targetY, targetZ);
    }

    public static double step()
    {
        return step;
    }
}
