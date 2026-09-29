package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.controls.KeyBindsList;

/**
 * Builds the Controls list without the keybinds of private features the connected server has not opened
 * ({@link net.shurui.shuruisutilities.client.PrivateKeybinds}). Only the LIST reads the filtered copy:
 * {@code Options.keyMappings} itself is untouched, so the bindings still load from and save to options.txt. With the
 * key (or the feature) nothing is filtered and the list is built from the very same array as before.
 */
@Mixin(KeyBindsList.class)
public abstract class MixinKeyBindsListHidePrivate
{
    @Redirect(method = "<init>",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;keyMappings:[Lnet/minecraft/client/KeyMapping;"))
    private KeyMapping[] su$visibleKeyMappings(Options options)
    {
        return net.shurui.shuruisutilities.client.PrivateKeybinds.visible(options.keyMappings);
    }
}
