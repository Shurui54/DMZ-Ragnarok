package net.shurui.dev.sdu.mixin.hud;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.dragonminez.client.gui.hud.HudStyle;

// The private enum constructor of DMZ's HudStyle, so DmzHudStyleMixin can append the Ragnarok style.
@Mixin(value = HudStyle.class, remap = false)
public interface DmzHudStyleInvoker
{
    @Invoker("<init>")
    static HudStyle sdu$create(String name, int ordinal, String configName)
    {
        throw new AssertionError();
    }
}
