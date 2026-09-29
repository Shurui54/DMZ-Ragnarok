package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.player.Player;

import com.dragonminez.common.stats.StatsData;


/**
 * Strips the two power bonuses the mutant trait gives a player through {@code StatsData}, so a mutant's numbers are a
 * non-mutant's numbers. The trait itself is left in place: the player is still flagged, {@code isMutant} still
 * answers true, and the lottery may still pick them. Only the mechanical payoff is removed.
 *
 * <p>Two reads, both here because both live on {@code StatsData}:
 *
 * <ul>
 *   <li>{@code getMutantTpMultiplier} feeds {@code getTpAdditiveMultiplier} and the {@code MUTANT} branch of
 *       {@code getTpSourceMultiplier}, so a mutant earns training points faster. Forced to 1.0 (no boost). The
 *       client TP tooltip drops its mutant line for free, because it only draws the line when this differs from 1.0.
 *   <li>{@code applyMutantFormPowerModifier} scales a legendary form's power up (with the skill) or down (without),
 *       from the mutant config. Forced to hand back the multiplier it was given, unchanged, so the form is worth
 *       exactly what it is worth to a non-mutant.
 * </ul>
 *
 * <p>PRIVATE since batch M: both are in force exactly when the logical server holds the Ragnarok Key; a keyless
 * server gets DMZ's mutant untouched. See {@link #su$nerfOn()} for why the side matters here.
 *
 * <p>Since S20 the decision lives in the Ragnarok Key: the server asks {@code MutantHooks.nerfActive()} (keyless:
 * off) and the client reads {@code MutantHooks.clientNerfActive()}, that same server answer as synced at login.
 *
 * <p>{@code require = 0} per the standing rule: a DMZ rename degrades to the mutant bonus simply staying, never to a
 * broken load. Both handlers capture DMZ's parameters exactly (argument capture is all or nothing), and the private
 * {@code applyMutantFormPowerModifier} is a legal mixin target.
 */
@Mixin(targets = "com.dragonminez.common.stats.StatsData", remap = false)
public abstract class MixinDmzMutantStats
{
    @Inject(method = "getMutantTpMultiplier", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void su$noMutantTpBonus(CallbackInfoReturnable<Double> cir)
    {
        if (su$nerfOn())
            cir.setReturnValue(1.0);
    }

    @Inject(method = "applyMutantFormPowerModifier", at = @At("HEAD"), cancellable = true, require = 0,
            remap = false)
    private void su$noMutantFormPower(String groupName, double multiplier, CallbackInfoReturnable<Double> cir)
    {
        if (su$nerfOn())
            cir.setReturnValue(multiplier);
    }

    /**
     * Is the nerf in force for the side this call is on?
     *
     * <p>This class is applied on BOTH sides: the client reads its own {@code StatsData} to draw the TP tooltip and
     * the form screens, so it must read the server-synced answer ({@code MutantHooks.clientNerfActive()}) rather
     * than the server-only {@code MutantHooks.nerfActive()}, or a client would draw a bonus the server it is on does
     * not give.
     * {@code this} is a {@code StatsData} once the mixin is merged, so the cast is safe; a null player (none is
     * expected, the field is final and set in the constructor) is treated as the server side.
     */
    private boolean su$nerfOn()
    {
        Player player = ((StatsData) (Object) this).getPlayer();
        boolean clientSide = player != null && player.level().isClientSide;
        return clientSide ? net.shurui.shuruisutilities.api.key.MutantHooks.clientNerfActive()
                : net.shurui.shuruisutilities.api.key.MutantHooks.nerfActive();
    }
}
