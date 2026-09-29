package net.shurui.shuruisutilities.guilds.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: carries the data to open a guild GUI. {@code mode 0} = the player's own guild view
 * ({@link GuildView}); {@code mode 1} = the admin list ({@link #summaries}). The client opens the matching
 * screen; screen classes are only referenced through {@link DistExecutor} so they never load on a server.
 */
public class PacketGuildGui implements ISUPacket
{
    public static final int MODE_PLAYER = 0;
    public static final int MODE_ADMIN = 1;

    public int mode;
    public GuildView view = new GuildView();
    public List<GuildSummary> summaries = new ArrayList<>();
    // Admin mode only: the current salvage rates, so the admin GUI can show and edit them. Rides in the admin branch
    // next to the guild summaries.
    public GuildSalvageConfigView salvageConfig = new GuildSalvageConfigView();

    public PacketGuildGui() {}

    public static PacketGuildGui player(GuildView view)
    {
        PacketGuildGui p = new PacketGuildGui();
        p.mode = MODE_PLAYER;
        p.view = view;
        return p;
    }

    public static PacketGuildGui admin(List<GuildSummary> summaries, GuildSalvageConfigView salvageConfig)
    {
        PacketGuildGui p = new PacketGuildGui();
        p.mode = MODE_ADMIN;
        p.summaries = summaries;
        p.salvageConfig = salvageConfig;
        return p;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(mode);
        if (mode == MODE_ADMIN)
        {
            buf.writeVarInt(summaries.size());
            for (GuildSummary s : summaries)
                s.write(buf);
            salvageConfig.write(buf);
        }
        else
        {
            view.write(buf);
        }
    }

    public static PacketGuildGui decode(FriendlyByteBuf buf)
    {
        PacketGuildGui p = new PacketGuildGui();
        p.mode = buf.readVarInt();
        if (p.mode == MODE_ADMIN)
        {
            int n = buf.readVarInt();
            for (int i = 0; i < n; i++)
                p.summaries.add(GuildSummary.read(buf));
            p.salvageConfig = GuildSalvageConfigView.read(buf);
        }
        else
        {
            p.view = GuildView.read(buf);
        }
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // Runs on the client; open the screen without referencing client classes on the server.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.guilds.client.GuildGuiClient.open(this));
    }

    public static void handler(final PacketGuildGui message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
