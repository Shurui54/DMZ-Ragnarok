package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;

/**
 * A Controls row's conflict check skips the hidden private keybinds too, so a visible key is not painted red over a
 * clash with a row the player cannot see (the racial key and the hidden role ability both default to V). With the
 * key nothing is filtered, so the check is exactly as before.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.controls.KeyBindsList$KeyEntry")
public abstract class MixinKeyBindsEntryHidePrivate
{
    @Redirect(method = "refreshEntry",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;keyMappings:[Lnet/minecraft/client/KeyMapping;"))
    private KeyMapping[] su$visibleKeyMappings(Options options)
    {
        return net.shurui.shuruisutilities.client.PrivateKeybinds.visible(options.keyMappings);
    }
}
