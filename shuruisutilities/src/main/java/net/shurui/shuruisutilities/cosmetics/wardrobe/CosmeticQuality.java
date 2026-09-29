package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How good one COPY of a cosmetic is. Normal, Super or Magic.
 *
 * <h2>Quality is a property of the INSTANCE, never of the definition</h2>
 * A Super Bat Hat and a Normal Bat Hat are the same {@link CosmeticDef} at two qualities. The quality lives on the
 * ownership row ({@link CosmeticOwnership#quality}), because a definition is a template shared by everybody and a
 * counter, an effect roll and a bound flag are all facts about one person's copy. The definition only says which
 * qualities a copy MAY be minted at, in {@link CosmeticDef#allowedQualities}, which is the question a crate asks
 * before it rolls.
 *
 * <h2>The key, never the ordinal</h2>
 * Same discipline as {@link CosmeticSlot}. Every persisted and wire form is the {@link #key}, a stable lowercase
 * string, so the order of the constants here carries no meaning and declaring a new one can never shift an
 * existing record onto a different quality. Ordering questions ("which of these two copies is better") go through
 * {@link #rank}, an explicit field, rather than through {@code ordinal()}.
 *
 * <p>The ONE exception, stated so it is not a surprise: {@code cosmetics.json} is written by Gson, which
 * serialises an enum by its CONSTANT NAME rather than by our key, exactly as it already does for
 * {@link CosmeticDef#slot}. So the JSON says {@code "SUPER"} while the NBT and the wire say {@code "super"}. Both
 * are stable strings we control and both are pinned: do not rename the constants and do not rename the keys.
 *
 * <h2>This REPLACED CosmeticTier, and the old keys are read explicitly</h2>
 * The previous enum was {@code CosmeticTier} with PLAIN / TRACKING / UNUSUAL, and it answered two questions at
 * once: what kind of thing is this, and does it have counters or an effect. The split is
 * {@link CosmeticDef#allowedQualities} (which qualities may exist) plus this (which quality a given copy is).
 *
 * <p>{@link #fromLegacyTierKey} maps the three old keys onto eligibility sets and returns null for anything else,
 * ON PURPOSE. The old {@code byKey} never threw, so an unrecognised key silently became the default, and during a
 * rename a silent default is silent data loss. The caller logs the unknown value instead of swallowing it.
 */
public enum CosmeticQuality
{
    /** Just the item. No counter, no effect. What the shop sells, always. */
    NORMAL("normal", 0),

    /** The item plus a counter that goes up as the owner plays. See {@link CosmeticCounter}. */
    SUPER("super", 1),

    /** The item plus a visual effect rolled from a pool. The rare one. */
    MAGIC("magic", 2);

    /** Stable lowercase key. PERSISTED on every ownership row, so never rename these. */
    public final String key;

    /**
     * Ordering weight for "which of these two copies is better", used when something has to pick one copy of a
     * cosmetic on the owner's behalf.
     *
     * <p>An explicit field rather than {@code ordinal()} so that inserting a constant in the middle later is a
     * decision about this number rather than an accident of declaration order. Note a copy can be Super AND Magic
     * once crates roll them independently, which this single-valued enum cannot express; that is a known limit of
     * the M0 shape and the rank is only ever a tie-break for a picker, never a gate.
     */
    public final int rank;

    CosmeticQuality(String key, int rank)
    {
        this.key = key;
        this.rank = rank;
    }

    public String langKey()
    {
        return "gui.dmz_ragnarok.cosmetics.quality." + key;
    }

    private static final CosmeticQuality[] VALUES = values();

    /** Never throws: an unknown or absent key reads as NORMAL rather than failing a load. */
    public static CosmeticQuality byKey(String key)
    {
        if (key != null)
        {
            String lower = key.toLowerCase(Locale.ROOT);
            for (CosmeticQuality q : VALUES)
                if (q.key.equals(lower))
                    return q;
        }
        return NORMAL;
    }

    public static List<String> keys()
    {
        List<String> out = new ArrayList<>(VALUES.length);
        for (CosmeticQuality q : VALUES)
            out.add(q.key);
        return out;
    }

    /** Whether a counter means anything on this quality. */
    public boolean usesCounters()
    {
        return this == SUPER;
    }

    /** Whether a rolled effect means anything on this quality. */
    public boolean usesEffect()
    {
        return this == MAGIC;
    }

    // ------------------------------------------------------------------ sets

    /** A fresh, mutable, canonically ordered set holding just {@link #NORMAL}. The eligibility default. */
    public static Set<CosmeticQuality> defaultSet()
    {
        return EnumSet.of(NORMAL);
    }

    /**
     * A set in CANONICAL order, null and duplicate safe, never empty.
     *
     * <p>Canonical means declaration order, which an {@link EnumSet} gives for free. That is what makes the
     * persisted form deterministic: two servers holding the same eligibility write the same bytes whatever order
     * an admin ticked the boxes in, so the state sync's content hash only moves on a real edit.
     *
     * <p>Never empty because an item eligible for no quality at all could not be granted by anything, which is a
     * cosmetic an admin cannot use and cannot see why. An empty input reads as {@link #defaultSet()}.
     */
    public static Set<CosmeticQuality> canonical(Collection<CosmeticQuality> in)
    {
        EnumSet<CosmeticQuality> out = EnumSet.noneOf(CosmeticQuality.class);
        if (in != null)
            for (CosmeticQuality q : in)
                if (q != null)
                    out.add(q);
        return out.isEmpty() ? defaultSet() : out;
    }

    /** The keys of a set, in canonical order, for NBT, the wire and an editor row. */
    public static List<String> keysOf(Collection<CosmeticQuality> in)
    {
        List<String> out = new ArrayList<>();
        for (CosmeticQuality q : canonical(in))
            out.add(q.key);
        return out;
    }

    /** Read a set back from keys. Unknown keys are dropped; an empty result reads as {@link #defaultSet()}. */
    public static Set<CosmeticQuality> setFromKeys(Collection<String> keys)
    {
        EnumSet<CosmeticQuality> out = EnumSet.noneOf(CosmeticQuality.class);
        if (keys != null)
            for (String k : keys)
            {
                if (k == null)
                    continue;
                String lower = k.toLowerCase(Locale.ROOT);
                for (CosmeticQuality q : VALUES)
                    if (q.key.equals(lower))
                        out.add(q);
            }
        return out.isEmpty() ? defaultSet() : out;
    }

    /** {@code "normal,super"}, for a list row or a flat editor field. */
    public static String join(Collection<CosmeticQuality> in)
    {
        return String.join(",", keysOf(in));
    }

    /** The inverse of {@link #join}. Blank reads as {@link #defaultSet()}. */
    public static Set<CosmeticQuality> split(String csv)
    {
        List<String> parts = new ArrayList<>();
        if (csv != null)
            for (String piece : csv.split(","))
                if (!piece.isBlank())
                    parts.add(piece.trim());
        return setFromKeys(parts);
    }

    // ------------------------------------------------------------------ the retired tier

    /**
     * The eligibility set an old {@code CosmeticTier} key means, or NULL when the key is not one of the three.
     *
     * <p>{@code plain} to {NORMAL}, {@code tracking} to {NORMAL, SUPER}, {@code unusual} to
     * {NORMAL, SUPER, MAGIC}. The old tier said what an item IS; eligibility says what it MAY BE, and an item
     * that carried counters could always also exist without them, so each old tier widens to itself and
     * everything below it.
     *
     * <p>NULL rather than a default for an unrecognised key, deliberately. This is the one place where being
     * quiet would destroy an admin's work without anybody noticing, so the caller logs it.
     */
    public static Set<CosmeticQuality> fromLegacyTierKey(String tierKey)
    {
        if (tierKey == null)
            return null;
        switch (tierKey.trim().toLowerCase(Locale.ROOT))
        {
        case "plain":
            return EnumSet.of(NORMAL);
        case "tracking":
            return EnumSet.of(NORMAL, SUPER);
        case "unusual":
            return EnumSet.of(NORMAL, SUPER, MAGIC);
        default:
            return null;
        }
    }
}
