package net.shurui.shuruisutilities.guilds.model;

import net.minecraft.ChatFormatting;

/**
 * Diplomatic stance one guild holds toward another. Relations are symmetric only once both sides agree
 * (an offered ally/truce becomes mutual when reciprocated); enemy declarations are one-sided.
 */
public enum GuildRelation
{
    ALLY("Ally", ChatFormatting.GREEN),
    TRUCE("Truce", ChatFormatting.AQUA),
    NEUTRAL("Neutral", ChatFormatting.YELLOW),
    ENEMY("Enemy", ChatFormatting.RED);

    private final String display;
    private final ChatFormatting color;

    GuildRelation(String display, ChatFormatting color)
    {
        this.display = display;
        this.color = color;
    }

    public String display()
    {
        return display;
    }

    public ChatFormatting color()
    {
        return color;
    }

    public static GuildRelation fromString(String s)
    {
        if (s == null)
            return null;
        for (GuildRelation r : values())
            if (r.name().equalsIgnoreCase(s) || r.display.equalsIgnoreCase(s))
                return r;
        return null;
    }
}
