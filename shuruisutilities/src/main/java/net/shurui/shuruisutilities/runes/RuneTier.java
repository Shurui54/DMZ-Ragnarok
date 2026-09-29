package net.shurui.shuruisutilities.runes;

import java.util.Locale;
import java.util.Random;

import net.minecraft.ChatFormatting;

/**
 * Equipment tier, rolled once per armour piece and per rune. The tier is the ceiling on how strong a roll can be:
 * it caps how many arrows a single stat may show, so a Low piece is visibly modest and a Mythical one is visibly
 * exceptional without either needing a separate stat table.
 *
 * <p>Five arrows is the hard maximum in either direction, matching the tooltip art, so MYTHICAL is the only tier
 * that can fill a row.
 */
public enum RuneTier
{
    LOW(1, ChatFormatting.GRAY, 60),
    MEDIUM(2, ChatFormatting.WHITE, 25),
    HIGH(3, ChatFormatting.AQUA, 10),
    LEGENDARY(4, ChatFormatting.GOLD, 4),
    MYTHICAL(5, ChatFormatting.LIGHT_PURPLE, 1);

    /** Largest number of arrows this tier may put on one stat, positive or negative. */
    private final int maxArrows;
    private final ChatFormatting colour;
    /** Relative chance of rolling this tier. Mythical is deliberately rare: the artist marked it event/raid grade. */
    private final int weight;

    RuneTier(int maxArrows, ChatFormatting colour, int weight)
    {
        this.maxArrows = maxArrows;
        this.colour = colour;
        this.weight = weight;
    }

    public int maxArrows()
    {
        return maxArrows;
    }

    public ChatFormatting colour()
    {
        return colour;
    }

    /** Title-case label for the tooltip, e.g. "Legendary". */
    public String label()
    {
        String n = name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    public static RuneTier byName(String n)
    {
        if (n == null)
            return LOW;
        try
        {
            return valueOf(n.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            return LOW;
        }
    }

    /** Weighted random tier, used when a piece or rune first gets one. */
    public static RuneTier roll(Random rng)
    {
        int total = 0;
        for (RuneTier t : values())
            total += t.weight;
        int pick = rng.nextInt(total);
        for (RuneTier t : values())
        {
            pick -= t.weight;
            if (pick < 0)
                return t;
        }
        return LOW;
    }
}
