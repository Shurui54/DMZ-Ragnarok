package net.shurui.shuruisutilities.guilds.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.network.FriendlyByteBuf;

import net.shurui.shuruisutilities.guilds.GuildConfig;

/**
 * Server -&gt; client snapshot of the raid-loss-recovery salvage rates, carried alongside the admin guild list so the
 * admin GUI can show and edit them. Plain fields so the server (building it from {@link GuildConfig}) and the client
 * (rendering it) share one definition, exactly like {@link GuildView} / {@link GuildSummary}.
 *
 * <p>The admin GUI never mutates a value directly: it dispatches {@code /guild admin salvage ...} commands (the same
 * button-dispatches-a-command idiom the rest of the guild GUI uses) and then re-requests this snapshot, so the server
 * config file stays the single source of truth.
 */
public class GuildSalvageConfigView
{
    public double containerRate = 1.0;
    public double blockRate = 0.5;
    public int scanHeightAbove = 64;
    // Raid WIN reward: how many raid tickets each participating raider gets on a win. 0 disables the reward. Carried
    // on the same admin snapshot so the admin GUI can show and step it with the same button-dispatches-a-command idiom.
    public int ticketReward = 3;
    // per-block-type overrides as {blockId, rate-as-string} rows, sorted by id for a stable display.
    public List<String[]> overrides = new ArrayList<>();

    public static GuildSalvageConfigView from(GuildConfig cfg)
    {
        GuildSalvageConfigView s = new GuildSalvageConfigView();
        s.containerRate = cfg.salvageContainerRate;
        s.blockRate = cfg.salvageBlockRate;
        s.scanHeightAbove = cfg.salvageScanHeightAbove;
        s.ticketReward = cfg.raidWinTicketReward;
        if (cfg.salvageBlockRateOverrides != null)
        {
            List<String> ids = new ArrayList<>(cfg.salvageBlockRateOverrides.keySet());
            ids.sort(String::compareTo);
            for (String id : ids)
            {
                Double rate = cfg.salvageBlockRateOverrides.get(id);
                s.overrides.add(new String[] { id, String.valueOf(rate == null ? 0.0 : rate) });
            }
        }
        return s;
    }

    public void write(FriendlyByteBuf buf)
    {
        buf.writeDouble(containerRate);
        buf.writeDouble(blockRate);
        buf.writeVarInt(scanHeightAbove);
        buf.writeVarInt(ticketReward);
        buf.writeVarInt(overrides.size());
        for (String[] o : overrides)
        {
            buf.writeUtf(o[0] == null ? "" : o[0]);
            buf.writeUtf(o[1] == null ? "" : o[1]);
        }
    }

    public static GuildSalvageConfigView read(FriendlyByteBuf buf)
    {
        GuildSalvageConfigView s = new GuildSalvageConfigView();
        s.containerRate = buf.readDouble();
        s.blockRate = buf.readDouble();
        s.scanHeightAbove = buf.readVarInt();
        s.ticketReward = buf.readVarInt();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
        {
            String id = buf.readUtf();
            String rate = buf.readUtf();
            s.overrides.add(new String[] { id, rate });
        }
        return s;
    }
}
