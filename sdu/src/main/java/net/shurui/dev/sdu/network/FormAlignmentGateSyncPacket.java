package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.form.FormAlignmentGateConfig;
import net.shurui.dev.sdu.form.FormAlignmentGateConfig.Bounds;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server -> client. The server's per-form alignment-gate map ({@link FormAlignmentGateConfig}), pushed on login and
 * after a form edit. On a dedicated server the client has no {@code config/sdu/form_alignment_gates.json}, so without
 * this the skills GUI can't show a form as alignment-locked. Each entry travels as {@code group.form} plus its four
 * optional bounds; each bound is a present-flag then, when present, the value.
 */
public class FormAlignmentGateSyncPacket {

    private final Map<String, Bounds> entries;

    public FormAlignmentGateSyncPacket(Map<String, Bounds> entries) {
        this.entries = entries == null ? Map.of() : entries;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Map.Entry<String, Bounds> e : entries.entrySet()) {
            buf.writeUtf(e.getKey());
            Bounds b = e.getValue();
            writeOpt(buf, b == null ? null : b.unlockMin);
            writeOpt(buf, b == null ? null : b.unlockMax);
            writeOpt(buf, b == null ? null : b.useMin);
            writeOpt(buf, b == null ? null : b.useMax);
        }
    }

    private static void writeOpt(FriendlyByteBuf buf, Integer v) {
        buf.writeBoolean(v != null);
        if (v != null) {
            buf.writeVarInt(v);
        }
    }

    private static Integer readOpt(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readVarInt() : null;
    }

    public static FormAlignmentGateSyncPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<String, Bounds> map = new LinkedHashMap<>(Math.max(16, n));
        for (int i = 0; i < n; i++) {
            String key = buf.readUtf();
            Integer unlockMin = readOpt(buf);
            Integer unlockMax = readOpt(buf);
            Integer useMin = readOpt(buf);
            Integer useMax = readOpt(buf);
            map.put(key, new Bounds(unlockMin, unlockMax, useMin, useMax));
        }
        return new FormAlignmentGateSyncPacket(map);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> FormAlignmentGateConfig.applySynced(entries)));
        context.setPacketHandled(true);
    }
}
