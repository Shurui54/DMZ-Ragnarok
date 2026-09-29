package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.form.FormQuestGateConfig;
import net.shurui.dev.sdu.form.FormQuestGateConfig.Gate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server -> client. The server's quest-gated form-purchase map ({@link FormQuestGateConfig}), pushed on
 * login and after a gate edit. On a dedicated server the client has no {@code config/sdu/form_quest_gates.json},
 * so without this {@code SkillsMenuScreenMixin} can't show a mapped form as quest-locked. Each entry travels
 * as {@code formSkill / questId / gateUpgrades}.
 */
public class FormQuestGateSyncPacket {

    private final Map<String, Gate> entries;

    public FormQuestGateSyncPacket(Map<String, Gate> entries) {
        this.entries = entries == null ? Map.of() : entries;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Map.Entry<String, Gate> e : entries.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue().questId == null ? "" : e.getValue().questId);
            buf.writeBoolean(e.getValue().gateUpgrades);
        }
    }

    public static FormQuestGateSyncPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<String, Gate> map = new LinkedHashMap<>(Math.max(16, n));
        for (int i = 0; i < n; i++) {
            String formSkill = buf.readUtf();
            String questId = buf.readUtf();
            boolean gateUpgrades = buf.readBoolean();
            map.put(formSkill, new Gate(questId, gateUpgrades));
        }
        return new FormQuestGateSyncPacket(map);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> FormQuestGateConfig.applySynced(entries)));
        context.setPacketHandled(true);
    }
}
