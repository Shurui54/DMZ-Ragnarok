package net.shurui.shuruisutilities.core.mixin.client;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dragonminez.client.render.effects.Aura3DRenderer;
import com.dragonminez.client.render.effects.AuraRenderer;
import com.dragonminez.client.render.effects.AuraStyle;
import com.dragonminez.common.init.entities.SpacePodEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.Util;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * While a player pilots a DMZ space pod, their 3D aura wraps the whole pod and trails BEHIND it, the way DMZ lays a
 * 3D aura over behind a player in fast flight.
 *
 * <p>Why the pod broke it: DMZ 2.2 lays the 3D aura over only when {@code AuraRenderer.isFastFlying(player)} is true,
 * and that reads DMZ's own flight state ({@code FlySkillEvent.isFlyingFast}). A pod passenger is not flying, so
 * {@code executeSmoothDraw}, {@code executeGodDraw} and {@code executeAura3DDraw} took their upright branch and drew a
 * standing, player-sized aura around the seated pilot, poking up through the pod no matter where it flew.
 *
 * <p>The fix keeps DMZ's own shell, shader, colours, style and animation and only replaces where the shell is placed.
 * At the HEAD of each of the three draw methods we save the pose (origin at the pilot's interpolated feet, world
 * axes), and the one {@code Aura3DRenderer.draw*} call in each method is redirected: for a pod pilot the pose is reset
 * to that saved base and laid out exactly like DMZ's flight branch (yaw, then {@code 90 - pitch} on X), but pivoting on
 * the pod's centre, along the pod's smoothed travel direction, scaled up so the shell encloses the pod. Everyone not in
 * a pod, the GUI preview ({@code upright}) and the 2D aura path are untouched.
 *
 * <p>A pod pilot is also kept out of DMZ's touching-aura merge (as DMZ does for fast flyers), because a merged aura is
 * drawn from the group's centroid and would no longer sit on the pod.
 *
 * <p>Client only, render thread only, a handful of float ops per layer. {@code require = 0} with a wrapped body: a DMZ
 * reshape degrades to the stock upright aura. The bind log is the only proof it wove.
 */
@Mixin(targets = "com.dragonminez.client.render.effects.AuraRenderer", remap = false)
public abstract class MixinDmzAuraPodEncase
{
    // DMZ's private GOD_CENTER (AuraRenderer, 2.2): the god shell's centre per unit of its draw scaleY.
    private static final float SU_GOD_CENTER = 1.28F;
    // How much bigger than its wrap of a pilot the shell is drawn around a 2-block pod. A smooth shell has radius
    // 0.8 * scale, so 1.75 gives a 1.4 block radius around the pod's ~1.1 block half width. Tuning knob.
    private static final float SU_POD_WRAP = 1.75F;
    // The pod model's visual centre above its origin: the geo model spans y 0.1 to 2.3 blocks at DMZ's 1.1 scale.
    private static final double SU_POD_CENTRE_Y = 1.2D;
    // How far (blocks, for a 2 block pod) the shell's centre sits behind the pod's centre, so the nose stays inside
    // the rounded front and the longer part of the flame streams out the back. The sparking flame is longer behind its
    // core than the smooth shell, so it needs less. Tuning knobs (verified in the podfly visual test).
    private static final float SU_POD_REAR_SHIFT = 0.55F;
    private static final float SU_POD_REAR_SHIFT_SPARKING = 0.1F;
    // Below this speed (blocks per tick) the pod counts as parked and the aura trails behind its heading instead.
    private static final double SU_MIN_SPEED = 0.05D;
    // Time constant (seconds) of the direction smoothing. The travel direction only changes once per tick, so this
    // turns those 20 Hz steps into a continuous swing without visible lag.
    private static final float SU_DIR_TAU = 0.08F;

    private static final AtomicBoolean SU_BIND_LOGGED = new AtomicBoolean(false);

