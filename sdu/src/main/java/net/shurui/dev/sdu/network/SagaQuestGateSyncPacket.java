package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.saga.SagaQuestGateConfig;
import net.shurui.dev.sdu.saga.SagaQuestGateConfig.Gate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server -> client. The server's quest-gated saga-unlock map ({@link SagaQuestGateConfig}), pushed on login
 * and after any gate edit. On a dedicated server the client has no {@code config/sdu/saga_quest_gates.json},
 * so without this the quest-tree mixin cannot show a saga as quest-locked. Each entry travels as
 * {@code sagaId / gateSaga / gateQuest}.
 */
public class SagaQuestGateSyncPacket {

    private final Map<String, Gate> entries;

    public SagaQuestGateSyncPacket(Map<String, Gate> entries) {
        this.entries = entries == null ? Map.of() : entries;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Map.Entry<String, Gate> e : entries.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue().gateSaga == null ? "" : e.getValue().gateSaga);
            buf.writeVarInt(e.getValue().gateQuest);
        }
    }

    public static SagaQuestGateSyncPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<String, Gate> map = new LinkedHashMap<>(Math.max(16, n));
        for (int i = 0; i < n; i++) {
            String sagaId = buf.readUtf();
            String gateSaga = buf.readUtf();
            int gateQuest = buf.readVarInt();
            map.put(sagaId, new Gate(gateSaga, gateQuest));
        }
        return new SagaQuestGateSyncPacket(map);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> SagaQuestGateConfig.applySynced(entries)));
        context.setPacketHandled(true);
    }
}
