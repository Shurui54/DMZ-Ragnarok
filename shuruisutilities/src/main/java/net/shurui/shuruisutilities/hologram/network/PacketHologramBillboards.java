package net.shurui.shuruisutilities.hologram.network;

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
 * Server to client: every image billboard that currently exists, in every dimension.
 *
 * <p>The whole set travels each time because it is tiny (a few dozen bytes each) and because a hologram edit can
 * move, resize or delete one, and a diff of that is more ways to be wrong than it is worth. Sent on join and
 * whenever a hologram is saved, deleted or refreshed.
 */
public class PacketHologramBillboards implements ISUPacket
{
    public List<GifBillboard> billboards = new ArrayList<>();

    public PacketHologramBillboards() {}

    public PacketHologramBillboards(List<GifBillboard> billboards)
    {
        this.billboards = billboards == null ? new ArrayList<>() : billboards;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(billboards.size());
        for (GifBillboard b : billboards)
            b.write(buf);
    }

    public static PacketHologramBillboards decode(FriendlyByteBuf buf)
    {
        PacketHologramBillboards p = new PacketHologramBillboards();
        int count = buf.readVarInt();
        for (int i = 0; i < count; i++)
            p.billboards.add(GifBillboard.read(buf));
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.hologram.client.HologramBillboards.accept(billboards));
    }

    public static void handler(final PacketHologramBillboards message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
