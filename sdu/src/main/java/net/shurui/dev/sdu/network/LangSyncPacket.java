package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server -> client. The server's generated race/form/skill display names+descriptions, pushed on login
 * and after any edit, so every client overlays them onto the active language and sees the custom names.
 */
public class LangSyncPacket {

    private final Map<String, String> entries;

    public LangSyncPacket(Map<String, String> entries) {
        this.entries = entries == null ? Map.of() : entries;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Map.Entry<String, String> e : entries.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue());
        }
    }

    public static LangSyncPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<String, String> map = new LinkedHashMap<>(Math.max(16, n));
        for (int i = 0; i < n; i++) {
            String k = buf.readUtf();
            map.put(k, buf.readUtf());
        }
        return new LangSyncPacket(map);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.dev.sdu.client.GeneratedLang.applySynced(entries)));
        context.setPacketHandled(true);
    }
}
