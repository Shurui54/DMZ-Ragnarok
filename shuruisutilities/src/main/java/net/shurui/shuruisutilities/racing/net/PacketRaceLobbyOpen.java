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
 * Server -&gt; client (fixed id 117): the race lobby state for a track, driving {@code RaceLobbyScreen} (R11). It
 * carries the track id, lap count and max racers, the seconds left before the lobby auto-starts (-1 when there is no
 * timer), and the current roster (each member's name, ready flag and whether it is a bot). It is sent to every human
 * in the lobby whenever the roster changes and once a second while the lobby is open, so the screen shows a live
 * countdown and roster. Ignored on the client unless the racing feature is synced.
 */
public class PacketRaceLobbyOpen implements ISUPacket
{
    /** One lobby member row: display name, ready flag, and whether it is a bot. */
    public record Member(String name, boolean ready, boolean bot) {}

    public String trackId = "";
    public int laps;
    public int maxRacers;
    public int secondsRemaining = -1;
    public final List<Member> members = new ArrayList<>();

    public PacketRaceLobbyOpen() {}

    public PacketRaceLobbyOpen(String trackId, int laps, int maxRacers, int secondsRemaining, List<Member> members)
    {
        this.trackId = trackId == null ? "" : trackId;
        this.laps = laps;
        this.maxRacers = maxRacers;
        this.secondsRemaining = secondsRemaining;
        if (members != null)
            this.members.addAll(members);
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(trackId);
        buf.writeVarInt(laps);
        buf.writeVarInt(maxRacers);
        buf.writeVarInt(secondsRemaining + 1); // shift so -1 encodes as 0 (VarInt is unsigned-friendly)
        buf.writeVarInt(members.size());
        for (Member m : members)
        {
            buf.writeUtf(m.name());
            buf.writeBoolean(m.ready());
            buf.writeBoolean(m.bot());
        }
    }

    public static PacketRaceLobbyOpen decode(FriendlyByteBuf buf)
    {
        PacketRaceLobbyOpen p = new PacketRaceLobbyOpen();
        p.trackId = buf.readUtf();
        p.laps = buf.readVarInt();
        p.maxRacers = buf.readVarInt();
        p.secondsRemaining = buf.readVarInt() - 1;
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            p.members.add(new Member(buf.readUtf(), buf.readBoolean(), buf.readBoolean()));
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.racing.client.RaceClientState.onLobbyOpen(this));
    }

    public static void handler(final PacketRaceLobbyOpen message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
