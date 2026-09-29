package net.shurui.shuruisutilities.racing.net;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.racing.tuning.RaceTuningDto;

/**
 * Server -&gt; client (fixed id 120): open the race tuning screen, prefilled with the current tuning. R10 builds the
 * screen; R0 pins the wire id and carries the {@link RaceTuningDto}. Ignored unless the racing feature is synced.
 */
public class PacketRaceTuningOpen implements ISUPacket
{
    public RaceTuningDto tuning = new RaceTuningDto();
    /** Empty = the server-wide tuning; otherwise the track id whose per-track override this screen edits. */
    public String trackId = "";

    public PacketRaceTuningOpen() {}

    public PacketRaceTuningOpen(RaceTuningDto tuning)
    {
        this(tuning, "");
    }

    public PacketRaceTuningOpen(RaceTuningDto tuning, String trackId)
    {
        this.tuning = tuning == null ? new RaceTuningDto() : tuning;
        this.trackId = trackId == null ? "" : trackId;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        tuning.encode(buf);
        buf.writeUtf(trackId, 256);
    }

    public static PacketRaceTuningOpen decode(FriendlyByteBuf buf)
    {
        PacketRaceTuningOpen p = new PacketRaceTuningOpen();
        p.tuning = RaceTuningDto.decode(buf);
        p.trackId = buf.readUtf(256);
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        final PacketRaceTuningOpen self = this;
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.racing.client.RaceClientState.onTuningOpen(self));
    }

    public static void handler(final PacketRaceTuningOpen message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
