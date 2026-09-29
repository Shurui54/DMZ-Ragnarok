package net.shurui.shuruisutilities.guilds.client;

import java.util.Map;

import net.shurui.shuruisutilities.guilds.network.PacketGuildClaims;

/**
 * Client-only bridge that loads a received {@link PacketGuildClaims} into {@link GuildClaimCacheClient}.
 * Kept separate (and referenced only via DistExecutor) so the server never touches client cache classes.
 */
public final class GuildClaimApply
{
    private GuildClaimApply() {}

    public static void apply(PacketGuildClaims packet)
    {
        Map<String, Map<Long, GuildClaimCacheClient.Claim>> bucket = GuildClaimCacheClient.newBucket();
        for (PacketGuildClaims.Row r : packet.rows)
            GuildClaimCacheClient.put(bucket, r.dim, r.cx, r.cz, r.color, r.guild);
        GuildClaimCacheClient.replaceAll(bucket);
    }
}
