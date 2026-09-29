package net.shurui.shuruisutilities.compat.customnpcs;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

// helpers for the CustomNPCs Zeni-shop integration. a trade's Zeni price lives as an int NBT tag on the sold
// ItemStack, so it rides the NPC's own save/sync (no extra packets) and is available everywhere the item is.
// TAG_BUY = Zeni to buy; TAG_SELL = Zeni paid, with the marker sold item suppressed (selling to the NPC).
// vanilla-only refs, safe to touch without CustomNPCs.
public final class ZeniShop
{
    private ZeniShop() {}

    public static final String TAG_BUY = "SUZeniBuy";
    public static final String TAG_SELL = "SUZeniSell";

    public static int buyPrice(ItemStack sold)
    {
        return read(sold, TAG_BUY);
    }

    public static int sellPrice(ItemStack sold)
    {
        return read(sold, TAG_SELL);
    }

    private static int read(ItemStack stack, String key)
    {
        if (stack == null || stack.isEmpty() || !stack.hasTag())
            return 0;
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(key) ? Math.max(0, tag.getInt(key)) : 0;
    }

    public static void setBuyPrice(ItemStack sold, int zeni)
    {
        write(sold, TAG_BUY, zeni);
    }

    public static void setSellPrice(ItemStack sold, int zeni)
    {
        write(sold, TAG_SELL, zeni);
    }

    private static void write(ItemStack stack, String key, int zeni)
    {
        if (stack == null || stack.isEmpty())
            return;
        if (zeni <= 0)
        {
            if (stack.hasTag())
            {
                stack.getTag().remove(key);
                cleanup(stack);
            }
        }
        else
        {
            stack.getOrCreateTag().putInt(key, zeni);
        }
    }

    // copy of sold with our price tags removed, so the handed-over item carries no SU NBT
    public static ItemStack stripped(ItemStack sold)
    {
        ItemStack copy = sold.copy();
        if (copy.hasTag())
        {
            copy.getTag().remove(TAG_BUY);
            copy.getTag().remove(TAG_SELL);
            cleanup(copy);
        }
        return copy;
    }

    private static void cleanup(ItemStack stack)
    {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.isEmpty())
            stack.setTag(null);
    }
}
