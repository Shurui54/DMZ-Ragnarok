package net.shurui.dev.shuruis_dmz_tournaments.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** S2C. Opens the editor hub menu. */
public class OpenHubPacket {

    public OpenHubPacket() {
    }

    public void encode(FriendlyByteBuf buf) {
    }

    public static OpenHubPacket decode(FriendlyByteBuf buf) {
        return new OpenHubPacket();
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> net.shurui.dev.shuruis_dmz_tournaments.client.gui.HubScreen::open));
        ctx.get().setPacketHandled(true);
    }
}
