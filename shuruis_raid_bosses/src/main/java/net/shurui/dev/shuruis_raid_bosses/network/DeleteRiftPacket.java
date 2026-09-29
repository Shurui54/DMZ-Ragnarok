package net.shurui.dev.shuruis_raid_bosses.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.dev.shuruis_raid_bosses.rift.RiftDefs;

/** Client -&gt; server. Delete a rift (op only). Open tears of that rift are closed with it. */
public class DeleteRiftPacket {

    private final String id;

    public DeleteRiftPacket(String id) {
        this.id = id;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(id);
    }

    public static DeleteRiftPacket decode(FriendlyByteBuf buf) {
        return new DeleteRiftPacket(buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp == null || !sp.hasPermissions(2)) {
                return;
            }
            RiftDefs.get(sp.getServer()).removeDef(id);
            // Hand the arena cell back, or a world churning through rifts exhausts cells hoarded by deleted ones.
            net.shurui.dev.shuruis_raid_bosses.rift.RiftArenaData.get(sp.getServer()).releaseCell(id);
            // Forget its wait and close its tears (a tear whose rift is gone leads nowhere). Keyless: none exist.
            net.shurui.dev.shuruis_raid_bosses.api.key.RaidKeyHooks.get().riftDeleted(id);
            sp.sendSystemMessage(Component.translatable("editor.dmz_ragnarok.rift.delete.ok", id));
        });
        ctx.get().setPacketHandled(true);
    }
}
