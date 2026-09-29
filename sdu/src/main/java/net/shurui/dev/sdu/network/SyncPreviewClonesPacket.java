package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.compat.cnpc.CnpcPreviewConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * S2C: per-quest saved-NPC appearances ({@code questKey -> KILL-ordered clone configs}), stored client-side in
 * {@link net.shurui.dev.sdu.client.ClientPreviewClones} so the DMZ quest-tree GUI can render its enemy preview as
 * the real Custom NPC. Sent on login and after a saga/side-quest edit. See
 * {@link net.shurui.dev.sdu.saga.QuestPreviewResolver}.
 */
public class SyncPreviewClonesPacket {

    private final Map<String, List<CnpcPreviewConfig>> map;

    public SyncPreviewClonesPacket(Map<String, List<CnpcPreviewConfig>> map) {
        this.map = map;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(map.size());
        for (Map.Entry<String, List<CnpcPreviewConfig>> e : map.entrySet()) {
            buf.writeUtf(e.getKey());
            List<CnpcPreviewConfig> list = e.getValue();
            buf.writeVarInt(list.size());
            for (CnpcPreviewConfig c : list) {
                buf.writeUtf(c.name() == null ? "" : c.name());
                buf.writeUtf(c.modelGeo() == null ? "" : c.modelGeo());
                buf.writeVarInt(c.skinType());
                buf.writeUtf(c.skinValue() == null ? "" : c.skinValue());
                buf.writeUtf(c.hairCode() == null ? "" : c.hairCode());
                buf.writeUtf(c.hairColor() == null ? "" : c.hairColor());
            }
        }
    }

    public static SyncPreviewClonesPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<String, List<CnpcPreviewConfig>> map = new LinkedHashMap<>(Math.max(4, n));
        for (int i = 0; i < n; i++) {
            String key = buf.readUtf();
            int m = buf.readVarInt();
            List<CnpcPreviewConfig> list = new ArrayList<>(m);
            for (int j = 0; j < m; j++) {
                list.add(new CnpcPreviewConfig(buf.readUtf(), buf.readUtf(), buf.readVarInt(),
                        buf.readUtf(), buf.readUtf(), buf.readUtf()));
            }
            map.put(key, list);
        }
        return new SyncPreviewClonesPacket(map);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.dev.sdu.client.ClientPreviewClones.set(map)));
        ctx.get().setPacketHandled(true);
    }
}
