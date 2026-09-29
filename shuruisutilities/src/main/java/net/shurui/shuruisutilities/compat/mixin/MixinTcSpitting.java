package net.shurui.shuruisutilities.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops Tinkers' Spitting ability from firing.
 *
 * <p>Spitting throws a {@code FluidEffectProjectile} carrying whatever is in the tool's tank, and the fluid's effect
 * is what lands - which for several fluids means changing blocks. None of that goes anywhere near
 * {@code BlockBreakGuard} or the terrain-regen capture, because it is Tinkers' own code acting on the world
 * directly, so it was taking terrain out of claims and protected regions with nothing to answer for it.
 *
 * <p>Only the release is cancelled. Charging the tool still animates, which is deliberate: the alternative is
 * injecting into {@code onToolUse}, whose return type is a Tinkers type this class cannot name (Tinkers is not a
 * compile dependency), and a charge that does nothing is a far smaller wart than a fragile injection.
 *
 * <p>The recipe is switched off in the same breath (see {@code TinkersOverridePack}) so nobody spends an ability
 * slot on it from here on; this exists for the cannons that are already out there.
 */
@Mixin(targets = "slimeknights.tconstruct.tools.modifiers.ability.fluid.SpittingModifier", remap = false)
public abstract class MixinTcSpitting
{
    @Inject(method = "onStoppedUsing", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void su$noSpit(CallbackInfo ci)
    {
        ci.cancel();
    }
}
