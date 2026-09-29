package net.shurui.shuruisutilities.combat;

import net.minecraft.world.phys.Vec3;

/**
 * The shape of a dash, as maths both sides can run.
 *
 * <p>This is deliberately a plain function library with no entities and no side in it, because the CLIENT has to be
 * able to reproduce the server's route exactly. The dash used to be driven entirely from the server, one velocity
 * packet per tick, and that is what made it feel jumpy and late: every tick the client's own prediction was overwritten
 * by a packet that had been in flight since the tick before, so the player was always being corrected toward where they
 * had already been. Now the server plans the route and sends it once, the client flies it locally at its own frame
 * rate, and the server watches from a distance and only intervenes if the two genuinely disagree.
 *
 * <p>For that to work the two sides must compute the same curve from the same inputs, which is the whole reason this
 * lives in one place instead of being written twice.
 */
public final class DashPath
{
    private DashPath() {}

    /** Nominal blocks per tick a planned route is timed at. */
    public static final double ROUTE_SPEED = 3.5D;

    /**
     * A route always takes at least this long and never longer, whatever its length. The floor is what stops a dash at
     * someone standing next to you from being a single frame teleport; the ceiling stops a dash across the whole range
     * from becoming a slow cruise. Both are deliberately short: the dash is meant to be punchy, and a long minimum was
     * what made a close dash feel like it was being winched in.
     */
    public static final int MIN_TRAVEL_TICKS = 5;
    public static final int MAX_TRAVEL_TICKS = 30;

    /** Where a dash aims to STOP relative to its target, measured eye to eye. */
    public static final double CONTACT_STANDOFF = 1.85D;

    /** How high over the target the back dash climbs before dropping in behind them. */
    private static final double OVER_TOP_HEIGHT = 4.5D;

    /**
     * Quadratic curve from {@code from} to {@code to}, bent toward {@code control}. {@code t} is clamped, so a caller
     * that runs past the end of the route gets the destination rather than a point beyond it.
     */
    public static Vec3 sample(Vec3 from, Vec3 control, Vec3 to, double t)
    {
        double u = Math.max(0.0D, Math.min(1.0D, t));
        double inv = 1.0D - u;
        double a = inv * inv;
        double b = 2.0D * inv * u;
        double c = u * u;
        return new Vec3(
                a * from.x + b * control.x + c * to.x,
                a * from.y + b * control.y + c * to.y,
                a * from.z + b * control.z + c * to.z);
    }

    /**
     * Which side of the target this dash is going to finish on, decided ONCE when the dash is planned.
     *
     * <p>Pinning it is the whole point. Working the side out from where the dasher currently is, every tick, makes the
     * destination rotate around the target as the dasher swings toward it: the flank keeps moving to stay "to their
     * left of where I am now", the dasher keeps chasing it, and the result is a lap of the target rather than a flank.
     * Fixed at the start, the destination is a place, and the dash simply goes there.
     */
    public static Vec3 pinnedSide(Vec3 sourceEye, Vec3 targetEye, Vec3 targetLook, DashMode mode)
    {
        Vec3 approach = sourceEye.subtract(targetEye);
        if (approach.lengthSqr() < 1.0E-6D)
            approach = new Vec3(0.0D, 0.0D, 1.0D);
        approach = approach.normalize();
        return switch (mode)
        {
            case STRAIGHT -> approach;
            case LEFT_ARC -> flank(approach, -1.0D);
            case RIGHT_ARC -> flank(approach, 1.0D);
            case OVER_TOP -> back(approach, targetLook);
        };
    }

