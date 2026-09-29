package net.shurui.shuruisutilities.combat;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;

/**
 * client -> server: "I double tapped sprint while flying."
 *
 * <p>Carries nothing. The gesture is the whole message and every condition on it - actually flying, flight maxed,
 * the ki and stamina to pay, not already mid-crash - is checked on the server, because a client that can ask for a
 * free one by lying is a client that will.
 */
public class PacketSonicBoom implements ISUPacket
{
    public PacketSonicBoom() {}

    @Override
    public void encode(FriendlyByteBuf buf)
    {
    }

    public static PacketSonicBoom decode(FriendlyByteBuf buf)
    {
        return new PacketSonicBoom();
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player != null)
            SonicBoomService.request(player);
    }

    public static void handler(final PacketSonicBoom message, Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
