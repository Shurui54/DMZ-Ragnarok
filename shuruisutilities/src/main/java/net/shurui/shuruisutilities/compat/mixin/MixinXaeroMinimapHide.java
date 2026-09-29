package net.shurui.shuruisutilities.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shurui.shuruisutilities.racing.client.RaceClientState;

/**
 * Hides Xaero's Minimap while the player is IN a race, so it does not sit over the race HUD and the race radar. It
 * only cancels the draw while {@link RaceClientState#isSessionActive()} is true, so the minimap comes back the instant
 * the race ends, with the user's own Xaero settings untouched (no toggle to save/restore).
 *
 * <p>Target, read from Xaero's Minimap 26.1.0: {@code xaero.hud.minimap.module.MinimapRenderer} is the minimap HUD
 * module's renderer, {@code void render(MinimapSession, ModuleRenderContext, GuiGraphics, float)}. The class also
 * carries the compiler's bridge {@code render(ModuleSession, ...)}, so the method is pinned by its full descriptor.
 * The handler captures no arguments (capture is all or nothing), and it is a STRING-form target with
 * {@code remap = false} under the config's {@code defaultRequire: 0}, like {@link MixinXaeroInfoDisplayRenderer}, so
 * with Xaero absent or the method reshaped by an update this simply does not apply. Client only.
 */
@Mixin(targets = "xaero.hud.minimap.module.MinimapRenderer", remap = false)
public abstract class MixinXaeroMinimapHide
{
    @Inject(method = "render(Lxaero/hud/minimap/module/MinimapSession;Lxaero/hud/render/module/ModuleRenderContext;"
            + "Lnet/minecraft/client/gui/GuiGraphics;F)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void dmzr$hideMinimapDuringRace(CallbackInfo ci)
    {
        if (RaceClientState.isSessionActive())
            ci.cancel();
    }
}
