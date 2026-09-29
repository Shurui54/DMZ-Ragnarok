package net.shurui.shuruisutilities.dragons;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Shared, deliberately cheap particle emission for the area moves.
 *
 * <h2>The performance rules live here, once</h2>
 * <ul>
 *   <li><b>Nobody nearby, nothing sent.</b> {@link #anyoneNear} is checked before every emission. A move running in
 *       an empty chunk is the common case on a big server (a boss arena nobody is at, a cloud left behind), and it
 *       should cost a distance check per emission tick and nothing else.</li>
 *   <li><b>Emit on an interval, never every tick.</b> Callers pass their tick counter to {@link #due}; at 20 Hz a
 *       per-tick emitter is 20x the packets for an effect the eye cannot tell apart.</li>
 *   <li><b>One packet per emission, not one per particle.</b> {@code sendParticles} with a count and a spread is a
 *       single packet that the client expands locally; looping it per particle sends N packets for the same result.</li>
 * </ul>
 *
 * <p>These are what keep the area moves from being the thing that lags a fight. If a new area effect is added, route
 * its particles through here rather than calling {@code sendParticles} directly, so the guards cannot be forgotten.
 */
public final class DragonAreaFx
{
    private DragonAreaFx() {}

    /**
     * How far away a player still counts as "watching" this effect, in blocks.
     *
     * <p>Comfortably past a typical particle render distance, so nothing visibly pops in at the boundary, while
     * still skipping the whole emission for an arena with nobody in it.
     */
    private static final double VIEW_RANGE = 48.0;

    /** True on the ticks an effect should emit, given its own countdown and how often it wants to fire. */
    public static boolean due(int ticksLeft, int intervalTicks)
    {
        return intervalTicks <= 1 || ticksLeft % intervalTicks == 0;
    }

    /**
     * True when at least one player is close enough for the effect to be worth drawing.
     *
     * <p>Stops at the first hit rather than counting everyone, so the cost is one distance check in the common case.
     */
    public static boolean anyoneNear(ServerLevel level, Vec3 at)
    {
        double sq = VIEW_RANGE * VIEW_RANGE;
        for (ServerPlayer player : level.players())
            if (player.distanceToSqr(at.x, at.y, at.z) <= sq)
                return true;
        return false;
    }

    /**
     * Emit one burst, in a single packet, only if somebody can see it.
     *
     * @param count how many particles the CLIENT will spawn from this one packet; keep it modest, since this is the
     *              number that actually costs frames on the viewer's machine.
     */
    public static void burst(ServerLevel level, ParticleOptions particle, Vec3 at,
                             int count, double spreadX, double spreadY, double spreadZ, double speed)
    {
        if (count <= 0 || !anyoneNear(level, at))
            return;
        level.sendParticles(particle, at.x, at.y, at.z, count, spreadX, spreadY, spreadZ, speed);
    }

    /**
     * Emit a ring of bursts around a centre, used for effects whose EDGE is the readable part (a heat boundary, a
     * cloud wall). Costs one visibility check for the whole ring rather than one per point.
     *
     * @param points how many points around the ring; small numbers read fine because each point is a burst.
     */
    public static void ring(ServerLevel level, ParticleOptions particle, Vec3 centre, double radius, double yOffset,
                            int points, int countPerPoint, double spread, double speed)
    {
        if (points <= 0 || countPerPoint <= 0 || !anyoneNear(level, centre))
            return;
        // Rotated by game time so the ring turns instead of sitting as a static ring of dots.
        double phase = (level.getGameTime() % 360L) * 0.05;
        for (int i = 0; i < points; i++)
        {
            double angle = phase + i * (Math.PI * 2.0 / points);
            level.sendParticles(particle,
                    centre.x + Math.cos(angle) * radius,
                    centre.y + yOffset,
                    centre.z + Math.sin(angle) * radius,
                    countPerPoint, spread, spread, spread, speed);
        }
    }
}
