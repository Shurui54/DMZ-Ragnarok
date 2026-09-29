package net.shurui.shuruisutilities.racing.net;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.api.key.RaceHooks;
import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.racing.tuning.RaceTuningDto;

/**
 * Client -&gt; server (fixed id 121): save the race tuning from the tuning screen. The {@link RaceTuningDto} is small
 * (comfortably under the 32767-byte serverbound ceiling). The server routes to the racing hook, which enforces the
 * admin permission and persists. Keyless the hook default is a no-op, so a keyless server never writes tuning.
 */
public class PacketRaceTuningSave implements ISUPacket
{
    public RaceTuningDto tuning = new RaceTuningDto();
    /** Empty = save the server-wide tuning; otherwise the track id whose per-track override this save writes. */
    public String trackId = "";

    public PacketRaceTuningSave() {}

    public PacketRaceTuningSave(RaceTuningDto tuning)
    {
        this(tuning, "");
    }

    public PacketRaceTuningSave(RaceTuningDto tuning, String trackId)
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

    public static PacketRaceTuningSave decode(FriendlyByteBuf buf)
    {
        PacketRaceTuningSave p = new PacketRaceTuningSave();
        p.tuning = RaceTuningDto.decode(buf);
        p.trackId = buf.readUtf(256);
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
            return;
        RaceHooks.get().saveTuning(player, trackId, tuning);
    }

    public static void handler(final PacketRaceTuningSave message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
