package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -&gt; server. One chunk of a large editor payload (saga, side quest, form, race, wish, dragon or lang),
 * GZIP'd and sliced by {@link DmzNet#sendLargeToServer(String, String)}, reassembled in
 * {@link ChunkedSaveBuffer}. Chunked because a single C2S payload may not exceed the 32767-byte wire limit.
 * Op-gated on every chunk.
 */
public class ChunkedSavePacket {

    private final String kind;
    private final int index;
    private final int total;
    private final byte[] data;

    public ChunkedSavePacket(String kind, int index, int total, byte[] data) {
        this.kind = kind;
        this.index = index;
        this.total = total;
        this.data = data == null ? new byte[0] : data;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(kind, 64);
        buf.writeVarInt(index);
        buf.writeVarInt(total);
        buf.writeByteArray(data);
    }

    public static ChunkedSavePacket decode(FriendlyByteBuf buf) {
        String kind = buf.readUtf(64);
        int index = buf.readVarInt();
        int total = buf.readVarInt();
        byte[] data = buf.readByteArray();
        return new ChunkedSavePacket(kind, index, total, data);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            // Op-gate on EVERY chunk, before buffering.
            if (player == null || player.getServer() == null || !net.shurui.dev.sdu.util.SduPerms.canEdit(player)) {
                return;
            }
            ChunkedSaveBuffer.accept(player, kind, index, total, data);
        });
        context.setPacketHandled(true);
    }
}
