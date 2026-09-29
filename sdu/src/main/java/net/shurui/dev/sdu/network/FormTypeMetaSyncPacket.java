package net.shurui.dev.sdu.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.form.FormTypeMeta;
import net.shurui.dev.sdu.form.FormTypeMetaConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server -> client. The server's per-form-type presentation meta ({@link FormTypeMetaConfig}: stock icon +
 * optional tint), pushed on login and after a form-type edit. On a dedicated server the client has no
 * {@code config/dragonminez/sdu_formtype_meta.json}, so without this the radial X-menu
 * ({@code AbstractRadialNodeMixin}) and skills screen ({@code SkillsMenuScreenMixin}) can't resolve a custom
 * type's icon/tint. Each type's meta travels as its JSON.
 */
public class FormTypeMetaSyncPacket {

    private final Map<String, FormTypeMeta> entries;

    public FormTypeMetaSyncPacket(Map<String, FormTypeMeta> entries) {
        this.entries = entries == null ? Map.of() : entries;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Map.Entry<String, FormTypeMeta> e : entries.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue().toJson().toString());
        }
    }

    public static FormTypeMetaSyncPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<String, FormTypeMeta> map = new LinkedHashMap<>(Math.max(16, n));
        for (int i = 0; i < n; i++) {
            String id = buf.readUtf();
            String json = buf.readUtf();
            try {
                JsonObject o = JsonParser.parseString(json).getAsJsonObject();
                map.put(id, FormTypeMeta.fromJson(o));
            } catch (Exception ignored) {
                // Skip a malformed entry rather than drop the whole sync.
            }
        }
        return new FormTypeMetaSyncPacket(map);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.dev.sdu.client.DmzAssets.applySyncedFormTypeMeta(entries)));
        context.setPacketHandled(true);
    }
}
