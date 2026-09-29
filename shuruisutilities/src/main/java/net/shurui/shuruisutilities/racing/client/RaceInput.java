package net.shurui.shuruisutilities.racing.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import net.shurui.shuruisutilities.hoverbike.HoverbikeEntity;
import net.shurui.shuruisutilities.racing.physics.KartState;
import net.shurui.shuruisutilities.racing.physics.RaceDriveParams;

/**
 * The rider client's input path for a race bike. Called once per client tick while the local player rides a bike
 * with the race flag: it pushes the shared {@link RaceDriveParams} and surface set from the session (packet 109)
 * onto the bike, and feeds the bike the steering keys the kart physics reads. Steering is A/D AND the rebindable
 * race arrow keys ({@code SUKeybinds.RACE_*}, defaulting to the arrow keys), OR'd together: Left/Right steer, Up
 * accelerate, Down brake; jump (space) hops / drifts. The MOUSE never steers.
 *
 * <p>The camera is UNLOCKED from the bike (owner's final controls, 2026-09-24): the player's yaw is left free so the
 * mouse free-looks a third-person chase camera around the bike, and it is used to AIM attacks (packet 114). When the
 * mouse has been idle for about {@link #IDLE_RECENTRE_TICKS} ticks the view eases smoothly back behind the bike's
 * heading (no snapping). While an autopilot effect is live (Flying Nimbus, or {@code /race debug autodrive}), it
 * drives the bike along the track centreline instead of reading the keys. The drift packets (115) are sent from the
 * physics branch itself, so this only supplies input.
 */
public final class RaceInput
{
    private RaceInput() {}

    /** How close (degrees) to the target heading autopilot must be before it stops steering. */
    private static final double AUTOPILOT_DEADZONE = 4.0;

    /** Ticks the mouse must be idle before the free-look view starts easing back behind the bike (~0.6 s at 20 tps). */
    private static final int IDLE_RECENTRE_TICKS = 12;
    /** Fraction of the remaining yaw gap the view closes per tick once recentring (smooth, no snap). Tuned to settle
     *  the ~0.5 s "ease behind the bike" the owner asked for while staying gentle. */
    private static final double RECENTRE_EASE = 0.18;
    /** Below this per-tick yaw change (degrees) the mouse counts as idle (filters rounding, not real look input). */
    private static final double MOUSE_IDLE_EPSILON = 0.05;

    // Free-look idle tracking for the single local player: the last observed view yaw/pitch and how long the mouse
    // has held still. Reset whenever the player is not on a race bike.
    private static float lastViewYaw = Float.NaN;
    private static float lastViewPitch = Float.NaN;
    private static int mouseIdleTicks;

    /** Autodrive lap logging (single local player), so a test run records a completed lap. */
    private static double autoLastFrac = -1;
    private static int autoLaps;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("dmz_ragnarok");

    public static void handle(Minecraft mc, HoverbikeEntity bike)
    {
        if (mc.player == null)
            return;

        RaceDriveParams params = RaceClientState.params();
        if (params != null)
            bike.setRaceParams(params);
        bike.setRaceSurface(RaceClientState.surfaceBlockSet());

        net.minecraft.client.player.Input in = mc.player.input;
        // Steering keys: the movement keys OR the rebindable race arrow keys, so either drives the kart physics.
        boolean up = in.up || net.shurui.shuruisutilities.client.SUKeybinds.RACE_ACCELERATE.isDown();
        boolean down = in.down || net.shurui.shuruisutilities.client.SUKeybinds.RACE_BRAKE.isDown();
        boolean left = in.left || net.shurui.shuruisutilities.client.SUKeybinds.RACE_STEER_LEFT.isDown();
        boolean right = in.right || net.shurui.shuruisutilities.client.SUKeybinds.RACE_STEER_RIGHT.isDown();
        boolean jump = in.jumping;

        KartState st = bike.raceKartState();
        if (st.autopilotTicks > 0)
        {
            int steer = autopilotSteer(bike);
            up = true;
            down = false;
            left = steer < 0;
            right = steer > 0;
            jump = false;
        }

        bike.setInput(up, down, left, right, mc.options.keySprint.isDown(), jump);

        // Free-look chase camera: the player's yaw is NOT locked to the bike (the mouse aims and free-looks). When the
        // mouse has been idle a moment, ease the view smoothly back behind the bike heading so it settles into a chase
        // view without snapping. Autopilot also recentres (there is no look input to preserve).
        updateFreeLookCamera(mc, bike, st.autopilotTicks > 0);
    }

