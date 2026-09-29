package net.shurui.shuruisutilities.guilds.model;

/**
 * Rank of a member within a guild. Higher {@link #weight} outranks lower. Modelled on the classic
 * faction hierarchy: leader &gt; officer &gt; member &gt; recruit.
 */
public enum GuildRole
{
    RECRUIT(0, "Recruit"),
    MEMBER(1, "Member"),
    OFFICER(2, "Officer"),
    LEADER(3, "Leader");

    private final int weight;
    private final String display;

    GuildRole(int weight, String display)
    {
        this.weight = weight;
        this.display = display;
    }

    public int weight()
    {
        return weight;
    }

    public String display()
    {
        return display;
    }

    /** True if this role strictly outranks {@code other}. */
    public boolean outranks(GuildRole other)
    {
        return this.weight > other.weight;
    }

    public boolean atLeast(GuildRole other)
    {
        return this.weight >= other.weight;
    }

    public static GuildRole fromString(String s)
    {
        if (s == null)
            return null;
        for (GuildRole r : values())
            if (r.name().equalsIgnoreCase(s) || r.display.equalsIgnoreCase(s))
                return r;
        return null;
    }
}