    // State for the draw in progress (render thread only, set at HEAD of each draw method, read by its redirect).
    private static boolean su$active;
    private static final Matrix4f SU_BASE_POSE = new Matrix4f();
    private static final Matrix3f SU_BASE_NORMAL = new Matrix3f();
    private static double su$offX, su$offY, su$offZ;
    private static float su$yaw, su$pitch, su$scale;

    // Smoothed travel direction {x, y, z} per pod id, and when it was last advanced.
    private static final Map<Integer, float[]> SU_DIRS = new HashMap<>();
    private static final Map<Integer, Long> SU_DIR_NANOS = new HashMap<>();

    @Shadow
    private static boolean isFastFlying(Player player)
    {
        throw new AssertionError();
    }

    @Inject(method = "executeSmoothDraw", at = @At("HEAD"), require = 0)
    private static void su$smoothHead(Player player, @Coerce Object data, AuraRenderer.AuraLayer layer,
            PoseStack poseStack, Matrix4f projection, float partialTick, boolean firstPerson, boolean upright,
            CallbackInfo ci)
    {
        su$capture(player, poseStack, partialTick, upright);
    }

    @Inject(method = "executeGodDraw", at = @At("HEAD"), require = 0)
    private static void su$godHead(Player player, @Coerce Object data, AuraRenderer.AuraLayer layer,
            PoseStack poseStack, Matrix4f projection, float partialTick, boolean firstPerson, boolean upright,
            CallbackInfo ci)
    {
        su$capture(player, poseStack, partialTick, upright);
    }

    @Inject(method = "executeAura3DDraw", at = @At("HEAD"), require = 0)
    private static void su$sparkingHead(Player player, @Coerce Object data, AuraRenderer.AuraLayer layer,
            PoseStack poseStack, Matrix4f projection, float partialTick, boolean firstPerson, boolean upright,
            CallbackInfo ci)
    {
        su$capture(player, poseStack, partialTick, upright);
    }

    @Redirect(method = "executeSmoothDraw", require = 0, at = @At(value = "INVOKE", remap = false,
            target = "Lcom/dragonminez/client/render/effects/Aura3DRenderer;drawSmooth(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lcom/dragonminez/client/render/effects/AuraStyle;FFFFFFFF)V"))
    private static void su$drawSmooth(PoseStack pose, Matrix4f projection, AuraStyle style, float alpha, float growth,
            float time, float phase, float sx, float sy, float sz, float backFace)
    {
        if (su$lay(pose, Aura3DRenderer.SMOOTH_CENTER * sy, SU_POD_REAR_SHIFT))
        {
            float s = su$scale;
            try
            {
                Aura3DRenderer.drawSmooth(pose, projection, style, alpha, growth, time, phase, sx * s, sy * s, sz * s,
                        backFace);
            }
            finally
            {
                pose.popPose();
            }
            return;
        }
        Aura3DRenderer.drawSmooth(pose, projection, style, alpha, growth, time, phase, sx, sy, sz, backFace);
    }

    @Redirect(method = "executeGodDraw", require = 0, at = @At(value = "INVOKE", remap = false,
            target = "Lcom/dragonminez/client/render/effects/Aura3DRenderer;drawGod(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lcom/dragonminez/client/render/effects/AuraStyle;FFFFFFF)V"))
    private static void su$drawGod(PoseStack pose, Matrix4f projection, AuraStyle style, float alpha, float growth,
            float time, float sx, float sy, float sz, float backFace)
    {
        if (su$lay(pose, SU_GOD_CENTER * sy, SU_POD_REAR_SHIFT))
        {
            float s = su$scale;
            try
            {
                Aura3DRenderer.drawGod(pose, projection, style, alpha, growth, time, sx * s, sy * s, sz * s, backFace);
            }
            finally
            {
                pose.popPose();
            }
            return;
        }
        Aura3DRenderer.drawGod(pose, projection, style, alpha, growth, time, sx, sy, sz, backFace);
    }

