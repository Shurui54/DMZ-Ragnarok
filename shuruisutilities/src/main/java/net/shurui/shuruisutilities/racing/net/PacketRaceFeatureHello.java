package net.shurui.shuruisutilities.racing.net;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Server -&gt; client (fixed id 108): the racing session hello. Its original job (announce the feature) is
 * superseded by the KeyFeature login sync ({@code ClientGate.feature("racing")}); it is kept registered so the
 * wire id is pinned and reused as a lightweight "a session you can see is active / gone" toggle. The client sink
 * ignores it unless the racing feature is synced.
 */
public class PacketRaceFeatureHello implements ISUPacket
{
    public boolean sessionActive;

    public PacketRaceFeatureHello() {}

    public PacketRaceFeatureHello(boolean sessionActive)
    {
        this.sessionActive = sessionActive;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(sessionActive);
    }

    public static PacketRaceFeatureHello decode(FriendlyByteBuf buf)
    {
        return new PacketRaceFeatureHello(buf.readBoolean());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.racing.client.RaceClientState.onHello(this));
    }

    public static void handler(final PacketRaceFeatureHello message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
