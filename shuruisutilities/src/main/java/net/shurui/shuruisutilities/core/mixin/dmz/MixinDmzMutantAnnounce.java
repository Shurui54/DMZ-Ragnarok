package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Replaces the line a new mutant sees. {@code MutantManager.grant} ends by sending the player DMZ's
 * {@code message.dragonminez.mutant.gained}. With the trait now inert (its bonuses are stripped by the sibling
 * mutant mixins), that congratulatory line would promise a power that no longer exists, so it is swapped for the
 * one plain line the owner asked for, carried by {@code message.dmz_ragnarok.mutant.gained} in the suite's own lang
 * files.
 *
 * <p>Done as a redirect of the {@code Component.translatable} call inside {@code grant}, guarded by the original
 * key, so grant still runs in full: the player is still flagged, the saved-data holder set is still updated, and
 * the stats sync still fires. Only the sentence changes. A second {@code translatable} call added to grant later
 * would pass through untouched.
 *
 * <p>PRIVATE since batch M: the joke shows exactly when the nerf is in force. Since S20 that decision lives in the
 * Ragnarok Key and is read through {@link net.shurui.shuruisutilities.api.key.MutantHooks#nerfActive()} (keyless:
 * off). A keyless server, where the
 * trait still pays, falls through to {@code Component.translatable(key)} and DMZ shows its own congratulations
 * again: a joke about an inert trait would be a lie there. The check is safe at any lifecycle point and never
 * throws.
 *
 * <p>{@code MutantManager} is a server class, so reading the server-side hook directly is right here and there is
 * no need for the side-aware form the sibling mixins use.
 *
 * <p>The DMZ target and {@code grant} are literal ({@code remap = false}); the vanilla {@code Component.translatable}
 * target carries {@code remap = true} so it refmaps in production. {@code require = 0} per the standing rule: a DMZ
 * rename degrades to DMZ's own message showing, never a broken load.
 */
@Mixin(targets = "com.dragonminez.server.util.MutantManager", remap = false)
public abstract class MixinDmzMutantAnnounce
{
    @Redirect(method = "grant",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;)"
                            + "Lnet/minecraft/network/chat/MutableComponent;",
                    remap = true),
            require = 0, remap = false)
    private static MutableComponent su$replaceGrantMessage(String key)
    {
        if ("message.dragonminez.mutant.gained".equals(key)
                && net.shurui.shuruisutilities.api.key.MutantHooks.nerfActive())
        {
            return Component.translatable("message.dmz_ragnarok.mutant.gained");
        }
        return Component.translatable(key);
    }
}
