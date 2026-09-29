package net.shurui.shuruisutilities.core.mixin.stats;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.player.Player;

import net.shurui.shuruisutilities.prestige.PrestigeCaps;
import net.shurui.shuruisutilities.stats.StatCapBypass;

/**
 * Prestige stat-cap boost, chokepoint 2 of 2: the purchase / level / total-stats budget.
 * {@code StatsData.getConfiguredMaxValue} feeds {@code getMaxAllowedIncreaseForStat} (IncreaseStatC2S, the
 * /stats command, capsules, and the client +stat button gating) plus {@code getConfiguredMaxTotalStatsRaw}
 * (= x6). We scale its return by the player's prestige cap multiplier so the spendable budget grows in
 * lockstep with the hard clamp widened by {@code MixinDmzStatCapClamp}.
 *
 * <p>Null player =&gt; multiplier 1.0 (no change). {@code require = 0} so a future DMZ reshape degrades to a
 * no-op instead of crashing; string targets {@code remap = false} per the SU DMZ-mixin convention
 * (descriptors resolve against DMZ, not Mojmap).</p>
 */
@Mixin(targets = "com.dragonminez.common.stats.StatsData", remap = false)
public abstract class MixinDmzStatCapBudget
{
    @Shadow
    @Final
    private Player player;

    @Inject(method = "getConfiguredMaxValue", at = @At("RETURN"), cancellable = true, require = 0)
    private void su$widenBudget(CallbackInfoReturnable<Integer> cir)
    {
        // Command bypass (/dmzstats set|add) and the per-character override re-assert grant an effectively
        // unbounded increase budget so getMaxAllowedIncreaseForStat allows the full requested amount even when the
        // stat is already at/over cap. Server-thread only; normal in-GUI purchases keep the real budget.
        if (StatCapBypass.active())
        {
            cir.setReturnValue(Integer.MAX_VALUE);
            return;
        }
        double mult = this.player == null ? 1.0 : PrestigeCaps.getCapMultiplier(this.player);
        if (mult <= 1.0)
            return;
        int original = cir.getReturnValueI();
        cir.setReturnValue((int) Math.min((double) original * mult, Integer.MAX_VALUE));
    }
}
