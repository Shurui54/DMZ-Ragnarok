package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client. Opens the {@code ShrineConfigScreen} carrying the entire Shenron-shrine config as one
 * JSON bundle ({@code {"wishes":[...],"colors":{"blue":{...},...}}}). The config is server-side state read
 * live at summon time, so the client is seeded from the server's live values here rather than a local file.
 */
public class OpenShrineConfigPacket {

    private final String bundle;

    public OpenShrineConfigPacket(String bundle) {
        this.bundle = bundle;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(bundle, 1_000_000);
    }

    public static OpenShrineConfigPacket decode(FriendlyByteBuf buf) {
        return new OpenShrineConfigPacket(buf.readUtf(1_000_000));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.dev.sdu.client.gui.shenron.ShrineConfigScreen.open(bundle)));
        context.setPacketHandled(true);
    }
}
