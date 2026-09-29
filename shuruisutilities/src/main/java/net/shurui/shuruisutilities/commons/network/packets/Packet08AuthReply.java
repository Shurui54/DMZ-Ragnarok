package net.shurui.shuruisutilities.commons.network.packets;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

public class Packet08AuthReply implements ISUPacket
{
    /*
     * reply from client with hash (empty if client does not have hash)
     */
    public String hash;

    public Packet08AuthReply(String hash)
    {
        this.hash = hash;
    }

    public static Packet08AuthReply decode(FriendlyByteBuf buf)
    {
        return new Packet08AuthReply(buf.readUtf());
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(hash);
    }

    @Override
    public void handle(NetworkEvent.Context context){
        NetworkUtils.handleNotHandled(this);
    }

    public static void handler(final Packet08AuthReply message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}