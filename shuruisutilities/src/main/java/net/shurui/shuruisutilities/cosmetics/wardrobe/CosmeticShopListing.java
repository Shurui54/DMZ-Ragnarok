package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * One offer in the cosmetic shop: a price in Shards, the terms it is sold on, and WHAT it sells.
 *
 * <h2>PERSISTED NAME. NEVER RENAME THIS CLASS OR ITS FIELDS.</h2>
 * Instances live in {@code cosmetics.json} under the {@code shopListings} map, keyed by {@link #id}, and travel
 * between shards through {@code ShardStateSync} with the {@code shop:} stamp namespace {@code CosmeticCatalog}
 * reserved for this. A listing edited on one shard therefore appears on all of them, which is the answer to open
 * question C13 and the same mechanism the catalogue itself uses.
 *
 * <h2>A listing sells one of three things</h2>
 * {@link #type} decides. A COSMETIC listing sells {@link #catalogId}; a KEY listing sells a crate key for
 * {@link #crateName}, {@link #quantity} at a time; a SET listing sells the bundle in {@link #setMembers}. One record
 * carries all three so the same editor and the same shop screen handle every product. Fields that do not apply to a
 * type are simply ignored for it, never a separate store.
 *
 * <h2>A listing is the OFFER, not the cosmetic</h2>
 * The price, the sale window and the per-player limit are properties of the offer, so an admin can retire a
 * listing, run a Halloween window or reprice something without editing the cosmetic. Two listings may point at one
 * cosmetic, and a cosmetic with no listing simply does not appear in the shop.
 *
 * <h2>Quality is deliberately NOT a field here</h2>
 * A shop purchase is always Normal, by owner decision, and {@code CosmeticShop} writes {@link CosmeticQuality#NORMAL}
 * unconditionally with no way to express anything else. That rule is therefore a function with no alternative to
 * express, not a convention a caller could break.
 *
 * <p>Plain-data object, public fields, explicit save/load/encode/decode.
 */
public class CosmeticShopListing
{
    /**
     * Stable id of this listing. NOT the cosmetic id: two listings may sell one cosmetic.
     *
     * <p>Sanitised like a cosmetic id so it can never contain a colon and cannot be mistaken for a namespaced
     * stamp key. The editor refuses to change it, so a rename is a delete plus a new entry.
     */
    public String id = "";

    /** What this listing sells. Decides which of the fields below matter. Default {@link CosmeticListingType#COSMETIC}. */
    public CosmeticListingType type = CosmeticListingType.COSMETIC;

    /** COSMETIC only: which {@link CosmeticDef#id} this offer sells. */
    public String catalogId = "";

    /**
     * KEY only: the crate-system crate whose key is handed over. The purchase hands out the exact stamped key
     * {@code /crate givekey} produces for this crate, so it opens the bound crate block like any other key.
     */
    public String crateName = "";

    /**
     * KEY only: how many keys one purchase hands over. At least 1. Operator-set, so a listing can sell keys singly
     * or in a bundle of five at a discount by pricing the listing accordingly.
     */
    public int quantity = 1;

    /**
     * SET only: the cosmetic ids in the bundle, in the order the operator added them. A bundle is bought once and
     * grants each member. Blank and duplicate ids are dropped on {@link #normalise}.
     */
    public final List<String> setMembers = new ArrayList<>();

    /**
     * SET only: whether buying re-grants members the player already owns.
     *
     * <p>Default TRUE, decided by the owner: duplicates are now tradeable, so a set that always grants a full fresh
     * copy of every member is a coherent product. Turned off, a set only grants the members the player is missing,
     * and is refused (charging nothing) when the player already owns them all.
     */
    public boolean setRegrantOwned = true;

    /**
     * A display name for the tile, used by KEY and SET listings which have no cosmetic to borrow a name from. Blank
     * falls back to a computed name (the crate's key label for a key, a generic bundle name for a set). Ignored by a
     * COSMETIC listing, which shows the cosmetic's own name.
     */
    public String displayName = "";

    /** Price in Shards (argent). Non-negative; a zero-price listing is a free grant, which is allowed. */
    public long priceShards = 0L;

    /** Parked without being deleted: a disabled listing does not appear in the shop and keeps its terms. */
    public boolean enabled = true;

    /** Sale window start, epoch millis. 0 means "always", i.e. no lower bound. */
    public long startEpoch = 0L;

    /** Sale window end, epoch millis. 0 means "always", i.e. no upper bound. */
    public long endEpoch = 0L;

    /** The left-hand column the offer sits under in the shop. Free text; blank falls back to the cosmetic's slot. */
    public String category = "";

    /** Ordering within a category, ascending. Ties fall back to the id. */
    public int sortOrder = 0;

    /** How many one player may ever buy of THIS listing. 0 means unlimited. */
    public int limitPerPlayer = 0;

    /** Draws the offer with a highlight in the shop. Cosmetic only, never a gate. */
    public boolean featured = false;

    /** Shown instead of the cosmetic's own description when set. Blank falls back to the cosmetic's. */
    public String descriptionOverride = "";

    public CosmeticShopListing()
    {
    }

    public CosmeticShopListing(String id)
    {
        this.id = id == null ? "" : id;
    }

    public CosmeticShopListing copy()
    {
        CosmeticShopListing c = new CosmeticShopListing(id);
        c.type = type;
        c.catalogId = catalogId;
        c.crateName = crateName;
        c.quantity = quantity;
        c.setMembers.addAll(setMembers);
        c.setRegrantOwned = setRegrantOwned;
        c.displayName = displayName;
        c.priceShards = priceShards;
        c.enabled = enabled;
        c.startEpoch = startEpoch;
        c.endEpoch = endEpoch;
        c.category = category;
        c.sortOrder = sortOrder;
        c.limitPerPlayer = limitPerPlayer;
        c.featured = featured;
        c.descriptionOverride = descriptionOverride;
        return c;
    }

    public CosmeticShopListing normalise()
    {
        id = CosmeticDef.sanitizeId(id);
        if (type == null)
            type = CosmeticListingType.COSMETIC;
        catalogId = CosmeticDef.sanitizeId(catalogId);
        crateName = CosmeticDef.sanitizeId(crateName);
        if (quantity < 1)
            quantity = 1;
        // De-duplicate and drop blanks from the set, keeping the operator's order.
        List<String> cleaned = new ArrayList<>();
        for (String m : setMembers)
        {
            String s = CosmeticDef.sanitizeId(m);
            if (!s.isBlank() && !cleaned.contains(s))
                cleaned.add(s);
        }
        setMembers.clear();
        setMembers.addAll(cleaned);
        if (displayName == null)
            displayName = "";
        displayName = displayName.trim();
        if (category == null)
            category = "";
        category = category.trim();
        if (descriptionOverride == null)
            descriptionOverride = "";
        if (priceShards < 0L)
            priceShards = 0L;
        if (limitPerPlayer < 0)
            limitPerPlayer = 0;
        if (startEpoch < 0L)
            startEpoch = 0L;
        if (endEpoch < 0L)
            endEpoch = 0L;
        return this;
    }

    /**
     * Whether this listing is on sale RIGHT NOW: enabled, inside its window, and its product is coherent. What
     * "coherent" means depends on {@link #type}, and this is the one question the shop and the purchase path both
     * ask so they can never disagree.
     *
     * <p>Note the KEY check is deliberately cheap and client-safe: it only asks that a crate is named, not that the
     * crate exists, because the crate system is a server-only module the client cannot see. The server purchase
     * path re-checks the crate is real before charging.
     */
    public boolean onSale(long now)
    {
        if (!enabled)
            return false;
        if (startEpoch > 0L && now < startEpoch)
            return false;
        if (endEpoch > 0L && now > endEpoch)
            return false;
        switch (type == null ? CosmeticListingType.COSMETIC : type)
        {
        case KEY:
            return !crateName.isBlank();
        case SET:
            for (String m : setMembers)
            {
                CosmeticDef def = CosmeticCatalog.get(m);
                if (def != null && def.enabled)
                    return true;
            }
            return false;
        case COSMETIC:
        default:
        {
            if (catalogId.isBlank())
                return false;
            CosmeticDef def = CosmeticCatalog.get(catalogId);
            return def != null && def.enabled;
        }
        }
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(id == null ? "" : id);
        buf.writeUtf((type == null ? CosmeticListingType.COSMETIC : type).key);
        buf.writeUtf(catalogId == null ? "" : catalogId);
        buf.writeUtf(crateName == null ? "" : crateName);
        buf.writeVarInt(Math.max(1, quantity));
        buf.writeVarInt(setMembers.size());
        for (String m : setMembers)
            buf.writeUtf(m == null ? "" : m);
        buf.writeBoolean(setRegrantOwned);
        buf.writeUtf(displayName == null ? "" : displayName);
        buf.writeVarLong(Math.max(0L, priceShards));
        buf.writeBoolean(enabled);
        buf.writeVarLong(Math.max(0L, startEpoch));
        buf.writeVarLong(Math.max(0L, endEpoch));
        buf.writeUtf(category == null ? "" : category);
        buf.writeVarInt(sortOrder);
        buf.writeVarInt(Math.max(0, limitPerPlayer));
        buf.writeBoolean(featured);
        buf.writeUtf(descriptionOverride == null ? "" : descriptionOverride);
    }

    public static CosmeticShopListing decode(FriendlyByteBuf buf)
    {
        CosmeticShopListing c = new CosmeticShopListing();
        c.id = buf.readUtf();
        c.type = CosmeticListingType.byKey(buf.readUtf());
        c.catalogId = buf.readUtf();
        c.crateName = buf.readUtf();
        c.quantity = buf.readVarInt();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            c.setMembers.add(buf.readUtf());
        c.setRegrantOwned = buf.readBoolean();
        c.displayName = buf.readUtf();
        c.priceShards = buf.readVarLong();
        c.enabled = buf.readBoolean();
        c.startEpoch = buf.readVarLong();
        c.endEpoch = buf.readVarLong();
        c.category = buf.readUtf();
        c.sortOrder = buf.readVarInt();
        c.limitPerPlayer = buf.readVarInt();
        c.featured = buf.readBoolean();
        c.descriptionOverride = buf.readUtf();
        return c.normalise();
    }

    /**
     * Serialise for the cross-server state sync. Deterministic field order, everything keyed by name, so two
     * shards holding the same listing produce identical bytes and the sync's content hash only moves on a real
     * edit. New fields are written only when they are non-default, so a plain cosmetic listing produces the same
     * bytes it did before this milestone and two converged servers cannot differ by a field one wrote as its
     * default.
     */
    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        t.putString("id", id == null ? "" : id);
        CosmeticListingType ty = type == null ? CosmeticListingType.COSMETIC : type;
        if (ty != CosmeticListingType.COSMETIC)
            t.putString("type", ty.key);
        t.putString("catalogId", catalogId == null ? "" : catalogId);
        if (crateName != null && !crateName.isBlank())
            t.putString("crateName", crateName);
        if (quantity != 1)
            t.putInt("quantity", Math.max(1, quantity));
        if (!setMembers.isEmpty())
        {
            ListTag members = new ListTag();
            for (String m : setMembers)
                if (m != null && !m.isBlank())
                    members.add(StringTag.valueOf(m));
            t.put("setMembers", members);
        }
        if (!setRegrantOwned)
            t.putBoolean("setRegrantOwned", false);
        if (displayName != null && !displayName.isBlank())
            t.putString("displayName", displayName);
        t.putLong("priceShards", Math.max(0L, priceShards));
        t.putBoolean("enabled", enabled);
        t.putLong("startEpoch", Math.max(0L, startEpoch));
        t.putLong("endEpoch", Math.max(0L, endEpoch));
        t.putString("category", category == null ? "" : category);
        t.putInt("sortOrder", sortOrder);
        t.putInt("limitPerPlayer", Math.max(0, limitPerPlayer));
        t.putBoolean("featured", featured);
        t.putString("descriptionOverride", descriptionOverride == null ? "" : descriptionOverride);
        return t;
    }

    public static CosmeticShopListing fromNbt(CompoundTag t)
    {
        CosmeticShopListing c = new CosmeticShopListing();
        c.id = t.getString("id");
        // Every field below is read by NAME with a default, so a listing written before this milestone loads as a
        // plain COSMETIC offer with no key, set, quantity or display name rather than failing.
        c.type = CosmeticListingType.byKey(t.getString("type"));
        c.catalogId = t.getString("catalogId");
        c.crateName = t.getString("crateName");
        c.quantity = t.contains("quantity") ? t.getInt("quantity") : 1;
        ListTag members = t.getList("setMembers", Tag.TAG_STRING);
        for (int i = 0; i < members.size(); i++)
            c.setMembers.add(members.getString(i));
        c.setRegrantOwned = !t.contains("setRegrantOwned") || t.getBoolean("setRegrantOwned");
        c.displayName = t.getString("displayName");
        c.priceShards = t.getLong("priceShards");
        c.enabled = !t.contains("enabled") || t.getBoolean("enabled");
        c.startEpoch = t.getLong("startEpoch");
        c.endEpoch = t.getLong("endEpoch");
        c.category = t.getString("category");
        c.sortOrder = t.getInt("sortOrder");
        c.limitPerPlayer = t.getInt("limitPerPlayer");
        c.featured = t.getBoolean("featured");
        c.descriptionOverride = t.getString("descriptionOverride");
        return c.normalise();
    }

    /** {@code "a,b,c"} of the set members, for a flat editor field. */
    public String membersCsv()
    {
        return String.join(",", setMembers);
    }

    /** Replace the set members from a comma or space separated list, sanitised and de-duplicated on normalise. */
    public void setMembersFromCsv(String csv)
    {
        setMembers.clear();
        if (csv != null)
            for (String piece : csv.split("[,\\s]+"))
                if (!piece.isBlank())
                    setMembers.add(piece.trim());
    }
}
