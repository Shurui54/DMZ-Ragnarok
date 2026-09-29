package net.shurui.shuruisutilities.auction;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * One auction-house listing. The listed {@link #stack} IS the escrow: it lives here (inside {@link AuctionStore})
 * from the moment it is taken off the seller until it is either delivered to a buyer's claim queue or returned to
 * the seller's claim queue. It is never held in two places at once.
 *
 * <p>Money is NOT held on the listing as a spendable balance: the bid zeni was already removed from the top
 * bidder's wallet by {@link net.shurui.shuruisutilities.economy.EconomyManager#trySpend} when they bid, and
 * {@link #currentBid} is only the RECORD of how much is owed back (to the bidder if outbid, or to the seller when
 * the auction settles). No zeni is minted until a claim is collected. See {@link AuctionStore}.
 */
public final class AuctionListing
{
    public UUID id;
    public UUID sellerId;
    public String sellerName;
    public ItemStack stack;
    public long startingBid;
    // 0 means no buy-now price was set.
    public long buyNowPrice;
    // 0 means no bid has been placed yet; currentBidderId is then null.
    public long currentBid;
    public UUID currentBidderId;
    public String currentBidderName;
    public long postedAtMillis;
    public long expiresAtMillis;

    /**
     * Cross-server only: the escrowed item's Sophisticated Backpacks contents (keyed by backpack UUID), taken off
     * the seller's server when the listing was created so they travel with the item to the buyer's server. Held in
     * its own database column by {@code ShardAuction}, NOT in {@link #save()}: the
     * single-server local store leaves a backpack's contents in its own store keyed by UUID and never needs this.
     */
    public transient net.minecraft.nbt.CompoundTag backpackContents;

    public AuctionListing() {}

    public boolean hasBid()
    {
        return currentBidderId != null && currentBid > 0;
    }

    public boolean hasBuyNow()
    {
        // buy-now stays available only while no bid has met or passed it (once bidding reaches it, it is gone).
        return buyNowPrice > 0 && (!hasBid() || currentBid < buyNowPrice);
    }

    /** The smallest amount a NEW bid must be, given the configured minimum increment. */
    public long minNextBid(long minIncrement)
    {
        return hasBid() ? currentBid + Math.max(1L, minIncrement) : startingBid;
    }

    public boolean isExpired(long nowMillis)
    {
        return nowMillis >= expiresAtMillis;
    }

    public CompoundTag save()
    {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", id);
        t.putUUID("seller", sellerId);
        t.putString("sellerName", sellerName == null ? "" : sellerName);
        CompoundTag item = new CompoundTag();
        stack.save(item);
        t.put("item", item);
        t.putLong("startingBid", startingBid);
        t.putLong("buyNow", buyNowPrice);
        t.putLong("currentBid", currentBid);
        if (currentBidderId != null)
        {
            t.putUUID("bidder", currentBidderId);
            t.putString("bidderName", currentBidderName == null ? "" : currentBidderName);
        }
        t.putLong("postedAt", postedAtMillis);
        t.putLong("expiresAt", expiresAtMillis);
        return t;
    }

    public static AuctionListing load(CompoundTag t)
    {
        AuctionListing l = new AuctionListing();
        l.id = t.getUUID("id");
        l.sellerId = t.getUUID("seller");
        l.sellerName = t.getString("sellerName");
        // Player property: normalize a pre-merge item id inside the stored stack before decoding, so an auctioned
        // item of one of our merged items is never silently lost (belt-and-suspenders over the registry remap).
        l.stack = ItemStack.of(
                net.shurui.shuruisutilities.ragnarok.LegacyIds.normalizeItemTag(t.getCompound("item")));
        l.startingBid = t.getLong("startingBid");
        l.buyNowPrice = t.getLong("buyNow");
        l.currentBid = t.getLong("currentBid");
        if (t.hasUUID("bidder"))
        {
            l.currentBidderId = t.getUUID("bidder");
            l.currentBidderName = t.getString("bidderName");
        }
        l.postedAtMillis = t.getLong("postedAt");
        l.expiresAtMillis = t.getLong("expiresAt");
        return l;
    }
}
