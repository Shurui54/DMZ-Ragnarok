package net.shurui.shuruisutilities.core.mixin.stats;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.GeneralServerConfig;
import com.dragonminez.common.init.item.consumables.CapsuleType;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Stats;

/**
 * Fix: DMZ stat capsules waste their points and stall progression when the target stat is already at cap.
 * DMZ's CapsuleItem.applyCapsuleStats computes getMaxAllowedIncreaseForStat and, if <= 0, shows capsule.full
 * and drops the whole value with no spill to non-maxed stats. Multi-stat capsules judge each stat
 * independently, so any part a maxed stat refuses is lost.
 *
 * We @Inject(cancellable) at HEAD, redistribute refused points, then return the message DMZ would have shown.
 * Inject+cancel not @Overwrite (with require=0) so a future DMZ reshape degrades to the stock path instead of
 * hard-failing.
 *
 * Per listed stat (called once per stat in the split list): place min(requested, allowed) in the listed stat,
 * then spill the remainder into the other five base stats in canonical order until exhausted or no headroom.
 * getMaxAllowedIncreaseForStat already folds in the shared budget AND SU's prestige-widened caps (via the
 * MixinDmzStatCap* mixins), so respect it and never read caps directly. Writes go through the same setters
 * DMZ's addToStat uses (post StatChangeEvent + write attribute base, auto-synced), so no extra sync needed.
 *
 * Item shrinks by 1 only if at least one point landed; otherwise left intact + capsule.full. remap=false
 * (DMZ descriptors), require=0. Capsule use is already server-side only (caller's !isClientSide guard).
 */
@Mixin(targets = "com.dragonminez.common.init.item.consumables.CapsuleItem", remap = false)
public abstract class MixinDmzCapsuleRedistribute
{
    // DMZ's canonical base-stat order, mirrored from IncreaseStatC2S.StatType / addToStat
    private static final String[] SU$STAT_ORDER = {"STR", "SKP", "RES", "VIT", "PWR", "ENE"};

    @Shadow
    @Final
    private CapsuleType type;

    @Shadow
    private Integer tierMultiplier;

    @Inject(method = "applyCapsuleStats", at = @At("HEAD"), cancellable = true, require = 0)
    private void su$redistributeCapsule(ItemStack capsule, StatsData data, String statName,
                                        CallbackInfoReturnable<Component> cir)
    {
        if (data == null || statName == null || capsule == null)
            return; // fall through to DMZ's stock impl

        GeneralServerConfig.CapsuleValues values =
                ConfigManager.getServerConfig().getGameplay().getCapsules().getCapsuleValues(this.type);
        int mult = this.tierMultiplier == null ? 1 : this.tierMultiplier;
        int requested = values.getPoints() * mult;

        // first pass: the listed stat gets first claim on its own points
        int placedHere = 0;
        int remaining = requested;
        int allowedHere = data.getMaxAllowedIncreaseForStat(statName, remaining);
        if (allowedHere > 0)
        {
            this.su$addToStat(data, statName, allowedHere);
            placedHere += allowedHere;
            remaining -= allowedHere;
        }

        // second pass: spill the refused remainder into whatever base stats still have headroom
        int redistributed = 0;
        String upperTarget = statName.toUpperCase();
        for (String other : SU$STAT_ORDER)
        {
            if (remaining <= 0)
                break;
            if (other.equals(upperTarget))
                continue; // already handled above
            int allowedOther = data.getMaxAllowedIncreaseForStat(other, remaining);
            if (allowedOther <= 0)
                continue;
            this.su$addToStat(data, other, allowedOther);
            redistributed += allowedOther;
            remaining -= allowedOther;
        }

        int totalPlaced = placedHere + redistributed;
        if (totalPlaced <= 0)
        {
            // nothing fit anywhere: keep DMZ's behaviour, no consume + "full" message
            cir.setReturnValue(Component.translatable("item.dragonminez.capsule.full", statName)
                    .withStyle(ChatFormatting.RED));
            return;
        }

        capsule.shrink(1);

        MutableComponent message;
        if (placedHere > 0)
        {
            message = Component.translatable("item.dragonminez.capsule.use", placedHere, statName)
                    .withStyle(ChatFormatting.GREEN);
        }
        else
        {
            // listed stat was full; show it as full so the player sees why nothing landed there
            message = Component.translatable("item.dragonminez.capsule.full", statName)
                    .withStyle(ChatFormatting.RED);
        }
        if (redistributed > 0)
        {
            message.append(Component.literal(" (+" + redistributed + ")").withStyle(ChatFormatting.AQUA));
        }
        cir.setReturnValue(message);
    }

    // mirror of DMZ's private CapsuleItem.addToStat: base-stat setters, same switch
    private void su$addToStat(StatsData data, String statName, int amount)
    {
        Stats stats = data.getStats();
        switch (statName.toUpperCase())
        {
            case "STR": stats.setStrength(stats.getStrength() + amount); break;
            case "SKP": stats.setStrikePower(stats.getStrikePower() + amount); break;
            case "RES": stats.setResistance(stats.getResistance() + amount); break;
            case "VIT": stats.setVitality(stats.getVitality() + amount); break;
            case "PWR": stats.setKiPower(stats.getKiPower() + amount); break;
            case "ENE": stats.setEnergy(stats.getEnergy() + amount); break;
            default: break;
        }
    }
}
