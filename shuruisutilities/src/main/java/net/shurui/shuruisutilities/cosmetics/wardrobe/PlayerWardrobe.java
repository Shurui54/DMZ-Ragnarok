package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * What one player currently has ON, and which tracker they chose to show for each.
 *
 * <p>Deliberately separate from ownership. Owning a cosmetic and wearing it are different facts with different
 * lifetimes: a revoked cosmetic must stop being worn without the wardrobe having to understand revocation, and a
 * player who unequips everything must not look like a player who owns nothing. Keeping them apart is also what
 * lets the two stores merge across shards by different rules, per player for this one and per row for the ledger.
 *
 * <p>Keyed by {@link CosmeticSlot#key}, never by ordinal, so turning {@link CosmeticSlot#BODY} on later reads
 * every existing record unchanged.
 *
 * <h2>A slot holds a RECORD now, not an id</h2>
 * {@link #equipped} used to map a slot key to a bare catalogue id. It now maps to an {@link EquippedCosmetic},
 * which names the instance as well as the cosmetic. See that class for why: once quality lives on the copy, "they
 * are wearing bat_hat" stops being an answer the moment somebody owns two bat_hats.
 *
 * <h2>{@link #chosenTrackers} stays per COSMETIC, and is not folded into the record</h2>
 * The two hold the same value and it would be tempting to keep only one. They are not the same FACT. The chosen
 * tracker is a preference about a cosmetic, which must survive taking the hat off and putting it back on (and
 * survive an admin moving the cosmetic to another slot); the record is about what is in a slot right now and
 * disappears the moment the slot is cleared. So this map is the source of truth and
 * {@link EquippedCosmetic#displayedTrackerId} is a denormalised copy of it, refreshed by the server whenever
 * either changes, which is what lets a client draw the number without a second lookup.
 */
public class PlayerWardrobe
{
    /** Slot key to what is worn in it. A slot with no entry is empty; an invalid record is never stored. */
    public final Map<String, EquippedCosmetic> equipped = new LinkedHashMap<>();

    /**
     * Catalogue id to the tracker id the wearer picked to display.
     *
     * <p>Kept per COSMETIC rather than per slot, so the choice survives taking a hat off and putting it back on,
     * and survives moving a cosmetic between slots in the editor. See the class note.
     */
    public final Map<String, String> chosenTrackers = new LinkedHashMap<>();

    public PlayerWardrobe()
    {
    }

    public PlayerWardrobe copy()
    {
        PlayerWardrobe c = new PlayerWardrobe();
        for (Map.Entry<String, EquippedCosmetic> e : equipped.entrySet())
            if (e.getKey() != null && e.getValue() != null)
                c.equipped.put(e.getKey(), e.getValue().copy());
        c.chosenTrackers.putAll(chosenTrackers);
        return c;
    }

    public boolean isEmpty()
    {
        return equipped.isEmpty() && chosenTrackers.isEmpty();
    }

    /** What is worn in this slot, or null when it is empty. */
    public EquippedCosmetic worn(CosmeticSlot slot)
    {
        if (slot == null)
            return null;
        EquippedCosmetic e = equipped.get(slot.key);
        if (e != null)
            return e;
        // A paired triggered slot (JOIN_LEAVE, TELEPORT) inherits from its retired single-direction keys until the
        // one-time migration rewrites them, and also whenever an un-migrated outfit arrives in a cross-shard merge
        // from an older shard. Reading the fallback here is what keeps playback correct without mutating anything.
        for (String legacy : slot.legacyKeys())
        {
            EquippedCosmetic l = equipped.get(legacy);
            if (l != null)
                return l;
        }
        return null;
    }

    /** The catalogue id in this slot, or "" when it is empty. The cheap read, for anything that only needs a name. */
    public String in(CosmeticSlot slot)
    {
        EquippedCosmetic e = worn(slot);
        return e == null || e.catalogId == null ? "" : e.catalogId;
    }

    /** Whether this cosmetic is worn in any slot. */
    public boolean wearing(String catalogId)
    {
        if (catalogId == null || catalogId.isBlank())
            return false;
        for (EquippedCosmetic e : equipped.values())
            if (e != null && catalogId.equals(e.catalogId))
                return true;
        return false;
    }

    /**
     * Put a record in a slot, or clear it when the record names nothing. Returns true if anything changed.
     *
     * <p>Compares the WHOLE record, not just the id, so re-equipping the same hat as a different instance, or a
     * refreshed count, both register as a change and get broadcast. A no-op returning false is what keeps the
     * server from sending a packet per click.
     */
    public boolean set(CosmeticSlot slot, EquippedCosmetic value)
    {
        if (slot == null)
            return false;
        EquippedCosmetic previous = equipped.get(slot.key);
        if (value == null || !value.valid())
        {
            if (previous == null)
                return false;
            equipped.remove(slot.key);
            return true;
        }
        if (previous != null && previous.sameAs(value))
            return false;
        equipped.put(slot.key, value.copy());
        // Equipping fresh into a paired triggered slot supersedes any retired single-direction record, so drop the
        // legacy keys now rather than leaving a stale one for the read fallback to shadow.
        for (String legacy : slot.legacyKeys())
            equipped.remove(legacy);
        return true;
    }

    /**
     * Fold the retired single-direction triggered records (join, leave, tp_depart, tp_arrive) into the two paired
     * slots (join_leave, teleport), once. A join or leave record becomes join_leave, a tp_depart or tp_arrive
     * record becomes teleport, preferring the join / tp_arrive value when both are present, and the old keys are
     * dropped. Returns whether anything changed, so the store can stamp only the players it actually migrated.
     *
     * <p>Idempotent: a wardrobe with no legacy keys, or one already migrated, returns false and is left untouched.
     * A record already sitting in a paired slot is kept and only the legacy keys are cleared, so a player who
     * re-equipped after the update does not have their choice overwritten.
     */
    public boolean migratePairedSlots()
    {
        boolean changed = foldLegacy(CosmeticSlot.JOIN_LEAVE, "join", "leave");
        changed |= foldLegacy(CosmeticSlot.TELEPORT, "tp_arrive", "tp_depart");
        return changed;
    }

    private boolean foldLegacy(CosmeticSlot paired, String preferKey, String otherKey)
    {
        EquippedCosmetic prefer = equipped.get(preferKey);
        EquippedCosmetic other = equipped.get(otherKey);
        if (prefer == null && other == null)
            return false;
        boolean changed = false;
        EquippedCosmetic chosen = prefer != null ? prefer : other;
        if (equipped.get(paired.key) == null && chosen != null && chosen.valid())
        {
            equipped.put(paired.key, chosen.copy());
            changed = true;
        }
        if (equipped.remove(preferKey) != null)
            changed = true;
        if (equipped.remove(otherKey) != null)
            changed = true;
        return changed;
    }

    /** Take whatever is in this slot off. Returns true if anything was in it. */
    public boolean clear(CosmeticSlot slot)
    {
        return set(slot, null);
    }

    /** Remove this cosmetic from wherever it is worn. Used when it is revoked, deleted or disabled. */
    public boolean removeEverywhere(String catalogId)
    {
        if (catalogId == null || catalogId.isBlank())
            return false;
        return equipped.values().removeIf(e -> e != null && catalogId.equals(e.catalogId));
    }

    public String tracker(String catalogId)
    {
        if (catalogId == null)
            return "";
        String v = chosenTrackers.get(catalogId);
        return v == null ? "" : v;
    }

    public boolean setTracker(String catalogId, String trackerId)
    {
        if (catalogId == null || catalogId.isBlank())
            return false;
        String previous = chosenTrackers.get(catalogId);
        if (trackerId == null || trackerId.isBlank())
        {
            if (previous == null)
                return false;
            chosenTrackers.remove(catalogId);
            return true;
        }
        if (trackerId.equals(previous))
            return false;
        chosenTrackers.put(catalogId, trackerId);
        return true;
    }

    /** Deterministic: both maps are written in sorted key order so the state sync hash only moves on a real edit. */
    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        CompoundTag eq = new CompoundTag();
        for (String k : sortedKeys(equipped))
        {
            EquippedCosmetic e = equipped.get(k);
            if (e != null && e.valid())
                eq.put(k, e.toNbt());
        }
        t.put("equipped", eq);
        t.put("trackers", sortedMap(chosenTrackers));
        return t;
    }

    private static List<String> sortedKeys(Map<String, ?> in)
    {
        List<String> keys = new ArrayList<>(in.keySet());
        keys.removeIf(k -> k == null || k.isBlank());
        Collections.sort(keys);
        return keys;
    }

    private static CompoundTag sortedMap(Map<String, String> in)
    {
        CompoundTag out = new CompoundTag();
        for (String k : sortedKeys(in))
        {
            String v = in.get(k);
            if (v != null && !v.isBlank())
                out.putString(k, v);
        }
        return out;
    }

    /**
     * Read a stored wardrobe, tolerating the pre-milestone shape where a slot held a bare string.
     *
     * <p>{@link EquippedCosmetic#fromTag} does the tolerating, so this walks the same loop either way. An old
     * record therefore loads as a Normal cosmetic with no instance recorded, which is the honest answer: nothing
     * on disk ever said which copy it was.
     */
    public static PlayerWardrobe fromNbt(CompoundTag t)
    {
        PlayerWardrobe w = new PlayerWardrobe();
        if (t == null)
            return w;
        CompoundTag eq = t.getCompound("equipped");
        for (String k : eq.getAllKeys())
        {
            EquippedCosmetic e = EquippedCosmetic.fromTag(eq.get(k));
            if (e != null)
                w.equipped.put(k, e);
        }
        CompoundTag tr = t.getCompound("trackers");
        for (String k : tr.getAllKeys())
        {
            String v = tr.getString(k);
            if (!v.isBlank())
                w.chosenTrackers.put(k, v);
        }
        return w;
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(equipped.size());
        for (Map.Entry<String, EquippedCosmetic> e : equipped.entrySet())
        {
            buf.writeUtf(e.getKey());
            e.getValue().encode(buf);
        }
        buf.writeVarInt(chosenTrackers.size());
        for (Map.Entry<String, String> e : chosenTrackers.entrySet())
        {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue());
        }
    }

    public static PlayerWardrobe decode(FriendlyByteBuf buf)
    {
        PlayerWardrobe w = new PlayerWardrobe();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
        {
            String slotKey = buf.readUtf();
            EquippedCosmetic e = EquippedCosmetic.decode(buf);
            if (e.valid())
                w.equipped.put(slotKey, e);
        }
        int m = buf.readVarInt();
        for (int i = 0; i < m; i++)
            w.chosenTrackers.put(buf.readUtf(), buf.readUtf());
        return w;
    }
}
