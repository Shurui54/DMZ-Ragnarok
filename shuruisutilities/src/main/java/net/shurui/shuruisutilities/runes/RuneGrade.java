package net.shurui.shuruisutilities.runes;

import java.util.Locale;
import java.util.Random;

import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;

/**
 * How good a rune is as an ITEM, separate from the tier it rolls.
 *
 * <p>Grade is fixed by which rune you are holding; tier is the roll inside it. The three grades share one tier
 * ladder rather than needing a parallel one: each grade weights the same five tiers differently, so a better grade
 * raises both the floor and the ceiling of what you can get.
 *
 * <p>Each band dips exactly 5% into the tier DIRECTLY BELOW it and stops there. That is the whole overlap: a greater
 * rune is Medium one time in twenty and never worse, a normal one is Low one time in twenty and never worse. It
 * keeps the roll worth watching without letting a grade land somewhere it has no business being.
 *
 * <p>The three grades sit on an EVEN ladder rather than a curve: mean tier cap runs 1.65, 2.80, 3.95, a step of
 * exactly 1.15 each time. That is what stops the middle grade being a formality. It carries through to what a player
 * actually sees on a piece: two runes of one grade reach four or more arrows 23%, 50% and 79% of the time, steps of
 * 27 and 29 points.
 *
 * <p>Greater runes stop short of a guarantee on purpose. They max a stat 63% of the time, not the 90% an earlier
 * tuning gave: a grade that always produced the same answer would make the roll inside it pointless, which is the
 * same mistake as a hard cut-off between bands.
 */
public enum RuneGrade
{
    /** Red sheen. Low and Medium, with a 10% reach into High. Mean tier cap 1.65. */
    LESSER("_lesser", ChatFormatting.RED, new int[] { 45, 45, 10, 0, 0 },
            new ResourceLocation("dmz_ragnarok", "textures/misc/rune_glint_red.png")),
    /** Green sheen. Medium and High, dipping 5% into Low and 20% into Legendary. Mean tier cap 2.80. */
    NORMAL("", ChatFormatting.GREEN, new int[] { 5, 30, 45, 20, 0 },
            new ResourceLocation("dmz_ragnarok", "textures/misc/rune_glint_green.png")),
    /** The stock enchantment sheen. High and above, dipping 5% into Medium. Mean tier cap 3.95. */
    GREATER("_greater", ChatFormatting.AQUA, new int[] { 0, 5, 25, 40, 30 }, null),
    /**
     * Golden sheen. The ADMIN rune: it does not roll a tier at all, it simply fills its stat to the ceiling with no
     * downside charged anywhere. The weights below are never drawn from (nothing rolls an admin rune, it is only
     * ever given out), and are pinned to the top tier so anything that does read them reads the strongest answer.
     */
    ADMIN("_admin", ChatFormatting.GOLD, new int[] { 0, 0, 0, 0, 100 },
            new ResourceLocation("dmz_ragnarok", "textures/misc/rune_glint_gold.png"));

    private final String suffix;
    private final ChatFormatting colour;
    /** Relative chance of each {@link RuneTier}, indexed by that tier's ordinal. */
    private final int[] tierWeights;
    /** Glint texture for this grade's sheen, or null to use vanilla's purple one. */
    private final ResourceLocation glint;

    RuneGrade(String suffix, ChatFormatting colour, int[] tierWeights, ResourceLocation glint)
    {
        this.suffix = suffix;
        this.colour = colour;
        this.tierWeights = tierWeights;
        this.glint = glint;
    }

    /** Registry-name suffix; NORMAL is unsuffixed so the existing rune ids are untouched. */
    public String suffix()
    {
        return suffix;
    }

    /**
     * Every grade carries a sheen: it is what tells them apart on sight, since all three share one piece of art.
     * The colour is the difference, which is why {@link #glintTexture()} rather than a plain boolean decides it.
     */
    public boolean foil()
    {
        return true;
    }

    /** The sheen's texture, or null for vanilla's. */
    public ResourceLocation glintTexture()
    {
        return glint;
    }

    public ChatFormatting colour()
    {
        return colour;
    }

    public String label()
    {
        String n = name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** The lowest tier this grade can produce at all, tails included. */
    public RuneTier floor()
    {
        RuneTier[] all = RuneTier.values();
        for (int i = 0; i < tierWeights.length; i++)
            if (tierWeights[i] > 0)
                return all[i];
        return RuneTier.LOW;
    }

    /** The highest tier this grade can produce at all, tails included. */
    public RuneTier ceiling()
    {
        RuneTier[] all = RuneTier.values();
        for (int i = tierWeights.length - 1; i >= 0; i--)
            if (tierWeights[i] > 0)
                return all[i];
        return RuneTier.LOW;
    }

    /** Percent chance of rolling exactly this tier at this grade, for tooltips and for checking the ladder. */
    public double chanceOf(RuneTier tier)
    {
        int total = 0;
        for (int w : tierWeights)
            total += w;
        if (total <= 0 || tier == null)
            return 0.0;
        return 100.0 * tierWeights[tier.ordinal()] / total;
    }

    /** A tier from this grade's weighted band, so the grade shapes the roll instead of replacing it. */
    public RuneTier rollTier(Random rng)
    {
        RuneTier[] all = RuneTier.values();
        int total = 0;
        for (int w : tierWeights)
            total += w;
        if (total <= 0)
            return RuneTier.LOW;
        int pick = rng.nextInt(total);
        for (int i = 0; i < tierWeights.length; i++)
        {
            pick -= tierWeights[i];
            if (pick < 0)
                return all[i];
        }
        return floor();
    }

    public static RuneGrade byName(String n)
    {
        if (n == null)
            return NORMAL;
        try
        {
            return valueOf(n.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            return NORMAL;
        }
    }
}
