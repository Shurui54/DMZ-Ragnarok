package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C: a DMZ NPC's resolved default combat stats for one entity id, cached client-side in
 * {@link net.shurui.dev.sdu.client.ClientNpcDefaults} so the KILL objective editor can auto-fill its
 * Health / Melee Damage / Ki Damage / AI Tier fields. {@code aiTier} is DMZ 1-based (1 = SIMPLE).
 */
public class SyncNpcDefaultsPacket {

    private final String entityId;
    private final double health;
    private final double melee;
    private final double ki;
    private final int aiTier;

    public SyncNpcDefaultsPacket(String entityId, double health, double melee, double ki, int aiTier) {
        this.entityId = entityId == null ? "" : entityId;
        this.health = health;
        this.melee = melee;
        this.ki = ki;
        this.aiTier = aiTier;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(entityId);
        buf.writeDouble(health);
        buf.writeDouble(melee);
        buf.writeDouble(ki);
        buf.writeVarInt(aiTier);
    }

    public static SyncNpcDefaultsPacket decode(FriendlyByteBuf buf) {
        return new SyncNpcDefaultsPacket(buf.readUtf(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> {
                    net.shurui.dev.sdu.client.ClientNpcDefaults.put(entityId, health, melee, ki, aiTier);
                    net.shurui.dev.sdu.client.gui.saga.ObjectiveEditScreen.onNpcDefaultsSynced(entityId);
                }));
        ctx.get().setPacketHandled(true);
    }
}
