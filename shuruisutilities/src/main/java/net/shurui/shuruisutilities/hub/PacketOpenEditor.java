package net.shurui.shuruisutilities.hub;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client -&gt; server: a hub-menu button (or keybind) asks the server to open one entry. Routing and
 * per-entry permission checks live in {@link HubServer#tryOpen}, so nothing opens that the player is not
 * allowed to see. {@link #which} is the entry id (an admin editor, a player tool, or {@code menu}/{@code
 * menu:admin} to (re)open a hub).
 */
public class PacketOpenEditor implements ISUPacket
{
    public String which = "";

    public PacketOpenEditor() {}

    public PacketOpenEditor(String which)
    {
        this.which = which == null ? "" : which;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(which);
    }

    public static PacketOpenEditor decode(FriendlyByteBuf buf)
    {
        return new PacketOpenEditor(buf.readUtf());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
            return;
        HubServer.tryOpen(player, which);
    }

    public static void handler(final PacketOpenEditor message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