    @Redirect(method = "executeAura3DDraw", require = 0, at = @At(value = "INVOKE", remap = false,
            target = "Lcom/dragonminez/client/render/effects/Aura3DRenderer;drawSparking(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lcom/dragonminez/client/render/effects/AuraStyle;FFFFFFFF)V"))
    private static void su$drawSparking(PoseStack pose, Matrix4f projection, AuraStyle style, float alpha,
            float growth, float time, float sx, float sy, float sz, float pivot, float backFace)
    {
        // the sparking flame pivots on its core (coreFactor * scaleY), the same point DMZ's flight branch pivots on.
        if (su$lay(pose, Aura3DRenderer.coreFactor("sparking") * sy, SU_POD_REAR_SHIFT_SPARKING))
        {
            float s = su$scale;
            try
            {
                Aura3DRenderer.drawSparking(pose, projection, style, alpha, growth, time, sx * s, sy * s, sz * s,
                        pivot, backFace);
            }
            finally
            {
                pose.popPose();
            }
            return;
        }
        Aura3DRenderer.drawSparking(pose, projection, style, alpha, growth, time, sx, sy, sz, pivot, backFace);
    }

    // DMZ's merge test treats a fast flyer as unmergeable; report a pod pilot the same way so its aura is never drawn
    // from a group centroid (it would leave the pod).
    @Redirect(method = "prepareShaderAura", require = 0, at = @At(value = "INVOKE", remap = false,
            target = "Lcom/dragonminez/client/render/effects/AuraRenderer;isFastFlying(Lnet/minecraft/world/entity/player/Player;)Z"))
    private static boolean su$podNeverMerges(Player player)
    {
        try
        {
            if (su$pod(player) != null)
            {
                return true;
            }
        }
        catch (Throwable ignored)
        {
        }
        return isFastFlying(player);
    }

    // Decide whether this draw is a pod pilot's, and if so save the base pose and work out the pod placement.
    private static void su$capture(Player player, PoseStack poseStack, float partialTick, boolean upright)
    {
        su$active = false;
        if (SU_BIND_LOGGED.compareAndSet(false, true))
        {
            try
            {
                LoggingHandler.sulog.info("[SpacePod] MixinDmzAuraPodEncase bound (3D aura wraps the pod, trails behind)");
            }
            catch (Throwable ignored)
            {
            }
        }
        try
        {
            if (upright || player == null || poseStack == null)
            {
                return;
            }
            SpacePodEntity pod = su$pod(player);
            if (pod == null)
            {
                return;
            }
            float pt = partialTick;
            float heading = Mth.rotLerp(pt, pod.yBodyRotO, pod.yBodyRot);

            // Pod centre in world space: DMZ's own visual transform (tilt and hover) of the model centre, turned by the
            // heading, on top of the pod's interpolated position.
            Vec3 local = pod.applyVisualTransform(new Vec3(0.0D, SU_POD_CENTRE_Y, 0.0D), pt);
            Vec3 turned = new Vec3(local.x, 0.0D, local.z).yRot(-heading * Mth.DEG_TO_RAD);
            double cx = Mth.lerp(pt, pod.xo, pod.getX()) + turned.x;
            double cy = Mth.lerp(pt, pod.yo, pod.getY()) + local.y;
            double cz = Mth.lerp(pt, pod.zo, pod.getZ()) + turned.z;
            // The pose origin DMZ hands these methods is the pilot's interpolated feet (drawShaderAura,
            // renderShaderGhostAura and renderShaderFirstPersonAura all translate there).
            su$offX = cx - Mth.lerp(pt, player.xo, player.getX());
            su$offY = cy - Mth.lerp(pt, player.yo, player.getY());
            su$offZ = cz - Mth.lerp(pt, player.zo, player.getZ());

            float[] dir = su$direction(pod, heading);
            double horizontal = Math.sqrt(dir[0] * dir[0] + dir[2] * dir[2]);
            su$yaw = (float) Math.toDegrees(Math.atan2(-dir[0], dir[2]));
            su$pitch = (float) Math.toDegrees(-Math.atan2(dir[1], horizontal));

            // Size relative to the pod, normalised by the pilot's own width so a DMZ body scale (which already grows
            // the shell DMZ hands us) does not grow it twice.
            float podHalf = pod.getBbWidth() * 0.5F;
            float pilotWidth = Math.max(0.3F, player.getBbWidth());
            su$scale = Mth.clamp(SU_POD_WRAP * podHalf * (0.6F / pilotWidth), 0.5F, 8.0F);

            SU_BASE_POSE.set(poseStack.last().pose());
            SU_BASE_NORMAL.set(poseStack.last().normal());
            su$active = true;
        }
        catch (Throwable ignored)
        {
            su$active = false;
        }
    }

