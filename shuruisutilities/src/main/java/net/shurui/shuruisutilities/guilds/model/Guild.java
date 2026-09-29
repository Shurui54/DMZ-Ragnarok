package net.shurui.shuruisutilities.guilds.model;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.shurui.shuruisutilities.commons.selections.WarpPoint;

/**
 * A guild and all its persistent state. Serialized to JSON (one file per guild) by the guild manager.
 * UUIDs are stored as strings so they survive Gson map-key serialization. Battle power is cached per
 * member ({@link #memberPower}) because it can only be read from DragonMineZ while a member is online.
 */
public class Guild
{
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String description = "";
    public String motd = "";
    public String leader = "";
    public boolean open = false;
    public long bank = 0L;
    public long createdAt = System.currentTimeMillis();

    /**
     * Extra claim chunks this guild has bought permanently by consuming a claim-upgrade item, applied ON TOP of
     * the size-and-power limit. It is added AFTER the MIN of the two limit terms (and after the hard cap) in
     * {@link net.shurui.shuruisutilities.guilds.GuildManager#maxClaims}, which is the only place it takes effect:
     * because the limit is a MIN, adding to either term alone would deliver nothing whenever the OTHER term is the
     * binding constraint, so a flat post-MIN bonus is the only form that reliably grants exactly this many more
     * chunks whichever term binds. Attaches to the GUILD, not the consumer, which is the natural reading of
     * "increase this guild's claim chunks" and means it is never lost or double counted when a member changes
     * guild. A plain int so Gson serializes it with no adapter; it defaults to 0, so a guild saved before this
     * field existed simply carries no bonus, and it travels across the shard network for free because the whole
     * guild is serialized to JSON by ShardGuildBridge.
     */
    public int claimBonus = 0;

    public Map<String, GuildRole> members = new HashMap<>();
    /** Last-known DMZ battle power per member (uuid -> BP), refreshed while online. */
    public Map<String, Double> memberPower = new HashMap<>();
    /**
     * Last-known display name per member (uuid -> name), recorded at join and refreshed whenever a member is
     * seen online. Display metadata only: the uuid is always the identity key, this is never keyed or compared
     * against. Absent for members carried over from before this field existed until they are next seen online
     * (or backfilled from the profile cache on load).
     */
    public Map<String, String> memberNames = new HashMap<>();
    public Set<String> invites = new HashSet<>();
    public Set<String> claims = new HashSet<>();
    public WarpPoint home;
    public Map<String, WarpPoint> warps = new HashMap<>();
    public Map<String, GuildRelation> relations = new HashMap<>();
    public Map<GuildFlag, Boolean> flags = new EnumMap<>(GuildFlag.class);
    public Map<GuildRole, Set<GuildPermission>> rolePermissions = new EnumMap<>(GuildRole.class);

    /**
     * Per MEMBER permission overrides, keyed by uuid string exactly like {@link #members}.
     *
     * <p>An entry beats the member's role: TRUE hands somebody a permission their rank does not carry, FALSE takes
     * one away that it does. ABSENT means "whatever the role says", which is every member of every guild until a
     * leader sets one, so this changes nothing on its own and a guild saved before it existed simply has none.
     *
     * <p>Deliberately cannot bind the LEADER, who keeps everything: a leader who could be denied a permission
     * could be locked out of their own guild by an override they then had no way to clear.
     */
    public Map<String, Map<GuildPermission, Boolean>> memberPermissions = new HashMap<>();

    /** Gson-friendly no-arg ctor. */
    public Guild() {}

    public Guild(String name, UUID leader)
    {
        this.name = name;
        this.leader = leader.toString();
        this.members.put(this.leader, GuildRole.LEADER);
        for (GuildRole role : GuildRole.values())
            this.rolePermissions.put(role, GuildPermission.defaultsFor(role));
        for (GuildFlag flag : GuildFlag.values())
            this.flags.put(flag, flag.defaultValue());
    }

    public UUID leaderUuid()
    {
        return UUID.fromString(leader);
    }

    public boolean isMember(UUID player)
    {
        return members.containsKey(player.toString());
    }

    public GuildRole roleOf(UUID player)
    {
        return members.get(player.toString());
    }

    public boolean isLeader(UUID player)
    {
        return leader.equals(player.toString());
    }

    public int memberCount()
    {
        return members.size();
    }

    /** Leaders always pass. Otherwise checks the role's granted permission set. */
    public boolean hasPermission(UUID player, GuildPermission perm)
    {
        GuildRole role = roleOf(player);
        if (role == null)
            return false;
        if (role == GuildRole.LEADER)
            return true; // checked before the override on purpose: a leader can never be denied out of their guild
        Boolean override = memberPermission(player, perm);
        if (override != null)
            return override;
        Set<GuildPermission> granted = rolePermissions.get(role);
        return granted != null && granted.contains(perm);
    }

    /** This member's override for a permission, or null when they simply follow their role. */
    public Boolean memberPermission(UUID player, GuildPermission perm)
    {
        if (player == null || perm == null)
            return null;
        Map<GuildPermission, Boolean> overrides = memberPermissions.get(player.toString());
        return overrides == null ? null : overrides.get(perm);
    }

    /** Grant ({@code TRUE}), deny ({@code FALSE}) or clear ({@code null}) one permission for one member. */
    public void setMemberPermission(UUID player, GuildPermission perm, Boolean allow)
    {
        if (player == null || perm == null)
            return;
        String key = player.toString();
        if (allow == null)
        {
            Map<GuildPermission, Boolean> overrides = memberPermissions.get(key);
            if (overrides != null)
            {
                overrides.remove(perm);
                if (overrides.isEmpty())
                    memberPermissions.remove(key); // do not leave empty maps behind to be saved for ever
            }
            return;
        }
        memberPermissions.computeIfAbsent(key, k -> new EnumMap<>(GuildPermission.class)).put(perm, allow);
    }

    public void setRolePermission(GuildRole role, GuildPermission perm, boolean allow)
    {
        Set<GuildPermission> set = rolePermissions.computeIfAbsent(role, r -> java.util.EnumSet.noneOf(GuildPermission.class));
        if (allow)
            set.add(perm);
        else
            set.remove(perm);
    }

    public boolean flag(GuildFlag flag)
    {
        Boolean v = flags.get(flag);
        return v == null ? flag.defaultValue() : v;
    }

    public GuildRelation relationTo(String otherGuildId)
    {
        return relations.getOrDefault(otherGuildId, GuildRelation.NEUTRAL);
    }

    /** Aggregate cached battle power of all members. */
    public double battlePower()
    {
        double sum = 0;
        for (double v : memberPower.values())
            sum += v;
        return sum;
    }
}
