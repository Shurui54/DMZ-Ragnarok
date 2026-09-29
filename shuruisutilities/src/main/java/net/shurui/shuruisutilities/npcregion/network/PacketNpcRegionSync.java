package net.shurui.shuruisutilities.npcregion.network;

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
 * Server -&gt; client: a lightweight snapshot of every NPC region (footprint + what it spawns + reward
 * ranges), used to draw the map outlines and the shift+hover info. Sent on login and whenever a region is
 * created / edited / deleted. Consumed by {@code NpcRegionCacheClient}.
 */
public class PacketNpcRegionSync implements ISUPacket
{
    public static final class Row
    {
        public String name, dim, entity;
        public int minX, minZ, maxX, maxZ, count, tpMin, tpMax, balMin, balMax;
        public int minY, maxY;
        public String title = "", description = "", difficulty = "";
        public boolean showTitle = true, showHud = true;
        /** Map overlay fill colour as {@code #RRGGBB}; blank = default. */
        public String color = "";

        public Row() {}

        public Row(String name, String dim, int minX, int minZ, int maxX, int maxZ, String entity, int count,
                   int tpMin, int tpMax, int balMin, int balMax)
        {
            this.name = name;
            this.dim = dim;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.entity = entity;
            this.count = count;
            this.tpMin = tpMin;
            this.tpMax = tpMax;
            this.balMin = balMin;
            this.balMax = balMax;
        }
    }

    public List<Row> rows = new ArrayList<>();
    // receiving player may manage regions, gates map outlines / hover info client-side
    public boolean canManage;

    public PacketNpcRegionSync() {}

    public PacketNpcRegionSync(List<Row> rows, boolean canManage)
    {
        this.rows = rows == null ? new ArrayList<>() : rows;
        this.canManage = canManage;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(canManage);
        buf.writeVarInt(rows.size());
        for (Row r : rows)
        {
            buf.writeUtf(r.name);
            buf.writeUtf(r.dim);
            buf.writeInt(r.minX);
            buf.writeInt(r.minZ);
            buf.writeInt(r.maxX);
            buf.writeInt(r.maxZ);
            buf.writeUtf(r.entity);
            buf.writeVarInt(r.count);
            buf.writeVarInt(r.tpMin);
            buf.writeVarInt(r.tpMax);
            buf.writeVarInt(r.balMin);
            buf.writeVarInt(r.balMax);
            buf.writeInt(r.minY);
            buf.writeInt(r.maxY);
            buf.writeUtf(r.title);
            buf.writeUtf(r.description);
            buf.writeUtf(r.difficulty);
            buf.writeBoolean(r.showTitle);
            buf.writeBoolean(r.showHud);
            buf.writeUtf(r.color);
        }
    }

    public static PacketNpcRegionSync decode(FriendlyByteBuf buf)
    {
        PacketNpcRegionSync p = new PacketNpcRegionSync();
        p.canManage = buf.readBoolean();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
        {
            Row r = new Row(buf.readUtf(), buf.readUtf(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
            r.minY = buf.readInt();
            r.maxY = buf.readInt();
            r.title = buf.readUtf();
            r.description = buf.readUtf();
            r.difficulty = buf.readUtf();
            r.showTitle = buf.readBoolean();
            r.showHud = buf.readBoolean();
            r.color = buf.readUtf();
            p.rows.add(r);
        }
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.apply(rows, canManage));
    }

    /** Client-only sink; kept in a nested class so the server never classloads client cache types. */
    private static final class ClientHandler
    {
        static void apply(List<Row> rows, boolean canManage)
        {
            List<net.shurui.shuruisutilities.compat.client.NpcRegionCacheClient.Entry> entries = new ArrayList<>();
            for (Row r : rows)
            {
                var e = new net.shurui.shuruisutilities.compat.client.NpcRegionCacheClient.Entry(
                        r.name, r.dim, r.minX, r.minZ, r.maxX, r.maxZ, r.entity, r.count,
                        r.tpMin, r.tpMax, r.balMin, r.balMax,
                        r.minY, r.maxY, r.title, r.description, r.difficulty, r.showTitle, r.showHud);
                e.setColor(r.color);
                entries.add(e);
            }
            net.shurui.shuruisutilities.compat.client.NpcRegionCacheClient.replaceAll(entries, canManage);
        }
    }

    public static void handler(final PacketNpcRegionSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
