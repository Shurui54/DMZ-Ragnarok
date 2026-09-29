package net.shurui.shuruisutilities.core.mixin.client.dmz;

import com.dragonminez.client.render.layer.DMZHairLayer;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;

import net.shurui.shuruisutilities.model.client.ModelClientCache;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses DragonMineZ's hair for a player under a {@code /model} override. DMZ draws hair through {@code renderHair}
 * (not the layer's {@code render}), so that is the target. See {@link DmzRacePartsLayerModelMixin} for the guard
 * rationale.
 */
@Mixin(value = DMZHairLayer.class, remap = false)
public abstract class DmzHairLayerModelMixin
{
    @Inject(method = "renderHair(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/MultiBufferSource;FII)V",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void su$suppressForModel(PoseStack poseStack, AbstractClientPlayer player, MultiBufferSource bufferSource,
            float partialTick, int packedLight, int packedOverlay, CallbackInfo ci)
    {
        try
        {
            if (player != null && ModelClientCache.resolve(player.getUUID()) != null)
                ci.cancel();
        }
        catch (Throwable ignored)
        {
        }
    }
}
