package net.shurui.shuruisutilities.core.mixin.client.dmz;

import com.dragonminez.client.render.layer.DMZSkinLayer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import net.shurui.shuruisutilities.model.client.ModelClientCache;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import software.bernie.geckolib.cache.object.BakedGeoModel;

/**
 * Suppresses DragonMineZ's skin / face body layer for a player under a {@code /model} override, so the chosen model
 * is drawn without the player's race skin, eyes, nose and mouth painted onto it. See
 * {@link DmzRacePartsLayerModelMixin} for the guard rationale.
 */
@Mixin(value = DMZSkinLayer.class, remap = false)
public abstract class DmzSkinLayerModelMixin
{
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/player/AbstractClientPlayer;Lsoftware/bernie/geckolib/cache/object/BakedGeoModel;Lnet/minecraft/client/renderer/RenderType;Lnet/minecraft/client/renderer/MultiBufferSource;Lcom/mojang/blaze3d/vertex/VertexConsumer;FII)V",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void su$suppressForModel(PoseStack poseStack, AbstractClientPlayer player, BakedGeoModel model,
            RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
            int packedLight, int packedOverlay, CallbackInfo ci)
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
