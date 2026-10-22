package net.shurui.dev.sdu.mixin.hud;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dragonminez.client.gui.hud.HudStyle;
import com.dragonminez.client.gui.hud.layout.HudElement;
import com.dragonminez.client.gui.hud.layout.HudLayout;
import com.dragonminez.common.config.HudPlacement;

import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudBridge;

/**
 * Teaches DMZ's {@code HudLayout} about the suite's appended elements: which style lists them, their base size and
 * their default placement. Everything else (box math, clamping, docking, the editor's drag and resize) is DMZ's own
 * code running unchanged on them.
 *
 * <p>{@code baseSize} and {@code defaultPlacement} are exhaustive switches over HudElement, which would throw for a
 * constant javac never saw, so ours are answered at HEAD before the switch is reached.
 */
@Mixin(value = HudLayout.class, remap = false)
public abstract class DmzHudLayoutMixin
{
    @Inject(method = "elements", at = @At("RETURN"), require = 0)
    private static void sdu$ragnarokElements(HudStyle style, CallbackInfoReturnable<List<HudElement>> cir)
    {
        RagnarokHudBridge.adjustElements(style, cir.getReturnValue());
    }

    @Inject(method = "extraElements", at = @At("RETURN"), require = 0)
    private static void sdu$ragnarokExtras(HudStyle style, CallbackInfoReturnable<List<HudElement>> cir)
    {
        RagnarokHudBridge.adjustExtras(cir.getReturnValue());
    }

    @Inject(method = "baseSize", at = @At("HEAD"), cancellable = true, require = 0)
    private static void sdu$ragnarokSize(HudStyle style, HudElement element, CallbackInfoReturnable<float[]> cir)
    {
        float[] size = RagnarokHudBridge.baseSize(element);
        if (size != null)
            cir.setReturnValue(size);
    }

    @Inject(method = "defaultPlacement", at = @At("HEAD"), cancellable = true, require = 0)
    private static void sdu$ragnarokDefault(HudStyle style, HudElement element, CallbackInfoReturnable<HudPlacement> cir)
    {
        HudPlacement placement = RagnarokHudBridge.defaultPlacement(element);
        if (placement != null)
            cir.setReturnValue(placement);
    }
}
