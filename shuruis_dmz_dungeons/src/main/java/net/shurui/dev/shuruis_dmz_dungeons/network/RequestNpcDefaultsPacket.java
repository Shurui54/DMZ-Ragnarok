package net.shurui.dev.shuruis_dmz_dungeons.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_dmz_dungeons.Config;
import net.shurui.dev.shuruis_dmz_dungeons.util.DmzNpcDefaults;

import java.util.function.Supplier;

// C2S: spawner editor asks for one entity's DMZ default stats when an admin picks a main/boss entity.
// gated behind canEdit (same as save) so randoms can't use it as an info oracle. server replies with
// SyncNpcDefaultsPacket, or sends nothing if the id has no DMZ defaults (editor keeps what it has).
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
            if (player == null || !Config.canEdit(player)) {
                return;
            }
            DmzNpcDefaults.Defaults d = DmzNpcDefaults.forEntityId(entityId);
            if (d == null) {
                return; // no defaults for this id, leave the client's values alone
            }
            SddNet.sendToPlayer(new SyncNpcDefaultsPacket(
                    entityId, d.health(), d.melee(), d.ki(), d.aiTier1Based()), player);
        });
        context.setPacketHandled(true);
    }
}
