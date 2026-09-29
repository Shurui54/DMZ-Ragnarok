package net.shurui.shuruisutilities.guilds.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * A capability that can be granted to a {@link GuildRole} inside the guild's own territory / management.
 * Used both for territory action gating (build/destroy/containers/interact) and for guild administration
 * (invite/kick/claim/bank/...). See {@link #defaultsFor(GuildRole)} for the out-of-the-box grants.
 */
public enum GuildPermission
{
    BUILD("build"),
    DESTROY("destroy"),
    CONTAINER("container"),
    INTERACT("interact"),
    INVITE("invite"),
    KICK("kick"),
    CLAIM("claim"),
    UNCLAIM("unclaim"),
    SET_HOME("sethome"),
    USE_HOME("home"),
    SET_WARP("setwarp"),
    USE_WARP("warp"),
    BANK_DEPOSIT("deposit"),
    BANK_WITHDRAW("withdraw"),
    SET_ROLE("setrole"),
    SET_RELATION("relation"),
    SET_FLAG("flag"),
    EDIT_INFO("info"),
    /**
     * Harm an NPC standing on the guild's own land: villagers, traders, golems and every NPC the suite adds.
     *
     * <p>Outsiders are refused outright, the same way building is, so a guild's shopkeepers cannot be picked off
     * by somebody who is not in the guild. This permission is what decides it for the guild's OWN members, which
     * is why it exists as a permission rather than a flag: a recruit should not be able to wipe out the village
     * the guild built, while an officer clearing a bad spawn should not have to ask.
     */
    DAMAGE_NPC("damagenpc");

    private final String id;

    GuildPermission(String id)
    {
        this.id = id;
    }

    public String id()
    {
        return id;
    }

    public static GuildPermission fromString(String s)
    {
        if (s == null)
            return null;
        for (GuildPermission p : values())
            if (p.id.equalsIgnoreCase(s) || p.name().equalsIgnoreCase(s))
                return p;
        return null;
    }

    /** Default permission grant for a role. Leaders implicitly have everything (checked separately). */
    public static Set<GuildPermission> defaultsFor(GuildRole role)
    {
        switch (role)
        {
            case LEADER:
                return EnumSet.allOf(GuildPermission.class);
            case OFFICER:
                return EnumSet.of(BUILD, DESTROY, CONTAINER, INTERACT, INVITE, KICK, CLAIM, UNCLAIM, SET_HOME, USE_HOME,
                        SET_WARP, USE_WARP, BANK_DEPOSIT, BANK_WITHDRAW, SET_FLAG, DAMAGE_NPC);
            case MEMBER:
                // a member can already break the guild's blocks, so withholding this from them would be odd.
                return EnumSet.of(BUILD, DESTROY, CONTAINER, INTERACT, USE_HOME, USE_WARP, BANK_DEPOSIT, DAMAGE_NPC);
            case RECRUIT:
            default:
                return EnumSet.of(CONTAINER, INTERACT, USE_HOME, BANK_DEPOSIT);
        }
    }
}
