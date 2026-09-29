package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.saga.SagaFileManager;

import java.util.function.Supplier;

/** Client -> server. Deletes a custom saga's file and quest folder. Op-gated. */
public class DeleteSagaPacket {

    private final String sagaId;
    private final String questFolder;

    public DeleteSagaPacket(String sagaId, String questFolder) {
        this.sagaId = sagaId;
        this.questFolder = questFolder;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sagaId);
        buf.writeUtf(questFolder);
    }

    public static DeleteSagaPacket decode(FriendlyByteBuf buf) {
        return new DeleteSagaPacket(buf.readUtf(), buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || player.getServer() == null || !net.shurui.dev.sdu.util.SduPerms.canEdit(player)) {
                return;
            }
            // Reject a malformed/traversal id server-side before it reaches the file manager.
            if (!net.shurui.dev.sdu.saga.SafeFileNames.isSafeSegment(sagaId)
                    || !net.shurui.dev.sdu.saga.SafeFileNames.isSafeSegment(questFolder)) {
                player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.saga.invalid_id", sagaId), false);
                return;
            }
            SagaFileManager.delete(player.getServer(), sagaId, questFolder);
            // drop the deleted saga out of DMZ's live registry too, or it stays fully playable until a restart:
            // the files are gone but the in-memory copy DMZ spawns and renders from is untouched. See DmzQuestReload.
            net.shurui.dev.sdu.saga.DmzQuestReload.reloadAndSync(player.getServer());
            // Prune any form-quest gate whose quest id no longer resolves after the reload (the saga's quests are
            // gone), or the form stays locked behind a quest that can never be completed. See bug 764.
            pruneDeadFormQuestGates();
            player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.saga.deleted", sagaId), false);
        });
        context.setPacketHandled(true);
    }

    /** Drop form-quest gates that point at a quest id which no longer resolves, then persist and resync clients. */
    static void pruneDeadFormQuestGates() {
        boolean changed = net.shurui.dev.sdu.form.FormQuestGateConfig.removeUnresolved(
                id -> com.dragonminez.common.quest.QuestRegistry.getQuest(id) != null);
        if (changed) {
            net.shurui.dev.sdu.form.FormQuestGateConfig.save();
            net.shurui.dev.sdu.network.DmzNet.syncFormQuestGatesToAll();
        }
    }
}
