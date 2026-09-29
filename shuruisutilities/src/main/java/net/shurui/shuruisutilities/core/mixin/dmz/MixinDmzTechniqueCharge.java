package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dragonminez.common.stats.techniques.Techniques;

/**
 * Marks when one of our techniques STARTS charging, so the charge animation can be played from the moment the key
 * goes down rather than only when a projectile happens to exist.
 *
 * <p>{@code startTechniqueCharge} is the first thing that knows a charge has begun and which technique it is for, so
 * it is the only place a charge-up can be hooked without a projectile.
 *
 * <p>Records the id only; the animation is sent from the server tick handler, which has the player. This class has
 * no player reference of its own - {@code Techniques} is per-character state, not per-entity.
 */
@Mixin(value = Techniques.class, remap = false)
public abstract class MixinDmzTechniqueCharge
{
    @Inject(method = "startTechniqueCharge", at = @At("TAIL"), require = 0, remap = false)
    private void su$noteChargeStart(String techniqueId, CallbackInfo ci)
    {
        net.shurui.shuruisutilities.dragons.MoveChargeTracker.noteChargeStarted(techniqueId);
    }
}
