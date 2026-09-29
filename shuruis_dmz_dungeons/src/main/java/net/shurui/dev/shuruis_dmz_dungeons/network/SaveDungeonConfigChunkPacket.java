package net.shurui.dev.shuruis_dmz_dungeons.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

// C2S: the dungeon config save (rules + the whole ordered floor list), split into ordered byte frames so the
// payload can never exceed the vanilla ServerboundCustomPayloadPacket ceiling (32767 bytes, enforced on the
// SERVER's decode) no matter how many floors it carries.
//
// SaveDungeonConfigPacket used to write every floor into ONE payload; a handful of richly configured floors
// (two SpawnerConfigs plus a weighted enemy-preset list, each able to hold a transform chain) crosses 32767
// bytes and the server drops the connection on decode (client sees Connection Lost). Its S2C twin
// (OpenDungeonConfigPacket) has the 1 MB clientbound ceiling instead, which is why the GUI opened but the save
// kicked. The rolled ROOM LAYOUT never travels here (server-side SavedData); this packet grows with floor COUNT
// and per-floor config size.
//
// The client serialises one SaveDungeonConfigPacket body with encode() and sends CHUNK_BYTES-sized frames; the
// server reassembles per sender (the channel is TCP-ordered) and, on the final frame, decodes with
// SaveDungeonConfigPacket.decode and applies via SaveDungeonConfigPacket.applyServer. Mirrors, the other way,
// the chunked transfer PacketRgNpcAssets / RgNpcAssetServer use for the model pack.
public class SaveDungeonConfigChunkPacket {

    // stays under the 32767 ceiling with headroom for the channel ResourceLocation, the SimpleChannel
    // discriminator and the three VarInts written before the byte array.
    public static final int CHUNK_BYTES = 30000;

    // hard cap on a reassembled save so a hostile client cannot pin server memory with an endless stream. A
    // 256-floor save sits far below this; a backstop only.
    private static final int MAX_TOTAL_BYTES = 4 * 1024 * 1024;

    // per-sender reassembly buffers. Touched only on the server thread (enqueueWork + logout cleanup), so a
    // plain HashMap is safe unsynchronised.
    private static final Map<UUID, byte[]> PENDING = new HashMap<>();
    private static final Map<UUID, Integer> PENDING_LEN = new HashMap<>();

    private final int seq;         // 0-based frame index
    private final int totalChunks; // number of frames in this save
    private final int totalBytes;  // length of the reassembled body
    private final byte[] chunk;

    public SaveDungeonConfigChunkPacket(int seq, int totalChunks, int totalBytes, byte[] chunk) {
        this.seq = seq;
        this.totalChunks = totalChunks;
        this.totalBytes = totalBytes;
        this.chunk = chunk;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(seq);
        buf.writeVarInt(totalChunks);
        buf.writeVarInt(totalBytes);
        buf.writeByteArray(chunk);
    }

    public static SaveDungeonConfigChunkPacket decode(FriendlyByteBuf buf) {
        int seq = buf.readVarInt();
        int totalChunks = buf.readVarInt();
        int totalBytes = buf.readVarInt();
        byte[] chunk = buf.readByteArray();
        return new SaveDungeonConfigChunkPacket(seq, totalChunks, totalBytes, chunk);
    }

    // drop any half-received save for a player who left mid-transfer, so a dropped stream never lingers in memory.
    public static void forget(UUID id) {
        PENDING.remove(id);
        PENDING_LEN.remove(id);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !player.hasPermissions(2)) {
                return;
            }
            UUID id = player.getUUID();
            // A malformed or oversized header aborts the whole transfer for this sender.
            if (totalBytes < 0 || totalBytes > MAX_TOTAL_BYTES || totalChunks <= 0
                    || seq < 0 || seq >= totalChunks) {
                forget(id);
                return;
            }
            byte[] buffer;
            if (seq == 0) {
                // a fresh save: start (or restart) this sender's buffer.
                buffer = new byte[totalBytes];
                PENDING.put(id, buffer);
                PENDING_LEN.put(id, 0);
            } else {
                buffer = PENDING.get(id);
                // out-of-order frame, or a header that disagrees with the run in progress: drop and wait for a resave.
                if (buffer == null || buffer.length != totalBytes) {
                    forget(id);
                    return;
                }
            }
            int at = PENDING_LEN.getOrDefault(id, 0);
            if (chunk.length == 0 || at + chunk.length > totalBytes) {
                forget(id);
                return;
            }
            System.arraycopy(chunk, 0, buffer, at, chunk.length);
            at += chunk.length;
            PENDING_LEN.put(id, at);

            if (seq == totalChunks - 1) {
                forget(id);
                if (at != totalBytes) {
                    return; // a frame went missing: ignore the incomplete save rather than decode garbage.
                }
                try {
                    FriendlyByteBuf full = new FriendlyByteBuf(Unpooled.wrappedBuffer(buffer));
                    SaveDungeonConfigPacket packet = SaveDungeonConfigPacket.decode(full);
                    SaveDungeonConfigPacket.applyServer(player, packet);
                } catch (Exception e) {
                    Shuruis_dmz_dungeons.LOGGER.warn("[{}] Failed to apply chunked dungeon config save: {}",
                            Shuruis_dmz_dungeons.MODID, e.toString());
                }
            }
        });
        context.setPacketHandled(true);
    }
}
