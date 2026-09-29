package net.shurui.shuruisutilities.guilds.model;

/**
 * Per-territory toggle governing what may happen inside a guild's claimed chunks. Defaults mirror a
 * typical protected home territory: no PvP, no hostile mobs, no explosions, no fire spread.
 */
public enum GuildFlag
{
    PVP("pvp", false),
    MONSTERS("monsters", false),
    EXPLOSIONS("explosions", false),
    FIRE_SPREAD("firespread", false),
    MOB_GRIEFING("mobgriefing", false);

    private final String id;
    private final boolean defaultValue;

    GuildFlag(String id, boolean defaultValue)
    {
        this.id = id;
        this.defaultValue = defaultValue;
    }

    public String id()
    {
        return id;
    }

    public boolean defaultValue()
    {
        return defaultValue;
    }

    public static GuildFlag fromString(String s)
    {
        if (s == null)
            return null;
        for (GuildFlag f : values())
            if (f.id.equalsIgnoreCase(s) || f.name().equalsIgnoreCase(s))
                return f;
        return null;
    }
}
