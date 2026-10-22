package net.shurui.shuruisutilities.npcregion;

import net.minecraft.util.RandomSource;

/**
 * The level scale shared by NPC region NPCs and the advanced spawner's mobs, the same shape as world Z orbs
 * ({@link net.shurui.shuruisutilities.zorb.ZOrbRewardMath}): a value is set at level 1 and at {@code scaleMaxLevel}
 * (default {@value #DEFAULT_MAX_LEVEL}) and every level between is a straight linear interpolation on the player's DMZ
 * level, clamped outside the two anchors. Exact at both anchors.
 *
 * <p>Rewards scale on the KILLER's level; a reward that is a range scales both ends, then rolls between them as
 * before. Stats scale on the level a mob was scaled for (see the stat scaling).
 *
 * <p>Migration: every existing configured value is the LEVEL 1 anchor, so a level 1 player sees exactly what the
 * config said before. A max-level anchor that was never set is AUTO: {@value #DEFAULT_MULTIPLE} times the level 1
 * value, recomputed from it, so editing the level 1 value moves the curve until an admin pins the max-level value.
 *
 * <p>Core so the key (regions), the dungeons module (spawners) and the client editors share one definition.
 */
public final class LevelScale
{
    /** Default level at which the max-level anchor is reached, matching the Z orb default. */
    public static final int DEFAULT_MAX_LEVEL = 100000;
    /** An unset max-level anchor is this many times the level 1 value. */
    public static final int DEFAULT_MULTIPLE = 100;

    private LevelScale() {}

    /** The interpolation fraction in {@code [0, 1]} (Z orb definition: level 1 is 0, {@code maxLevel} is 1). */
    public static double fraction(int level, int maxLevel)
    {
        return net.shurui.shuruisutilities.zorb.ZOrbRewardMath.fraction(level, maxLevel);
    }

    /** {@code atLevel1 + (atMax - atLevel1) * fraction}, unrounded. */
    public static double lerp(double atLevel1, double atMax, int level, int maxLevel)
    {
        return atLevel1 + (atMax - atLevel1) * fraction(level, maxLevel);
    }

    /** As {@link #lerp(double, double, int, int)}, rounded and clamped to a non-negative int. */
    public static int lerpInt(int atLevel1, int atMax, int level, int maxLevel)
    {
        double v = Math.round(lerp(atLevel1, atMax, level, maxLevel));
        return v <= 0 ? 0 : v >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }

    /** The automatic max-level anchor for an int value: {@value #DEFAULT_MULTIPLE} times it, saturating. */
    public static int autoMax(int atLevel1)
    {
        long v = (long) Math.max(0, atLevel1) * DEFAULT_MULTIPLE;
        return v >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }

    /** The automatic max-level anchor for a float value. */
    public static float autoMax(float atLevel1)
    {
        return Math.max(0f, atLevel1) * DEFAULT_MULTIPLE;
    }

    /**
     * A reward roll scaled to a level: both ends of the {@code [lo1, hi1]} range at level 1 and {@code [loMax, hiMax]}
     * at {@code maxLevel} are interpolated, then a uniform value between them is rolled, exactly like the unscaled
     * range roll. 0 when the interpolated top is not positive.
     */
    public static int roll(int lo1, int hi1, int loMax, int hiMax, int level, int maxLevel, RandomSource random)
    {
        int a = lerpInt(Math.min(lo1, hi1), Math.min(loMax, hiMax), level, maxLevel);
        int b = lerpInt(Math.max(lo1, hi1), Math.max(loMax, hiMax), level, maxLevel);
        int lo = Math.min(a, b), hi = Math.max(a, b);
        if (hi <= 0)
            return 0;
        if (hi <= lo)
            return lo;
        long span = (long) hi - lo + 1;
        return span > Integer.MAX_VALUE ? lo + (int) (random.nextDouble() * (hi - lo)) : lo + random.nextInt((int) span);
    }
}
