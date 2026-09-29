package net.shurui.shuruisutilities.guilds.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Side-agnostic snapshot of a guild sent from the server to the client to populate the guild GUI. Kept
 * as plain fields so both the server (building it) and the client (rendering it) share one definition.
 */
public class GuildView
{
    public boolean hasGuild;
    public String name = "";
    public String description = "";
    public String motd = "";
    public String leaderName = "";
    public String myRole = "";
    public boolean open;
    public long bank;
    public double power;
    public int claimCount;
    public long maxClaims;
    public boolean homeSet;
    public String currency = "Zeni";

    // decides whether a claim is legal (the server is authoritative). ----
    // The planet this guild already owns, or "" if none. One planet per guild, flat; independent of the chunk cap.
    public String ownedPlanetName = "";
    // Whether the viewing player is currently standing on a generated planet's surface (i.e. a claim/unclaim here
    // is even possible). When false the tab shows guidance instead of a Claim/Unclaim button.
    public boolean onPlanet;
    // The name and surface size (blocks per side) of the planet under the player, valid only when onPlanet.
    public String hereName = "";
    public int hereSurfaceSize;
    // Ownership of the planet under the player: "" unclaimed, "self" this guild owns it, otherwise the owning
    // guild's display name. Valid only when onPlanet.
    public String hereOwner = "";

    // The salvage stacks this guild has waiting from a destroyed planet, as {displayName, count} rows in the order the
    // vault holds them (containers first, bulk blocks after). The row INDEX is the withdraw key: the Take button on
    // row i dispatches "guild salvage take i". Empty when the guild has no salvage.
    public java.util.List<String[]> salvage = new ArrayList<>();
    // Total number of salvage stacks (may exceed the rows actually sent, which are capped for the GUI). Lets the tab
    // show "showing N of M" and offer Take All even when not every stack is listed.
    public int salvageTotal;
    // Whether the VIEWING player's guild rank may withdraw salvage (the BANK_WITHDRAW permission, officer+ by default).
    // The client hides the Take controls when false, but the server-side withdraw handler is the real gate: it re-runs
    // this exact check and refuses a withdraw from an under-ranked member regardless of what the client shows.
    public boolean canWithdrawSalvage;

    public List<String[]> members = new ArrayList<>();   // {name, role}
    // {guildName, declared, effective, raidReason, raidLockoutRemaining}
    // The last two are filled server-side by running the authoritative RaidCheck for THIS row: raidReason is the
    // RaidCheck.Result enum name ("OK" when a raid may start now, or the specific refusal), and is "" for any row
    // whose effective stance is not ENEMY (those rows get no raid control at all). raidLockoutRemaining is the
    // human-readable time left, only meaningful when raidReason is "LOCKED_OUT". The GUI only reflects these; it
    // never re-derives the raid rules client-side, so the server stays the single authority.
    public List<String[]> relations = new ArrayList<>(); // {guildName, declared, effective, raidReason, lockoutLeft}
    public List<String[]> flags = new ArrayList<>();     // {flagId, "true"/"false"}
    // Per-rank permission matrix for the Permissions tab. One row per GuildPermission in enum order:
    // {permId, recruit, member, officer, leader}, where each role column is "true"/"false". The LEADER column is
    // always "true" because Guild.hasPermission short circuits to true for the leader regardless of what the stored
    // rolePermissions hold, so the GUI must never imply a leader grant can be removed. The client decides interactive
    // vs read-only from myRole (LEADER means the viewer is the guild leader); no separate leader flag is carried.
    public List<String[]> permissions = new ArrayList<>(); // {permId, recruit, member, officer, leader}
    public List<String> warps = new ArrayList<>();

    public void write(FriendlyByteBuf buf)
    {
        buf.writeBoolean(hasGuild);
        if (!hasGuild)
            return;
        buf.writeUtf(name);
        buf.writeUtf(description);
        buf.writeUtf(motd);
        buf.writeUtf(leaderName);
        buf.writeUtf(myRole);
        buf.writeBoolean(open);
        buf.writeLong(bank);
        buf.writeDouble(power);
        buf.writeVarInt(claimCount);
        buf.writeLong(maxClaims);
        buf.writeBoolean(homeSet);
        buf.writeUtf(currency);
        buf.writeUtf(ownedPlanetName);
        buf.writeBoolean(onPlanet);
        buf.writeUtf(hereName);
        buf.writeVarInt(hereSurfaceSize);
        buf.writeUtf(hereOwner);
        writeRows(buf, salvage, 2);
        buf.writeVarInt(salvageTotal);
        buf.writeBoolean(canWithdrawSalvage);
        writeRows(buf, members, 2);
        writeRows(buf, relations, 5);
        writeRows(buf, flags, 2);
        writeRows(buf, permissions, 5);
        buf.writeVarInt(warps.size());
        for (String w : warps)
            buf.writeUtf(w);
    }

    public static GuildView read(FriendlyByteBuf buf)
    {
        GuildView v = new GuildView();
        v.hasGuild = buf.readBoolean();
        if (!v.hasGuild)
            return v;
        v.name = buf.readUtf();
        v.description = buf.readUtf();
        v.motd = buf.readUtf();
        v.leaderName = buf.readUtf();
        v.myRole = buf.readUtf();
        v.open = buf.readBoolean();
        v.bank = buf.readLong();
        v.power = buf.readDouble();
        v.claimCount = buf.readVarInt();
        v.maxClaims = buf.readLong();
        v.homeSet = buf.readBoolean();
        v.currency = buf.readUtf();
        v.ownedPlanetName = buf.readUtf();
        v.onPlanet = buf.readBoolean();
        v.hereName = buf.readUtf();
        v.hereSurfaceSize = buf.readVarInt();
        v.hereOwner = buf.readUtf();
        v.salvage = readRows(buf, 2);
        v.salvageTotal = buf.readVarInt();
        v.canWithdrawSalvage = buf.readBoolean();
        v.members = readRows(buf, 2);
        v.relations = readRows(buf, 5);
        v.flags = readRows(buf, 2);
        v.permissions = readRows(buf, 5);
        int wc = buf.readVarInt();
        for (int i = 0; i < wc; i++)
            v.warps.add(buf.readUtf());
        return v;
    }

    private static void writeRows(FriendlyByteBuf buf, List<String[]> rows, int cols)
    {
        buf.writeVarInt(rows.size());
        for (String[] r : rows)
            for (int c = 0; c < cols; c++)
                buf.writeUtf(r[c] == null ? "" : r[c]);
    }

    private static List<String[]> readRows(FriendlyByteBuf buf, int cols)
    {
        int n = buf.readVarInt();
        List<String[]> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
        {
            String[] r = new String[cols];
            for (int c = 0; c < cols; c++)
                r[c] = buf.readUtf();
            rows.add(r);
        }
        return rows;
    }
}
