package net.shurui.shuruisutilities.core.mixin.client;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.dragonminez.common.init.entities.SpacePodEntity;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.shurui.shuruisutilities.client.autopilot.SpaceAutopilotClient;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Reshapes the ki aura into rocket exhaust for the LOCAL player while they are piloting a DMZ space pod under space
 * autopilot, so the flame reads as thrust rather than a standing aura. {@link MixinDmzAuraLayer} already QUEUES the aura
 * for that pilot (and gates the same autopilot condition), but queueing cannot alter the draw; this mixin supplies the
 * geometry.
 *
 * <p>DMZ's {@code AuraRenderer.executeAuraShaderDraw} builds the third-person aura pose as
 * {@code pushPose; translate; mulPose(camera.rotation()); mulPose(YP 180); [mulPose(ZP) if over-shoulder];
 * scale(finalX, finalY*pitchSquash, finalZ); translate(0, 0.7, 0)}. The pose already BAKES world->view (DMZ draws the
 * aura via {@code VertexBuffer.drawWithShader} with the pose AS the model-view, no separate RenderSystem modelview), and
 * the {@code mulPose(camera.rotation())} + {@code mulPose(YP180)} pair only billboards the quad. We {@link Redirect}
 * exactly the {@code scale} call in that block (ordinal 2 across the whole method: two earlier {@code scale} calls set up
 * the model matrices first) and, for a pod pilot, reorient the aura in TRUE WORLD SPACE so its long axis points out the
 * back of the pod (opposite the pod's heading), then enlarge it, before applying the scale.</p>
 *
 * <p>The plume is welded to the pod's rear in the world, so it points straight back from every camera angle: the pilot
 * in first or third person, and any OTHER player watching the pod go by, all see the exhaust behind it rather than
 * standing up like a normal aura. We do this by cancelling exactly DMZ's OWN two billboard multipliers
 * ({@code mulPose(camera.rotation())} then {@code mulPose(YP180)}) and laying our world orientation on in their place:
 * {@code append = YP180 * camera.rotation()^-1 * worldOrient}, so the pose keeps whatever world->view it already baked
 * (vanilla OR a shaderpack view stack) and ends at {@code world->view * worldOrient}. Cancelling DMZ's own multipliers
 * rather than reconstructing world->view from the camera is what makes it correct under Iris too: under Iris the pose
 * DMZ bakes does NOT carry the half turn {@code camera.rotation()} carries, so reconstructing the view left a stray 180
 * that aimed the plume forwards. An earlier build reverted this to a fixed screen-space half turn to dodge that bug, but
 * a fixed turn only reads as "back" from one angle and points down or sideways from every other, which is the regression
 * this restores. {@code require = 0} plus a full try/catch means any future DMZ reshape of this block degrades to DMZ's
 * own scale, never a crash, so the fragility that motivated the revert cannot take the client down.</p>
 *
 * <p>Ordinal choice is deliberate, not incidental. Another aura-shaping mod redirects {@code mulPose(camera.rotation())}
 * (ordinal 0) and the trailing {@code translate} (ordinal 2) in this SAME method. Two {@code @Redirect}s on one
 * invocation is illegal in Mixin, so targeting the {@code scale} at ordinal 2 (a call neither of those touches) lets both
 * mods coexist. We do NOT redirect {@code mulPose}, the trailing {@code translate}, or {@code Camera.getXRot()}, even
 * though some of those would read nicer in isolation; coexistence wins.</p>
 *
 * <p>The target method is {@code private static}, so the handler is static and captures the method's own parameters by
 * appending the full parameter list (verified against the deobf DMZ jar with {@code javap}); the pilot is parameter 0.
 * The two {@code AuraRenderer} inner types are captured as {@code @Coerce Object} because {@code CachedAuraData} is
 * package-private and neither is needed here. {@code remap = false} on the injector keeps the DMZ-only method name
 * literal, while {@code remap = true} on the {@code @At} lets the refmap translate {@code PoseStack.scale} to its SRG
 * name ({@code m_85841_}) so the redirect still binds against the obfuscated production DMZ jar.</p>
 *
 * <p>{@code require = 0}: DMZ is a hard dependency so the class is present, but a future DMZ build that reshapes this
 * method degrades to leaving the aura un-reshaped instead of crashing the client. The whole body is wrapped so any
 * failure falls back to the untouched {@code scale}: a cosmetic effect must never break the render pipeline. A miss is
 * silent, so the one-shot bind log below is the only runtime proof it wove. Client-only (listed in the "client" block of
 * {@code mixins.shuruisutilities.json}); it never loads server-side.</p>
 */
@Mixin(targets = "com.dragonminez.client.render.effects.AuraRenderer", remap = false)
public abstract class MixinDmzAuraRenderer
{
    // uniform enlargement on X and Y so the exhaust reads bigger than a standing aura. Z is left alone on purpose: even
    // after the world-space reorientation below the plume should not have its own depth stretched. Tuning knob.
    private static final float POD_AURA_ENLARGE = 1.6F;

    // fraction of the pod's bounding-box height, measured up from its origin (its feet/base), that locates the pod's
    // visual centre. The centre redirect drops the aura by the live ride offset (down to the pod origin) and then lifts
    // it back up by this much so the exhaust sits on the pod's body rather than at its base. Tuning knob: larger raises
    // the aura toward the pod's nose, smaller sinks it toward the base.
    private static final float POD_CENTRE_HEIGHT_FRACTION = 0.5F;

    private static final AtomicBoolean SU_AURA_RENDERER_BIND_LOGGED = new AtomicBoolean(false);

    private static final AtomicBoolean SU_AURA_CENTRE_BIND_LOGGED = new AtomicBoolean(false);

    // Redirect the aura-pose scale (ordinal 2). method = the DMZ-only name is kept literal (remap = false); the @At is
    // remap = true so PoseStack.scale is refmapped to m_85841_ against the obfuscated production jar. The full trailing
    // parameter list captures executeAuraShaderDraw's own args; the pilot is parameter 0. require = 0 keeps a missed
    // target silent (no reshape) rather than failing the SU mixin config and crashing clients, so the bind log below is
    // the only proof it wove.
    @Redirect(
            method = "executeAuraShaderDraw",
            require = 0,
            remap = false,
            at = @At(
                    value = "INVOKE",
                    ordinal = 2,
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V",
                    remap = true))
    private static void su$reshapePodAura(PoseStack pose, float sx, float sy, float sz,
            Player player, @Coerce Object cachedAuraData, @Coerce Object auraLayer, PoseStack poseStack,
            Minecraft minecraft, Matrix4f projection, float partialTick, float alpha, boolean overShoulder)
    {
        // log-once on first weave/invocation. require = 0 fails SILENTLY, so this line appearing is the proof the
        // injector bound; its absence is the proof it did not. Guarded + latched so it prints exactly once.
        if (SU_AURA_RENDERER_BIND_LOGGED.compareAndSet(false, true))
        {
            try
            {
                LoggingHandler.sulog.info("[SpacePod] MixinDmzAuraRenderer bound "
                        + "(AuraRenderer.executeAuraShaderDraw scale ordinal 2, world-space pod exhaust orientation)");
            }
            catch (Throwable ignored)
            {
            }
        }
        try
        {
            // Reshape the aura into rear-pointing exhaust for a pod pilot. WHO qualifies:
            //   - the LOCAL pilot, only under autopilot (a genuinely powered-up hand-flying local pilot keeps a normal
            //     aura, matching MixinDmzAuraLayer's show/hide decision);
            //   - ANY remote pilot whose aura is being drawn, so other players watching a pod see the exhaust point out
            //     the back too. A remote client cannot see that pod's autopilot state, so we do not gate remote pods on
            //     it: a pod pilot's aura reads as thrust regardless.
            SpacePodEntity pod = su$ridingSpacePod(player);
            boolean localPilot = player == Minecraft.getInstance().player;
            boolean reshape = pod != null && (!localPilot || SpaceAutopilotClient.isActive());
            Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
            if (reshape && camera != null)
            {
                // The aura reaches this draw in a pose that already BAKES world->view (DMZ draws it via
                // VertexBuffer.drawWithShader with pose.last().pose() AS the model-view), then multiplies in
                // mulPose(camera.rotation()) and mulPose(YP180) to billboard the quad. We UNDO exactly those two DMZ
                // multipliers and lay worldOrient on in their place, so the aura's long axis points out the pod's rear
                // in the WORLD, welded to the pod: it turns with the pod's heading, never with the camera, and reads
                // correctly for the pilot AND for any other viewer, because nothing about the orientation depends on
                // where the camera is. Cancelling DMZ's OWN camera.rotation()/YP180 (rather than rebuilding world->view
                // from the camera) is what keeps it correct under the Iris shaderpack view stack, where the baked pose
                // does not carry the half turn camera.rotation() carries.

                // The aura's long axis is LOCAL +Y (DMZ's billboard quad spans local X/Y in the z=0 plane with the
                // texture top at +Y, and DMZ's trailing translate(0, 0.7, 0) lifts the flame up +Y).
                final Vector3f auraLongAxis = new Vector3f(0f, 1f, 0f);

                // The pod's rear in world space, opposite its facing. MC forward = (-sin(yaw)cos(pitch), -sin(pitch),
                // cos(yaw)cos(pitch)); rear is its negation, so the exhaust points exactly opposite travel/facing in 3D
                // (a climb or dive tilts the plume too). getViewYRot/getViewXRot are interpolated so it stays smooth.
                float yawRad = (float) Math.toRadians(pod.getViewYRot(partialTick));
                float pitchRad = (float) Math.toRadians(pod.getViewXRot(partialTick));
                float cosPitch = (float) Math.cos(pitchRad);
                Vector3f rearWorld = new Vector3f(
                        (float) Math.sin(yawRad) * cosPitch,
                        (float) Math.sin(pitchRad),
                        (float) -Math.cos(yawRad) * cosPitch);
                if (rearWorld.lengthSquared() < 1.0e-6f)
                {
                    rearWorld.set(0f, 0f, -1f);
                }

                // shortest-arc rotation that carries the aura's long axis onto the pod's rear.
                Quaternionf worldOrient = new Quaternionf().rotationTo(auraLongAxis, rearWorld.normalize());

                // Undo DMZ's billboard pair and apply worldOrient as one appended rotation. Cancelling only DMZ's own
                // multipliers leaves the pose's baked world->view (vanilla or shaderpack) untouched.
                Quaternionf camRotInv = new Quaternionf(camera.rotation()).conjugate();
                Quaternionf append = new Quaternionf().rotationY((float) Math.PI).mul(camRotInv).mul(worldOrient);
                pose.mulPose(append);

                // enlarge into an exhaust plume.
                pose.scale(sx * POD_AURA_ENLARGE, sy * POD_AURA_ENLARGE, sz);
                return;
            }
            // not a qualifying pod pilot (or no camera): the exact vanilla scale, zero behaviour change for everyone else.
            pose.scale(sx, sy, sz);
        }
        catch (Throwable t)
        {
            // cosmetic only: any failure leaves the aura at DMZ's own scale rather than breaking the frame.
            pose.scale(sx, sy, sz);
        }
    }

    // Redirect the FIRST world-space translate (ordinal 1: DMZ's translate(0.0, 0.05, 0.0)), which runs immediately
    // BEFORE mulPose(camera.rotation()) converts the pose into camera-relative space. Verified against the deobf DMZ jar
    // with javap: in executeAuraShaderDraw the translate:(DDD)V calls are ordinal 0 (an earlier model-matrix block),
    // then ordinal 1 = the (0.0, 0.05, 0.0) call sitting between pushPose and the camera-rotation mulPose. Correcting the
    // aura's centre HAS to happen here, in world/entity space; a correction applied after the camera rotation would be
    // screen-space and would swim as the camera moved. Ordinal 1 is also the one translate in this method that NoeaBosses
    // leaves alone (it redirects mulPose(camera.rotation()) ordinal 0 and the trailing translate ordinal 2), so this
    // redirect and that mod coexist. remap = false keeps the DMZ-only method name literal; the @At is remap = true so
    // PoseStack.translate is refmapped against the obfuscated production jar. Full trailing parameter list captures
    // executeAuraShaderDraw's own args; the pilot is parameter 0. require = 0 keeps a missed target silent.
    @Redirect(
            method = "executeAuraShaderDraw",
            require = 0,
            remap = false,
            at = @At(
                    value = "INVOKE",
                    ordinal = 1,
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V",
                    remap = true))
    private static void su$centrePodAura(PoseStack pose, double x, double y, double z,
            Player player, @Coerce Object cachedAuraData, @Coerce Object auraLayer, PoseStack poseStack,
            Minecraft minecraft, Matrix4f projection, float partialTick, float alpha, boolean overShoulder)
    {
        // log-once on first weave/invocation, latched independently of the scale redirect so this line appearing proves
        // THIS redirect bound on its own. require = 0 fails SILENTLY, so its presence/absence is the only runtime proof.
        if (SU_AURA_CENTRE_BIND_LOGGED.compareAndSet(false, true))
        {
            try
            {
                LoggingHandler.sulog.info("[SpacePod] MixinDmzAuraRenderer centre redirect bound (translate ordinal 1)");
            }
            catch (Throwable ignored)
            {
            }
        }
        try
        {
            if (player != null)
            {
                SpacePodEntity pod = su$ridingSpacePod(player);
                if (pod != null)
                {
                    // DMZ seats the pilot at the pod's position plus a Y-only ride offset, then draws the aura relative to
                    // the PILOT, so the aura floats that offset above the pod. Read the offset LIVE (this frame's real Y
                    // separation) so it stays correct if DMZ ever retunes its seating; then lift back up to the pod's
                    // visual centre rather than its origin (which sits at the pod's base).
                    double rideOffsetY = player.getY() - pod.getY();
                    double podCentre = POD_CENTRE_HEIGHT_FRACTION * pod.getBbHeight();
                    double podCentreDrop = rideOffsetY - podCentre;
                    pose.translate(x, y - podCentreDrop, z);
                    return;
                }
            }
            // not a pilot: the exact vanilla translate, zero behaviour change for everyone else.
            pose.translate(x, y, z);
        }
        catch (Throwable t)
        {
            // cosmetic only: any failure leaves the aura at DMZ's own position rather than breaking the frame.
            pose.translate(x, y, z);
        }
    }

    // the DMZ space pod the rendered player is riding (directly or nested), or null. Both redirects need the pod ENTITY
    // (the scale redirect for its heading, the centre redirect for its live Y and bounding box), so this returns it and
    // keeps the single ride walk in one place. This walk is duplicated per caller across the codebase (server-side
    // PlanetCourse and SpaceTravelModule each have their own, and MixinDmzAuraLayer walks an AbstractClientPlayer), which
    // matches the established idiom rather than forcing a shared helper through the working aura-layer mixin. The local
    // pilot's autopilot state (the extra gate on the LOCAL pilot only) comes from SpaceAutopilotClient, the client mirror
    // the server syncs via PacketSpaceAutopilotSync to the controlling pilot; a remote pod pilot's autopilot is unknown
    // here, which is why remote pods are reshaped whenever their aura is drawn rather than gated on it.
    private static SpacePodEntity su$ridingSpacePod(Player player)
    {
        for (Entity vehicle = player.getVehicle(); vehicle != null; vehicle = vehicle.getVehicle())
        {
            if (vehicle instanceof SpacePodEntity pod)
            {
                return pod;
            }
        }
        return null;
    }
}
