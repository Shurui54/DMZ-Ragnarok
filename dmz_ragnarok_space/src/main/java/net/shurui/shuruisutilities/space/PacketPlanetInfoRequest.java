package net.shurui.shuruisutilities.space;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client -&gt; server "refresh me" for the planet-info overlay. Carries NO payload: the SERVER picks the aimed-at body
 * (via {@link GeneratedPlanets#bodyAlongRay}) and builds the authoritative view, so nothing is trusted from the client.
 * Fired only when the aimed-at planet changes or on a once-a-second throttle, never per-frame.
 */
public class PacketPlanetInfoRequest implements ISUPacket
{
    public PacketPlanetInfoRequest()
    {
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
    }

    public static PacketPlanetInfoRequest decode(FriendlyByteBuf buf)
    {
        return new PacketPlanetInfoRequest();
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
        {
            return;
        }
        PlanetInfoServer.push(player);
    }

    public static void handler(final PacketPlanetInfoRequest message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
