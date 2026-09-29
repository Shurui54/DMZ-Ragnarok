package net.shurui.shuruisutilities.core.mixin.client;

import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shurui.shuruisutilities.client.combat.DashAuraPlacement;
import net.shurui.shuruisutilities.client.combat.DashAuraState;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Lays a dashing player's aura over the screen instead of standing it upright behind them.
 *
 * <p>A dash reads as speed only if the aura trails the direction of travel. DragonMineZ draws an aura as an upright
 * billboard anchored on the player, which is right for standing and charging and wrong for a player being flung
 * forwards. Rather than draw a second aura of our own, this reorients the one DMZ is already drawing, so the player
 * keeps their real aura colour, form cosmetics and shader, and the dash costs no extra draw.
 *
 * <h2>Which instructions this claims, and why that is safe</h2>
 * SU already mixes into this same method twice, in {@link MixinDmzAuraRenderer}: it owns {@code scale} ordinal 2 and
 * {@code translate} ordinal 1, and that class documents that it chose those two deliberately to leave
 * {@code mulPose} ordinal 0 and {@code translate} ordinal 2 free. This class claims exactly that free pair, plus
 * {@code Camera.getXRot} which nothing else touches. Two {@code @Redirect}s on ONE instruction is illegal in Mixin and
 * crashes the client, so the split matters and must be preserved by anything added here later.
 *
 * <h2>What each injection does</h2>
 * <ul>
 *   <li>The two {@code renderShaderAura} injections open and close a per draw note of which player is being drawn and
 *       whether their aura should be laid over. The redirects below fire inside that draw and have no other way to know
 *       whose aura they are shaping. It is a {@link ThreadLocal} because the render thread is the only one here and a
 *       plain field would be a data race waiting to happen.</li>
 *   <li>The fade constant is held up while dashing. DMZ fades an aura out quickly once it is not being sustained, which
 *       would blink the dash aura out mid dash.</li>
 *   <li>{@code Camera.getXRot} is reported as level while laid over. DMZ uses camera pitch to cull or foreshorten the
 *       plume, and a laid over aura is no longer in that geometry, so the real pitch makes it vanish at exactly the
 *       angles a dash is most visible.</li>
 *   <li>{@code translate} ordinal 2 is the upright anchor offset. Zeroed while laid over, since the placement is being
 *       driven from the screen space offset instead.</li>
 *   <li>{@code mulPose} ordinal 0 is the camera billboard. After it runs we add the pitch sampled screen offset and the
 *       trailing rotation, and mirror the whole thing when the over shoulder camera has swapped to the left side, so
 *       the aura trails behind the player from the viewer's side rather than crossing them.</li>
 * </ul>
 *
 * <p>Every injection is {@code require = 0} and every body is wrapped: if DMZ moves this code, the dash aura silently
 * goes back to standing upright rather than taking the client down. The bind log is the only proof it wove.
 */
@Mixin(targets = "com.dragonminez.client.render.effects.AuraRenderer", remap = false)
public abstract class MixinDmzAuraDashFlip
{
    // Degrees the laid over aura is rotated so its plume trails the travel direction rather than pointing up.
    private static final float TRAIL_ROTATION_DEG = -125.0F;

    // Fade floor while dashing. DMZ's own value is far lower, which is correct for an aura that is being released and
    // wrong for one that is being actively driven by a dash.
    private static final float DASH_FADE = 0.2F;

    private static final AtomicBoolean SU_DASH_FLIP_BIND_LOGGED = new AtomicBoolean(false);

    // The player whose aura is being drawn right now AND whose aura should be laid over, or null. Set at the head of
    // each aura draw and cleared at its return, so it can never leak into an unrelated draw.
    private static final ThreadLocal<AbstractClientPlayer> SU_LAID_OVER = new ThreadLocal<>();

