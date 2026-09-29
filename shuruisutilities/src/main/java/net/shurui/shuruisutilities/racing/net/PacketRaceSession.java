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
import net.shurui.shuruisutilities.racing.physics.RaceDriveParams;

/**
 * Server -&gt; client (fixed id 109): the per-race session setup, sent once at race start. Carries only NUMBERS: the
 * shared {@link RaceDriveParams} every racing bike drives on, the surface block ids the client tests for off-road,
 * and the track centreline (up to 256 points) for the minimap. The client runs the kart-physics branch and draws
 * the minimap from these; positions and items stay server authoritative.
 */
public class PacketRaceSession implements ISUPacket
{
    /** A hard cap so a long track never blows the packet up; the server samples down to this. */
    public static final int MAX_CENTRELINE = 256;

    public RaceDriveParams params = new RaceDriveParams();
    public int[] surfaceBlockIds = new int[0];
    public final List<float[]> centreline = new ArrayList<>();

    public PacketRaceSession() {}

    public PacketRaceSession(RaceDriveParams params, int[] surfaceBlockIds, List<float[]> centreline)
    {
        this.params = params == null ? new RaceDriveParams() : params;
        this.surfaceBlockIds = surfaceBlockIds == null ? new int[0] : surfaceBlockIds;
        if (centreline != null)
            for (float[] p : centreline)
            {
                if (this.centreline.size() >= MAX_CENTRELINE)
                    break;
                this.centreline.add(p);
            }
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        params.encode(buf);
        buf.writeVarInt(surfaceBlockIds.length);
        for (int id : surfaceBlockIds)
            buf.writeVarInt(id);
        int n = Math.min(centreline.size(), MAX_CENTRELINE);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++)
        {
            float[] p = centreline.get(i);
            buf.writeFloat(p[0]);
            buf.writeFloat(p[1]);
            buf.writeFloat(p[2]);
        }
    }

    public static PacketRaceSession decode(FriendlyByteBuf buf)
    {
        PacketRaceSession p = new PacketRaceSession();
        p.params = RaceDriveParams.decode(buf);
        int s = buf.readVarInt();
        p.surfaceBlockIds = new int[s];
        for (int i = 0; i < s; i++)
            p.surfaceBlockIds[i] = buf.readVarInt();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            p.centreline.add(new float[] { buf.readFloat(), buf.readFloat(), buf.readFloat() });
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.racing.client.RaceClientState.onSession(this));
    }

    public static void handler(final PacketRaceSession message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
