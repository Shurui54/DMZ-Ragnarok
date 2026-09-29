package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.dragonminez.common.config.GeneralServerConfig.GameplayConfig;

import net.shurui.shuruisutilities.prestige.client.SuCapClient;

/**
 * Fix: "prestige not showing max stats increase". MixinDmzStatCapBudget widens the purchase budget
 * (StatsData.getConfiguredMaxValue), but DMZ's character (X) stats screen reads the raw config cap
 * getMaxValue() directly in three places, bypassing the widened method. So the displayed max, the recursive
 * TP-cost readout, and the radar-chart scaling stayed pinned to the un-prestiged cap even though the player
 * could keep buying past it.
 *
 * This redirect scales the raw getMaxValue() by the local player's synced prestige cap multiplier at those
 * three sites: initStatButtons (feeds calculateRecursiveCost so the "+" cost curve aligns with the raised cap),
 * renderStatsInfo (same for the bottom TP-cost readout + tooltip), and renderStatisticsInfoHexagon (the radar
 * reference value, so the hexagon fills relative to the raised cap instead of overflowing).
 *
 * getConfiguredMaxValue-based sites (e.g. damage-reduction k_factor) are already widened by MixinDmzStatCapBudget
 * and left alone. multiplier 1.0 = no change. remap=false (DMZ descriptors), require=0 so a future DMZ reshape
 * degrades to the stock display instead of crashing.
 */
@Mixin(targets = "com.dragonminez.client.gui.character.CharacterStatsScreen", remap = false)
public abstract class MixinDmzStatDisplayCap
{
    @Redirect(
            method = {"initStatButtons", "renderStatsInfo", "renderStatisticsInfoHexagon"},
            at = @At(value = "INVOKE",
                    target = "Lcom/dragonminez/common/config/GeneralServerConfig$GameplayConfig;getMaxValue()Ljava/lang/Integer;"),
            require = 0)
    private Integer su$widenDisplayedCap(GameplayConfig config)
    {
        int base = config.getMaxValue();
        double mult = SuCapClient.multiplier();
        if (mult <= 1.0)
            return base;
        return (int) Math.min((double) base * mult, Integer.MAX_VALUE);
    }
}
