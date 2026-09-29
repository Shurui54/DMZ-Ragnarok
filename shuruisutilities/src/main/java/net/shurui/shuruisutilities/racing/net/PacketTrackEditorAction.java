package net.shurui.shuruisutilities.racing.net;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.api.key.RaceHooks;
import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Client -&gt; server (fixed id 119): a track-editor GUI action (the R3 editor screen; the in-world wand goes through
 * a server-side interaction handler instead). {@code action} = the editor operation, {@code pos} the target
 * position, {@code arg} an operation parameter (a width delta, a node id, ...). The server owns the track; it
 * routes to the racing hook, which enforces the admin permission. Keyless the hook default is a no-op.
 */
public class PacketTrackEditorAction implements ISUPacket
{
    public String trackId = "";
    public int action;
    public BlockPos pos = BlockPos.ZERO;
    public int arg;
    /** A string payload (a block resource-location id for the block actions); empty when unused. */
    public String text = "";

    public PacketTrackEditorAction() {}

    public PacketTrackEditorAction(String trackId, int action, BlockPos pos, int arg)
    {
        this(trackId, action, pos, arg, "");
    }

    public PacketTrackEditorAction(String trackId, int action, BlockPos pos, int arg, String text)
    {
        this.trackId = trackId == null ? "" : trackId;
        this.action = action;
        this.pos = pos == null ? BlockPos.ZERO : pos;
        this.arg = arg;
        this.text = text == null ? "" : text;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(trackId);
        buf.writeVarInt(action);
        buf.writeBlockPos(pos);
        buf.writeVarInt(arg);
        buf.writeUtf(text);
    }

    public static PacketTrackEditorAction decode(FriendlyByteBuf buf)
    {
        PacketTrackEditorAction p = new PacketTrackEditorAction();
        p.trackId = buf.readUtf();
        p.action = buf.readVarInt();
        p.pos = buf.readBlockPos();
        p.arg = buf.readVarInt();
        p.text = buf.readUtf();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
            return;
        RaceHooks.get().editorAction(player, trackId, action, pos, arg, text);
    }

    public static void handler(final PacketTrackEditorAction message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
