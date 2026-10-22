package net.shurui.dev.sdu.mixin.hud;

import java.util.Arrays;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dragonminez.client.gui.hud.HudStyle;

import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudSlot;

/**
 * Appends the "Ragnarok" style to DMZ's {@code HudStyle} enum, last, so DMZ's HUD editor offers it after Vanilla when
 * its style button cycles {@code values()}, and {@code fromConfigName("ragnarok")} finds it. Its label is the lang key
 * DMZ derives, {@code gui.dragonminez.hud_editor.style.ragnarok}.
 */
@Mixin(value = HudStyle.class, remap = false)
public abstract class DmzHudStyleMixin
{
    @Shadow
    @Final
    @Mutable
    private static HudStyle[] $VALUES;

    @Inject(method = "<clinit>", at = @At("TAIL"), require = 0)
    private static void sdu$appendRagnarokStyle(CallbackInfo ci)
    {
        int base = $VALUES.length;
        HudStyle[] extended = Arrays.copyOf($VALUES, base + 1);
        extended[base] = DmzHudStyleInvoker.sdu$create(RagnarokHudSlot.STYLE_ENUM_NAME, base, RagnarokHudSlot.STYLE_NAME);
        $VALUES = extended;
    }
}
