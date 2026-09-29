package net.shurui.shuruisutilities.ranks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

// server -> client: each online player's rank NAME (empty = none), so the client can draw the badge above heads
// and in the tab list, animating it from its own copy of the rank index. Loaded into RankClientCache via DistExecutor.
public class PacketRankSync implements ISUPacket
{
    public Map<UUID, String> ranks = new HashMap<>();

    public PacketRankSync() {}

    public PacketRankSync(Map<UUID, String> ranks)
    {
        this.ranks = ranks;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(ranks.size());
        for (Map.Entry<UUID, String> e : ranks.entrySet())
        {
            buf.writeUUID(e.getKey());
            buf.writeUtf(e.getValue());
        }
    }

    public static PacketRankSync decode(FriendlyByteBuf buf)
    {
        PacketRankSync p = new PacketRankSync();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
        {
            UUID id = buf.readUUID();
            p.ranks.put(id, buf.readUtf());
        }
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.ranks.client.RankClientCache.replaceAll(ranks));
    }

    public static void handler(final PacketRankSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
