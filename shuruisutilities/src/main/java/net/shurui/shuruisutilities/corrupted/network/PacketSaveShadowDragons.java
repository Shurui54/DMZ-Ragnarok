package net.shurui.shuruisutilities.corrupted.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.corrupted.ShadowDragonDef;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client -&gt; server: save the edited shadow-dragon defs. Carries every slot's def as NBT ({@link
 * ShadowDragonDef#save()}). Server-authoritative: node-gated, every slot is looked up by its own index (never
 * created client-side), and all incoming values are checked by {@link ShadowDragonSanitizer} before persist (checked
 * for validity, not held to a ceiling: the stats have no upper bound).
 * Writes the display name, entity type, the base and transformed dragon models, the numeric stats, and the arena +
 * explicit spawn (the arena also has a convenience WorldEdit path via {@link PacketSetShadowDragonBounds}, but manual
 * corner entry is persisted here).
 */
public class PacketSaveShadowDragons implements ISUPacket
{
    /** Each edited def as an NBT compound; keyed by its own index field, not by list position. */
    public List<CompoundTag> defs = new ArrayList<>();

    public PacketSaveShadowDragons() {}

    public PacketSaveShadowDragons(List<CompoundTag> defs)
    {
        this.defs = defs == null ? new ArrayList<>() : defs;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(defs.size());
        for (CompoundTag t : defs)
            buf.writeNbt(t);
    }

    public static PacketSaveShadowDragons decode(FriendlyByteBuf buf)
    {
        PacketSaveShadowDragons p = new PacketSaveShadowDragons();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            p.defs.add(buf.readNbt());
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // S20: the handler body is private (the shadow dragon editor, like its hub row) and lives in the Ragnarok Key.
        // Keyless the hook ignores the packet. The stored definitions it edits stay in core and are read by the public encounter.
        ServerPlayer sender = context.getSender();
        if (sender != null)
            net.shurui.shuruisutilities.api.key.CorruptedHooks.get().onSaveDefs(sender, this);
    }

    public static void handler(final PacketSaveShadowDragons message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
