package net.shurui.shuruisutilities.racing.net;

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
 * Server -&gt; client (fixed id 113): the final results table, sent when a race finishes. One entry per racer, in
 * finishing order. R5/R11 draw the table from this.
 */
public class PacketRaceResults implements ISUPacket
{
    /**
     * One row: display name, finishing place (1-based), race time in ticks, whether the racer DNF'd, the racer's best
     * lap in ticks this race (0 if none), and whether this race set a new personal record (best lap or best race).
     */
    public record Entry(String name, int place, int timeTicks, boolean dnf, int bestLapTicks, boolean newRecord) {}

    public final List<Entry> entries = new ArrayList<>();

    public PacketRaceResults() {}

    public PacketRaceResults(List<Entry> entries)
    {
        if (entries != null)
            this.entries.addAll(entries);
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(entries.size());
        for (Entry e : entries)
        {
            buf.writeUtf(e.name());
            buf.writeVarInt(e.place());
            buf.writeVarInt(e.timeTicks());
            buf.writeBoolean(e.dnf());
            buf.writeVarInt(Math.max(0, e.bestLapTicks()));
            buf.writeBoolean(e.newRecord());
        }
    }

    public static PacketRaceResults decode(FriendlyByteBuf buf)
    {
        PacketRaceResults p = new PacketRaceResults();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            p.entries.add(new Entry(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                    buf.readVarInt(), buf.readBoolean()));
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.racing.client.RaceClientState.onResults(this));
    }

    public static void handler(final PacketRaceResults message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
