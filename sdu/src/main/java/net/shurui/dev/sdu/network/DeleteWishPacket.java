package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.wish.WishFileManager;

import java.util.function.Supplier;

/** Client -> server. Deletes a dragon's wishes file from the world save. Op-gated. */
public class DeleteWishPacket {

    private final String dragon;

    public DeleteWishPacket(String dragon) {
        this.dragon = dragon;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(dragon);
    }

    public static DeleteWishPacket decode(FriendlyByteBuf buf) {
        return new DeleteWishPacket(buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || player.getServer() == null || !net.shurui.dev.sdu.util.SduPerms.canEdit(player)) {
                return;
            }
            WishFileManager.delete(player.getServer(), dragon);
            player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.wish.deleted", dragon), false);
        });
        context.setPacketHandled(true);
    }
}
