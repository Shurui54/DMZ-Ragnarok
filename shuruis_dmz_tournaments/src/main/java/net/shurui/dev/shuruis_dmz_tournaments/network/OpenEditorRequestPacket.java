package net.shurui.dev.shuruis_dmz_tournaments.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.server.permission.PermissionAPI;
import net.shurui.dev.shuruis_dmz_tournaments.command.Permissions;

import java.util.function.Supplier;

/** C2S. Hub-menu button asks the server to gather data and open one editor. Op-gated. */
public class OpenEditorRequestPacket {

    private final String which;

    public OpenEditorRequestPacket(String which) {
        this.which = which;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(which);
    }

    public static OpenEditorRequestPacket decode(FriendlyByteBuf buf) {
        return new OpenEditorRequestPacket(buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !PermissionAPI.getPermission(player, Permissions.ADMIN)) {
                return;
            }
            switch (which) {
                case "tournament" -> TournamentNet.openTournamentEditor(player);
                default -> {
                }
            }
        });
        context.setPacketHandled(true);
    }
}
