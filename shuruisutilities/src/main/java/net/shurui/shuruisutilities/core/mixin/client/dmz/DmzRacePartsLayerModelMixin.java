package net.shurui.shuruisutilities.core.mixin.client.dmz;

import com.dragonminez.client.render.layer.DMZRacePartsLayer;
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
 * Suppresses DragonMineZ's race-parts body layer for a player under a {@code /model} override, so the chosen rgnpc /
 * DragonMineZ-NPC model is drawn clean instead of with the player's race painted over it. Argument capture is
 * all-or-nothing, so the target's full parameter list is taken exactly (the erased {@link AbstractClientPlayer}
 * overload). {@code require = 0} + {@code remap = false}: if DMZ reshapes this the suppression simply stops.
 */
@Mixin(value = DMZRacePartsLayer.class, remap = false)
public abstract class DmzRacePartsLayerModelMixin
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
