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
 * Server -&gt; client: the full set of guild-claimed chunks, each with an ARGB color already resolved
 * relative to the receiving player (own guild / ally / enemy / neutral) and the owning guild's name for
 * tooltips. Consumed by {@code GuildClaimCacheClient} and drawn on Xaero's maps by the guild highlighter.
 */
public class PacketGuildClaims implements ISUPacket
{
    public static final class Row
    {
        public String dim;
        public int cx, cz, color;
        public String guild;

        public Row() {}

        public Row(String dim, int cx, int cz, int color, String guild)
        {
            this.dim = dim;
            this.cx = cx;
            this.cz = cz;
            this.color = color;
            this.guild = guild;
        }
    }

    public List<Row> rows = new ArrayList<>();

    public PacketGuildClaims() {}

    public PacketGuildClaims(List<Row> rows)
    {
        this.rows = rows;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(rows.size());
        for (Row r : rows)
        {
            buf.writeUtf(r.dim);
            buf.writeVarInt(r.cx);
            buf.writeVarInt(r.cz);
            buf.writeInt(r.color);
            buf.writeUtf(r.guild);
        }
    }

    public static PacketGuildClaims decode(FriendlyByteBuf buf)
    {
        PacketGuildClaims p = new PacketGuildClaims();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
        {
            Row r = new Row();
            r.dim = buf.readUtf();
            r.cx = buf.readVarInt();
            r.cz = buf.readVarInt();
            r.color = buf.readInt();
            r.guild = buf.readUtf();
            p.rows.add(r);
        }
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.guilds.client.GuildClaimApply.apply(this));
    }

    public static void handler(final PacketGuildClaims message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
