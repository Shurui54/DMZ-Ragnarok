package net.shurui.shuruisutilities.ragnarok;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * RETIRED, id 93 kept as an inert hole. This once carried one chunk of the server-streamed rgnpc model pack. As of
 * September 2026 the model set ships in the jar again (see {@code RgNpcPackFinder}), so nothing is sent on this id
 * and {@link #handle} does nothing. The class stays registered so the id is never reused and an older peer's packet
 * still decodes; encode / decode keep the original wire format for that reason.
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
        // INERT HOLE. Packet id 93 was rgnpc model asset streaming, retired in September 2026 when the model set
        // returned to the jar. Kept registered so the id is never reused and an older peer's packet decodes without
        // error; nothing is done with it. See RgNpcPackFinder for the bundled loader that replaced this.
    }

    public static void handler(final PacketRgNpcAssets message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
