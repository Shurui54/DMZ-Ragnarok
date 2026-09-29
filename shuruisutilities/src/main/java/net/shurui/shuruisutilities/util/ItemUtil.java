package net.shurui.shuruisutilities.util;

import net.shurui.shuruisutilities.commons.selections.WorldPoint;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.registries.ForgeRegistries;

public final class ItemUtil
{
    // 1.20.1: SignBlockEntity no longer has a single messages array (the old 1.16 SRG field field_145915_a). Text is
    // now stored as front/back SignText. We operate on the FRONT side, which matches the single-sided 1.16 behaviour.
    public static Component[] getText(SignBlockEntity sign)
    {
        return sign.getText(true).getMessages(false);
    }

    public static void setText(SignBlockEntity sign, Component[] text)
    {
        SignText signText = sign.getText(true);
        for (int i = 0; i < text.length && i < 4; i++)
        {
            signText = signText.setMessage(i, text[i]);
        }
        sign.setText(signText, true);
        sign.setChanged();
    }

    public static int getItemDamage(ItemStack stack)
    {
        try
        {
            return stack.getDamageValue();
        }
        catch (Exception e)
        {
            if (stack.getItem() == null)
                LoggingHandler.sulog.error("ItemStack item is null when checking getItemDamage");
            else
                LoggingHandler.sulog.error(String.format("Item %s threw exception on getItemDamage",
                        stack.getItem().getClass().getName()));
            return 0;
        }
    }

    public static boolean isItemFrame(HangingEntity entity)
    {
        return entity instanceof ItemFrame;
    }

    public static boolean isSign(Block block)
    {
        return block instanceof WallSignBlock || block instanceof StandingSignBlock;
    }

    public static Component[] getSignText(WorldPoint point)
    {
        BlockEntity te = point.getTileEntity();
        if (te instanceof SignBlockEntity)
        {
            SignBlockEntity sign = (SignBlockEntity) te;
            return ItemUtil.getText(sign);
        }
        return null;
    }

    public static CompoundTag getTagCompound(ItemStack itemStack)
    {
        CompoundTag tag = itemStack.getTag();
        if (tag == null)
        {
            tag = new CompoundTag();
            itemStack.setTag(tag);
        }
        return tag;
    }

    public static CompoundTag getCompoundTag(CompoundTag tag, String side)
    {
        CompoundTag subTag = tag.getCompound(side);
        tag.put(side, subTag);
        return subTag;
    }

    public static String getItemName(Item item)
    {
        return ForgeRegistries.ITEMS.getKey(item).toString();
    }

    public static String getItemName(ItemStack itemstack)
    {
        return getItemName(itemstack.getItem());
    }
}
