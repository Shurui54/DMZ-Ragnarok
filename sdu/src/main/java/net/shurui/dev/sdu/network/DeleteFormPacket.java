package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.form.FormFileManager;

import java.util.function.Supplier;

/** Client -> server. Deletes a form group's config file. Op-gated. */
public class DeleteFormPacket {

    private final String ownerRace;
    private final String groupName;

    public DeleteFormPacket(String ownerRace, String groupName) {
        this.ownerRace = ownerRace;
        this.groupName = groupName;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(ownerRace);
        buf.writeUtf(groupName);
    }

    public static DeleteFormPacket decode(FriendlyByteBuf buf) {
        return new DeleteFormPacket(buf.readUtf(), buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || player.getServer() == null || !net.shurui.dev.sdu.util.SduPerms.canEdit(player)) {
                return;
            }
            FormFileManager.delete(ownerRace, groupName);
            // Purge the group from online players' UsedForms now (offline handled by login scrub + tombstone).
            // delete() sanitizes the group name for the tombstone/UsedForms key, so scrub sanitized too.
            net.shurui.dev.sdu.form.FormTombstoneScrub.scrubGroupOnline(
                    player.getServer(), net.shurui.dev.sdu.util.SduIds.sanitize(groupName));
            boolean resynced = net.shurui.dev.sdu.compat.DmzCompat.resyncConfigsToAll(player.getServer());
            player.displayClientMessage(Component.translatable(resynced
                    ? "message.dmz_ragnarok.npc.form.deleted.synced"
                    : "message.dmz_ragnarok.npc.form.deleted.rejoin", groupName), false);
        });
        context.setPacketHandled(true);
    }
}
