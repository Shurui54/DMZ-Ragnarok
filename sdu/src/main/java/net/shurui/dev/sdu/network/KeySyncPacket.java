package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -&gt; client, on login. Tells the client whether the server has Shurui's Key installed, so
 * client-driven, key-gated features (the form preview) can gate themselves on a dedicated server without
 * the client needing the (server-side-only) key mod.
 */
public class KeySyncPacket {

    private final boolean present;

    public KeySyncPacket(boolean present) {
        this.present = present;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(present);
    }

    public static KeySyncPacket decode(FriendlyByteBuf buf) {
        return new KeySyncPacket(buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.dev.sdu.api.ClientGate.set(present)));
        context.setPacketHandled(true);
    }
}
