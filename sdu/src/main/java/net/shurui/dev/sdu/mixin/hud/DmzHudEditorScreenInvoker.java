package net.shurui.dev.sdu.mixin.hud;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.dragonminez.client.gui.config.HudEditorScreen;

// Flips DMZ's HUD editor to its Extras page, so the suite's old "move HUD" key lands on the panels it used to move.
@Mixin(value = HudEditorScreen.class, remap = false)
public interface DmzHudEditorScreenInvoker
{
    @Invoker("toggleExtras")
    void sdu$toggleExtras();
}
