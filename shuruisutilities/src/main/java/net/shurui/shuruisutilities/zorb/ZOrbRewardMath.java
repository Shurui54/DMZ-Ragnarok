package net.shurui.shuruisutilities.zorb;

/**
 * The EXACT level scale for world Z orb rewards. TP and zeni are set by the operator at level 1 and at
 * {@code scaleMaxLevel} (default 100000); every level between is a straight linear interpolation, clamped outside
 * the anchors. The value is deterministic (no random range for the world spawner): the same level always yields the
 * same amount, exact at the two anchors, and the proof (/zorbs prove) reads these functions directly.
 *
 * <p>Kept in core so both the private reward path (key) and the client editor can share one definition, and so a
 * unit check can call it with no server. Never throws.
 */
public final class ZOrbRewardMath
{
    private ZOrbRewardMath() {}

    /** The interpolation fraction for a level against a config, clamped to {@code [0, 1]}. */
    public static double fraction(int level, int scaleMaxLevel)
    {
        int max = Math.max(2, scaleMaxLevel);
        int lv = level < 1 ? 1 : Math.min(level, max);
        return (double) (lv - 1) / (double) (max - 1);
    }

    /**
     * The base TP for a collecting player's DMZ level, exact at level 1 ({@code tpAtLevel1}) and at
     * {@code scaleMaxLevel} ({@code tpAtMaxLevel}). Does NOT apply the last-orb completion multiplier; see
     * {@link #tpForLevel(ZOrbGlobals, int, boolean)}.
     */
    public static long tpForLevel(ZOrbGlobals g, int level)
    {
        double t = fraction(level, g.scaleMaxLevel);
        double v = g.tpAtLevel1 + (double) (g.tpAtMaxLevel - g.tpAtLevel1) * t;
        return Math.round(v);
    }

    /** As {@link #tpForLevel(ZOrbGlobals, int)}, applying the completion multiplier when {@code last} is true. */
    public static long tpForLevel(ZOrbGlobals g, int level, boolean last)
    {
        long base = tpForLevel(g, level);
        if (!last)
            return base;
        return Math.round(base * Math.max(1.0, g.completionMultiplier));
    }

    /** The base zeni for a level, exact at the two anchors. */
    public static long zeniForLevel(ZOrbGlobals g, int level)
    {
        double t = fraction(level, g.scaleMaxLevel);
        double v = g.zeniAtLevel1 + (double) (g.zeniAtMaxLevel - g.zeniAtLevel1) * t;
        return Math.round(v);
    }

    /** As {@link #zeniForLevel(ZOrbGlobals, int)}, applying the completion multiplier when {@code last} is true. */
    public static long zeniForLevel(ZOrbGlobals g, int level, boolean last)
    {
        long base = zeniForLevel(g, level);
        if (!last)
            return base;
        return Math.round(base * Math.max(1.0, g.completionMultiplier));
    }
}
