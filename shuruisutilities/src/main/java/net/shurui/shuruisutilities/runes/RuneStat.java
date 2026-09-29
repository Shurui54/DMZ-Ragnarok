package net.shurui.shuruisutilities.runes;

import java.util.Locale;

import net.minecraft.ChatFormatting;

/**
 * The six stats an armour rune can affect, in the order the tooltip lists them.
 *
 * <p>Colours match the artist's rune set (red STR, yellow SKP, blue RES, green VIT, purple PWR, orange ENE) so a
 * rune item, its arrow row and its slot pip all read as the same thing without anyone having to learn a legend.
 */
public enum RuneStat
{
    STR("str", ChatFormatting.RED),
    SKP("skp", ChatFormatting.YELLOW),
    RES("res", ChatFormatting.BLUE),
    VIT("vit", ChatFormatting.GREEN),
    PWR("pwr", ChatFormatting.LIGHT_PURPLE),
    ENE("ene", ChatFormatting.GOLD);

    /** Item/texture suffix, e.g. {@code rune_str}. */
    private final String key;
    private final ChatFormatting colour;

    RuneStat(String key, ChatFormatting colour)
    {
        this.key = key;
        this.colour = colour;
    }

    public String key()
    {
        return key;
    }

    public ChatFormatting colour()
    {
        return colour;
    }

    /** Uppercase label shown at the end of the arrow row, e.g. "STR". */
    public String label()
    {
        return name();
    }

    public static RuneStat byKey(String k)
    {
        if (k == null)
            return null;
        String n = k.trim().toUpperCase(Locale.ROOT);
        for (RuneStat s : values())
            if (s.name().equals(n))
                return s;
        return null;
    }

    /**
     * The stat this one is bought at the expense of. Pushing a stat up pushes its opposite down, so every rune is a
     * choice between two things a build actually wants rather than a flat upgrade.
     *
     * <p>The three pairs are the ones that trade against each other in play: raw melee against ki output, the speed
     * you hit with against the ki you have to spend, and how much punishment you can take against how well you shrug
     * it off. Pairing is symmetric, so {@code a.opposite().opposite() == a} and the six stats cover each other
     * exactly once.
     *
     * <p>A switch rather than a field because an enum constant cannot reference a later one in its own constructor.
     */
    public RuneStat opposite()
    {
        return switch (this)
        {
            case STR -> PWR;
            case PWR -> STR;
            case SKP -> ENE;
            case ENE -> SKP;
            case VIT -> RES;
            case RES -> VIT;
        };
    }

    /** One representative of each opposing pair, for callers that want to walk the pairs rather than the stats. */
    public static RuneStat[] pairLeaders()
    {
        return new RuneStat[] { STR, SKP, VIT };
    }
}
