package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.shurui.dev.sdu.dmz.DmzNpcDefaults;

import java.util.function.Supplier;

/**
 * C2S: the KILL objective editor asks the server for a DMZ NPC's default combat stats (health / melee / ki /
 * AI tier) for the given entity id. The server resolves them via {@link DmzNpcDefaults} and replies with a
 * {@link SyncNpcDefaultsPacket}. Op-gated (perm level 2), mirroring {@link RequestClonesPacket}.
 */
public class RequestNpcDefaultsPacket {

    private final String entityId;

    public RequestNpcDefaultsPacket(String entityId) {
        this.entityId = entityId == null ? "" : entityId;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(entityId);
    }

    public static RequestNpcDefaultsPacket decode(FriendlyByteBuf buf) {
        return new RequestNpcDefaultsPacket(buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !player.hasPermissions(2)) {
                return;
            }
            DmzNpcDefaults.Defaults d = DmzNpcDefaults.lookup(entityId);
            if (d == null) {
                return; // no DMZ defaults resolve for this id; leave the editor's current values untouched.
            }
            DmzNet.channel().send(PacketDistributor.PLAYER.with(() -> player),
                    new SyncNpcDefaultsPacket(entityId, d.health(), d.melee(), d.ki(), d.aiTier()));
        });
        context.setPacketHandled(true);
    }
}
