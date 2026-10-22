package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.util.SduPerms;

import java.util.function.Supplier;

/**
 * C2S: the {@link net.shurui.dev.sdu.client.gui.SduHubScreen} asks the server to open one of the built-in
 * editors ({@code race}/{@code form}/{@code saga}/{@code sidequest}/{@code wish}); the server loads that
 * editor's data and sends the matching {@code Open*EditorPacket} back.
 */
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
            if (!SduPerms.canEdit(player)) {
                return;
            }
            DmzNet.openEditorFor(player, which);
        });
        context.setPacketHandled(true);
    }
}