    /**
     * Where the dash means to end up, relative to the target.
     *
     * <p>Recomputed every tick by both sides while travelling. Measured eye to eye so the dash arrives at head height
     * rather than at the target's feet.
     *
     * <h2>Which modes pin their side and which track, and why they differ</h2>
     * Only the LATERAL flanks pin. Their side is derived from where the DASHER is, and the dasher is moving fast, so
     * recomputing it every tick makes "their left, from where I am now" rotate away as fast as the dasher closes on it.
     * That tail chase is what produced a full lap of the target.
     *
     * <p>The back dash is the opposite case and must NOT pin. Its side comes from the TARGET's own facing, which owes
     * nothing to where the dasher is, so tracking it cannot spiral. Pinning it instead meant that the moment the target
     * turned to face the incoming dash, which is the single most likely thing for anyone to do, their back went with
     * them and the dash landed on the spot their back used to be. That is in front of them.
     *
     * <p>A straight dash tracks too, for the same reason it cannot spiral: it aims at the place the dasher is already
     * coming from.
     *
     * @param targetFacing the target's body facing, used by the back dash. See {@link #bodyFacing}.
     * @param radius half the target's width. Without it the stop point is measured from the CENTRE of the target, which
     *               for anything bigger than a player is still inside them, so a dash meant to end behind a large boss
     *               ended up in the middle of it.
     */
    public static Vec3 destination(Vec3 sourceEye, Vec3 targetEye, Vec3 targetFacing, double radius, double halfHeight,
            DashMode mode, Vec3 pinnedSide)
    {
        double standoff = CONTACT_STANDOFF + Math.max(0.0D, radius);
        Vec3 side = pinnedSide;
        Vec3 approach = sourceEye.subtract(targetEye);
        approach = approach.lengthSqr() < 1.0E-6D ? null : approach.normalize();
        if (mode == DashMode.STRAIGHT && approach != null)
            side = approach;
        else if (mode == DashMode.OVER_TOP)
            side = back(approach == null ? side : approach, targetFacing);
        if (side == null || side.lengthSqr() < 1.0E-6D)
            side = new Vec3(0.0D, 0.0D, 1.0D);
        Vec3 point = targetEye.add(side.normalize().scale(standoff));
        // The back dash finishes a little above their shoulder line rather than level with it, so the last of the
        // descent never clips through the body on the way down.
        return mode == DashMode.OVER_TOP ? point.add(0.0D, Math.max(0.0D, halfHeight) * 0.5D, 0.0D) : point;
    }

    // The dasher's left (-1) or right (+1) as they close on the target, flattened so a flank never ends up above or
    // below them. `approach` points from the target BACK toward the dasher, so travel is its negation.
    private static Vec3 flank(Vec3 approach, double sign)
    {
        Vec3 travel = new Vec3(-approach.x, 0.0D, -approach.z);
        if (travel.lengthSqr() < 1.0E-6D)
            return approach;
        travel = travel.normalize();
        // dir cross up gives the right hand side of dir in Minecraft's coordinate system.
        Vec3 right = travel.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (right.lengthSqr() < 1.0E-6D)
            return approach;
        return right.normalize().scale(sign);
    }

    /**
     * The direction a living target's BODY faces, which is what "behind them" should mean.
     *
     * <p>Not {@code getLookAngle}. That is head rotation, and on an NPC the head swivels to watch whoever is nearby
     * while the body stays put, so a dash meant to land behind an NPC that had turned to look at you was measuring
     * from a facing the body never had. Body rotation is also the one an onlooker reads as "which way is it facing".
     */
    public static Vec3 bodyFacing(net.minecraft.world.entity.Entity entity)
    {
        float yaw = entity instanceof net.minecraft.world.entity.LivingEntity living ? living.yBodyRot
                : entity.getYRot();
        double radians = Math.toRadians(yaw);
        return new Vec3(-Math.sin(radians), 0.0D, Math.cos(radians));
    }

    // Directly behind the target, by their own rotation. Falls back to the side the dasher came from if the target has
    // no usable facing.
    private static Vec3 back(Vec3 approach, Vec3 targetLook)
    {
        if (targetLook == null || targetLook.lengthSqr() < 1.0E-6D)
            return approach;
        Vec3 flat = new Vec3(-targetLook.x, 0.0D, -targetLook.z);
        return flat.lengthSqr() < 1.0E-6D ? approach : flat.normalize();
    }

