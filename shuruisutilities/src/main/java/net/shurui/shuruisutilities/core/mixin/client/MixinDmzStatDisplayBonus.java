package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.dragonminez.common.stats.StatsData;

/**
 * Folds SU's (and DMZ's own) BonusStats into the six stat numbers on DMZ's character (X) screen, so shrine
 * buffs and other bonuses show there and may display over the max, not just in the hover tooltip.
 *
 * DMZ's renderStatsInfo shows modifiedValue = baseValue * totalMult and excludes BonusStats. We recompose it
 * to match getBattlePowerExact: shown = (base + multBonus) * totalMult + flatBonus, where multBonus is the
 * multiplicable bucket (scaled) and flatBonus the flat bucket (unscaled). No clamping, over-max is the point.
 * The trailing "xN" suffix is untouched.
 *
 * pure Sponge-Mixin (no MixinExtras on the 1.20.1 compile classpath). modifiedValue is built from three
 * sibling locals a single @ModifyVariable can't all see, so a @Redirect on getTotalMultiplier(statName)
 * records the current key (returning the real multiplier), then @ModifyVariable on modifiedValue re-derives
 * base+multiplier by that key. both require=0 / remap=false so a future DMZ reshape degrades to the vanilla
 * display. render thread only, so the single-slot handoff field needs no sync.
 */
@Mixin(targets = "com.dragonminez.client.gui.character.CharacterStatsScreen", remap = false)
public abstract class MixinDmzStatDisplayBonus
{
    @Shadow
    private StatsData statsData;

    // stat key of the row being laid out, captured from the getTotalMultiplier redirect
    @Unique
    private String su$currentStatKey;

    @Redirect(
            method = "renderStatsInfo",
            at = @At(value = "INVOKE",
                    target = "Lcom/dragonminez/common/stats/StatsData;getTotalMultiplier(Ljava/lang/String;)D"),
            require = 0)
    private double su$captureStatKey(StatsData statsData, String statName)
    {
        this.su$currentStatKey = statName;
        return statsData.getTotalMultiplier(statName);
    }

    @ModifyVariable(method = "renderStatsInfo", at = @At("STORE"), name = "modifiedValue", require = 0)
    private double su$foldBonuses(double modifiedValue)
    {
        String statName = this.su$currentStatKey;
        StatsData sd = this.statsData;
        if (statName == null || sd == null)
            return modifiedValue;
        try
        {
            // RES bonuses live under the DEF bonus key in DMZ's BonusStats (see DmzBridge.bonusKey)
            String key = "RES".equals(statName) ? "DEF" : statName;
            int base = sd.getCurrentStatValue(statName);
            double totalMult = sd.getTotalMultiplier(statName);
            double multBonus = sd.getBonusStats().calculateBonus(key, base, true);
            double flatBonus = sd.getBonusStats().calculateBonus(key, base, false);
            // mirror getBattlePowerExact's composition (minus the BP scale factor, screen shows raw units):
            // mult bonus rides the multiplier, flat bonus added after.
            return ((double) base + multBonus) * totalMult + flatBonus;
        }
        catch (Throwable t)
        {
            return modifiedValue;
        }
    }
}
