package net.shurui.shuruisutilities.commons.network.packets;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

public class Packet00Handshake implements ISUPacket
{

    public static Packet00Handshake decode(FriendlyByteBuf buf)
    {
        return new Packet00Handshake();
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
    }

    @Override
    public void handle(NetworkEvent.Context context){
        NetworkUtils.handleNotHandled(this);
    }

    public static void handler(final Packet00Handshake message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
