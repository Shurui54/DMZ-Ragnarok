package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.form.FormTypeRenamer;
import net.shurui.dev.sdu.util.SduIds;
import net.shurui.dev.sdu.util.SduPerms;

import java.util.function.Supplier;

/**
 * Client -> server. Rename a <em>custom</em> form-type id ({@code oldId} -> {@code newId}) everywhere it is
 * stored, preserving player progress. Op-gated; a rejection is a chat message with no side effects.
 *
 * <p>The whole cascade runs server-side in {@link FormTypeRenamer#rename(MinecraftServer, String, String)}.
 * Default DMZ types cannot be renamed, and a collision with any existing id is rejected before anything is
 * written. The client migrates its own radial-ordering store optimistically after sending
 * ({@code RadialOrdering.renameFormTypeOrder}).
 */
public class RenameFormTypePacket {

    private final String oldId;
    private final String newId;

    public RenameFormTypePacket(String oldId, String newId) {
        this.oldId = oldId == null ? "" : oldId;
        this.newId = newId == null ? "" : newId;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(oldId);
        buf.writeUtf(newId);
    }

    public static RenameFormTypePacket decode(FriendlyByteBuf buf) {
        return new RenameFormTypePacket(buf.readUtf(), buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || player.getServer() == null || !SduPerms.canEdit(player)) {
                if (player != null) {
                    player.displayClientMessage(
                            Component.translatable("message.dmz_ragnarok.npc.formtype.rename.no_permission"), false);
                }
                return;
            }
            MinecraftServer server = player.getServer();
            String from = SduIds.sanitize(oldId);
            String to = SduIds.sanitize(newId);
            FormTypeRenamer.Result result = FormTypeRenamer.rename(server, from, to);
            player.displayClientMessage(result.message(), false);
        });
        context.setPacketHandled(true);
    }
}
