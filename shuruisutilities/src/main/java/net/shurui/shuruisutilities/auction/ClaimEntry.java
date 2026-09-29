package net.shurui.shuruisutilities.auction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * One pending payout in a player's auction claim queue: either an ITEM (an unsold listing returned to its seller, or
 * a won/bought item delivered to its buyer) or a lump of ZENI (an outbid refund, or a seller's sale proceeds). It is
 * held inside {@link AuctionStore} until the owner collects it from the Claim tab.
 *
 * <p>A zeni entry does NOT hold minted currency: it is only the RECORD of how much the store owes the player. The
 * actual credit through {@link net.shurui.shuruisutilities.economy.EconomyManager#add} happens only at collect time,
 * exactly once, after the entry has been removed from the persisted store (see {@link AuctionStore#collectAll}).
 */
public final class ClaimEntry
{
    public boolean isItem;
    public ItemStack stack = ItemStack.EMPTY;
    public long zeni;
    // a short translation key describing why the payout exists, shown in the Claim tab.
    public String reasonKey = "";
    // For a Sophisticated Backpacks item across a cross-server auction: the contents (keyed by backpack UUID, empty
    // tag = a clear marker), taken off the seller's server when the listing was created and written into the
    // COLLECTOR's server store when they collect, so the contents travel with the item. Null when the item carries
    // no backpack, and unused by the single-server local store (its contents never leave that server's store).
    public CompoundTag backpackContents;

    public ClaimEntry() {}

    public static ClaimEntry item(ItemStack stack, String reasonKey)
    {
        ClaimEntry e = new ClaimEntry();
        e.isItem = true;
        e.stack = stack;
        e.reasonKey = reasonKey;
        return e;
    }

    public static ClaimEntry item(ItemStack stack, String reasonKey, CompoundTag backpackContents)
    {
        ClaimEntry e = item(stack, reasonKey);
        if (backpackContents != null && !backpackContents.isEmpty())
            e.backpackContents = backpackContents;
        return e;
    }

    public static ClaimEntry zeni(long amount, String reasonKey)
    {
        ClaimEntry e = new ClaimEntry();
        e.isItem = false;
        e.zeni = amount;
        e.reasonKey = reasonKey;
        return e;
    }

    public CompoundTag save()
    {
        CompoundTag t = new CompoundTag();
        t.putBoolean("isItem", isItem);
        if (isItem)
        {
            CompoundTag item = new CompoundTag();
            stack.save(item);
            t.put("item", item);
            if (backpackContents != null && !backpackContents.isEmpty())
                t.put("backpacks", backpackContents);
        }
        else
        {
            t.putLong("zeni", zeni);
        }
        t.putString("reason", reasonKey == null ? "" : reasonKey);
        return t;
    }

    public static ClaimEntry load(CompoundTag t)
    {
        ClaimEntry e = new ClaimEntry();
        e.isItem = t.getBoolean("isItem");
        if (e.isItem)
        {
            // Player property: normalize a pre-merge item id in the stored stack before decoding (see AuctionListing).
            e.stack = ItemStack.of(
                    net.shurui.shuruisutilities.ragnarok.LegacyIds.normalizeItemTag(t.getCompound("item")));
            if (t.contains("backpacks"))
                e.backpackContents = t.getCompound("backpacks");
        }
        else
        {
            e.zeni = t.getLong("zeni");
        }
        e.reasonKey = t.getString("reason");
        return e;
    }
}
