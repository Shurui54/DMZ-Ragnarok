package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.saga.SideQuestFileManager;

import java.util.function.Supplier;

/** Client -> server. Deletes a side quest's file from the world save. Op-gated. */
public class DeleteSideQuestPacket {

    private final String fileName;

    public DeleteSideQuestPacket(String fileName) {
        this.fileName = fileName;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(fileName);
    }

    public static DeleteSideQuestPacket decode(FriendlyByteBuf buf) {
        return new DeleteSideQuestPacket(buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || player.getServer() == null || !net.shurui.dev.sdu.util.SduPerms.canEdit(player)) {
                return;
            }
            // Reject a malformed/traversal path server-side before it reaches the file manager.
            if (!net.shurui.dev.sdu.saga.SafeFileNames.isSafeRelJson(fileName)) {
                player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.sidequest.invalid_file", fileName), false);
                return;
            }
            SideQuestFileManager.delete(player.getServer(), fileName);
            // as with the saga delete: the file is gone but DMZ's in-memory copy is not, so without this the deleted
            // side quest stays live until a restart. See DmzQuestReload.
            net.shurui.dev.sdu.saga.DmzQuestReload.reloadAndSync(player.getServer());
            // As with saga deletion: prune any form-quest gate now pointing at a missing quest. See bug 764.
            DeleteSagaPacket.pruneDeadFormQuestGates();
            player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.sidequest.deleted", fileName), false);
        });
        context.setPacketHandled(true);
    }
}