    // Whether the current draw belongs to a dashing player at all. Separate from the above because the fade should be
    // held up even in the one case the aura is NOT laid over (the local player in front facing third person), where the
    // aura is still a dash aura and still should not blink out.
    private static final ThreadLocal<Boolean> SU_DASHING = new ThreadLocal<>();

    @Inject(method = "renderShaderAura", at = @At("HEAD"), require = 0, remap = false)
    private static void su$markDashAura(@Coerce Object entry, PoseStack poseStack, Minecraft minecraft,
            Matrix4f projectionMatrix, CallbackInfo ci)
    {
        try
        {
            AbstractClientPlayer player = su$playerOf(entry);
            // A pod pilot's aura belongs to MixinDmzAuraRenderer, which points it out the back of the pod and leaves
            // it there. Two mixins reorienting one aura is how it ends up swinging, so the pod wins outright here
            // rather than the two of them taking turns.
            if (player == null || su$ridingSpacePod(player) || !DashAuraState.isDashing(player.getId()))
            {
                SU_LAID_OVER.remove();
                SU_DASHING.remove();
                return;
            }
            SU_DASHING.set(Boolean.TRUE);
            if (su$shouldLayOver(player, minecraft))
                SU_LAID_OVER.set(player);
            else
                SU_LAID_OVER.remove();
        }
        catch (Throwable t)
        {
            SU_LAID_OVER.remove();
            SU_DASHING.remove();
        }
    }

    @Inject(method = "renderShaderAura", at = @At("RETURN"), require = 0, remap = false)
    private static void su$clearDashAura(@Coerce Object entry, PoseStack poseStack, Minecraft minecraft,
            Matrix4f projectionMatrix, CallbackInfo ci)
    {
        SU_LAID_OVER.remove();
        SU_DASHING.remove();
    }

    @ModifyConstant(method = "renderShaderAura", constant = @Constant(floatValue = 0.005F), require = 0, remap = false)
    private static float su$holdDashAuraVisible(float original)
    {
        return Boolean.TRUE.equals(SU_DASHING.get()) ? DASH_FADE : original;
    }

