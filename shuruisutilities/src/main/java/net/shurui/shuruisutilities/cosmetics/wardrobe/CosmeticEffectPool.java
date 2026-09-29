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
 * A named, ordered, weighted set of {@link CosmeticEffect} ids. What a Magic roll draws from.
 *
 * <h2>PERSISTED NAME. NEVER RENAME THIS CLASS OR ITS FIELDS.</h2>
 * Instances live in {@code cosmetics.json} under the {@code pools} map. A pool id is persisted twice over: a
 * crate will name one, {@link CosmeticDef#defaultEffectPoolId} names one as the fallback for anything that
 * grants a Magic copy without being a crate, and {@link CosmeticOwnership#effectPoolId} records which pool a
 * copy actually came out of, for ever. Renaming a pool id therefore breaks a future roll AND falsifies history.
 *
 * <h2>Why a pool at all, rather than a class per effect</h2>
 * A crate names ONE pool, so "different crates roll different effects" is an admin editing a field rather than a
 * developer writing a class. The pool is also the unit the published odds screen reads: A5 of the plan makes
 * crate odds public, so the weights here are player-facing and are authored to read as percentages.
 *
 * <h2>Weights are relative and do not have to sum to anything</h2>
 * {@link #totalWeight()} is the denominator, so 60/30/10 and 6/3/1 are the same pool. The shipped catalogue
 * writes 60/30/10 anyway so the odds screen needs no arithmetic to explain itself.
 *
 * <p>Weight ZERO is meaningful: the entry stays in the pool, is listed in the editor, and is never rolled. Same
 * idea as {@code TaskDef.weight == 0} and as {@link CosmeticEffect#enabled}, and it is how an effect is pulled
 * out of circulation for a season without losing its place or its odds history.
 *
 * <p>ORDER is authored and preserved. The list is the order an editor and the odds screen display in, which is
 * why this is a list of entries rather than a map: a map would sort itself, and an admin who put the showpiece
 * last meant it to read last.
 *
 * <h2>Nothing rolls anything yet</h2>
 * There is deliberately no pick method here. Rolling belongs to the crate milestone, which owns the random
 * source, the pity rules and the audit line that has to be written when a Magic copy is minted. This record is
 * the data it will read.
 */
public class CosmeticEffectPool
{
    /** One effect and how likely it is, relative to the rest of the pool. */
    public static class WeightedEffect
    {
        /** An id from the {@code effects} map. Not validated here: see {@link CosmeticCatalog#danglingPoolRefs}. */
        public String effectId = "";

        /** Relative weight. Zero means listed but never rolled. Never negative after {@link #normalise()}. */
        public int weight = 0;

        public WeightedEffect()
        {
        }

        public WeightedEffect(String effectId, int weight)
        {
            this.effectId = effectId == null ? "" : effectId;
            this.weight = weight;
        }

        public WeightedEffect copy()
        {
            return new WeightedEffect(effectId, weight);
        }

        public WeightedEffect normalise()
        {
            effectId = CosmeticDef.sanitizeId(effectId);
            if (weight < 0)
                weight = 0;
            return this;
        }

        public void encode(FriendlyByteBuf buf)
        {
            buf.writeUtf(effectId == null ? "" : effectId);
            buf.writeVarInt(Math.max(0, weight));
        }

        public static WeightedEffect decode(FriendlyByteBuf buf)
        {
            WeightedEffect e = new WeightedEffect();
            e.effectId = buf.readUtf();
            e.weight = buf.readVarInt();
            return e.normalise();
        }

        public CompoundTag toNbt()
        {
            CompoundTag t = new CompoundTag();
            t.putString("effectId", effectId == null ? "" : effectId);
            t.putInt("weight", Math.max(0, weight));
            return t;
        }

        public static WeightedEffect fromNbt(CompoundTag t)
        {
            WeightedEffect e = new WeightedEffect();
            e.effectId = t.getString("effectId");
            e.weight = t.getInt("weight");
            return e.normalise();
        }
    }

    /** Stable lowercase id, sanitised so it can never contain a colon. See the class note on renaming. */
    public String id = "";

    /** Shown in the editor and on the odds screen. Supports the suite's &amp; colour codes. */
    public String displayName = "New Pool";

    /**
     * Parked without being deleted. A disabled pool stops being offered and keeps every copy already rolled out
     * of it, the same posture {@link CosmeticEffect#enabled} and {@link CosmeticDef#enabled} take.
     */
    public boolean enabled = true;

    /** The entries, in authored order. */
    public List<WeightedEffect> entries = new ArrayList<>();

    public CosmeticEffectPool()
    {
    }

    public CosmeticEffectPool(String id)
    {
        this.id = id == null ? "" : id;
    }

    public CosmeticEffectPool copy()
    {
        CosmeticEffectPool p = new CosmeticEffectPool(id);
        p.displayName = displayName;
        p.enabled = enabled;
        for (WeightedEffect e : entries)
            if (e != null)
                p.entries.add(e.copy());
        return p;
    }

    /** Fill in anything a hand-edited file left null, and make the entry list a SET keyed by effect id. */
    public CosmeticEffectPool normalise()
    {
        id = CosmeticDef.sanitizeId(id);
        if (displayName == null)
            displayName = "";
        if (entries == null)
            entries = new ArrayList<>();
        // Deduplicated on the FIRST occurrence, never summed. Two entries for one effect would make the odds
        // screen disagree with the roll, and summing them would silently double an effect an admin only meant to
        // list once. The first wins because that is the position the admin put it in.
        List<WeightedEffect> kept = new ArrayList<>(entries.size());
        Set<String> seen = new LinkedHashSet<>();
        for (WeightedEffect e : entries)
        {
            if (e == null)
                continue;
            e.normalise();
            if (e.effectId.isBlank() || !seen.add(e.effectId))
                continue;
            kept.add(e);
        }
        entries.clear();
        entries.addAll(kept);
        return this;
    }

    /** The denominator for the published odds. Zero means nothing in this pool may be rolled. */
    public int totalWeight()
    {
        int sum = 0;
        for (WeightedEffect e : entries)
            if (e != null && e.weight > 0)
                sum += e.weight;
        return sum;
    }

    /** Whether this pool could actually produce a result. A pool of parked entries is valid but empty. */
    public boolean rollable()
    {
        return enabled && !id.isBlank() && totalWeight() > 0;
    }

    public boolean references(String effectId)
    {
        return weightEntry(effectId) != null;
    }

    public int weightOf(String effectId)
    {
        WeightedEffect e = weightEntry(effectId);
        return e == null ? 0 : e.weight;
    }

    /** The published chance of this effect, 0 to 1. Exactly what the odds screen shows, computed in one place. */
    public double chanceOf(String effectId)
    {
        int total = totalWeight();
        return total <= 0 ? 0.0D : (double) weightOf(effectId) / (double) total;
    }

    /** The ids in authored order, including parked ones. */
    public List<String> effectIds()
    {
        List<String> out = new ArrayList<>(entries.size());
        for (WeightedEffect e : entries)
            if (e != null && !e.effectId.isBlank())
                out.add(e.effectId);
        return out;
    }

    /**
     * Draw one effect id by weight, or blank when the pool is not rollable (disabled, empty or all-zero weight).
     *
     * <p>The one weighted-draw implementation, so a crate roll and an admin grant that omits the effect both pick
     * the same way. Parked entries (weight zero) are listed but never drawn, exactly as the odds screen shows.
     */
    public String roll(net.minecraft.util.RandomSource random)
    {
        if (random == null || !rollable())
            return "";
        int total = totalWeight();
        if (total <= 0)
            return "";
        int r = random.nextInt(total);
        for (WeightedEffect e : entries)
        {
            if (e == null || e.weight <= 0 || e.effectId.isBlank())
                continue;
            r -= e.weight;
            if (r < 0)
                return e.effectId;
        }
        return "";
    }

    /** Add an effect or retune one already listed, KEEPING its position. The only write path an editor needs. */
    public void put(String effectId, int weight)
    {
        String clean = CosmeticDef.sanitizeId(effectId);
        if (clean.isEmpty())
            return;
        WeightedEffect existing = weightEntry(clean);
        if (existing != null)
            existing.weight = Math.max(0, weight);
        else
            entries.add(new WeightedEffect(clean, Math.max(0, weight)));
    }

    public boolean remove(String effectId)
    {
        WeightedEffect e = weightEntry(effectId);
        return e != null && entries.remove(e);
    }

    private WeightedEffect weightEntry(String effectId)
    {
        String clean = CosmeticDef.sanitizeId(effectId);
        if (clean.isEmpty())
            return null;
        for (WeightedEffect e : entries)
            if (e != null && clean.equals(e.effectId))
                return e;
        return null;
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(id == null ? "" : id);
        buf.writeUtf(displayName == null ? "" : displayName);
        buf.writeBoolean(enabled);
        buf.writeVarInt(entries.size());
        for (WeightedEffect e : entries)
            e.encode(buf);
    }

    public static CosmeticEffectPool decode(FriendlyByteBuf buf)
    {
        CosmeticEffectPool p = new CosmeticEffectPool();
        p.id = buf.readUtf();
        p.displayName = buf.readUtf();
        p.enabled = buf.readBoolean();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            p.entries.add(WeightedEffect.decode(buf));
        return p.normalise();
    }

    /**
     * Serialise for the cross-server state sync. Deterministic: the entry list is written in its STORED order,
     * which is authored and therefore part of the record, and every other value is keyed by name. Two shards
     * holding the same pool produce identical bytes, which is what stops them republishing each other forever.
     */
    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        t.putString("id", id == null ? "" : id);
        t.putString("displayName", displayName == null ? "" : displayName);
        t.putBoolean("enabled", enabled);
        ListTag list = new ListTag();
        for (WeightedEffect e : entries)
            if (e != null)
                list.add(e.toNbt());
        t.put("entries", list);
        return t;
    }

    public static CosmeticEffectPool fromNbt(CompoundTag t)
    {
        CosmeticEffectPool p = new CosmeticEffectPool();
        p.id = t.getString("id");
        p.displayName = t.getString("displayName");
        p.enabled = !t.contains("enabled") || t.getBoolean("enabled");
        ListTag list = t.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
            p.entries.add(WeightedEffect.fromNbt(list.getCompound(i)));
        return p.normalise();
    }
}
