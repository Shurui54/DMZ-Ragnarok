package net.shurui.shuruisutilities.core.mixin.client.dmz;

import com.dragonminez.client.render.DMZPlayerRenderer;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;

import net.shurui.shuruisutilities.disguise.client.DisguiseRenderSwap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The in-world DISGUISE body: around DragonMineZ's per-player render, a disguised player's {@code Character}
 * appearance is swapped for the target's and then restored. All the logic (and why it is safe) is in
 * {@link DisguiseRenderSwap}; this is only the attachment point.
 *
 * <p>HEAD and RETURN of the erased {@code AbstractClientPlayer} overload of {@code render}, parameters exactly the
 * target's plus the CI (argument capture is all or nothing). {@code require = 0} + {@code remap = false}: if DMZ
 * reshapes the renderer the disguise body simply stops applying (name, tab, chat and skin still change).
 */
@Mixin(value = DMZPlayerRenderer.class, remap = false)
public abstract class DmzPlayerRenderDisguiseMixin
{
    @Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0, remap = false)
    private void su$disguiseBegin(AbstractClientPlayer player, float yaw, float partialTick, PoseStack poseStack,
            MultiBufferSource buffer, int packedLight, CallbackInfo ci)
    {
        DisguiseRenderSwap.begin(player);
    }

    @Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), require = 0, remap = false)
    private void su$disguiseEnd(AbstractClientPlayer player, float yaw, float partialTick, PoseStack poseStack,
            MultiBufferSource buffer, int packedLight, CallbackInfo ci)
    {
        DisguiseRenderSwap.end();
    }
}
