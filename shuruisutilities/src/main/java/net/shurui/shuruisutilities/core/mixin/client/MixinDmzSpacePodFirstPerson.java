package net.shurui.shuruisutilities.core.mixin.client;

import com.dragonminez.common.init.entities.SpacePodEntity;
import com.dragonminez.common.init.entities.vehicle.VehicleEntity;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shurui.shuruisutilities.client.podview.PodFirstPersonRender;

/**
 * Skips DMZ's entity-pass draw of the space pod the local player rides in first person, so
 * {@link PodFirstPersonRender} can draw it after the translucent world instead (the pod glass hid water and quest
 * markers). Every other pod, and this one in third person, falls straight through.
 *
 * <p>The target is DMZ's own {@code render(SpacePodEntity, ...)} (the generic override the bridge {@code m_7392_} calls;
 * DMZ's jar keeps that name), so {@code remap = false}. {@code require = 0}: a DMZ change only drops the fix.
 */
@Mixin(targets = "com.dragonminez.client.init.entities.renderer.vehicle.VehicleRenderer", remap = false)
public abstract class MixinDmzSpacePodFirstPerson
{
    @Inject(method = "render(Lcom/dragonminez/common/init/entities/vehicle/VehicleEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void su$deferInFirstPerson(VehicleEntity pod, float yaw, float partialTick, PoseStack pose,
            MultiBufferSource buffers, int light, CallbackInfo ci)
    {
        try
        {
            // DMZ 2.2 draws every vehicle through VehicleRenderer; only the space pod is deferred.
            if (pod instanceof SpacePodEntity && PodFirstPersonRender.deferred(pod))
                ci.cancel();
        }
        catch (Throwable ignored)
        {
            // Fall through to DMZ's own draw.
        }
    }
}