    // Push a pose laid along the pod's rear, pivoting the shell's centre (centreY, in the draw's unscaled units) on
    // the pod. Returns false (nothing pushed) for any draw that is not a pod pilot's.
    private static boolean su$lay(PoseStack pose, float centreY, float rearShift)
    {
        if (!su$active)
        {
            return false;
        }
        su$active = false;
        try
        {
            float s = su$scale;
            pose.pushPose();
            pose.last().pose().set(SU_BASE_POSE);
            pose.last().normal().set(SU_BASE_NORMAL);
            pose.translate(su$offX, su$offY, su$offZ);
            // Same composition as DMZ's flight branch: after YP(180 - yaw) local +Z is the pod's rear, and
            // XP(90 - pitch) swings the shell's long +Y axis onto it, so the flame streams opposite travel.
            pose.mulPose(Axis.YP.rotationDegrees(180.0F - su$yaw));
            pose.mulPose(Axis.XP.rotationDegrees(90.0F - su$pitch));
            pose.translate(0.0F, -centreY * s + rearShift * (s / SU_POD_WRAP), 0.0F);
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    // The pod's smoothed travel direction (unit vector). Moving: its per-tick motion. Parked: straight back along
    // its heading. Exponentially smoothed per frame so the once-per-tick motion steps read as one continuous swing.
    private static float[] su$direction(SpacePodEntity pod, float heading)
    {
        double vx = pod.getX() - pod.xo;
        double vy = pod.getY() - pod.yo;
        double vz = pod.getZ() - pod.zo;
        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        float tx, ty, tz;
        if (speed > SU_MIN_SPEED)
        {
            tx = (float) (vx / speed);
            ty = (float) (vy / speed);
            tz = (float) (vz / speed);
        }
        else
        {
            float rad = heading * Mth.DEG_TO_RAD;
            tx = -Mth.sin(rad);
            ty = 0.0F;
            tz = Mth.cos(rad);
        }

        int id = pod.getId();
        long now = Util.getNanos();
        float[] d = SU_DIRS.get(id);
        Long last = SU_DIR_NANOS.get(id);
        if (d == null || last == null || now - last > 500_000_000L)
        {
            if (SU_DIRS.size() > 32)
            {
                SU_DIRS.clear();
                SU_DIR_NANOS.clear();
            }
            d = new float[] { tx, ty, tz };
            SU_DIRS.put(id, d);
        }
        else
        {
            float dt = (now - last) / 1.0e9F;
            float k = 1.0F - (float) Math.exp(-dt / SU_DIR_TAU);
            d[0] += (tx - d[0]) * k;
            d[1] += (ty - d[1]) * k;
            d[2] += (tz - d[2]) * k;
            float len = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            if (len < 1.0e-4F)
            {
                d[0] = tx;
                d[1] = ty;
                d[2] = tz;
            }
            else
            {
                d[0] /= len;
                d[1] /= len;
                d[2] /= len;
            }
        }
        SU_DIR_NANOS.put(id, now);
        return d;
    }

    // The DMZ space pod this player rides (directly or nested), or null.
    private static SpacePodEntity su$pod(Player player)
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
