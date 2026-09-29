package net.shurui.shuruisutilities.hologram.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Server to client: one chunk of the zipped hologram images an admin drops in
 * {@code <gamedir>/ShuruisUtilities/hologram_gifs/}.
 *
 * <p>Chunked because the set is well over the single packet size limit. The client reassembles by {@code version}
 * in {@code HologramGifCache} and decodes each image into textures from there. Sent on join, and again to
 * everyone when an admin reloads the folder.
 */
public class PacketHologramGifs implements ISUPacket
{
    public int version;
    public int index;
    public int total;
    public byte[] data = new byte[0];

    public PacketHologramGifs() {}

    public PacketHologramGifs(int version, int index, int total, byte[] data)
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

    public static PacketHologramGifs decode(FriendlyByteBuf buf)
    {
        PacketHologramGifs p = new PacketHologramGifs();
        p.version = buf.readVarInt();
        p.index = buf.readVarInt();
        p.total = buf.readVarInt();
        p.data = buf.readByteArray();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> net.shurui.shuruisutilities.hologram.client.HologramGifCache
                .accept(version, index, total, data));
    }

    public static void handler(final PacketHologramGifs message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
