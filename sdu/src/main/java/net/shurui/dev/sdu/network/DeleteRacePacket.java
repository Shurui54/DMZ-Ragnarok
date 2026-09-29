package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.race.RaceFileManager;

import java.util.function.Supplier;

/** Client -> server. Deletes a custom race folder. Op-gated; refuses DMZ's built-in races. */
public class DeleteRacePacket {

    private final String raceId;

    public DeleteRacePacket(String raceId) {
        this.raceId = raceId;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(raceId);
    }

    public static DeleteRacePacket decode(FriendlyByteBuf buf) {
        return new DeleteRacePacket(buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || player.getServer() == null || !net.shurui.dev.sdu.util.SduPerms.canEdit(player)) {
                return;
            }
            if (isDefaultRace(raceId)) {
                player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.race.delete_builtin", raceId), false);
                return;
            }
            RaceFileManager.delete(raceId);
            boolean resynced = net.shurui.dev.sdu.compat.DmzCompat.resyncConfigsToAll(player.getServer());
            player.displayClientMessage(Component.translatable(resynced
                    ? "message.dmz_ragnarok.npc.race.deleted.synced"
                    : "message.dmz_ragnarok.npc.race.deleted.rejoin", raceId), false);
        });
        context.setPacketHandled(true);
    }

    private static boolean isDefaultRace(String id) {
        try {
            return com.dragonminez.common.config.ConfigManager.isDefaultRace(id);
        } catch (Throwable t) {
            return false;
        }
    }
}
