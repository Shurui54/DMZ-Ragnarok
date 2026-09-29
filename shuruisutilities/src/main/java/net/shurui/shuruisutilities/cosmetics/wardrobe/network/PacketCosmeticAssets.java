package net.shurui.shuruisutilities.cosmetics.wardrobe.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.api.key.CosmeticHooks;
import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * The cosmetic art stream (SU channel id 150, both directions on one id), the same arrangement the rgnpc model pack
 * uses on its pair of ids 93 and 97, folded into one packet with a {@link #kind} byte.
 *
 * <ul>
 *   <li>{@link #CHUNK}, server to client: one chunk (at most 256 KB) of the zipped art pack the Ragnarok Key builds
 *       from its own jar. The client appends it in {@code CosmeticAssetCache} and answers with a HAVE.</li>
 *   <li>{@link #HAVE}, client to server: "of the cosmetic art pack I hold version {@code version}, and
 *       {@code haveChunks} leading chunks of it" ({@link #COMPLETE} for a whole applied pack). Sent unprompted on
 *       join, so a client with a warm cache is sent ZERO bytes, and again after every chunk as the acknowledgement
 *       that releases the next one. On a keyless server {@link CosmeticHooks.Impl#assetReport} is a no-op, so a
 *       keyless server answers nothing and sends nothing.</li>
 * </ul>
 *
 * <p>The handler checks the reception side: a client can send either half, so a CHUNK arriving at the server, or a
 * HAVE arriving at a client, is dropped. Nothing in a HAVE is trusted for anything but bandwidth.
 */
public class PacketCosmeticAssets implements ISUPacket
{
    /** Server to client: one chunk of the pack. */
    public static final byte CHUNK = 0;
    /** Client to server: what the client holds (join report and per-chunk acknowledgement). */
    public static final byte HAVE = 1;

    /** {@code haveChunks} value meaning "I hold this version in full", as opposed to a count of leading chunks. */
    public static final int COMPLETE = -1;

    public byte kind;
    public int version;
    /** CHUNK: this chunk's index. HAVE: leading chunks held, or {@link #COMPLETE}. */
    public int index;
    /** CHUNK: how many chunks the pack is. Unused by HAVE. */
    public int total;
    /** CHUNK: the bytes. Empty for HAVE. */
    public byte[] data = new byte[0];

    public PacketCosmeticAssets() {}

    public static PacketCosmeticAssets chunk(int version, int index, int total, byte[] data)
    {
        PacketCosmeticAssets p = new PacketCosmeticAssets();
        p.kind = CHUNK;
        p.version = version;
        p.index = index;
        p.total = total;
        p.data = data == null ? new byte[0] : data;
        return p;
    }

    public static PacketCosmeticAssets have(int version, int haveChunks)
    {
        PacketCosmeticAssets p = new PacketCosmeticAssets();
        p.kind = HAVE;
        p.version = version;
        p.index = haveChunks;
        return p;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeByte(kind);
        buf.writeInt(version);
        if (kind == CHUNK)
        {
            buf.writeVarInt(index);
            buf.writeVarInt(total);
            buf.writeByteArray(data);
        }
        else
        {
            buf.writeVarInt(index + 1);   // shifted so COMPLETE (-1) stays a legal varint
        }
    }

    public static PacketCosmeticAssets decode(FriendlyByteBuf buf)
    {
        PacketCosmeticAssets p = new PacketCosmeticAssets();
        p.kind = buf.readByte();
        p.version = buf.readInt();
        if (p.kind == CHUNK)
        {
            p.index = buf.readVarInt();
            p.total = buf.readVarInt();
            p.data = buf.readByteArray();
        }
        else
        {
            p.index = buf.readVarInt() - 1;
        }
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        if (context.getDirection().getReceptionSide() == LogicalSide.SERVER)
        {
            if (kind != HAVE)
                return;
            ServerPlayer player = context.getSender();
            if (player != null)
                CosmeticHooks.get().assetReport(player, version, index);
            return;
        }
        if (kind != CHUNK)
            return;
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
        {
            net.shurui.shuruisutilities.cosmetics.wardrobe.client.CosmeticAssetCache.accept(version, index, total, data);
            // The server holds the next chunk back until this arrives (acknowledgement pacing, as rgnpc).
            net.shurui.shuruisutilities.cosmetics.wardrobe.client.CosmeticAssetCache.acknowledge();
        });
    }

    public static void handler(final PacketCosmeticAssets message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
