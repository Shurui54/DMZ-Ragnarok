package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.lang.GeneratedLangStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Client -> server. The editor sends its generated race/form/skill display names+descriptions so the
 * SERVER owns them ({@code config/sdu/generated_lang.json}) and can push them to every client. Op-gated.
 */
public class SaveLangPacket {

    private final Map<String, String> entries;

    public SaveLangPacket(Map<String, String> entries) {
        this.entries = entries == null ? Map.of() : entries;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Map.Entry<String, String> e : entries.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue());
        }
    }

    public static SaveLangPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<String, String> map = new LinkedHashMap<>(Math.max(16, n));
        for (int i = 0; i < n; i++) {
            String k = buf.readUtf();
            map.put(k, buf.readUtf());
        }
        return new SaveLangPacket(map);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || player.getServer() == null || !net.shurui.dev.sdu.util.SduPerms.canEdit(player)) {
                return;
            }
            apply(player, entries);
        });
        context.setPacketHandled(true);
    }

    /** Persist the generated-lang entries on the server and push them to all clients. Caller must have permission-gated. */
    public static void apply(ServerPlayer player, Map<String, String> entries) {
        if (entries == null) {
            return;
        }
        // The editor sends its COMPLETE overlay every save, so this is an authoritative full snapshot: replace the
        // store (dropping keys the client cleared) rather than merge, or an emptied description/name would linger on
        // the server and sync straight back, reverting the clear (bug 780). replaceAll guards against an empty
        // snapshot, so an un-seeded client can never wipe the store.
        GeneratedLangStore.replaceAll(entries);
        DmzNet.syncLangToAll(player.getServer());
    }
}
