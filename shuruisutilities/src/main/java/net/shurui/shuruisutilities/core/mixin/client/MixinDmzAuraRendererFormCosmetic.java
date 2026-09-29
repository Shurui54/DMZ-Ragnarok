package net.shurui.shuruisutilities.core.mixin.client;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dragonminez.common.stats.StatsData;

import net.minecraft.world.entity.player.Player;

import net.shurui.shuruisutilities.cosmetics.form.client.FormCosmeticClientStore;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Extends the form-cosmetic render-target scope over the KI AURA, whose colour DMZ resolves in a DEFERRED batched
 * pass ({@code AuraRenderer.getAuraLayers}, called from {@code processThirdPersonAuras}) rather than inside the
 * player renderer, so the main renderer scope from {@link MixinDmzPlayerRendererFormCosmetic} does not cover it.
 * Here we set the render target to the aura's own player for the duration of {@code getAuraLayers}, so the
 * {@code Character.getActiveFormData} override reaches the aura colour too.
 *
 * <p>{@code require = 0}: {@code getAuraLayers} is a private helper, so if a future DMZ build renames or reshapes it
 * this degrades to the stock aura colour (the aura simply is not recoloured) instead of crashing. Client-only.
 */
@Mixin(targets = "com.dragonminez.client.render.effects.AuraRenderer", remap = false)
public abstract class MixinDmzAuraRendererFormCosmetic
{
    private static final AtomicBoolean SU_FC_AURA_BIND_LOGGED = new AtomicBoolean(false);

    @Inject(method = "getAuraLayers", at = @At("HEAD"), remap = false, require = 0)
    private static void su$auraFormCosmeticBegin(Player player, StatsData stats, float partialTick, CallbackInfoReturnable<?> cir)
    {
        if (SU_FC_AURA_BIND_LOGGED.compareAndSet(false, true))
        {
            try
            {
                LoggingHandler.sulog.info(
                        "[FormCosmetic] MixinDmzAuraRendererFormCosmetic bound (AuraRenderer.getAuraLayers aura-colour scope)");
            }
            catch (Throwable ignored)
            {
            }
        }
        try
        {
            if (player != null)
                FormCosmeticClientStore.setRenderTarget(player.getUUID());
        }
        catch (Throwable ignored)
        {
        }
    }

    @Inject(method = "getAuraLayers", at = @At("RETURN"), remap = false, require = 0)
    private static void su$auraFormCosmeticEnd(Player player, StatsData stats, float partialTick, CallbackInfoReturnable<?> cir)
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
