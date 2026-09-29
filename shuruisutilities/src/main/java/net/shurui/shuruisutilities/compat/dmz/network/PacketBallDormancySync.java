package net.shurui.shuruisutilities.compat.dmz.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * server to client: the full set of DragonMineZ ball set ids that are DORMANT on this shard. The client stashes it
 * in {@link net.shurui.shuruisutilities.client.dormancy.BallDormancyClient}; the block renderer reads it to draw a
 * dormant set grey and see through. A FULL replacement each time (not a delta) so the client's view is always the
 * server's authoritative state, exactly the shape {@link net.shurui.shuruisutilities.corrupted.network.PacketDefiledSync}
 * uses for the defiled flags. Sent on login and dimension change, and broadcast the moment any set's dormancy flips.
 * The client cache fails closed (empty, so nothing greys) until the first sync arrives.
 */
public class PacketBallDormancySync implements ISUPacket
{
    public List<String> dormantSets;

    public PacketBallDormancySync()
    {
        this.dormantSets = new ArrayList<>();
    }

    public PacketBallDormancySync(List<String> dormantSets)
    {
        this.dormantSets = dormantSets == null ? new ArrayList<>() : dormantSets;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(dormantSets.size());
        for (String set : dormantSets)
            buf.writeUtf(set);
    }

    public static PacketBallDormancySync decode(FriendlyByteBuf buf)
    {
        int size = buf.readVarInt();
        List<String> sets = new ArrayList<>(size);
        for (int i = 0; i < size; i++)
            sets.add(buf.readUtf());
        return new PacketBallDormancySync(sets);
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // Client only: hand the set list to the SU client cache. The client-only class is referenced only inside the
        // CLIENT branch so nothing client-side is classloaded on a dedicated server.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.shuruisutilities.client.dormancy.BallDormancyClient.apply(dormantSets));
    }

    public static void handler(final PacketBallDormancySync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
