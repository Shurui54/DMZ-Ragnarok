package net.shurui.shuruisutilities.core.mixin.client;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;

import net.shurui.shuruisutilities.cosmetics.form.client.FormCosmeticClientStore;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Scopes "whose form cosmetic override applies" to the player being drawn. DMZ's {@code Character.getActiveFormData}
 * (the single point every appearance layer reads a form's colours from) carries no owner reference, so at the HEAD
 * of {@code DMZPlayerRenderer.render} we record the rendered player's UUID and at every RETURN we clear it. The
 * companion {@code MixinDmzCharacterFormCosmetic} reads that UUID to pick the right player's override.
 *
 * <p>{@code require = 0}: DMZ is a hard dependency so the class is present, but if a future DMZ build reshapes the
 * renderer this degrades to no override (the target stays stock) instead of crashing the client. Client-only.
 */
@Mixin(targets = "com.dragonminez.client.render.DMZPlayerRenderer", remap = false)
public abstract class MixinDmzPlayerRendererFormCosmetic
{
    private static final AtomicBoolean SU_FC_RENDERER_BIND_LOGGED = new AtomicBoolean(false);

    @Inject(method = "render", at = @At("HEAD"), remap = false, require = 0)
    private void su$formCosmeticBegin(AbstractClientPlayer entity, float entityYaw, float partialTick,
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, CallbackInfo ci)
    {
        if (SU_FC_RENDERER_BIND_LOGGED.compareAndSet(false, true))
        {
            try
            {
                LoggingHandler.sulog.info(
                        "[FormCosmetic] MixinDmzPlayerRendererFormCosmetic bound (DMZPlayerRenderer.render render-target scope)");
            }
            catch (Throwable ignored)
            {
            }
        }
        try
        {
            if (entity != null)
                FormCosmeticClientStore.setRenderTarget(entity.getUUID());
        }
        catch (Throwable ignored)
        {
        }
    }

    // RETURN injects at every exit (including early returns), so the scope is always cleared even if DMZ bails out.
    @Inject(method = "render", at = @At("RETURN"), remap = false, require = 0)
    private void su$formCosmeticEnd(AbstractClientPlayer entity, float entityYaw, float partialTick,
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, CallbackInfo ci)
    {
        try
        {
            FormCosmeticClientStore.clearRenderTarget();
        }
        catch (Throwable ignored)
        {
        }
    }
}
