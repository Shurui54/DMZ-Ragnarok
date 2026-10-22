package net.shurui.dev.sdu.mixin.hud;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dragonminez.client.gui.config.HudEditorScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHud;

/**
 * DMZ's HUD editor draws each of its HUD classes by hand before its boxes; draw the suite's previews in the same
 * place (right after DMZ's last main HUD, or its last extra), so they sit under the drag boxes exactly like DMZ's own.
 * {@code render} overrides Screen's, so the injector remaps; the INVOKE targets are DMZ's own methods and do not.
 */
@Mixin(value = HudEditorScreen.class, remap = false)
public abstract class DmzHudEditorScreenMixin
{
    @Inject(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lcom/dragonminez/client/gui/hud/TechniqueHotbarHUD;render(Lnet/minecraft/client/gui/GuiGraphics;FII)V",
                    shift = At.Shift.AFTER, remap = false),
            require = 0, remap = true)
    private void sdu$previewMain(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci)
    {
        var window = Minecraft.getInstance().getWindow();
        RagnarokHud.drawEditorPreview(graphics, partialTick, window.getGuiScaledWidth(), window.getGuiScaledHeight(), false);
    }

    @Inject(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lcom/dragonminez/client/gui/hud/WorldBossContributionHUD;render(Lnet/minecraft/client/gui/GuiGraphics;FII)V",
                    shift = At.Shift.AFTER, remap = false),
            require = 0, remap = true)
    private void sdu$previewExtras(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci)
    {
        var window = Minecraft.getInstance().getWindow();
        RagnarokHud.drawEditorPreview(graphics, partialTick, window.getGuiScaledWidth(), window.getGuiScaledHeight(), true);
    }
}
