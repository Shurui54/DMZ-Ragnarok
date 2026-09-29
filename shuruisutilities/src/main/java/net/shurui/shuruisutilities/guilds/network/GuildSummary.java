package net.shurui.shuruisutilities.guilds.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * One row in the admin guild list: enough to identify and triage a guild at a glance.
 */
public class GuildSummary
{
    public String name = "";
    public String leaderName = "";
    public int members;
    public int claims;
    public long maxClaims;
    public double power;
    public long bank;

    public void write(FriendlyByteBuf buf)
    {
        buf.writeUtf(name);
        buf.writeUtf(leaderName);
        buf.writeVarInt(members);
        buf.writeVarInt(claims);
        buf.writeLong(maxClaims);
        buf.writeDouble(power);
        buf.writeLong(bank);
    }

    public static GuildSummary read(FriendlyByteBuf buf)
    {
        GuildSummary s = new GuildSummary();
        s.name = buf.readUtf();
        s.leaderName = buf.readUtf();
        s.members = buf.readVarInt();
        s.claims = buf.readVarInt();
        s.maxClaims = buf.readLong();
        s.power = buf.readDouble();
        s.bank = buf.readLong();
        return s;
    }
}
