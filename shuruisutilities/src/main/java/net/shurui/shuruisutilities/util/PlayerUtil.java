package net.shurui.shuruisutilities.util;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.phys.HitResult;

public abstract class PlayerUtil
{

    // swaps in newItems, returns the old inventory
    public static List<ItemStack> swapInventory(Player player, List<ItemStack> newItems)
    {
        List<ItemStack> oldItems = new ArrayList<>();
        for (int slotIdx = 0; slotIdx < player.getInventory().getContainerSize(); slotIdx++)
        {
            oldItems.add(player.getInventory().getItem(slotIdx));
            if (newItems != null && slotIdx < newItems.size())
                player.getInventory().setItem(slotIdx, newItems.get(slotIdx));
            else
                player.getInventory().setItem(slotIdx, ItemStack.EMPTY);
        }
        return oldItems;
    }

    // give the stack, or drop it if the inventory is full
    public static void give(Player player, ItemStack item)
    {
        ItemEntity entityitem = player.drop(item, false);
        if (entityitem != null)
        {
            entityitem.setNoPickUpDelay();
            entityitem.setTarget(player.getGameProfile().getId());
        }
    }

    // effectString = comma-separated id:duration:amplifier (or id:duration) tuples
    public static void applyPotionEffects(Player player, String effectString)
    {
        String[] effects = effectString.replaceAll("\\s", "").split(","); // example = 9:5:0
        for (String poisonEffect : effects)
        {
            String[] effectValues = poisonEffect.split(":");
            if (effectValues.length < 2)
            {
                // LoggingHandler.sulog.warn("Too few arguments for potion effects");
            }
            else if (effectValues.length > 3)
            {
                LoggingHandler.sulog.warn("Too many arguments for potion effects");
            }
            else
            {
                try
                {
                    int potionID = Integer.parseInt(effectValues[0]);
                    int effectDuration = Integer.parseInt(effectValues[1]);
                    int amplifier = 0;
                    if (effectValues.length == 3)
                        amplifier = Integer.parseInt(effectValues[2]);
                    if (MobEffect.byId(potionID) == null)
                    {
                        LoggingHandler.sulog.warn("Invalid potion ID {}", potionID);
                        continue;
                    }
                    player.addEffect(new MobEffectInstance(MobEffect.byId(potionID), effectDuration * 20, amplifier, false,
                            true, true));
                }
                catch (NumberFormatException e)
                {
                    LoggingHandler.sulog.warn("Invalid potion ID:duration:amplifier data.");
                }
            }
        }
    }

    public static CompoundTag getPersistedTag(Player player, boolean createIfMissing)
    {
        CompoundTag tag = player.getPersistentData();
        if (createIfMissing)// ?
            player.getPersistentData();
        return tag;
    }

    // what the player is looking at; null if nothing
    public static HitResult getPlayerLookingSpot(Player player)
    {
        if (player instanceof Player)
            return getPlayerLookingSpot(player,
                    player.getAttribute(net.minecraftforge.common.ForgeMod.BLOCK_REACH.get()).getValue());
        else
            return getPlayerLookingSpot(player, 5.0);
    }

    // maxDistance kept around 5. null if nothing.
    public static HitResult getPlayerLookingSpot(Player player, double maxDistance)
    {
        return player.pick(maxDistance, 1.0F, true);
    }

}
