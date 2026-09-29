package net.shurui.dev.sdu.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.form.FormAuraConfig;
import net.shurui.dev.sdu.form.FormAuraData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server -> client. The server's per-form extra aura layers ({@link FormAuraConfig}), pushed on login and
 * after a form edit. On a dedicated server the client has no {@code config/sdu/form_auras.json}, so without
 * this {@code AuraRendererMixin} sees no layers and multi-layer auras don't render. Each form's aura data
 * travels as its JSON.
 */
public class FormAuraSyncPacket {

    private final Map<String, FormAuraData> entries;

    public FormAuraSyncPacket(Map<String, FormAuraData> entries) {
        this.entries = entries == null ? Map.of() : entries;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Map.Entry<String, FormAuraData> e : entries.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue().toJson().toString());
        }
    }

    public static FormAuraSyncPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<String, FormAuraData> map = new LinkedHashMap<>(Math.max(16, n));
        for (int i = 0; i < n; i++) {
            String name = buf.readUtf();
            String json = buf.readUtf();
            try {
                JsonObject o = JsonParser.parseString(json).getAsJsonObject();
                map.put(name, FormAuraData.fromJson(o));
            } catch (Exception ignored) {
                // Skip a malformed entry rather than drop the whole sync.
            }
        }
        return new FormAuraSyncPacket(map);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> FormAuraConfig.applySynced(entries)));
        context.setPacketHandled(true);
    }
}
