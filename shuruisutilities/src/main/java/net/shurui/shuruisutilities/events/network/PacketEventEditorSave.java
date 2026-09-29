package net.shurui.shuruisutilities.events.network;

import java.util.function.Supplier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.api.key.EventWorldHooks;
import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Client -&gt; server (fixed id 129): save an edited event. The body is the whole {@code EventDef} as NBT (a single
 * compound; whole-event edits use this NBT pair rather than the string-capped PacketEditorData path). The server is
 * authoritative: it requires op level 2 and hands the def to {@link EventWorldHooks} (keyless: an inert no-op; the
 * key validates and persists it). The id is pinned here so the wire never shifts.
 */
public class PacketEventEditorSave implements ISUPacket
{
    public CompoundTag def = new CompoundTag();

    public PacketEventEditorSave() {}

    public PacketEventEditorSave(CompoundTag def)
    {
        this.def = def == null ? new CompoundTag() : def;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeNbt(def);
    }

    public static PacketEventEditorSave decode(FriendlyByteBuf buf)
    {
        CompoundTag tag = buf.readNbt();
        return new PacketEventEditorSave(tag == null ? new CompoundTag() : tag);
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer sender = context.getSender();
        if (sender == null || !sender.hasPermissions(2))
            return; // authoring is op-gated; a non-op save is silently dropped, matching the editor transport.
        EventWorldHooks.get().saveDef(sender, def);
    }

    public static void handler(final PacketEventEditorSave message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
