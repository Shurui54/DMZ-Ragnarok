package net.shurui.shuruisutilities.corrupted.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.corrupted.ShadowDragonDef;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: the refreshed seven defs after a {@link PacketSetShadowDragonBounds}, so the open editor
 * updates its arena "bounds set" indicator (and any server-side clamp) without leaving the screen. Carries the
 * full set rather than a single slot so the client just re-seeds every tab's state from a single source of truth.
 */
public class PacketShadowDragonBoundsResult implements ISUPacket
{
    public List<CompoundTag> defs = new ArrayList<>();

    public PacketShadowDragonBoundsResult() {}

    public PacketShadowDragonBoundsResult(List<ShadowDragonDef> defList)
    {
        for (ShadowDragonDef d : defList)
            this.defs.add(d.save());
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(defs.size());
        for (CompoundTag t : defs)
            buf.writeNbt(t);
    }

    public static PacketShadowDragonBoundsResult decode(FriendlyByteBuf buf)
    {
        PacketShadowDragonBoundsResult p = new PacketShadowDragonBoundsResult();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            p.defs.add(buf.readNbt());
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.client.gui.editor.ShadowDragonEditScreen.boundsResult(defs));
    }

    public static void handler(final PacketShadowDragonBoundsResult message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
