package net.shurui.dev.shuruis_dmz_tournaments.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_dmz_tournaments.entity.TournamentNpcs;

import java.util.function.Supplier;

/** C2S, on picking a tournament from the NPC browser. Server replies with a fresh {@link OpenSignupPacket}. */
public class OpenSignupRequestPacket {
    private final String defId;

    public OpenSignupRequestPacket(String defId) {
        this.defId = defId;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(defId);
    }

    public static OpenSignupRequestPacket decode(FriendlyByteBuf buf) {
        return new OpenSignupRequestPacket(buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp != null) TournamentNpcs.openSignup(sp, defId);
        });
        ctx.get().setPacketHandled(true);
    }
}