    // Track the mouse: if the view has held still for IDLE_RECENTRE_TICKS, ease the player yaw toward the bike heading
    // by RECENTRE_EASE per tick. Any real look movement resets the idle timer, so the player keeps full free-look and
    // aim; the view only drifts back on its own once they stop moving the mouse.
    private static void updateFreeLookCamera(Minecraft mc, HoverbikeEntity bike, boolean forceRecentre)
    {
        float viewYaw = mc.player.getYRot();
        float viewPitch = mc.player.getXRot();

        boolean moved = !Float.isNaN(lastViewYaw)
                && (Math.abs(Mth.wrapDegrees(viewYaw - lastViewYaw)) > MOUSE_IDLE_EPSILON
                        || Math.abs(viewPitch - lastViewPitch) > MOUSE_IDLE_EPSILON);
        if (moved)
            mouseIdleTicks = 0;
        else
            mouseIdleTicks++;

        if (forceRecentre || mouseIdleTicks >= IDLE_RECENTRE_TICKS)
        {
            float target = bike.getYRot();
            float delta = Mth.wrapDegrees(target - viewYaw);
            float eased = viewYaw + delta * (float) RECENTRE_EASE;
            mc.player.setYRot(eased);
            mc.player.yRotO = eased; // no interpolation jump; the ease is already smooth
            viewYaw = eased;
        }

        lastViewYaw = viewYaw;
        lastViewPitch = viewPitch;
    }

    /**
     * The local player's aim direction for a race attack (packet 114): the crosshair look direction projected onto the
     * horizontal road plane, normalised. Falls back to the player's yaw heading when looking near-straight up or down
     * (a degenerate horizontal projection). Server-validated, so this only needs to be a sane unit vector.
     */
    public static double[] aimDirection(Minecraft mc)
    {
        if (mc.player == null)
            return new double[] { 0.0, 1.0 };
        net.minecraft.world.phys.Vec3 look = mc.player.getViewVector(1.0F);
        double x = look.x;
        double z = look.z;
        double len = Math.sqrt(x * x + z * z);
        if (len < 1.0e-4)
        {
            double rad = Math.toRadians(mc.player.getYRot());
            return new double[] { -Math.sin(rad), Math.cos(rad) };
        }
        return new double[] { x / len, z / len };
    }

    // -1 steer left, +1 steer right, 0 hold, aiming a few points ahead on the centreline.
    private static int autopilotSteer(HoverbikeEntity bike)
    {
        List<float[]> line = RaceClientState.centreline();
        if (line.isEmpty())
            return 0;
        double bx = bike.getX();
        double bz = bike.getZ();
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < line.size(); i++)
        {
            float[] pt = line.get(i);
            double dx = pt[0] - bx;
            double dz = pt[2] - bz;
            double d2 = dx * dx + dz * dz;
            if (d2 < best)
            {
                best = d2;
                nearest = i;
            }
        }
        // lap logging for the autodrive test: progress wraps from near the end back to the start
        double frac = nearest / (double) line.size();
        if (autoLastFrac >= 0 && autoLastFrac > 0.8 && frac < 0.2)
        {
            autoLaps++;
            LOG.info("[race autodrive] completed lap {} (client autopilot along the centreline)", autoLaps);
        }
        autoLastFrac = frac;

        // aim a few samples ahead (the centreline wraps around a lap)
        float[] target = line.get((nearest + 4) % line.size());
        double dx = target[0] - bx;
        double dz = target[2] - bz;
        double desiredYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double delta = Mth.wrapDegrees(desiredYaw - bike.getYRot());
        if (delta > AUTOPILOT_DEADZONE)
            return 1; // increase yaw = turn right
        if (delta < -AUTOPILOT_DEADZONE)
            return -1; // decrease yaw = turn left
        return 0;
    }
}
