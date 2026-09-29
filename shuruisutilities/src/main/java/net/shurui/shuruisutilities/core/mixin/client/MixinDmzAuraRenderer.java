package net.shurui.shuruisutilities.core.mixin.client;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.dragonminez.common.init.entities.SpacePodEntity;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import org.joml.Matrix4f;

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
 * the model matrices first) and, only for the local autopilot pilot, add ONE fixed half turn so the plume points out
 * the back instead of up, then enlarge it.</p>
 *
 * <p>This used to weld the plume to the pod in WORLD space: it cancelled DMZ's two billboard multipliers and laid the
 * aura's long axis along the pod's rear heading, so the exhaust turned with the pod and never with the camera. That
 * reads better on paper and it did not survive contact. Cancelling another mod's matrices means depending on exactly
 * which matrices they are, and that assumption breaks under a shaderpack view stack, under DMZ's over-shoulder camera,
 * and any time DMZ reorders that block. When it broke it did not fail quietly: the plume swung, span, or aimed
 * forwards. A fixed rotation on top of whatever billboard DMZ built cannot do any of that, because nothing in it reads
 * the camera or the pod. The cost is stated plainly: the plume trails rather than tracking the pod's yaw.</p>
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

    // Half turn about the billboard's Z, which takes the aura's long axis (local +Y) from pointing up to pointing
    // back. The one knob for which way the exhaust lies; 180 is straight out the back. Nothing here reads the camera
    // or the pod, so whatever this is set to, it stays there.
    private static final float POD_AURA_BACKWARD_DEG = 180.0F;

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
            // pod exhaust is an autopilot-only cue for the LOCAL pilot; anyone else, or any non-autopilot pod, falls
            // through to DMZ's own scale unchanged. See su$podExhaustActive for how the client learns the autopilot state.
            SpacePodEntity pod = su$podExhaustActive(player) ? su$ridingSpacePod(player) : null;
            if (pod != null)
            {
                // The plume is pointed backwards and LEFT there. It used to be welded to the pod in world space:
                // DMZ's own billboard multipliers were cancelled and the aura's long axis laid along the pod's rear
                // heading, so the exhaust turned with the pod and never with the camera. That is the better idea on
                // paper and it did not survive contact. Cancelling another mod's matrices means depending on exactly
                // which matrices they are, and that assumption breaks under a shaderpack view stack, under DMZ's
                // over-shoulder camera, and any time DMZ reorders that block; when it broke, the plume swung, span or
                // aimed forward instead of failing quietly.
                //
                // So: keep DMZ's billboard exactly as it built it and add ONE fixed rotation on top. The aura's long
                // axis is local +Y, so a half turn about the billboard's Z points it the other way, out the back. It
                // cannot swing, because nothing here reads the camera or the pod's heading. The cost is honest: the
                // plume no longer tracks the pod's yaw, it simply trails. The line trail behind the pod is drawn
                // elsewhere (DashTrailRenderer) and is untouched by any of this.
                pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(POD_AURA_BACKWARD_DEG));

                // enlarge into an exhaust plume, exactly as before.
                pose.scale(sx * POD_AURA_ENLARGE, sy * POD_AURA_ENLARGE, sz);
                return;
            }
            // not the local pilot, or no autopilot armed: the exact vanilla scale, zero behaviour change for everyone else.
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
    // matches the established idiom rather than forcing a shared helper through the working aura-layer mixin.
    // true only when the pod exhaust should shape this aura: the rendered player is the LOCAL client's own player AND
    // that client's space autopilot is armed. SpaceAutopilotClient is the client mirror the server syncs (via
    // PacketSpaceAutopilotSync) ONLY to the controlling pilot, so it describes the local player and no one else; a remote
    // pod pilot's autopilot state is simply not known on this client, so their aura is never reshaped into an exhaust.
    // This is the client-side twin of the server's SpaceAutopilot.isActive(player) that the space-hazard immunity uses,
    // so the two features cannot disagree about when a pod is "auto travelling".
    private static boolean su$podExhaustActive(Player player)
    {
        if (player == null || player != Minecraft.getInstance().player)
        {
            return false;
        }
        return SpaceAutopilotClient.isActive();
    }

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
