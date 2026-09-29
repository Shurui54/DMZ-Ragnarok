package net.shurui.dev.sdu.network;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.race.RaceAuraConfig;

/**
 * Server -> client. The server's per-race base-aura size multipliers ({@link RaceAuraConfig}), pushed on
 * login and after a race edit. DMZ's {@code character.json} POJO drops our custom
 * {@code auraWidth}/{@code auraHeight} keys, so without this {@code AuraScaleMixin} can't read a race's
 * base-aura size. Each entry travels as {@code raceId} + two floats.
 */
public class RaceAuraSyncPacket {

    private final Map<String, RaceAuraConfig.Size> entries;

    public RaceAuraSyncPacket(Map<String, RaceAuraConfig.Size> entries) {
        this.entries = entries == null ? Map.of() : entries;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Map.Entry<String, RaceAuraConfig.Size> e : entries.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeFloat(e.getValue().width);
            buf.writeFloat(e.getValue().height);
        }
    }

    public static RaceAuraSyncPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<String, RaceAuraConfig.Size> map = new LinkedHashMap<>(Math.max(16, n));
        for (int i = 0; i < n; i++) {
            String id = buf.readUtf();
            float w = buf.readFloat();
            float h = buf.readFloat();
            map.put(id, new RaceAuraConfig.Size(w, h));
        }
        return new RaceAuraSyncPacket(map);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> RaceAuraConfig.applySynced(entries)));
        context.setPacketHandled(true);
    }
}
