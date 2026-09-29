package net.shurui.shuruisutilities.events.network;

import java.util.function.Supplier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Server -&gt; client (fixed id 128): open the event editor. The body is one NBT compound carrying either a single
 * event to edit or the event list, plus the pick lists (region names, raid/rift ids) the editor tabs need. The id
 * is pinned here so the wire never shifts; the client editor that consumes it lands in a later batch (E11), so for
 * now the client sink is an inert no-op and the payload is simply parsed and dropped. See the design notes.
 */
public class PacketEventEditorOpen implements ISUPacket
{
    public CompoundTag data = new CompoundTag();

    public PacketEventEditorOpen() {}

    public PacketEventEditorOpen(CompoundTag data)
    {
        this.data = data == null ? new CompoundTag() : data;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeNbt(data);
    }

    public static PacketEventEditorOpen decode(FriendlyByteBuf buf)
    {
        CompoundTag tag = buf.readNbt();
        return new PacketEventEditorOpen(tag == null ? new CompoundTag() : tag);
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // E11: open the admin event editor. The dispatch is a CLIENT-only class (never classloaded server side),
        // and it gates every open on ClientGate.feature("events") so a keyless server never pops the editor.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.client.gui.editor.EventEditorClient.open(data));
    }

    public static void handler(final PacketEventEditorOpen message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
