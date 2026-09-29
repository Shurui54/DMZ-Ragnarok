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
 * Server -&gt; client: open the shadow-dragon editor, carrying all seven per-slot {@link ShadowDragonDef}s
 * (each serialized via {@link ShadowDragonDef#save()}). One screen with a tab per slot is pushed on receipt.
 * Modelled on {@link net.shurui.shuruisutilities.npcregion.network.PacketOpenNpcRegionEditor} (typed transport,
 * not the generic string editor transport, because a slot carries a dozen numeric stats plus arena bounds).
 */
public class PacketOpenShadowDragonEditor implements ISUPacket
{
    /** Each def as an NBT compound (ShadowDragonDef.save()); ordered by slot 1..7. */
    public List<CompoundTag> defs = new ArrayList<>();

    public PacketOpenShadowDragonEditor() {}

    public PacketOpenShadowDragonEditor(List<ShadowDragonDef> defList)
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

    public static PacketOpenShadowDragonEditor decode(FriendlyByteBuf buf)
    {
        PacketOpenShadowDragonEditor p = new PacketOpenShadowDragonEditor();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            p.defs.add(buf.readNbt());
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.client.gui.editor.ShadowDragonEditScreen.open(defs));
    }

    public static void handler(final PacketOpenShadowDragonEditor message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
