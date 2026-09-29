package net.shurui.dev.sdu.network;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.DmzNpc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Server-side reassembler for {@link ChunkedSavePacket}. Fills a per-(player, kind) buffer by index; once every
 * chunk has arrived it GZIP-decompresses, UTF-8 decodes and dispatches the JSON to the matching Save* apply method.
 */
public final class ChunkedSaveBuffer {

    private ChunkedSaveBuffer() {
    }

    private static final Gson GSON = new Gson();
    private static final Type STRING_MAP = new TypeToken<LinkedHashMap<String, String>>() {}.getType();

    /** Reject a stream claiming more than this many chunks (guards against a malicious/huge total). */
    private static final int MAX_CHUNKS = 512;
    /** Reject a stream once its accumulated compressed size exceeds this (8 MB). */
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    /** Discard a partial buffer that has not received a chunk within this window (ms). */
    private static final long STALE_MS = 60_000L;

    private static final Map<Key, Partial> BUFFERS = new ConcurrentHashMap<>();

    private record Key(UUID player, String kind) {
    }

    private static final class Partial {
        final byte[][] chunks;
        final int total;
        int received;
        long accumulated;
        long lastTouched;

        Partial(int total) {
            this.chunks = new byte[total][];
            this.total = total;
            this.lastTouched = System.currentTimeMillis();
        }
    }

    /**
     * Accept one chunk. Caller (packet handler) must already have verified the sender and edit permission.
     * On the final chunk, reassembles and dispatches by {@code kind}.
     */
    public static void accept(ServerPlayer player, String kind, int index, int total, byte[] data) {
        if (data == null) {
            data = new byte[0];
        }
        if (total <= 0 || total > MAX_CHUNKS || index < 0 || index >= total) {
            DmzNpc.LOGGER.warn("[{}] Rejecting chunked save '{}' from {}: bad index/total {}/{}",
                    DmzNpc.MODID, kind, player.getGameProfile().getName(), index, total);
            return;
        }

        evictStale();

        Key key = new Key(player.getUUID(), kind);
        Partial partial = BUFFERS.get(key);
        // A new stream starts on index 0, or when the total changes: reset any prior partial buffer.
        if (partial == null || partial.total != total || (index == 0 && partial.received > 0 && partial.chunks[0] != null)) {
            partial = new Partial(total);
            BUFFERS.put(key, partial);
        }

        if (partial.chunks[index] != null) {
            return; // duplicate chunk
        }
        partial.chunks[index] = data;
        partial.received++;
        partial.accumulated += data.length;
        partial.lastTouched = System.currentTimeMillis();

        if (partial.accumulated > MAX_BYTES) {
            BUFFERS.remove(key);
            DmzNpc.LOGGER.warn("[{}] Discarding oversized chunked save '{}' from {} ({} bytes)",
                    DmzNpc.MODID, kind, player.getGameProfile().getName(), partial.accumulated);
            player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.save.too_large"), false);
            return;
        }

        if (partial.received < partial.total) {
            return;
        }

        BUFFERS.remove(key);

        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        try {
            for (byte[] c : partial.chunks) {
                joined.write(c);
            }
        } catch (Exception e) {
            DmzNpc.LOGGER.warn("[{}] Failed to join chunked save '{}'", DmzNpc.MODID, kind, e);
            return;
        }

        String json;
        try {
            json = gunzipUtf8(joined.toByteArray());
        } catch (Exception e) {
            DmzNpc.LOGGER.warn("[{}] Failed to decompress chunked save '{}'", DmzNpc.MODID, kind, e);
            player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.save.decompress_failed"), false);
            return;
        }

        dispatch(player, kind, json);
    }

    private static void dispatch(ServerPlayer player, String kind, String json) {
        switch (kind) {
            case "saga" -> SaveSagaPacket.apply(player, json);
            case "sidequest" -> SaveSideQuestPacket.apply(player, json);
            case "form" -> SaveFormPacket.apply(player, json);
            case "race" -> SaveRacePacket.apply(player, json);
            case "wish" -> SaveWishPacket.apply(player, json);
            // "dragon" was the custom-dragon pack save; editor and writer are gone. An old client sending one
            // falls through to the unknown-kind warning, which is right: there's nowhere to put it, and accepting
            // it silently would just lose the data.
            case "shrineconfig" -> ShrineConfigSave.apply(player, json);
            case "lang" -> {
                Map<String, String> map = GSON.fromJson(json, STRING_MAP);
                SaveLangPacket.apply(player, map == null ? new LinkedHashMap<>() : map);
            }
            default -> DmzNpc.LOGGER.warn("[{}] Unknown chunked save kind '{}' from {}",
                    DmzNpc.MODID, kind, player.getGameProfile().getName());
        }
    }

    private static String gunzipUtf8(byte[] gz) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static void evictStale() {
        long now = System.currentTimeMillis();
        BUFFERS.entrySet().removeIf(e -> now - e.getValue().lastTouched > STALE_MS);
    }
}
