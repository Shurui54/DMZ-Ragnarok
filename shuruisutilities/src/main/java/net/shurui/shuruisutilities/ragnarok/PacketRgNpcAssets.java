package net.shurui.shuruisutilities.ragnarok;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: one chunk of the zipped rgnpc (ninjin) model pack an admin drops in
 * {@code <gamedir>/ShuruisUtilities/rgnpc/}. Chunked because the set is far past any single-packet size, and
 * released one chunk at a time by {@link RgNpcAssetServer}, each only after the client acknowledges the last. The client reassembles by
 * {@code version} in {@code RgNpcAssetCache} and feeds it to the in-memory {@code RgNpcPackResources}.
 */
public class PacketRgNpcAssets implements ISUPacket
{
    public int version;
    public int index;
    public int total;
    public byte[] data = new byte[0];

    public PacketRgNpcAssets() {}

    public PacketRgNpcAssets(int version, int index, int total, byte[] data)
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

    public static PacketRgNpcAssets decode(FriendlyByteBuf buf)
    {
        PacketRgNpcAssets p = new PacketRgNpcAssets();
        p.version = buf.readVarInt();
        p.index = buf.readVarInt();
        p.total = buf.readVarInt();
        p.data = buf.readByteArray();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
        {
            net.shurui.shuruisutilities.ragnarok.client.RgNpcAssetCache.accept(version, index, total, data);
            // The server holds the next chunk back until this arrives. See RgNpcAssetServer.
            net.shurui.shuruisutilities.ragnarok.client.RgNpcAssetCache.acknowledge();
        });
    }

    public static void handler(final PacketRgNpcAssets message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
