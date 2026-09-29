package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> client. Opens the addon's main editor hub menu on the player's screen. */
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
                () -> net.shurui.dev.sdu.client.gui.SduHubScreen::open));
        ctx.get().setPacketHandled(true);
    }
}
