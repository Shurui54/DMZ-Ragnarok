package net.shurui.dev.shuruis_dmz_dungeons.block;

import java.util.Random;
import java.util.UUID;

// The four rarity tiers a dungeon crate (chest or barrel) can be. Carries the tier colour (tints the latch/band and
// the reward screen frame) and the deterministic roll helpers CrateConversionTask uses to pick each crate's tier and
// which barrels are promoted.
//
// Pure data + math: no Minecraft container/level/render types, so it loads on both server and client and carries no
// shuruisutilities dependency.
//
// Rarity is rolled deterministically per viewer from (position, epoch, UUID), so nothing is synced and two players at
// one crate can see their own rarity. Loot is per-player instanced (DungeonCrateData). The epoch helper slices time
// into refresh windows so a player's stored CONTENTS re-roll on a farmable floor (refreshHours > 0).
public enum CrateTier {

    // colour = latch/band tint, key = rarity lang key, pitch = crate chime pitch, rising with rarity so a mythic reads
    // brighter than a common without a second sound per tier.
    COMMON(0xFF9E9E9E, "common", 0.85f),
    UNCOMMON(0xFF3FA34D, "uncommon", 1.0f),
    RARE(0xFF3B6FE0, "rare", 1.15f),
    MYTHIC(0xFFE0862B, "mythic", 1.3f);

    // opaque ARGB the client uses for the latch quad / barrel bands.
    public final int color;
    // suffix of the "tier.dmz_ragnarok.dungeons.<name>" lang key.
    public final String key;
    // playback pitch for this rarity's crate chimes.
    public final float pitch;

    CrateTier(int color, String key, float pitch) {
        this.color = color;
        this.key = key;
        this.pitch = pitch;
    }

    public String langKey() {
        return "tier.dmz_ragnarok.dungeons." + key;
    }

    private static final CrateTier[] VALUES = values();

    // ticks in one real hour (20 tps * 3600 s). one refresh window == refreshHours of these.
    public static final long TICKS_PER_HOUR = 72000L;

    // the sensible default weights (percent-ish; any positive total works) when a floor has not been tuned: most
    // containers are common, a mythic is rare. index == ordinal.
    public static final int[] DEFAULT_WEIGHTS = { 60, 25, 12, 3 };

    // which refresh window a game-time falls in. refreshHours <= 0 => never refresh (epoch 0 forever, contents persist
    // until looted, run-once floors). positive => time sliced into windows, tier and contents re-roll at each boundary.
    public static long epoch(long gameTime, int refreshHours) {
        if (refreshHours <= 0) {
            return 0L;
        }
        long window = (long) refreshHours * TICKS_PER_HOUR;
        return Math.floorDiv(gameTime, window);
    }

    // the container's shared tier for an epoch, weighted by the floor's weights. deterministic in (position, epoch,
    // weights). degenerate weights fall back to DEFAULT_WEIGHTS so a container always has a tier.
    public static CrateTier roll(long posKey, long epoch, int[] weights) {
        int[] w = normaliseWeights(weights);
        int total = w[0] + w[1] + w[2] + w[3];
        if (total <= 0) {
            return COMMON;
        }
        // a fixed salt keeps the tier stream distinct from the barrel-selection stream below.
        Random rng = new Random(mix(posKey, epoch, 0L, 0x9E3779B97F4A7C15L));
        int pick = rng.nextInt(total);
        int acc = 0;
        for (int i = 0; i < VALUES.length; i++) {
            acc += w[i];
            if (pick < acc) {
                return VALUES[i];
            }
        }
        return COMMON;
    }

    // PER-VIEWER tier: same weighted roll, but the viewer's UUID folds into the seed mix, so two players at one crate
    // in the same window each see (and loot) their OWN rarity. Both the client colour handler and the server open path
    // call this, so seen rarity == looted rarity. Null viewer degrades to the shared roll above. The block's baked TIER
    // property is vestigial: kept to avoid churn, read by neither side for rarity.
    public static CrateTier roll(long posKey, long epoch, UUID viewer, int[] weights) {
        int[] w = normaliseWeights(weights);
        int total = w[0] + w[1] + w[2] + w[3];
        if (total <= 0) {
            return COMMON;
        }
        long v = viewer == null ? 0L : (viewer.getMostSignificantBits() ^ viewer.getLeastSignificantBits());
        Random rng = new Random(mix(posKey, epoch, v, 0x9E3779B97F4A7C15L));
        int pick = rng.nextInt(total);
        int acc = 0;
        for (int i = 0; i < VALUES.length; i++) {
            acc += w[i];
            if (pick < acc) {
                return VALUES[i];
            }
        }
        return COMMON;
    }

    // whether a barrel at this position is a promoted crate. Only this share (default 5) is, deterministic in position
    // so server and client agree. epoch is deliberately NOT folded in: a barrel does not flip crate/plain over time,
    // only its tier does (roll above).
    public static boolean barrelSelected(long posKey, int barrelChancePercent) {
        return selected(posKey, barrelChancePercent, 0xC2B2AE3D27D4EB4FL);
    }

    // the same for a CHEST. Chests were promoted unconditionally, hence the default 100 (old floors convert all of
    // them). Its own salt so the chests kept do not correlate with the barrels promoted at the same percentage.
    public static boolean chestSelected(long posKey, int chestChancePercent) {
        return selected(posKey, chestChancePercent, 0xD6E8FEB86659FD93L);
    }

    // shared deterministic percentage roll behind both. 0 and 100 short-circuit so neither end depends on the mixer.
    private static boolean selected(long posKey, int chancePercent, long salt) {
        int pct = Math.max(0, Math.min(100, chancePercent));
        if (pct <= 0) {
            return false;
        }
        if (pct >= 100) {
            return true;
        }
        Random rng = new Random(mix(posKey, 0L, 0L, salt));
        return rng.nextInt(100) < pct;
    }

    public static CrateTier byOrdinal(int ordinal) {
        return VALUES[Math.floorMod(ordinal, VALUES.length)];
    }

    private static int[] normaliseWeights(int[] weights) {
        if (weights == null || weights.length < VALUES.length) {
            return DEFAULT_WEIGHTS;
        }
        int[] out = new int[VALUES.length];
        int total = 0;
        for (int i = 0; i < VALUES.length; i++) {
            out[i] = Math.max(0, weights[i]);
            total += out[i];
        }
        return total <= 0 ? DEFAULT_WEIGHTS : out;
    }

    // stable 64-bit mix of a packed position, epoch, viewer term and per-stream salt. splitmix64-style finaliser so
    // nearby positions/epochs/viewers do not correlate. viewer term is 0 for the viewer-independent streams.
    //
    // package-private, not private, because {@link CrateMetal} rolls the SAME crate on the same position and epoch and
    // must not correlate: it shares this mixer with a different salt to stay independent.
    static long mix(long posKey, long epoch, long viewer, long salt) {
        long z = posKey * 0xFF51AFD7ED558CCDL + epoch * 0xC4CEB9FE1A85EC53L
                + viewer * 0x9E3779B97F4A7C15L + salt;
        z = (z ^ (z >>> 33)) * 0xFF51AFD7ED558CCDL;
        z = (z ^ (z >>> 33)) * 0xC4CEB9FE1A85EC53L;
        return z ^ (z >>> 33);
    }
}
