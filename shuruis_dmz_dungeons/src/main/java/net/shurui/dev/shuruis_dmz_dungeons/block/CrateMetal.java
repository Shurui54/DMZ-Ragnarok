package net.shurui.dev.shuruis_dmz_dungeons.block;

import java.util.Random;
import java.util.UUID;

// the metal a dungeon crate is banded in: bronze, iron or gold. Second axis of a crate's identity alongside CrateTier's
// four rarities; together they name the twelve models the Basic Crate Pack provides (crate_<rarity>_<metal>).
//
// Rolled like the rarity: deterministic in (position, epoch, viewer) against per-floor weights, so nothing is stored or
// synced. Its own SALT keeps it independent of the rarity roll, so a mythic crate is not always gold (see CrateTier.mix).
//
// pure data + math, no Minecraft types, loads on server and client alike.
public enum CrateMetal {

    // colours are a tint the renderer/GUI may use for the tier chip. lang keys resolve the metal name for tooltips.
    BRONZE(0xFFB87333, "bronze"),
    IRON(0xFFC7C9CC, "iron"),
    GOLD(0xFFE6B422, "gold");

    public final int color;
    public final String key;

    CrateMetal(int color, String key) {
        this.color = color;
        this.key = key;
    }

    public String langKey() {
        return "metal.dmz_ragnarok.dungeons." + key;
    }

    private static final CrateMetal[] VALUES = values();

    public static final int METAL_COUNT = 3;

    // most crates are bronze, gold is the rare one. Same shape of default as CrateTier.DEFAULT_WEIGHTS: any positive
    // total works, index == ordinal.
    public static final int[] DEFAULT_WEIGHTS = { 60, 30, 10 };

    // distinct from CrateTier's 0x9E3779B97F4A7C15 so the metal and the rarity of one crate are independent draws.
    private static final long SALT = 0x6A09E667F3BCC909L;

    /** The metal every player sees, for floors that do not vary it per viewer. */
    public static CrateMetal roll(long posKey, long epoch, int[] weights) {
        return roll(posKey, epoch, null, weights);
    }

    /**
     * The metal for one viewer, like the rarity: two players at one crate each open their own instanced reward, so each
     * sees the crate it came out of. Null viewer degrades to the shared roll.
     */
    public static CrateMetal roll(long posKey, long epoch, UUID viewer, int[] weights) {
        int[] w = normaliseWeights(weights);
        int total = 0;
        for (int v : w) {
            total += v;
        }
        if (total <= 0) {
            return BRONZE;
        }
        long v = viewer == null ? 0L : (viewer.getMostSignificantBits() ^ viewer.getLeastSignificantBits());
        Random rng = new Random(CrateTier.mix(posKey, epoch, v, SALT));
        int pick = rng.nextInt(total);
        int acc = 0;
        for (int i = 0; i < VALUES.length; i++) {
            acc += w[i];
            if (pick < acc) {
                return VALUES[i];
            }
        }
        return BRONZE;
    }

    public static CrateMetal byOrdinal(int ordinal) {
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
}
