package net.shurui.shuruisutilities.client.combat;

import net.minecraft.world.phys.Vec3;

/**
 * Where a dashing player's aura sits on screen, as a function of how steeply they are angled.
 *
 * <p>A dash aura is not drawn standing upright behind the player like a normal one. It is laid over the screen and
 * rotated so it trails the direction of travel, which means its screen position has to change with pitch: an aura that
 * looks right on a level dash sits over the player's face in a dive. Rather than one formula, five hand placed anchors
 * are interpolated between, because the correct offset is not linear in pitch and eyeballed anchors land far closer
 * than any curve fit would.
 *
 * <p>Pitch here follows Minecraft's convention: negative is looking up, positive is looking down.
 */
public final class DashAuraPlacement
{
    private DashAuraPlacement() {}

    public record Offset(double x, double y) {}

    // The five anchors, from straight up to straight down. These are placements, not physics, so they are tuned by eye.
    private static final Offset CLIMB = new Offset(0.15D, -0.55D);
    private static final Offset MID_CLIMB = new Offset(0.45D, 0.15D);
    private static final Offset LEVEL = new Offset(1.0D, 0.15D);
    private static final Offset MID_DIVE = new Offset(0.7D, 0.05D);
    private static final Offset DIVE = new Offset(1.25D, -0.55D);

    // Degrees of pitch at which the mid anchors sit. Beyond the outer pair the placement stops moving, so an impossible
    // pitch cannot throw the aura off screen.
    private static final float MID_PITCH = 45.0F;

    /**
     * Screen offset for a given pitch, interpolated between the anchors.
     */
    public static Offset sample(float pitch)
    {
        if (pitch <= -90.0F)
            return CLIMB;
        if (pitch >= 90.0F)
            return DIVE;
        if (pitch < -MID_PITCH)
            return lerp(CLIMB, MID_CLIMB, (pitch + 90.0F) / (90.0F - MID_PITCH));
        if (pitch < 0.0F)
            return lerp(MID_CLIMB, LEVEL, (pitch + MID_PITCH) / MID_PITCH);
        if (pitch < MID_PITCH)
            return lerp(LEVEL, MID_DIVE, pitch / MID_PITCH);
        return lerp(MID_DIVE, DIVE, (pitch - MID_PITCH) / (90.0F - MID_PITCH));
    }

    private static Offset lerp(Offset a, Offset b, float t)
    {
        float f = t < 0.0F ? 0.0F : (t > 1.0F ? 1.0F : t);
        return new Offset(a.x() + (b.x() - a.x()) * f, a.y() + (b.y() - a.y()) * f);
    }

    /**
     * Whether a REMOTE player's dash aura should be laid over the screen at all.
     *
     * <p>The laid over placement is built around the dash coming toward or away from the camera. Seen side on it reads
     * as an aura lying flat in mid air, so a crossing dash keeps the ordinary upright aura. The test is simply how
     * aligned the dash heading is with the line from the viewer.
     */
    public static boolean layOverForRemote(Vec3 heading, Vec3 viewerToPlayer)
    {
        if (heading == null || viewerToPlayer == null)
            return false;
        double hl = heading.length();
        double vl = viewerToPlayer.length();
        if (hl < 1.0E-4D || vl < 1.0E-4D)
            return false;
        double dot = heading.scale(1.0D / hl).dot(viewerToPlayer.scale(1.0D / vl));
        return Math.abs(dot) > 0.35D;
    }
}
