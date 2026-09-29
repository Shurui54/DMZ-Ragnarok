package net.shurui.shuruisutilities.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Turns Tinkers' Necrotic ("Life Steal") off.
 *
 * <h2>Why it had to go</h2>
 * Every number Necrotic uses is a PERCENTAGE OF DAMAGE, written for a game where a good hit is eight points. On a
 * DMZ server a hit is four figures, so the same percentages read completely differently:
 * <ul>
 *   <li>The weapon half heals {@code 5% * level} of the damage dealt - a full heal on any hit that lands.</li>
 *   <li>The armour half is worse: on being hit it grants REGENERATION for {@code (25% * level * damage) * 50} ticks.
 *       One taken hit is an hour of regeneration, which is exactly the complaint.</li>
 * </ul>
 *
 * <h2>Why a mixin and not just the datapack</h2>
 * Necrotic is defined in Java, not in {@code tinkering/modifiers/}, so there is no JSON to override - only its
 * recipe, which is switched off alongside this (see {@code TinkersOverridePack}). That alone would leave every tool
 * and every piece of armour that ALREADY carries it working exactly as before, and those are the ones being
 * complained about. Cancelling the hooks reaches those too.
 *
 * <p>The tooltip is cancelled with the rest so a tool stops advertising a Life Steal it no longer has.
 *
 * <p>Lives in the non-required compat config and targets Tinkers by name: with no tconstruct installed nothing here
 * loads. Handlers take no target arguments on purpose - Tinkers is not a compile dependency, so its types cannot be
 * named here, and Mixin allows a handler to omit them entirely.
 */
@Mixin(targets = "slimeknights.tconstruct.tools.modifiers.traits.melee.NecroticModifier", remap = false)
public abstract class MixinTcNecrotic
{
    /** The weapon half: heals the attacker a percentage of the damage dealt. */
    @Inject(method = "onMonsterMeleeHit", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void su$noMeleeLifesteal(CallbackInfo ci)
    {
        ci.cancel();
    }

    /** Delegates to the above on a fully charged crit; cancelled here as well so neither route survives. */
    @Inject(method = "afterMeleeHit", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void su$noCritLifesteal(CallbackInfo ci)
    {
        ci.cancel();
    }

    /** The armour half, and the expensive one: the hour of regeneration off a single taken hit. */
    @Inject(method = "onAttacked", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void su$noRegenOnHit(CallbackInfo ci)
    {
        ci.cancel();
    }

    /**
     * The bow half. Returns whether the projectile should be stopped, and Tinkers' own answer is always false, so
     * returning false is not a behaviour change - it just skips the healing on the way.
     */
    @Inject(method = "onProjectileHitEntity", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void su$noProjectileLifesteal(CallbackInfoReturnable<Boolean> cir)
    {
        cir.setReturnValue(false);
    }

    /** Stop claiming a Life Steal percentage the tool no longer has. */
    @Inject(method = "addTooltip", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void su$noLifestealTooltip(CallbackInfo ci)
    {
        ci.cancel();
    }
}
