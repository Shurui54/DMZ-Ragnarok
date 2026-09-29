package net.shurui.dev.shuruis_dmz_dungeons.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// S2C reply to RequestNpcDefaultsPacket: entity id + its DMZ default health/melee/ki/AI-tier.
// AI tier is DMZ's 1-based value. client stashes it in ClientNpcDefaults for the open editor to read.
public class SyncNpcDefaultsPacket {

    private final String entityId;
    private final double health;
    private final double melee;
    private final double ki;
    private final int aiTier1Based;

    public SyncNpcDefaultsPacket(String entityId, double health, double melee, double ki, int aiTier1Based) {
        this.entityId = entityId == null ? "" : entityId;
        this.health = health;
        this.melee = melee;
        this.ki = ki;
        this.aiTier1Based = aiTier1Based;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(entityId);
        buf.writeDouble(health);
        buf.writeDouble(melee);
        buf.writeDouble(ki);
        buf.writeInt(aiTier1Based);
    }

    public static SyncNpcDefaultsPacket decode(FriendlyByteBuf buf) {
        return new SyncNpcDefaultsPacket(buf.readUtf(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        net.shurui.dev.shuruis_dmz_dungeons.client.ClientNpcDefaults.put(
                                entityId, health, melee, ki, aiTier1Based)));
        ctx.get().setPacketHandled(true);
    }
}
