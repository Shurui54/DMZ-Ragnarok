package net.shurui.shuruisutilities.client.space;

import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Client-side cinematic camera for the PLANET clash. DragonMineZ already engages a third-person clash camera for our
 * struggle (its own {@code BeamClashStateS2C} activates {@code BeamClashCinematicCamera} because our attacker is a real
 * {@code ServerPlayer}); the only defect is FRAMING. DragonMineZ frames the midpoint between the local player's eye and
 * the OPPONENT OWNER, which for us is the invisible {@code PlanetDefenderEntity} that sits 50..300 blocks away out at the
 * clash point, so DragonMineZ either falls back to a shot of the player (opponent untracked) or frames empty space 16
 * blocks off a midpoint over a hundred blocks away. This holder supplies the SHOT DragonMineZ's own camera then applies,
 * framing the actual struggle instead.
 *
 * <h2>How it plugs in</h2>
 * We do NOT run our own camera. {@code MixinDmzClashCamera} injects the HEAD of DragonMineZ's {@code computeShot} and,
 * only when this holder reports a planet clash is active, returns the shot {@link #computeShot} builds. DragonMineZ's own
 * {@code CameraMixin} then applies it exactly as it applies a normal clash shot. That means there is never a second
 * camera and never a fight: DragonMineZ computes one shot per frame and, for a planet clash, that shot is ours. Enter and
 * exit are entirely DragonMineZ's: it turns the third-person camera on and off off its own packet, so we can never strand
 * a displaced camera. See the exit note on {@link #accept}.
 *
 * <h2>The anchor</h2>
 * The struggle happens at the player's own giant ball, which the server holds nearly still for the whole clash and which
 * IS client-tracked. The server sends us that ball's entity id (see {@link net.shurui.shuruisutilities.space.PacketPlanetClashCam});
 * we look the entity up every frame so the anchor is always the ball's live position, never a stale copy.
 *
 * <h2>The framing</h2>
 * Take DragonMineZ's {@code computeShot} as the stylistic reference: a side offset, a height lift, a decisiveness-scaled
 * positional and angular shake, and a line-of-sight clamp so the camera never ends up inside geometry. The one difference
 * is composition: DragonMineZ stands off to the SIDE and looks at a midpoint, which only works when the two fighters are a
 * few blocks apart. Our "opponent" is a whole planet up to 300 blocks down the axis, so a pure side-on shot would push the
 * planet ~90 degrees off screen. Instead we sit BEHIND the ball (on the far side from the planet), lifted and offset for a
 * three-quarter angle, and look at the ball. The planet, being beyond the ball along the same axis and huge, fills the
 * background, so both the ball and the planet stay readable in one frame.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class PlanetClashCamera
{

    // How far BEHIND the ball (opposite the planet) the camera sits, in blocks, before the ball-size term. Small enough
    // that the ball reads large in the foreground, large enough that the giant ball is not clipping the near plane.
    private static final double BACK_BASE = 8.0;
    // Extra standoff per block of ball width, so a bigger world-ender does not overflow the frame. A giant ball's width
    // runs a handful of blocks, so this adds roughly another handful of blocks of pull-back.
    private static final double BACK_PER_WIDTH = 1.2;
    // Hard clamp on the behind-distance so neither a zero-size read nor a freak width can jam the camera into the ball or
    // fling it uselessly far.
    private static final double BACK_MIN = 8.0;
    private static final double BACK_MAX = 26.0;
    // Side offset and height lift as fractions of the behind-distance, giving a stable three-quarter angle at any pull
    // back rather than a flat dead-behind view.
    private static final double SIDE_RATIO = 0.45;
    private static final double HEIGHT_RATIO = 0.32;

    // Positional and angular shake, copied from DragonMineZ so the feel matches a normal clash. The multipliers and the
    // sine/cosine frequencies are its values verbatim; decisiveness scales them from a calm 0.5x up to 1.5x as the meter
    // swings decisively one way (see readDecisiveness).
    private static final double SHAKE_POS = 0.16;
    private static final float SHAKE_ANGLE = 0.9F;
    // Positional smoothing toward the freshly computed camera spot, so a tick of ball jitter glides instead of snapping.
    // On the FIRST frame of a clash we snap instead (see needSnap) so there is no swing in from a stale position.
    private static final float POSITION_SMOOTH = 0.25F;
    // Line-of-sight clamp margin, in blocks: pull the camera this far short of any block between it and the clash point,
    // so it never ends up inside an asteroid or the planet itself. DragonMineZ uses the same 0.5 margin.
    private static final double WALL_MARGIN = 0.5;
    // Degrees-per-radian, for the yaw/pitch conversion (Mth.atan2 returns radians). Named so the maths reads plainly.
    private static final double RAD_TO_DEG = 57.29577951308232;

    // whether the current DragonMineZ clash is one of OURS. Set by the server packet; forced back false the moment
    // DragonMineZ's own clash ends (see onClientTick), so our override can never outlive the cinematic it rides on.
    private static volatile boolean planetClash = false;
    // the anchor ball's entity id, or -1. Its live position is the clash point.
    private static volatile int ballEntityId = -1;
    // the target planet centre and visual radius, for the framing axis and size.
    private static volatile Vec3 planetCentre = Vec3.ZERO;
    private static volatile float planetRadius = 0.0F;

    // the smoothed camera position carried between frames, and whether the next frame should SNAP to it (first frame of a
    // fresh clash) rather than lerp. Render-thread only, so plain fields are fine.
    private static Vec3 smoothedCam = null;
    private static boolean needSnap = true;

    // latched one-shot log so a failure in the framing maths (a DragonMineZ API drift, a null we did not foresee) is
    // reported ONCE and then never again, and never crashes the render thread: on any failure we simply supply no shot
    // and DragonMineZ's own (valid, if poorly framed) camera stands, which is exactly the pre-existing behaviour.
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean(false);

    private PlanetClashCamera()
    {
    }

    /**
     * Server marker sink (via {@link net.shurui.shuruisutilities.space.PacketPlanetClashCam}). Flips our framing override
     * on or off and caches the anchor.
     *
     * <p><b>Exit safety.</b> An {@code active=false} here is only ONE of THREE independent ways the camera returns to
     * normal, so no exit path can strand it: (1) DragonMineZ turns its cinematic OFF the instant the clash resolves, the
     * ball is destroyed, the player dies or they change dimension, at which point its {@code computeShot} is not called
     * and our override is never consulted; (2) {@link #onClientTick} forces our flag false whenever DragonMineZ's clash is
     * no longer active, tying our lifetime to its; (3) if the anchor ball entity cannot be found (gone, or a different
     * dimension), {@link #computeShot} returns null and DragonMineZ's own shot stands. This packet's false is the cleanest
     * of the three but not the load-bearing one.
     */
    public static void accept(boolean active, int ballId, Vec3 centre, float radius)
    {
        if (active)
        {
            // a fresh activation (was off) must snap the smoothed camera to its first computed spot rather than glide in
            // from wherever the last clash left it.
            if (!planetClash)
            {
                needSnap = true;
            }
            ballEntityId = ballId;
            planetCentre = centre == null ? Vec3.ZERO : centre;
            planetRadius = radius;
            planetClash = true;
        }
        else
        {
            clear();
        }
    }

    /** Drop all planet-clash state so the next frame supplies no override. Idempotent. */
    private static void clear()
    {
        planetClash = false;
        ballEntityId = -1;
        planetCentre = Vec3.ZERO;
        planetRadius = 0.0F;
        smoothedCam = null;
        needSnap = true;
    }

    /** True while our framing override should be supplied. Read by the mixin. */
    public static boolean isPlanetClash()
    {
        return planetClash;
    }

    /**
     * Tie our lifetime to DragonMineZ's clash: the moment its cinematic is no longer active, force our flag false. This is
     * the belt-and-braces that guarantees we never keep overriding a camera DragonMineZ has already handed back to
     * vanilla, even if our own {@code inactive} packet was somehow missed. Wrapped so a DragonMineZ client-API drift here
     * only loses this one safeguard (the other two exits still hold) and never spams or crashes.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || !planetClash)
        {
            return;
        }
        try
        {
            if (!com.dragonminez.client.clash.ClientBeamClashState.isActive())
            {
                clear();
            }
        }
        catch (Throwable ignored)
        {
            // could not read DragonMineZ's clash state (API drift): leave our flag as the packet set it. The
            // computeShot null-anchor fallback and DragonMineZ's own deactivate still cover the exit.
        }
    }

    /**
     * Build the shot that frames the clash point, or {@code null} to decline (which leaves DragonMineZ's own shot in
     * place). Called only from the mixin, only while DragonMineZ's cinematic is active. Never throws: any failure logs
     * once and returns null.
     */
    public static Shot computeShot(BlockGetter level, LocalPlayer player, float partialTick)
    {
        if (!planetClash || player == null || level == null)
        {
            return null;
        }
        try
        {
            // the anchor: the player's own ball, looked up live so the clash point tracks the ball's real position. A
            // null lookup (ball gone / different dimension) declines the override, so a destroyed ball can never strand us.
            Level clientLevel = player.level();
            if (clientLevel == null)
            {
                return null;
            }
            Entity ball = clientLevel.getEntity(ballEntityId);
            if (ball == null)
            {
                return null;
            }
            Vec3 anchor = ball.getPosition(partialTick);

            // the framing axis: from the ball toward the planet. Fall back to the player's look if the ball is somehow
            // sitting exactly on the planet centre, so we never divide by a zero-length axis.
            Vec3 toPlanet = planetCentre.subtract(anchor);
            Vec3 axis = toPlanet.lengthSqr() < 1.0E-6 ? player.getViewVector(partialTick) : toPlanet.normalize();

            // a stable horizontal side vector, taken from the axis's horizontal projection so a steep shot still yields a
            // sane left/right. If the axis is very nearly vertical (planet straight up or down), pick a fixed side.
            Vec3 axisHoriz = new Vec3(axis.x, 0.0, axis.z);
            if (axisHoriz.lengthSqr() < 1.0E-4)
            {
                axisHoriz = new Vec3(1.0, 0.0, 0.0);
            }
            axisHoriz = axisHoriz.normalize();
            Vec3 side = new Vec3(0.0, 1.0, 0.0).cross(axisHoriz).normalize();

            // pull-back distance scaled by the ball's on-screen size (its bounding-box width, which DragonMineZ sizes to
            // the ball) so a bigger world-ender does not overflow the frame, then clamped.
            double ballWidth = ball.getBbWidth();
            double back = Mth.clamp(BACK_BASE + ballWidth * BACK_PER_WIDTH, BACK_MIN, BACK_MAX);
            double sideOff = back * SIDE_RATIO;
            double height = back * HEIGHT_RATIO;

            // the composed target: behind the ball (away from the planet), offset to the side and lifted, so the ball is
            // foreground and the planet backdrop. Look target is the ball itself, the actual struggle.
            Vec3 target = anchor
                    .add(axis.scale(-back))
                    .add(side.scale(sideOff))
                    .add(0.0, height, 0.0);

            // smooth toward the target (snap on the first frame of a clash), so ball jitter glides.
            if (needSnap || smoothedCam == null)
            {
                smoothedCam = target;
                needSnap = false;
            }
            else
            {
                smoothedCam = smoothedCam.add(target.subtract(smoothedCam).scale(POSITION_SMOOTH));
            }

            // decisiveness-scaled shake, DragonMineZ's constants and phases verbatim.
            float decisiveness = readDecisiveness();
            double shake = SHAKE_POS * (0.5 + (double) decisiveness);
            float angleShake = SHAKE_ANGLE * (0.5F + decisiveness);
            float t = (float) player.tickCount + partialTick;
            Vec3 camPos = smoothedCam
                    .add(side.scale(Math.sin((double) t * 1.7) * shake))
                    .add(0.0, Math.cos((double) t * 2.3) * shake * 0.6, 0.0);

            // never let the camera sit inside geometry between it and the clash point.
            camPos = clampToLineOfSight(level, ball, anchor, camPos);

            // look from the camera at the ball; convert to yaw/pitch exactly as DragonMineZ does, then add the angular
            // shake so the framing breathes like a real clash.
            Vec3 dir = anchor.subtract(camPos);
            double horiz = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
            float pitch = (float) (-(Mth.atan2(dir.y, horiz) * RAD_TO_DEG));
            float yaw = (float) (Mth.atan2(dir.z, dir.x) * RAD_TO_DEG - 90.0);
            yaw += (float) (Math.sin((double) t * 3.1) * (double) angleShake);
            pitch += (float) (Math.cos((double) t * 2.7) * (double) angleShake * 0.7);

            // guard against a NaN slipping into the camera transform, which would be a far worse bug than declining.
            if (!isFinite(camPos) || !Float.isFinite(yaw) || !Float.isFinite(pitch))
            {
                return null;
            }
            return new Shot(camPos, yaw, pitch);
        }
        catch (Throwable t)
        {
            if (FAILURE_LOGGED.compareAndSet(false, true))
            {
                LoggingHandler.sulog.warn("[PlanetClashCamera] Failed to build the planet-clash shot; falling back to "
                        + "DragonMineZ's own camera. This is logged once.", t);
            }
            return null;
        }
    }

    // read DragonMineZ's synced clash advantage (0..1, 0.5 even) and turn it into a 0..1 decisiveness the same way
    // DragonMineZ does: |advantage - 0.5| * 2. Guarded so a drifted getter simply yields a calm, centred shake.
    private static float readDecisiveness()
    {
        try
        {
            float advantage = com.dragonminez.client.clash.ClientBeamClashState.advantage();
            return Math.abs(advantage - 0.5F) * 2.0F;
        }
        catch (Throwable ignored)
        {
            return 0.0F;
        }
    }

    // DragonMineZ's line-of-sight clamp idea: raytrace from the clash point to the camera and, if a block is in the way,
    // pull the camera in to just short of it so the view is never inside geometry.
    private static Vec3 clampToLineOfSight(BlockGetter level, Entity viewer, Vec3 from, Vec3 to)
    {
        Vec3 delta = to.subtract(from);
        double len = delta.length();
        if (len < 1.0E-4)
        {
            return to;
        }
        Vec3 dir = delta.scale(1.0 / len);
        Vec3 rayEnd = from.add(dir.scale(len + WALL_MARGIN));
        BlockHitResult hit = level.clip(
                new ClipContext(from, rayEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
        if (hit.getType() != HitResult.Type.MISS)
        {
            double allowed = Math.max(0.0, hit.getLocation().distanceTo(from) - WALL_MARGIN);
            if (allowed < len)
            {
                return from.add(dir.scale(allowed));
            }
        }
        return to;
    }

    private static boolean isFinite(Vec3 v)
    {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    /** The camera transform we hand back through DragonMineZ's own {@code computeShot}. */
    public record Shot(Vec3 pos, float yaw, float pitch)
    {
    }
}
