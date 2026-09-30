package net.shurui.shuruisutilities.ragnarok;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * client -&gt; server: "of the rgnpc model pack, this is the version I already hold, and this much of it".
 *
 * <h2>Why this exists</h2>
 * Without it the server pushed the whole pack, several megabytes, to every client on every join, whether or not
 * that client already had an identical copy on disk. The client discarded the resend (its cache compares versions),
 * so the bytes bought nothing, but they were still on the wire during the exact window a join is most fragile: the
 * KeepAlive the server sends 15 seconds in sits in the send queue behind them, and a player whose downlink cannot
 * drain the pack in time never answers it and is dropped at 30 seconds for a timeout. They reconnect, the transfer
 * starts again from the first byte, and they never get in. That loop is what this packet ends.
 *
 * <h2>It is also the acknowledgement</h2>
 * The client sends it again after every chunk it receives, and the server releases the next chunk only on hearing
 * it. A report sent once at join still left the server free to pour the rest of the pack into the connection at
 * its own speed, and on a thin link that surplus sat in the proxy in front of the KeepAlive, which is the same
 * timeout by a different road. One packet doing both jobs means the resume logic and the pacing can never disagree
 * about where a transfer stands.
 *
 * <h2>The two fields</h2>
 * {@code version} is the pack version the client holds, {@code 0} when it holds nothing. {@code haveChunks} is
 * {@link #COMPLETE} when that version is whole and applied, otherwise the number of leading chunks of it the client
 * has kept from an interrupted transfer. Chunks are sent and stored strictly in order, so a count is enough to say
 * what is missing and the server can resume from it rather than restart.
 *
 * <p>Nothing here is trusted for anything but bandwidth. The worst a lying client achieves is being sent a pack it
 * already has, or being sent none and drawing the fallback Steve, both of which it can do to itself anyway by
 * deleting its cache.
 */
public class PacketRgNpcAssetsHave implements ISUPacket
{
    /** {@code haveChunks} value meaning "I have this version in full", as opposed to a count of leading chunks. */
    public static final int COMPLETE = -1;

    public int version;
    public int haveChunks;

    public PacketRgNpcAssetsHave() {}

    public PacketRgNpcAssetsHave(int version, int haveChunks)
    {
        this.version = version;
        this.haveChunks = haveChunks;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeInt(version);
        buf.writeVarInt(haveChunks + 1);   // shifted so COMPLETE (-1) stays a legal varint
    }

    public static PacketRgNpcAssetsHave decode(FriendlyByteBuf buf)
    {
        int version = buf.readInt();
        return new PacketRgNpcAssetsHave(version, buf.readVarInt() - 1);
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // INERT HOLE. Packet id 97 was the client's rgnpc asset handshake, retired in September 2026 when the model
        // set returned to the jar. Kept registered so the id is never reused and an older client's report decodes
        // without error; the server does nothing with it now.
    }

    public static void handler(final PacketRgNpcAssetsHave message, Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
