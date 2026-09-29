package net.shurui.dev.shuruis_dmz_tournaments.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_dmz_tournaments.data.TournamentData;
import net.shurui.dev.shuruis_dmz_tournaments.util.TextUtil;

import java.util.function.Supplier;

/** Client -&gt; server. Delete a tournament definition (op only). */
public class DeleteDefPacket {
    private final String id;

    public DeleteDefPacket(String id) {
        this.id = id;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(id);
    }

    public static DeleteDefPacket decode(FriendlyByteBuf buf) {
        return new DeleteDefPacket(buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp == null || !sp.hasPermissions(2)) return;
            TournamentData.get(sp.getServer()).removeDef(id);
            sp.sendSystemMessage(TextUtil.color("&eDeleted tournament '" + id + "'."));
        });
        ctx.get().setPacketHandled(true);
    }
}
