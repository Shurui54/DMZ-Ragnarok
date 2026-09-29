package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

import net.shurui.shuruisutilities.disguise.client.DisguiseSkins;

/**
 * The disguise SKIN in the world: a disguised player's Minecraft skin (and its slim / wide arm model) is the target's
 * once {@link DisguiseSkins} has resolved it. Every consumer that asks the player for its skin follows along, which
 * includes DragonMineZ's skin-textured bodies and hands as well as vanilla's own renderer. Until the download lands,
 * and for everyone who is not disguised, the answer is untouched.
 *
 * <p>Vanilla target, so it is remapped through the SU refmap like the other vanilla client mixins. RETURN keeps the
 * original lookup (and its side effects) intact and only swaps the answer.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class MixinAbstractClientPlayerDisguiseSkin
{
    @Inject(method = "getSkinTextureLocation", at = @At("RETURN"), cancellable = true, require = 0)
    private void su$disguiseSkin(CallbackInfoReturnable<ResourceLocation> cir)
    {
        DisguiseSkins.Resolved r = DisguiseSkins.get(((AbstractClientPlayer) (Object) this).getUUID());
        if (r != null && r.location != null)
            cir.setReturnValue(r.location);
    }

    @Inject(method = "getModelName", at = @At("RETURN"), cancellable = true, require = 0)
    private void su$disguiseModel(CallbackInfoReturnable<String> cir)
    {
        DisguiseSkins.Resolved r = DisguiseSkins.get(((AbstractClientPlayer) (Object) this).getUUID());
        if (r != null)
            cir.setReturnValue(r.slim ? "slim" : "default");
    }
}
