package net.shurui.shuruisutilities.prestige;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/** Client -&gt; server: the player pressed the "Prestige" button. Server validates and prestiges, then refreshes. */
public class PacketPrestigeAction implements ISUPacket
{
    public String action = "";

    public PacketPrestigeAction() {}

    public PacketPrestigeAction(String action)
    {
        this.action = action == null ? "" : action;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(action);
    }

    public static PacketPrestigeAction decode(FriendlyByteBuf buf)
    {
        return new PacketPrestigeAction(buf.readUtf());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // S19b: the handler body is private (prestige is keyed, owner Q1) and lives in the Ragnarok Key. Keyless the
        // hook ignores the packet.
        ServerPlayer sender = context.getSender();
        if (sender != null)
            net.shurui.shuruisutilities.api.key.PrestigeHooks.get().onAction(sender, action);
    }

    public static void handler(final PacketPrestigeAction message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
