package net.shurui.shuruisutilities.cosmetics.argent;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Server to client: the receiving player's OWN Shards balance, for the HUD readout.
 *
 * <p>Carries no uuid, and that is the privacy guarantee rather than a saving: the only account this packet can
 * ever describe is the one on the other end of the connection it was sent down, so a client cannot learn anybody
 * else's balance even by reading the wire. Same shape as {@code PacketZeniSync} for the same reason.
 *
 * <p>Sent on login and on every change, never on a timer. A balance moves on a store grant, a chargeback or a
 * purchase and on nothing else, so there is nothing for a poll to catch that {@code ArgentSync} does not already
 * push. The Zeni equivalent polls once a second only because its own manager mutates through several paths that
 * cannot say whose balance moved.
 *
 * <p>Negative values are never sent. {@code ArgentSync} drops an unreadable balance (-1 from a database that
 * could not be reached) rather than forwarding it, so the client keeps the last figure it was told instead of
 * blanking the element every time the database hiccups.
 */
public class PacketShardSync implements ISUPacket
{
    public long balance;

    public PacketShardSync() {}

    public PacketShardSync(long balance)
    {
        this.balance = balance;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarLong(balance);
    }

    public static PacketShardSync decode(FriendlyByteBuf buf)
    {
        PacketShardSync p = new PacketShardSync();
        p.balance = buf.readVarLong();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.client.hud.ShardClientCache.set(Math.max(0L, balance)));
    }

    public static void handler(final PacketShardSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
