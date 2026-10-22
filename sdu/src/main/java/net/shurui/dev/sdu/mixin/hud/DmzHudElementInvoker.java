package net.shurui.dev.sdu.mixin.hud;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.dragonminez.client.gui.hud.layout.HudElement;

// The private enum constructor of DMZ's HudElement (javac passes the constant name and ordinal first), so
// DmzHudElementMixin can append the suite's HUD elements. Literal names, remap=false (DMZ class).
@Mixin(value = HudElement.class, remap = false)
public interface DmzHudElementInvoker
{
    @Invoker("<init>")
    static HudElement sdu$create(String name, int ordinal, String id, boolean meter)
    {
        throw new AssertionError();
    }
}
