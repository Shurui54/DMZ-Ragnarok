package net.shurui.shuruisutilities.economy;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

// server -> client: the receiving player's OWN zeni balance, for the stat HUD readout. Sent only to the player it
// belongs to, so a client never learns anyone else's balance from this. Carries no uuid for the same reason: the
// only account it can ever describe is the one receiving it.
public class PacketZeniSync implements ISUPacket
{
    public long balance;

    public PacketZeniSync() {}

    public PacketZeniSync(long balance)
    {
        this.balance = balance;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarLong(balance);
    }

    public static PacketZeniSync decode(FriendlyByteBuf buf)
    {
        PacketZeniSync p = new PacketZeniSync();
        p.balance = buf.readVarLong();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.client.hud.ZeniClientCache.set(balance));
    }

    public static void handler(final PacketZeniSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
