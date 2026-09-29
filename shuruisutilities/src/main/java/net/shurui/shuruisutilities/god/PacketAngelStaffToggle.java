package net.shurui.shuruisutilities.god;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * client -&gt; server: the player picked the Angel's Staff entry in DMZ's ki weapon quick menu.
 *
 * <p>Carries nothing. The client is asking to toggle its own staff and nothing else, and the server re-checks the
 * live Angel title before acting, so a forged packet from someone who is not the Angel does nothing at all.
 */
public class PacketAngelStaffToggle implements ISUPacket
{
    public PacketAngelStaffToggle() {}

    @Override
    public void encode(FriendlyByteBuf buf)
    {
    }

    public static PacketAngelStaffToggle decode(FriendlyByteBuf buf)
    {
        return new PacketAngelStaffToggle();
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player != null)
            net.shurui.shuruisutilities.api.key.RoleHooks.get().toggleAngelStaff(player); // keyless: nothing
    }

    public static void handler(final PacketAngelStaffToggle message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
