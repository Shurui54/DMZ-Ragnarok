package net.shurui.shuruisutilities.corrupted.network;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client -&gt; server: "set the arena for slot N from my WorldEdit selection". The server reads the sender's
 * current WorldEdit cuboid selection, normalizes it to the full vertical build column (keeping only the X/Z
 * footprint, like the raid-bosses addon's arenas), stores it on that slot's def, and replies with the refreshed
 * seven defs via {@link PacketShadowDragonBoundsResult} so the open editor updates live. Node-gated server side.
 */
public class PacketSetShadowDragonBounds implements ISUPacket
{
    public int slot;

    public PacketSetShadowDragonBounds() {}

    public PacketSetShadowDragonBounds(int slot)
    {
        this.slot = slot;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(slot);
    }

    public static PacketSetShadowDragonBounds decode(FriendlyByteBuf buf)
    {
        PacketSetShadowDragonBounds p = new PacketSetShadowDragonBounds();
        p.slot = buf.readVarInt();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // S20: the handler body is private (the shadow dragon editor, like its hub row) and lives in the Ragnarok Key.
        // Keyless the hook ignores the packet. The arena it sets is stored in core.
        ServerPlayer sender = context.getSender();
        if (sender != null)
            net.shurui.shuruisutilities.api.key.CorruptedHooks.get().onSetBounds(sender, this);
    }

    public static void handler(final PacketSetShadowDragonBounds message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
