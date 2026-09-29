package net.shurui.shuruisutilities.compat.customnpcs;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

// client -> server, one boolean: which tab of a Zeni shop this player is looking at (true = Buy).
//
// Sent when a trader screen opens and again on every tab switch, so the server's copy is current before any trade
// can be clicked. Nothing is persisted: see ZeniShopTabs for why in-memory is the right lifetime.
//
// Registered unconditionally alongside every other SU packet, so this class must not touch CustomNPCs types. It
// does not: the payload is a boolean and the handler only writes ZeniShopTabs, which is vanilla-only too. A client
// without CustomNPCs simply never sends it.
public class PacketZeniShopTab implements ISUPacket
{
    private final boolean showBuy;

    public PacketZeniShopTab(boolean showBuy)
    {
        this.showBuy = showBuy;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(showBuy);
    }

    public static PacketZeniShopTab decode(FriendlyByteBuf buf)
    {
        return new PacketZeniShopTab(buf.readBoolean());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
            return;
        ZeniShopTabs.set(player.getUUID(), showBuy);
    }

    public static void handler(final PacketZeniShopTab message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
