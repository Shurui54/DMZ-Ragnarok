package net.shurui.shuruisutilities.auction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The auction house's single source of truth: every open listing (with its escrowed item) and every player's claim
 * queue (unsold items, won/bought items, outbid refunds and sale proceeds). An overworld-attached {@link SavedData},
 * mirroring {@link net.shurui.shuruisutilities.guilds.raid.GuildRaidVault}'s take/hold/hand-over discipline so an
 * item can never be duplicated or destroyed.
 *
 * <h2>Escrow and ordering rules (why nothing dupes or is lost)</h2>
 * <ul>
 *   <li><b>The listed item lives ONLY here</b> from the moment it is taken off the seller (see
 *       {@code AuctionServer}, in the Ragnarok Key) until it is moved into a claim queue. It is never in a player's
 *       inventory and in a listing at the same time.</li>
 *   <li><b>Bid zeni is removed at bid time</b> via {@link net.shurui.shuruisutilities.economy.EconomyManager#trySpend}
 *       (atomic, fails closed if unaffordable). The obligation to return it lives as {@link AuctionListing#currentBid}
 *       (while it is the top bid) or as a zeni {@link ClaimEntry} (after an outbid or a settle). Zeni is only minted
 *       again by {@code EconomyManager.add} at COLLECT time. Across the two events a bidder spends X and later
 *       collects X, so the currency is conserved.</li>
 *   <li><b>Settle and refund are pure store moves</b> (no economy calls): expiry and buy-now relabel the held bid as
 *       a seller zeni-claim and move the item to a buyer/seller claim, in one mutation, then persist. Re-running a
 *       sweep after a crash cannot double-pay because the listing is already gone.</li>
 *   <li><b>Money-affecting mutations persist immediately</b> ({@link #saveNow}) because the economy persists its JSON
 *       immediately; matching that closes the window in which a spent-but-unrecorded bid could be lost. Item-only
 *       moves that also touch a player inventory (create, collect delivery) use plain {@link #setDirty()} so they
 *       co-persist with player data on the world save, exactly like GuildRaidVault, and every collect removes the
 *       claim from the persisted store BEFORE handing anything over, so a crash can only ever lose the just-removed
 *       payout, never duplicate it.</li>
 * </ul>
 */
public final class AuctionStore extends SavedData
{
    private static final String NAME = "shuruisutilities_auction";

    // listing id -> listing (the escrowed item lives on the listing)
    private final Map<UUID, AuctionListing> listings = new LinkedHashMap<>();
    // player uuid -> pending payouts
    private final Map<UUID, List<ClaimEntry>> claims = new LinkedHashMap<>();

    public static AuctionStore get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(AuctionStore::load, AuctionStore::new, NAME);
    }

    private static AuctionStore load(CompoundTag tag)
    {
        AuctionStore s = new AuctionStore();
        ListTag list = tag.getList("listings", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            AuctionListing l = AuctionListing.load(list.getCompound(i));
            s.listings.put(l.id, l);
        }
        ListTag claimList = tag.getList("claims", Tag.TAG_COMPOUND);
        for (int i = 0; i < claimList.size(); i++)
        {
            CompoundTag c = claimList.getCompound(i);
            UUID player = c.getUUID("player");
            List<ClaimEntry> entries = new ArrayList<>();
            for (Tag t : c.getList("entries", Tag.TAG_COMPOUND))
            {
                entries.add(ClaimEntry.load((CompoundTag) t));
            }
            if (!entries.isEmpty())
            {
                s.claims.put(player, entries);
            }
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        ListTag list = new ListTag();
        for (AuctionListing l : listings.values())
        {
            list.add(l.save());
        }
        tag.put("listings", list);
        ListTag claimList = new ListTag();
        for (Map.Entry<UUID, List<ClaimEntry>> e : claims.entrySet())
        {
            if (e.getValue().isEmpty())
            {
                continue;
            }
            CompoundTag c = new CompoundTag();
            c.putUUID("player", e.getKey());
            ListTag entries = new ListTag();
            for (ClaimEntry ce : e.getValue())
            {
                entries.add(ce.save());
            }
            c.put("entries", entries);
            claimList.add(c);
        }
        tag.put("claims", claimList);
        return tag;
    }

    // mark dirty AND flush to disk now. Used after money-affecting mutations so a spent bid's record is as durable as
    // the economy's own immediate JSON write. save() only writes dirty SavedData, so this is cheap between the rare,
    // human-paced auction actions.
    private void saveNow(MinecraftServer server)
    {
        setDirty();
        server.getLevel(Level.OVERWORLD).getDataStorage().save();
    }

    public Collection<AuctionListing> allListings()
    {
        return new ArrayList<>(listings.values());
    }

    public AuctionListing getListing(UUID id)
    {
        return listings.get(id);
    }

    public int activeCountFor(UUID seller)
    {
        int n = 0;
        for (AuctionListing l : listings.values())
        {
            if (l.sellerId.equals(seller))
            {
                n++;
            }
        }
        return n;
    }

    public List<ClaimEntry> claimsFor(UUID player)
    {
        List<ClaimEntry> list = claims.get(player);
        return list == null ? List.of() : new ArrayList<>(list);
    }

    // every player's whole claim queue, for the one-time import into the shared table when a server joins a network.
    public Map<UUID, List<ClaimEntry>> allClaims()
    {
        Map<UUID, List<ClaimEntry>> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<ClaimEntry>> e : claims.entrySet())
            if (!e.getValue().isEmpty())
                out.put(e.getKey(), new ArrayList<>(e.getValue()));
        return out;
    }

    /**
     * Record a listing whose item has ALREADY been taken off the seller by the caller (see
     * {@code AuctionServer.handleCreate}). Item-only + inventory-paired, so plain setDirty (co-persists with player
     * data on the world save).
     */
    public void addListing(AuctionListing listing)
    {
        listings.put(listing.id, listing);
        setDirty();
    }

    public Result placeBid(MinecraftServer server, UUID id, UUID bidder, String bidderName, long amount,
            long minIncrement)
    {
        AuctionListing l = listings.get(id);
        if (l == null || l.isExpired(System.currentTimeMillis()))
        {
            return Result.fail("message.dmz_ragnarok.core.auction.gone");
        }
        if (l.sellerId.equals(bidder))
        {
            return Result.fail("message.dmz_ragnarok.core.auction.own");
        }
        long min = l.minNextBid(minIncrement);
        if (amount < min)
        {
            return Result.fail("message.dmz_ragnarok.core.auction.bid_too_low", min);
        }
        // take the bid zeni first; fails closed and touches nothing if unaffordable.
        if (!net.shurui.shuruisutilities.economy.EconomyManager.trySpend(bidder, amount))
        {
            return Result.fail("message.dmz_ragnarok.core.auction.cant_afford");
        }
        // refund the previous top bidder as a zeni claim (record only; money is re-minted when they collect).
        if (l.hasBid())
        {
            addClaim(l.currentBidderId, ClaimEntry.zeni(l.currentBid, "message.dmz_ragnarok.core.auction.reason.outbid"));
        }
        l.currentBid = amount;
        l.currentBidderId = bidder;
        l.currentBidderName = bidderName;
        saveNow(server);
        return Result.ok("message.dmz_ragnarok.core.auction.bid_placed");
    }

    public Result buyNow(MinecraftServer server, UUID id, UUID buyer, String buyerName, double taxPercent)
    {
        AuctionListing l = listings.get(id);
        if (l == null || l.isExpired(System.currentTimeMillis()))
        {
            return Result.fail("message.dmz_ragnarok.core.auction.gone");
        }
        if (l.sellerId.equals(buyer))
        {
            return Result.fail("message.dmz_ragnarok.core.auction.own");
        }
        if (!l.hasBuyNow())
        {
            return Result.fail("message.dmz_ragnarok.core.auction.no_buynow");
        }
        long price = l.buyNowPrice;
        if (!net.shurui.shuruisutilities.economy.EconomyManager.trySpend(buyer, price))
        {
            return Result.fail("message.dmz_ragnarok.core.auction.cant_afford");
        }
        // a standing top bid (always below buy-now, see hasBuyNow) is refunded to that bidder.
        if (l.hasBid())
        {
            addClaim(l.currentBidderId, ClaimEntry.zeni(l.currentBid, "message.dmz_ragnarok.core.auction.reason.outbid"));
        }
        long proceeds = afterTax(price, taxPercent);
        addClaim(l.sellerId, ClaimEntry.zeni(proceeds, "message.dmz_ragnarok.core.auction.reason.sold"));
        addClaim(buyer, ClaimEntry.item(l.stack, "message.dmz_ragnarok.core.auction.reason.bought"));
        listings.remove(l.id);
        saveNow(server);
        return Result.ok("message.dmz_ragnarok.core.auction.bought");
    }

    public Result cancel(UUID id, UUID requester)
    {
        AuctionListing l = listings.get(id);
        if (l == null)
        {
            return Result.fail("message.dmz_ragnarok.core.auction.gone");
        }
        if (!l.sellerId.equals(requester))
        {
            return Result.fail("message.dmz_ragnarok.core.auction.not_owner");
        }
        if (l.hasBid())
        {
            // never yank an item out from under a live bid; the bidder is owed a fair settle.
            return Result.fail("message.dmz_ragnarok.core.auction.has_bid");
        }
        addClaim(l.sellerId, ClaimEntry.item(l.stack, "message.dmz_ragnarok.core.auction.reason.cancelled"));
        listings.remove(l.id);
        setDirty();
        return Result.ok("message.dmz_ragnarok.core.auction.cancelled");
    }

    /**
     * Move every expired listing into the right claim queue: a bid winner gets the item and the seller gets the
     * (taxed) proceeds; an unsold listing goes back to the seller. No economy calls here (the winning bid zeni was
     * already taken when the bid was placed; it becomes a seller zeni-claim), so the whole sweep is one atomic store
     * mutation and re-running it after a crash cannot double-pay. Returns how many listings were settled.
     */
    public int sweepExpired(long nowMillis, double taxPercent)
    {
        List<AuctionListing> expired = new ArrayList<>();
        for (AuctionListing l : listings.values())
        {
            if (l.isExpired(nowMillis))
            {
                expired.add(l);
            }
        }
        for (AuctionListing l : expired)
        {
            if (l.hasBid())
            {
                addClaim(l.sellerId, ClaimEntry.zeni(afterTax(l.currentBid, taxPercent),
                        "message.dmz_ragnarok.core.auction.reason.sold"));
                addClaim(l.currentBidderId, ClaimEntry.item(l.stack, "message.dmz_ragnarok.core.auction.reason.won"));
            }
            else
            {
                addClaim(l.sellerId, ClaimEntry.item(l.stack, "message.dmz_ragnarok.core.auction.reason.expired"));
            }
            listings.remove(l.id);
        }
        if (!expired.isEmpty())
        {
            setDirty();
        }
        return expired.size();
    }

    /**
     * Hand a player every pending payout. The whole queue is removed from the persisted store FIRST
     * ({@link #saveNow}), then delivered, so a crash mid-delivery can only lose the undelivered remainder and never
     * duplicate a payout. Zeni is minted here (and only here) via {@code EconomyManager.add}; items overflow to the
     * ground rather than vanish. Returns how many entries were delivered.
     */
    public int collectAll(MinecraftServer server, ServerPlayer player)
    {
        UUID id = player.getUUID();
        List<ClaimEntry> queue = claims.remove(id);
        if (queue == null || queue.isEmpty())
        {
            return 0;
        }
        // the queue is now durably gone before anything is handed over.
        saveNow(server);
        for (ClaimEntry e : queue)
        {
            if (e.isItem)
            {
                giveOrDrop(player, e.stack);
            }
            else
            {
                net.shurui.shuruisutilities.economy.EconomyManager.add(id, e.zeni);
            }
        }
        return queue.size();
    }

    private void addClaim(UUID player, ClaimEntry entry)
    {
        claims.computeIfAbsent(player, k -> new ArrayList<>()).add(entry);
    }

    // amount minus the sale tax (a percentage), clamped at zero. Tax is a zeni sink.
    private static long afterTax(long amount, double taxPercent)
    {
        if (taxPercent <= 0.0)
        {
            return Math.max(0L, amount);
        }
        long tax = (long) Math.floor(amount * (taxPercent / 100.0));
        return Math.max(0L, amount - tax);
    }

    // add to the player's inventory, dropping to the ground whatever does not fit, so an item can never be lost.
    private static void giveOrDrop(ServerPlayer player, ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return;
        }
        ItemStack copy = stack.copy();
        player.getInventory().add(copy); // mutates copy down to whatever did not fit
        if (!copy.isEmpty())
        {
            player.drop(copy, false);
        }
        player.inventoryMenu.broadcastChanges();
    }

    /** Outcome of a store mutation: success flag plus a translation key (and optional args) to show the player. */
    public static final class Result
    {
        public final boolean ok;
        public final String messageKey;
        public final Object[] args;

        private Result(boolean ok, String messageKey, Object[] args)
        {
            this.ok = ok;
            this.messageKey = messageKey;
            this.args = args;
        }

        public static Result ok(String key, Object... args)
        {
            return new Result(true, key, args);
        }

        public static Result fail(String key, Object... args)
        {
            return new Result(false, key, args);
        }
    }
}
