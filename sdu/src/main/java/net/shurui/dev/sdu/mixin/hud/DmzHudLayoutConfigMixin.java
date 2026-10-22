package net.shurui.dev.sdu.mixin.hud;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dragonminez.common.config.HudLayoutConfig;

import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudSlot;

/**
 * DMZ normalises the stored HUD style on every read and maps anything it does not know to "xenoverse", which would
 * throw the Ragnarok style away the moment it was saved. Keep it. Common side because the config class is shared;
 * a string compare is all it does.
 */
@Mixin(value = HudLayoutConfig.class, remap = false)
public abstract class DmzHudLayoutConfigMixin
{
    @Inject(method = "normalizeStyle", at = @At("HEAD"), cancellable = true, require = 0)
    private static void sdu$keepRagnarok(String raw, CallbackInfoReturnable<String> cir)
    {
        if (raw != null && RagnarokHudSlot.STYLE_NAME.equalsIgnoreCase(raw.trim()))
            cir.setReturnValue(RagnarokHudSlot.STYLE_NAME);
    }
}
