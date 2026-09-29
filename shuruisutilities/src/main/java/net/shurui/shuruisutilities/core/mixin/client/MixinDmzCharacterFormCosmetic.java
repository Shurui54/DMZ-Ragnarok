package net.shurui.shuruisutilities.core.mixin.client;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.stats.character.Character;

import net.shurui.shuruisutilities.cosmetics.form.client.FormCosmeticClientStore;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Applies a player's single form cosmetic override at the ONE choke point every appearance layer reads from:
 * {@code Character.getActiveFormData()} (and its stack-form twin). When the player currently being rendered (see
 * {@link MixinDmzPlayerRendererFormCosmetic}) has an override whose target matches the active form, we return an
 * appearance-overridden COPY of the form (all stats copied verbatim, only colours/outline replaced). Because it is
 * this single method, body/hair/eye/tint and the race-parts colours all pick the override up automatically.
 *
 * <p>Client-only and non-invasive: the override is consulted only when a render is in progress (render target set)
 * and only produces an appearance-only copy, so server-side stat logic is never affected even in singleplayer, and
 * a null render target (any non-render call) returns DMZ's own value untouched.
 *
 * <p>{@code require = 0}: degrades to the stock form on any DMZ change instead of crashing. Any exception falls
 * through to the original return value.
 */
@Mixin(value = Character.class, remap = false)
public abstract class MixinDmzCharacterFormCosmetic
{
    private static final AtomicBoolean SU_FC_CHAR_BIND_LOGGED = new AtomicBoolean(false);

    @Inject(method = "getActiveFormData", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void su$overrideActiveFormAppearance(CallbackInfoReturnable<FormConfig.FormData> cir)
    {
        su$logOnce();
        try
        {
            FormConfig.FormData base = cir.getReturnValue();
            if (base == null)
                return;
            UUID uuid = FormCosmeticClientStore.currentRenderTarget();
            if (uuid == null)
                return;
            Character self = (Character) (Object) this;
            FormConfig.FormData derived = FormCosmeticClientStore.derive(
                    uuid, self.getActiveFormGroup(), self.getActiveForm(), base);
            if (derived != null)
                cir.setReturnValue(derived);
        }
        catch (Throwable ignored)
        {
            // leave DMZ's own form data in place
        }
    }

    @Inject(method = "getActiveStackFormData", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void su$overrideActiveStackFormAppearance(CallbackInfoReturnable<FormConfig.FormData> cir)
    {
        try
        {
            FormConfig.FormData base = cir.getReturnValue();
            if (base == null)
                return;
            UUID uuid = FormCosmeticClientStore.currentRenderTarget();
            if (uuid == null)
                return;
            Character self = (Character) (Object) this;
            FormConfig.FormData derived = FormCosmeticClientStore.derive(
                    uuid, self.getActiveStackFormGroup(), self.getActiveStackForm(), base);
            if (derived != null)
                cir.setReturnValue(derived);
        }
        catch (Throwable ignored)
        {
        }
    }

    private static void su$logOnce()
    {
        if (SU_FC_CHAR_BIND_LOGGED.compareAndSet(false, true))
        {
            try
            {
                LoggingHandler.sulog.info(
                        "[FormCosmetic] MixinDmzCharacterFormCosmetic bound (Character.getActiveFormData override)");
            }
            catch (Throwable ignored)
            {
            }
        }
    }
}