    // Report the camera as level while the aura is laid over. remap = true on the @At so Camera.getXRot refmaps against
    // the obfuscated production jar, matching how SU's other aura redirects are written.
    @Redirect(
            method = "executeAuraShaderDraw",
            require = 0,
            remap = false,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;getXRot()F",
                    remap = true))
    private static float su$keepDashPlumeVisible(Camera camera)
    {
        try
        {
            if (SU_LAID_OVER.get() != null)
                return 0.0F;
        }
        catch (Throwable ignored)
        {
        }
        return camera.getXRot();
    }

    // translate ordinal 2: the upright anchor offset, zeroed while laid over. Ordinal 1 belongs to MixinDmzAuraRenderer
    // and must not be touched here.
    @Redirect(
            method = "executeAuraShaderDraw",
            require = 0,
            remap = false,
            at = @At(
                    value = "INVOKE",
                    ordinal = 2,
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V",
                    remap = true))
    private static void su$dropUprightOffset(PoseStack pose, double x, double y, double z,
            Player player, @Coerce Object cachedAuraData, @Coerce Object auraLayer, PoseStack poseStack,
            Minecraft minecraft, Matrix4f projection, float partialTick, float alpha, boolean overShoulder)
    {
        try
        {
            if (SU_LAID_OVER.get() != null)
            {
                pose.translate(0.0D, 0.0D, 0.0D);
                return;
            }
        }
        catch (Throwable ignored)
        {
        }
        pose.translate(x, y, z);
    }

    // mulPose ordinal 0: the camera billboard. The laid over placement is applied immediately after it, in screen space.
    // scale ordinal 2 and translate ordinal 1 belong to MixinDmzAuraRenderer and must not be touched here.
    @Redirect(
            method = "executeAuraShaderDraw",
            require = 0,
            remap = false,
            at = @At(
                    value = "INVOKE",
                    ordinal = 0,
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionf;)V",
                    remap = true))
    private static void su$layAuraOverScreen(PoseStack pose, Quaternionf cameraRotation,
            Player player, @Coerce Object cachedAuraData, @Coerce Object auraLayer, PoseStack poseStack,
            Minecraft minecraft, Matrix4f projection, float partialTick, float alpha, boolean overShoulder)
    {
        if (SU_DASH_FLIP_BIND_LOGGED.compareAndSet(false, true))
        {
            try
            {
                LoggingHandler.sulog.info("[Dash] MixinDmzAuraDashFlip bound "
                        + "(AuraRenderer.executeAuraShaderDraw mulPose ordinal 0, dash aura laid over screen)");
            }
            catch (Throwable ignored)
            {
            }
        }
        // The billboard itself always runs. Everything below only adds to it, so a failure here leaves an ordinary aura.
        pose.mulPose(cameraRotation);
        try
        {
            AbstractClientPlayer dashing = SU_LAID_OVER.get();
            if (dashing == null)
                return;
            // The mirror. With the over the shoulder camera swung to the left, an unmirrored aura trails across the
            // player instead of away from them, so the whole placement is flipped on X to keep it on the outside.
            if (su$cameraOnLeft())
                pose.scale(-1.0F, 1.0F, 1.0F);
            DashAuraPlacement.Offset offset = DashAuraPlacement.sample(dashing.getViewXRot(minecraft.getFrameTime()));
            pose.translate(offset.x(), offset.y(), 0.0D);
            pose.mulPose(Axis.ZP.rotationDegrees(TRAIL_ROTATION_DEG));
        }
        catch (Throwable ignored)
        {
        }
    }

    // Whether this player's dash aura should be laid over rather than left upright.
    private static boolean su$shouldLayOver(AbstractClientPlayer player, Minecraft minecraft)
    {
        boolean local = player == minecraft.player;
        // Front facing third person looks AT the player, so a laid over aura would be seen edge on and read as a glitch.
        if (local && minecraft.options.getCameraType() == CameraType.THIRD_PERSON_FRONT)
            return false;
        if (local)
            return true;
        Vec3 heading = DashAuraState.heading(player.getId());
        Vec3 viewerToPlayer = minecraft.gameRenderer.getMainCamera().getPosition().subtract(player.getEyePosition());
        return DashAuraPlacement.layOverForRemote(heading, viewerToPlayer);
    }

    // DMZ's over the shoulder camera reports which side it currently sits on. It is optional and may not be running, so
    // this is reflective and fails to "not on the left", which is the unmirrored default.
    private static boolean su$cameraOnLeft()
    {
        try
        {
            Class<?> cam = Class.forName("com.dragonminez.client.render.camera.OverShoulderCamera");
            Object running = cam.getMethod("isRunning").invoke(null);
            if (!(running instanceof Boolean b) || !b)
                return false;
            Object side = cam.getMethod("getCurrentSide").invoke(null);
            return side instanceof Number n && n.doubleValue() < -0.05D;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    // True while this player is in a DMZ space pod, directly or nested.
    private static boolean su$ridingSpacePod(AbstractClientPlayer player)
    {
        for (net.minecraft.world.entity.Entity vehicle = player.getVehicle(); vehicle != null;
                vehicle = vehicle.getVehicle())
        {
            if (vehicle instanceof com.dragonminez.common.init.entities.SpacePodEntity)
                return true;
        }
        return false;
    }

    // Pull the player out of DMZ's aura queue entry without compiling against its type, which keeps this mixin from
    // failing to load outright if that record moves or is renamed.
    private static AbstractClientPlayer su$playerOf(Object entry)
    {
        if (entry == null)
            return null;
        try
        {
            Object p = entry.getClass().getMethod("player").invoke(entry);
            return p instanceof AbstractClientPlayer acp ? acp : null;
        }
        catch (Throwable t)
        {
            return null;
        }
    }
}