    /**
     * The single control point of the curve. A straight dash gets the midpoint, which collapses the curve to a line;
     * every other mode places its control relative to the TARGET, and that placement is the arc.
     *
     * <p>Relative to the target rather than to the middle of the trip, and that distinction is the whole fix for a dash
     * that went through people. A quadratic curve bulges toward its control point at the HALFWAY mark, so a control
     * placed at the midpoint of a long trip bends the curve out in empty space far from anyone and is already back on
     * the straight line by the time it reaches the target. Anchoring the control to the target instead puts the bend
     * where the obstacle actually is.
     *
     * <p>The other half of the same trap: a quadratic only rises HALF way toward its control, so a lift meant to clear
     * a five block tall boss has to be specified at twice the height it needs to achieve. That is why the vertical term
     * below is doubled and looks larger than it should.
     *
     * @param pinnedSide the side of the target this dash finishes on, fixed at plan time
     * @param radius     half the target's width
     * @param halfHeight half the target's height
     */
    public static Vec3 control(Vec3 origin, Vec3 destination, Vec3 targetEye, Vec3 pinnedSide, DashMode mode,
            double radius, double halfHeight)
    {
        if (mode == DashMode.STRAIGHT)
            return origin.add(destination).scale(0.5D);

        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        double standoff = CONTACT_STANDOFF + Math.max(0.0D, radius);
        double trip = origin.distanceTo(destination);
        // Flattened, so the geometry below is the same whether the dash comes in level or from above.
        Vec3 approach = origin.subtract(targetEye);
        approach = new Vec3(approach.x, 0.0D, approach.z);
        approach = approach.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : approach.normalize();

        if (mode == DashMode.OVER_TOP)
        {
            // Straight above them, pulled back toward the dasher so the climb starts before the player arrives rather
            // than turning into a vertical wall at the last moment.
            return targetEye
                    .add(up.scale(2.0D * (Math.max(0.0D, halfHeight) + OVER_TOP_HEIGHT)
                            + Math.min(3.0D, trip * 0.06D)))
                    .add(approach.scale(standoff * 0.6D));
        }

        Vec3 side = pinnedSide;
        if (side == null || side.lengthSqr() < 1.0E-6D)
            return origin.add(destination).scale(0.5D);
        side = side.normalize();
        // Proportional to the trip, bounded at both ends: a short dash with a big lean looks like a stumble, and a long
        // one with a fixed small lean reads as straight.
        double lean = Math.max(1.2D, Math.min(5.0D, trip * 0.18D));
        // The corner between "in front of them" and "out on the chosen side", so the curve rounds that corner instead
        // of cutting the diagonal straight through where they are standing.
        Vec3 corner = approach.scale(0.75D).add(side).normalize();
        // A little lift on every lateral arc. A perfectly flat swerve reads as sliding; a shallow climb reads as flying.
        return targetEye.add(corner.scale(standoff + lean)).add(up.scale(lean * 0.2D));
    }

    /**
     * Cheap length estimate: the curve walked in a few straight pieces. The exact arc length of a quadratic has a
     * closed form, but this only feeds a tick count that is clamped at both ends anyway.
     */
    public static double length(Vec3 from, Vec3 control, Vec3 to)
    {
        double total = 0.0D;
        Vec3 previous = from;
        for (int i = 1; i <= 8; i++)
        {
            Vec3 point = sample(from, control, to, i / 8.0D);
            total += point.distanceTo(previous);
            previous = point;
        }
        return total;
    }

    /** How many ticks a route of this length is given. */
    public static int travelTicks(double length)
    {
        return Math.max(MIN_TRAVEL_TICKS,
                Math.min(MAX_TRAVEL_TICKS, (int) Math.ceil(Math.max(0.0D, length) / ROUTE_SPEED)));
    }
}
