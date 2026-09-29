package net.shurui.shuruisutilities.core.misc;

import net.shurui.shuruisutilities.commons.network.packets.Packet00Handshake;
import net.shurui.shuruisutilities.util.PlayerInfo;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent.Context;

public class Packet0HandshakeHandler extends Packet00Handshake
{
    public Packet0HandshakeHandler()
    {
    }

    public static Packet0HandshakeHandler decode(FriendlyByteBuf buf)
    {
        return new Packet0HandshakeHandler();
    }

    @Override
    public void handle(Context context)
    {
        PlayerInfo.get(context.getSender()).setHasSUClient(true);
        // Diagnostic only, and it fires on every join (so once per server switch too). Keep it at debug, which is
        // off unless an operator turns the SU debug flag on, so a normal switch does not print a handshake line.
        LoggingHandler.sulog.debug(Translator.format("Recieved Handshake packet from %s",
                context.getSender().getDisplayName().getString()));
    }
}
