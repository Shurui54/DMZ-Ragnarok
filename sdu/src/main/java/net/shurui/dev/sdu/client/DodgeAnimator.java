package net.shurui.dev.sdu.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import software.bernie.geckolib.model.GeoModel;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side, purely cosmetic dodge flourish: when a player dodges (resolved on the server, which
 * then broadcasts a {@link net.shurui.dev.sdu.network.DodgeAnimPacket}), their upper body twists 45
 * degrees to one side and eases back to normal over {@link #DURATION_MS}.
 *
 * <p>The twist is applied in {@link #apply} from a {@code PlayerModel.setupAnim} mixin, so it stacks
 * on top of whatever pose the model is already in. It deliberately does nothing to the local player
 * while the camera is in first person, so the first-person view/held-hand is never affected.
 */
public final class DodgeAnimator {

    /** Full twist angle at the peak of the animation (45 degrees). */
    private static final float MAX_ANGLE = Mth.PI / 4f;
    /** Total out-and-back duration, in milliseconds. */
    private static final long DURATION_MS = 500L;

    /** entityId -> in-progress twist. Main (render/client) thread only, so a plain HashMap is fine. */
    private static final Map<Integer, State> ACTIVE = new HashMap<>();

    private record State(long start, float sign) {
    }

    private DodgeAnimator() {
    }

    /** Begin (or restart) the twist for an entity. {@code left} picks the direction. */
    public static void trigger(int entityId, boolean left) {
        ACTIVE.put(entityId, new State(System.currentTimeMillis(), left ? 1f : -1f));
    }

    /**
     * Current twist angle for an entity in radians (0 when idle). A half-sine ramp gives a smooth
     * out-and-back: 0 -> +MAX at the midpoint -> 0. Expired entries are dropped here.
     */
    private static float angle(int entityId) {
        State s = ACTIVE.get(entityId);
        if (s == null) {
            return 0f;
        }
        long dt = System.currentTimeMillis() - s.start;
        if (dt < 0 || dt >= DURATION_MS) {
            ACTIVE.remove(entityId);
            return 0f;
        }
        float t = dt / (float) DURATION_MS; // 0..1
        return s.sign * MAX_ANGLE * Mth.sin(Mth.PI * t);
    }

    /** Apply the current twist to a player model. Called at the tail of {@code setupAnim}. */
    public static void apply(PlayerModel<?> model, LivingEntity entity) {
        float angle = angle(entity.getId());
        if (angle == 0f) {
            return;
        }
        // Never twist the local player's own model while in first person: that render path draws the
        // held arm directly, so a twist would leak into the first-person view.
        Minecraft mc = Minecraft.getInstance();
        if (entity == mc.player && mc.options.getCameraType().isFirstPerson()) {
            return;
        }

        float sin = Mth.sin(angle);
        float cos = Mth.cos(angle);

        // Torso twists fully; the head follows at half so the character still roughly faces its look
        // direction rather than snapping its gaze 45 degrees away.
        model.body.yRot += angle;
        model.head.yRot += angle * 0.5f;
        twistArm(model.rightArm, angle, sin, cos);
        twistArm(model.leftArm, angle, sin, cos);

        // Vanilla setupAnim already copied the outer skin layers from the base parts before this
        // injection ran, so re-sync the ones we just moved (hat/jacket/sleeves).
        model.hat.copyFrom(model.head);
        model.jacket.copyFrom(model.body);
        model.rightSleeve.copyFrom(model.rightArm);
        model.leftSleeve.copyFrom(model.leftArm);
    }

    /**
     * Apply the current twist to a DMZ GeckoLib player model (the path actually used in-game, since DMZ
     * replaces the vanilla player renderer). In that rig the {@code waist} bone parents the head, body
     * and both arms while the legs hang off {@code root}, so rotating {@code waist} alone twists the
     * upper body and leaves the legs planted. Called at the tail of {@code DMZPlayerModel.setCustomAnimations}.
     */
    public static void applyGeo(GeoModel<?> model, int entityId) {
        float angle = angle(entityId);
        if (angle == 0f) {
            return;
        }
        // DMZ renders the first-person POV with this same model, so keep the local player untouched in
        // first person (matches the "don't affect first person view" requirement).
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && entityId == mc.player.getId() && mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        // Add on top of whatever DMZ already posed the waist to this frame; GeckoLib resets bones from
        // the animation snapshot each frame, so this stacks cleanly without drifting.
        model.getBone("waist").ifPresent(bone -> bone.setRotY(bone.getRotY() + angle));
    }

    /**
     * Swing an arm around the body's central vertical axis: rotate its shoulder pivot (in the x/z
     * plane) and turn the arm itself by the same angle so it stays attached to the twisting torso.
     * The rest pivot is a constant (x = +/-5, z = 0), so applying this each frame from those live
     * values is stable and returns to rest when the angle hits 0.
     */
    private static void twistArm(ModelPart arm, float angle, float sin, float cos) {
        float x = arm.x;
        float z = arm.z;
        arm.x = x * cos - z * sin;
        arm.z = x * sin + z * cos;
        arm.yRot += angle;
    }
}
