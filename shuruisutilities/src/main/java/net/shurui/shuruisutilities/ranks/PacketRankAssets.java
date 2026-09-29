package net.shurui.shuruisutilities.ranks;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: one chunk of the zipped rank-badge textures the admin drops in
 * {@code <gamedir>/ShuruisUtilities/ranks/}. The set is chunked because it is well over the single-packet size
 * limit; the client reassembles by {@code version} in {@link net.shurui.shuruisutilities.ranks.client.RankAssetCache}
 * and feeds it to the in-memory {@code RankPackResources}. Sent on join.
 */
public class PacketRankAssets implements ISUPacket
{
    public int version;
    public int index;
    public int total;
    public byte[] data = new byte[0];

    public PacketRankAssets() {}

    public PacketRankAssets(int version, int index, int total, byte[] data)
    {
        this.version = version;
        this.index = index;
        this.total = total;
        this.data = data == null ? new byte[0] : data;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(version);
        buf.writeVarInt(index);
        buf.writeVarInt(total);
        buf.writeByteArray(data);
    }

    public static PacketRankAssets decode(FriendlyByteBuf buf)
    {
        PacketRankAssets p = new PacketRankAssets();
        p.version = buf.readVarInt();
        p.index = buf.readVarInt();
        p.total = buf.readVarInt();
        p.data = buf.readByteArray();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.ranks.client.RankAssetCache.accept(version, index, total, data));
    }

    public static void handler(final PacketRankAssets message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
