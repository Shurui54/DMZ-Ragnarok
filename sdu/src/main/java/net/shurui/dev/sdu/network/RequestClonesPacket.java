package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.shurui.dev.sdu.compat.cnpc.DmzCnpcCompat;

import java.util.List;
import java.util.function.Supplier;

/**
 * C2S: the saga/quest editor asks the server for the list of saved Custom NPC clones (to populate the
 * "Saved NPC" picker). The server reads them (behind the CNPC gate) and replies with a {@link SyncClonesPacket}.
 */
public class RequestClonesPacket {

    public RequestClonesPacket() {
    }

    public void encode(FriendlyByteBuf buf) {
    }

    public static RequestClonesPacket decode(FriendlyByteBuf buf) {
        return new RequestClonesPacket();
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !player.hasPermissions(2)) {
                return;
            }
            List<String> tokens = DmzCnpcCompat.cnpcAvailable()
                    ? net.shurui.dev.sdu.compat.cnpc.CnpcCloneSpawner.listCloneTokens()
                    : List.of();
            DmzNet.channel().send(PacketDistributor.PLAYER.with(() -> player), new SyncClonesPacket(tokens));
        });
        context.setPacketHandled(true);
    }
}
