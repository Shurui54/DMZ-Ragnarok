package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.form.FormLevelGateConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server -> client. The server's per-form minimum-level map ({@link FormLevelGateConfig}), pushed on login and
 * after a form edit. On a dedicated server the client has no {@code config/sdu/form_level_gates.json}, so
 * without this the skills GUI can't show a form as level-locked. Each entry travels as {@code group.form / minLevel}.
 */
public class FormLevelGateSyncPacket {

    private final Map<String, Integer> entries;

    public FormLevelGateSyncPacket(Map<String, Integer> entries) {
        this.entries = entries == null ? Map.of() : entries;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Map.Entry<String, Integer> e : entries.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeVarInt(e.getValue() == null ? 0 : e.getValue());
        }
    }

    public static FormLevelGateSyncPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<String, Integer> map = new LinkedHashMap<>(Math.max(16, n));
        for (int i = 0; i < n; i++) {
            String key = buf.readUtf();
            int level = buf.readVarInt();
            map.put(key, level);
        }
        return new FormLevelGateSyncPacket(map);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> FormLevelGateConfig.applySynced(entries)));
        context.setPacketHandled(true);
    }
}
