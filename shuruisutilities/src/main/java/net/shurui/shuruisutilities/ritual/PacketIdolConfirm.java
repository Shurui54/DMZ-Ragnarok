package net.shurui.shuruisutilities.ritual;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;

/**
 * client -> server: the player pressed confirm on the idol.
 *
 * <p>Carries nothing. Everything the ritual needs is read from the sender on the server, and there is nothing here a
 * crafted packet could claim: it can ask to perform the ritual, and the ritual decides for itself whether that is
 * allowed. Sending it without an idol, without the level, or as the wrong race simply gets a refusal.
 */
public class PacketIdolConfirm implements ISUPacket
{
    public PacketIdolConfirm() {}

    @Override
    public void encode(FriendlyByteBuf buf)
    {
    }

    public static PacketIdolConfirm decode(FriendlyByteBuf buf)
    {
        return new PacketIdolConfirm();
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player != null)
            net.shurui.shuruisutilities.api.key.RitualHooks.get().confirmRecreation(player); // keyless: refused
    }

    public static void handler(final PacketIdolConfirm message, Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
