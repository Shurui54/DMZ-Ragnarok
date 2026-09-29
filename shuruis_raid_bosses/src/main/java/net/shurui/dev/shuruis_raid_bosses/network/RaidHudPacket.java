package net.shurui.dev.shuruis_raid_bosses.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * S2C: state of the raid progress bar ({@code RaidHudOverlay}, the DMZ ki-sense styled bar that replaced
 * the vanilla boss bar). Carries remaining NPC count during enemy waves, boss health once a single boss
 * is the fight. {@code active=false} hides the bar (raid over / cancelled).
 */
public class RaidHudPacket {

    public static final int MODE_NPC_COUNT = 0;
    public static final int MODE_BOSS_HP = 1;

    private final boolean active;
    private final int mode;
    private final String label;
    private final float current;
    private final float max;

    public RaidHudPacket(boolean active, int mode, String label, float current, float max) {
        this.active = active;
        this.mode = mode;
        this.label = label == null ? "" : label;
        this.current = current;
        this.max = max;
    }

    public static RaidHudPacket clear() {
        return new RaidHudPacket(false, MODE_NPC_COUNT, "", 0, 0);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeVarInt(mode);
        buf.writeUtf(label);
        buf.writeFloat(current);
        buf.writeFloat(max);
    }

    public static RaidHudPacket decode(FriendlyByteBuf buf) {
        boolean active = buf.readBoolean();
        int mode = buf.readVarInt();
        String label = buf.readUtf();
        float current = buf.readFloat();
        float max = buf.readFloat();
        return new RaidHudPacket(active, mode, label, current, max);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.dev.shuruis_raid_bosses.client.RaidHudState.set(active, mode, label, current, max)));
        ctx.get().setPacketHandled(true);
    }
}
