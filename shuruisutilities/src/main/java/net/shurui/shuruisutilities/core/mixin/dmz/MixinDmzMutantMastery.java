package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.LivingEntity;


/**
 * Removes the mutant mastery bonus. {@code PotionEffectHelper.applyMasteryGainMultiplier} multiplies every mastery
 * gain by {@code getMutantMasteryMultiplier}, which reads the {@code MUTANT} mob effect and returns the mutant
 * config's mastery multiplier. Forced to 1.0 so a mutant masters forms at a non-mutant's rate.
 *
 * <p>This is the one mutant payoff keyed on the {@code MUTANT} mob effect rather than the {@code StatsData} effect
 * flag, which is why the effect itself is left on the player (it is a purely cosmetic icon with no attribute
 * modifiers) while its single mechanical use is neutralised here.
 *
 * <p>PRIVATE since batch M: the nerf is in force exactly when the logical server holds the Ragnarok Key
 * ({@code MutantHooks.nerfActive()}); a keyless server keeps DMZ's mastery bonus. The side is taken from the entity so
 * the client reads the server-synced answer ({@code MutantHooks.clientNerfActive()}) rather than the server-only gate.
 *
 * <p>Since S20 the decision lives in the Ragnarok Key: the server asks {@code MutantHooks.nerfActive()} (keyless:
 * off) and the client reads {@code MutantHooks.clientNerfActive()}, that same server answer as synced at login.
 *
 * <p>{@code require = 0} per the standing rule: a DMZ rename degrades to the mutant bonus staying, never a broken
 * load. The target is {@code private static}, a legal mixin target, and the handler captures its one parameter.
 */
@Mixin(targets = "com.dragonminez.server.util.PotionEffectHelper", remap = false)
public abstract class MixinDmzMutantMastery
{
    @Inject(method = "getMutantMasteryMultiplier", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void su$noMutantMasteryBonus(LivingEntity entity, CallbackInfoReturnable<Double> cir)
    {
        boolean clientSide = entity != null && entity.level().isClientSide;
        if (clientSide ? net.shurui.shuruisutilities.api.key.MutantHooks.clientNerfActive()
                : net.shurui.shuruisutilities.api.key.MutantHooks.nerfActive())
            cir.setReturnValue(1.0);
    }
}
