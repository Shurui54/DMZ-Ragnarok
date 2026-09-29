package net.shurui.shuruisutilities.racing.net;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Server -&gt; client (fixed id 118): the live track-editor preview payload (centreline, edges, gates, nodes, pads).
 * R2 defines the payload format and the {@code TrackPreviewRenderer} that draws it; R0 keeps it an opaque byte
 * blob so the wire id is pinned without committing to the geometry encoding. Ignored unless the racing feature is
 * synced.
 */
public class PacketTrackEditorSync implements ISUPacket
{
    public String trackId = "";
    public byte[] payload = new byte[0];
    /** The editor's currently selected node id on the server (for the GUI's node picker), or -1. */
    public int selectedNode = -1;

    public PacketTrackEditorSync() {}

    public PacketTrackEditorSync(String trackId, byte[] payload)
    {
        this(trackId, payload, -1);
    }

    public PacketTrackEditorSync(String trackId, byte[] payload, int selectedNode)
    {
        this.trackId = trackId == null ? "" : trackId;
        this.payload = payload == null ? new byte[0] : payload;
        this.selectedNode = selectedNode;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(trackId);
        buf.writeByteArray(payload);
        buf.writeVarInt(selectedNode);
    }

    public static PacketTrackEditorSync decode(FriendlyByteBuf buf)
    {
        PacketTrackEditorSync p = new PacketTrackEditorSync();
        p.trackId = buf.readUtf();
        p.payload = buf.readByteArray();
        p.selectedNode = buf.readVarInt();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.racing.client.RaceClientState.onEditorSync(this));
    }

    public static void handler(final PacketTrackEditorSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
