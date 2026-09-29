package net.shurui.shuruisutilities.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: generic payload that opens (or refreshes) one of the hub editors. {@link #editor}
 * selects the client screen; {@link #meta} carries a small flat list of screen-specific values (e.g. a
 * title context); {@link #rows} carries the list contents, one variable-length string row per entry. Every
 * new editor reuses this one packet instead of adding its own, keeping the network surface small.
 */
public class PacketEditorData implements ISUPacket
{
    public String editor = "";
    public List<String> meta = new ArrayList<>();
    public List<List<String>> rows = new ArrayList<>();

    public PacketEditorData() {}

    public PacketEditorData(String editor, List<String> meta, List<List<String>> rows)
    {
        this.editor = editor == null ? "" : editor;
        this.meta = meta == null ? new ArrayList<>() : meta;
        this.rows = rows == null ? new ArrayList<>() : rows;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(editor);
        buf.writeVarInt(meta.size());
        for (String s : meta)
            buf.writeUtf(s == null ? "" : s);
        buf.writeVarInt(rows.size());
        for (List<String> row : rows)
        {
            buf.writeVarInt(row.size());
            for (String c : row)
                buf.writeUtf(c == null ? "" : c);
        }
    }

    public static PacketEditorData decode(FriendlyByteBuf buf)
    {
        PacketEditorData p = new PacketEditorData();
        p.editor = buf.readUtf();
        int mc = buf.readVarInt();
        for (int i = 0; i < mc; i++)
            p.meta.add(buf.readUtf());
        int rc = buf.readVarInt();
        for (int i = 0; i < rc; i++)
        {
            int cols = buf.readVarInt();
            List<String> row = new ArrayList<>(cols);
            for (int c = 0; c < cols; c++)
                row.add(buf.readUtf());
            p.rows.add(row);
        }
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.client.gui.EditorScreens.open(this));
    }

    public static void handler(final PacketEditorData message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
