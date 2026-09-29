package net.shurui.shuruisutilities.core.mixin.stats;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.dragonminez.common.stats.character.Stats;

/**
 * Fix: an active SU shrine buff (a BonusStats overlay under su_shrine, and any other BonusStats source like
 * Raid Bosses' Z-Souls) must never eat into the per-stat purchase budget.
 *
 * DMZ's getMaxAllowedIncreaseForStat computes headroom as getConfiguredMaxValue() - getCurrentStatValue(stat).
 * In stock 2.1.2 getCurrentStatValue already returns the raw attribute-base stat, so bonuses don't leak in;
 * this redirect makes that explicit and future-proof by recomputing the subtracted "current" value straight
 * from the base Stats getters on this one call site. If a future DMZ folds a BonusStats overlay into
 * getCurrentStatValue (a buffed stat would read at/over cap and silently zero the budget: the "shrines block
 * upgrades" report), the purchase path stays pinned to the base stat the player actually paid for.
 *
 * only the getCurrentStatValue call inside getMaxAllowedIncreaseForStat is redirected; the method is left alone
 * elsewhere (display, tooltips). no bonuses = identical value, unchanged. remap=false (DMZ descriptors),
 * require=0 so a DMZ reshape degrades to the stock path.
 */
@Mixin(targets = "com.dragonminez.common.stats.StatsData", remap = false)
public abstract class MixinDmzStatCapPurchaseBase
{
    @Shadow
    public abstract Stats getStats();

    @Redirect(
            method = "getMaxAllowedIncreaseForStat",
            at = @At(value = "INVOKE",
                    target = "Lcom/dragonminez/common/stats/StatsData;getCurrentStatValue(Ljava/lang/String;)I"),
            require = 0)
    private int su$budgetFromBaseStat(com.dragonminez.common.stats.StatsData self, String statName)
    {
        Stats stats = this.getStats();
        if (stats == null || statName == null)
            return self.getCurrentStatValue(statName);
        switch (statName.toUpperCase())
        {
            case "STR": return stats.getStrength();
            case "SKP": return stats.getStrikePower();
            case "RES": return stats.getResistance();
            case "VIT": return stats.getVitality();
            case "PWR": return stats.getKiPower();
            case "ENE": return stats.getEnergy();
            default:    return self.getCurrentStatValue(statName);
        }
    }
}
