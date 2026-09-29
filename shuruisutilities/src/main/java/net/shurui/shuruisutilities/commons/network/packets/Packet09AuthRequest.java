package net.shurui.shuruisutilities.commons.network.packets;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

public class Packet09AuthRequest implements ISUPacket
{
    /*
     * request to put hash in client keystore
     */
    public String hash;

    public Packet09AuthRequest(String hash)
    {
        this.hash = hash;
    }

    public static Packet09AuthRequest decode(FriendlyByteBuf buf)
    {
        return new Packet09AuthRequest(buf.readUtf());
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

    public static void handler(final Packet09AuthRequest message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}