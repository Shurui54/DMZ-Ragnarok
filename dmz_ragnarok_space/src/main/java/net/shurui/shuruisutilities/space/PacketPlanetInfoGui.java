package net.shurui.shuruisutilities.space;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: a built {@link PlanetInfoView} for the planet-info overlay. The client sink is referenced only
 * through {@link DistExecutor} so it never classloads on a dedicated server.
 */
public class PacketPlanetInfoGui implements ISUPacket
{
    public PlanetInfoView view = new PlanetInfoView();

    public PacketPlanetInfoGui()
    {
    }

    public PacketPlanetInfoGui(PlanetInfoView view)
    {
        this.view = view;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        view.write(buf);
    }

    public static PacketPlanetInfoGui decode(FriendlyByteBuf buf)
    {
        PacketPlanetInfoGui p = new PacketPlanetInfoGui();
        p.view = PlanetInfoView.read(buf);
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.client.space.PlanetInfoClient.accept(this));
    }

    public static void handler(final PacketPlanetInfoGui message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
