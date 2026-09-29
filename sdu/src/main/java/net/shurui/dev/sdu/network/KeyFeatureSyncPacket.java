package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server -&gt; client, on login (and again if the key installs a new feature while players are online). Carries the
 * sorted set of PRIVATE feature ids the server can prove its Ragnarok Key installed this run
 * ({@link DmzNet#clientFeatureIds()}: a marked id whose installed hook answers {@code available()}), so client UI
 * for a private feature can follow the INSTALLED feature, one id at a time.
 *
 * <p>Why this is separate from {@link KeySyncPacket}: that one carries the single "key installed" answer
 * ({@code RagnarokKey.present()}), this one says WHICH private features the key installed, so a screen can follow
 * the one feature it belongs to.
 * Client code reads {@link net.shurui.dev.sdu.api.ClientGate#feature(String)}, never {@code KeyFeatures} directly
 * (the key mod is server-only, so the client would answer wrong).
 */
public class KeyFeatureSyncPacket {

    private final List<String> ids;

    public KeyFeatureSyncPacket(List<String> ids) {
        this.ids = ids;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(ids.size());
        for (String id : ids) {
            buf.writeUtf(id);
        }
    }

    public static KeyFeatureSyncPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(buf.readUtf());
        }
        return new KeyFeatureSyncPacket(ids);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> {
                    net.shurui.dev.sdu.api.ClientGate.setFeatures(ids);
                    net.shurui.dev.sdu.DmzNpc.LOGGER.info("[dmz_ragnarok] client gate features: {}", ids);
                }));
        context.setPacketHandled(true);
    }
}
