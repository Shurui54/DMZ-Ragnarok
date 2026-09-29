package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.event.RacialSkillHandler;

import java.util.function.Supplier;

/** Client -> server. The player pressed the racial keybind; try to activate their ACTIVE racial. */
public class ActivateRacialPacket {

    public ActivateRacialPacket() {
    }

    public void encode(FriendlyByteBuf buf) {
    }

    public static ActivateRacialPacket decode(FriendlyByteBuf buf) {
        return new ActivateRacialPacket();
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                RacialSkillHandler.activateFromKey(player);
            }
        });
        context.setPacketHandled(true);
    }
}
