package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * A cosmetic crate: what a bound {@code Crate} rolls, and the single source of its published odds.
 *
 * <h2>PERSISTED NAME. NEVER RENAME THIS CLASS OR ITS FIELDS.</h2>
 * Instances live in {@code cosmetics.json} under the {@code crates} map, keyed by {@link #crateName}, and travel
 * between shards through {@code ShardStateSync} with the {@code crate:} stamp namespace {@code CosmeticCatalog}
 * reserved for exactly this. They do NOT live in {@code crates.json}, because that file does not travel between
 * shards ({@code ShardConfigFiles.ROOTS} covers only {@code config/dragonminez} and {@code config/sdu}), and
 * published odds must be identical on every server.
 *
 * <h2>Bound to the existing crate by name</h2>
 * {@link #crateName} equals a {@code Crate.name}. Everything else about that crate is reused unchanged: the block,
 * the key item and its NBT stamp, the spin animation, the reveal item entity. This record adds ONLY the odds and
 * the roll: which cosmetics may drop and how likely each is, the chance of Super, the chance of Magic, and which
 * effect pool a Magic roll draws from. A crate with no {@link CosmeticCrate} bound to it opens exactly as it does
 * today; one with a record bound rolls a cosmetic instead of its {@code crates.json} rewards.
 *
 * <h2>One table, two readers</h2>
 * {@link CrateOdds} builds a single cumulative weight array from {@link #entries}. Its {@code rows()} renders that
 * array for the published odds screen and its {@code pick()} walks the SAME array for the roll, so the number a
 * player is shown and the number the crate rolls against cannot drift, because there is no second copy to drift
 * from.
 *
 * <p>Plain-data object, public fields, explicit save/load/encode/decode, matching every other editable record.
 */
public class CosmeticCrate
{
    /** One cosmetic this crate may drop, and how likely it is relative to the rest. */
    public static class Entry
    {
        /** A {@link CosmeticDef#id}. Not validated here: a dangling id shows on the odds screen and never rolls. */
        public String catalogId = "";

        /** Relative weight. Zero means listed but never rolled. Never negative after {@link #normalise()}. */
        public int weight = 0;

        public Entry()
        {
        }

        public Entry(String catalogId, int weight)
        {
            this.catalogId = catalogId == null ? "" : catalogId;
            this.weight = weight;
        }

        public Entry copy()
        {
            return new Entry(catalogId, weight);
        }

        public Entry normalise()
        {
            catalogId = CosmeticDef.sanitizeId(catalogId);
            if (weight < 0)
                weight = 0;
            return this;
        }

        public void encode(FriendlyByteBuf buf)
        {
            buf.writeUtf(catalogId == null ? "" : catalogId);
            buf.writeVarInt(Math.max(0, weight));
        }

        public static Entry decode(FriendlyByteBuf buf)
        {
            Entry e = new Entry();
            e.catalogId = buf.readUtf();
            e.weight = buf.readVarInt();
            return e.normalise();
        }

        public CompoundTag toNbt()
        {
            CompoundTag t = new CompoundTag();
            t.putString("catalogId", catalogId == null ? "" : catalogId);
            t.putInt("weight", Math.max(0, weight));
            return t;
        }

        public static Entry fromNbt(CompoundTag t)
        {
            Entry e = new Entry();
            e.catalogId = t.getString("catalogId");
            e.weight = t.getInt("weight");
            return e.normalise();
        }
    }

    /**
     * Equals a {@code Crate.name}. The binding key, and the record's own id in the catalogue.
     *
     * <p>Sanitised the same way a cosmetic id is, so it can never contain a colon and cannot be mistaken for a
     * namespaced stamp key. Renaming it orphans the binding to the world crate.
     */
    public String crateName = "";

    /** Shown on the odds screen and the editor list. Supports the suite's &amp; colour codes. */
    public String displayName = "New Crate";

    /**
     * Parked without being deleted. A disabled record makes its crate open as a plain (non-cosmetic) crate again
     * and its odds screen stops offering, the same posture {@link CosmeticDef#enabled} takes.
     */
    public boolean enabled = true;

    /** The cosmetics this crate may drop, in authored order. */
    public List<Entry> entries = new ArrayList<>();

    /**
     * The chance, 0 to 100, that a roll is upgraded to Super, applied only to a cosmetic whose eligibility set
     * includes SUPER. Independent of {@link #magicPercent}, so a copy can be both.
     */
    public int superPercent = 0;

    /**
     * The chance, 0 to 100, that a roll is upgraded to Magic, applied only to a cosmetic whose eligibility set
     * includes MAGIC. When it is, an effect is drawn from {@link #magicPoolId}.
     */
    public int magicPercent = 0;

    /**
     * Which {@link CosmeticEffectPool} a Magic roll draws its effect from. Blank falls back to the rolled
     * cosmetic's own {@link CosmeticDef#defaultEffectPoolId}, so a crate need not name a pool if every cosmetic in
     * it already carries one.
     */
    public String magicPoolId = "";

    public CosmeticCrate()
    {
    }

    public CosmeticCrate(String crateName)
    {
        this.crateName = crateName == null ? "" : crateName;
    }

    public CosmeticCrate copy()
    {
        CosmeticCrate c = new CosmeticCrate(crateName);
        c.displayName = displayName;
        c.enabled = enabled;
        c.superPercent = superPercent;
        c.magicPercent = magicPercent;
        c.magicPoolId = magicPoolId;
        for (Entry e : entries)
            if (e != null)
                c.entries.add(e.copy());
        return c;
    }

    /** Fill in anything a hand-edited file left null, clamp the percentages, and dedupe entries by cosmetic id. */
    public CosmeticCrate normalise()
    {
        crateName = CosmeticDef.sanitizeId(crateName);
        if (displayName == null)
            displayName = "";
        if (magicPoolId == null)
            magicPoolId = "";
        magicPoolId = CosmeticDef.sanitizeId(magicPoolId);
        superPercent = clampPercent(superPercent);
        magicPercent = clampPercent(magicPercent);
        if (entries == null)
            entries = new ArrayList<>();
        // Deduplicated on the FIRST occurrence, never summed: two entries for one cosmetic would make the odds
        // screen disagree with the roll, and the first wins because that is the position the admin put it in.
        List<Entry> kept = new ArrayList<>(entries.size());
        Set<String> seen = new LinkedHashSet<>();
        for (Entry e : entries)
        {
            if (e == null)
                continue;
            e.normalise();
            if (e.catalogId.isBlank() || !seen.add(e.catalogId))
                continue;
            kept.add(e);
        }
        entries.clear();
        entries.addAll(kept);
        return this;
    }

    private static int clampPercent(int v)
    {
        return Math.max(0, Math.min(100, v));
    }

    /** The denominator for the published cosmetic odds. Zero means nothing in this crate may be rolled. */
    public int totalWeight()
    {
        int sum = 0;
        for (Entry e : entries)
            if (e != null && e.weight > 0)
                sum += e.weight;
        return sum;
    }

    /** Whether opening this crate could actually produce a cosmetic. */
    public boolean rollable()
    {
        return enabled && totalWeight() > 0;
    }

    public Entry entry(String catalogId)
    {
        String clean = CosmeticDef.sanitizeId(catalogId);
        if (clean.isEmpty())
            return null;
        for (Entry e : entries)
            if (e != null && clean.equals(e.catalogId))
                return e;
        return null;
    }

    /** Add a cosmetic or retune one already listed, KEEPING its position. The only write path an editor needs. */
    public void put(String catalogId, int weight)
    {
        String clean = CosmeticDef.sanitizeId(catalogId);
        if (clean.isEmpty())
            return;
        Entry existing = entry(clean);
        if (existing != null)
            existing.weight = Math.max(0, weight);
        else
            entries.add(new Entry(clean, Math.max(0, weight)));
    }

    public boolean remove(String catalogId)
    {
        Entry e = entry(catalogId);
        return e != null && entries.remove(e);
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(crateName == null ? "" : crateName);
        buf.writeUtf(displayName == null ? "" : displayName);
        buf.writeBoolean(enabled);
        buf.writeVarInt(superPercent);
        buf.writeVarInt(magicPercent);
        buf.writeUtf(magicPoolId == null ? "" : magicPoolId);
        buf.writeVarInt(entries.size());
        for (Entry e : entries)
            e.encode(buf);
    }

    public static CosmeticCrate decode(FriendlyByteBuf buf)
    {
        CosmeticCrate c = new CosmeticCrate();
        c.crateName = buf.readUtf();
        c.displayName = buf.readUtf();
        c.enabled = buf.readBoolean();
        c.superPercent = buf.readVarInt();
        c.magicPercent = buf.readVarInt();
        c.magicPoolId = buf.readUtf();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            c.entries.add(Entry.decode(buf));
        return c.normalise();
    }

    /**
     * Serialise for the cross-server state sync. Deterministic: the entry list keeps its authored order and every
     * other value is keyed by name, so two shards holding the same crate produce identical bytes and the sync's
     * content hash only moves on a real edit.
     */
    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        t.putString("crateName", crateName == null ? "" : crateName);
        t.putString("displayName", displayName == null ? "" : displayName);
        t.putBoolean("enabled", enabled);
        t.putInt("superPercent", superPercent);
        t.putInt("magicPercent", magicPercent);
        t.putString("magicPoolId", magicPoolId == null ? "" : magicPoolId);
        ListTag list = new ListTag();
        for (Entry e : entries)
            if (e != null)
                list.add(e.toNbt());
        t.put("entries", list);
        return t;
    }

    public static CosmeticCrate fromNbt(CompoundTag t)
    {
        CosmeticCrate c = new CosmeticCrate();
        c.crateName = t.getString("crateName");
        c.displayName = t.getString("displayName");
        c.enabled = !t.contains("enabled") || t.getBoolean("enabled");
        c.superPercent = t.getInt("superPercent");
        c.magicPercent = t.getInt("magicPercent");
        c.magicPoolId = t.getString("magicPoolId");
        ListTag list = t.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
            c.entries.add(Entry.fromNbt(list.getCompound(i)));
        return c.normalise();
    }
}
