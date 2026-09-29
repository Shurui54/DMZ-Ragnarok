package net.shurui.shuruisutilities.cosmetics.form.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.cosmetics.form.FormCosmetic;

/**
 * Server to client: form cosmetic overrides for other players so everyone renders the same styling. Two shapes:
 * a FULL snapshot (sent to a joining player, replaces the client's whole table) or an incremental update (one
 * player changed or cleared). A cleared/unentitled player is sent as an entry with {@code present == false} so the
 * client drops it and reverts to the stock form look.
 *
 * <p>Entitlement is never trusted from the client: the server only ever puts a currently-entitled player's
 * override on this wire (see {@code FormCosmeticManager}), so a lapsed supporter's styling simply stops being
 * broadcast and the client removes it.
 */
public class PacketFormCosmeticSync implements ISUPacket
{
    public boolean full;
    public List<UUID> ids = new ArrayList<>();
    public List<Boolean> present = new ArrayList<>();
    public List<FormCosmetic> cosmetics = new ArrayList<>();

    public PacketFormCosmeticSync() {}

    private PacketFormCosmeticSync(boolean full)
    {
        this.full = full;
    }

    /** A full-table replacement (only entitled players' overrides are included). */
    public static PacketFormCosmeticSync fullTable(java.util.Map<UUID, FormCosmetic> table)
    {
        PacketFormCosmeticSync p = new PacketFormCosmeticSync(true);
        for (java.util.Map.Entry<UUID, FormCosmetic> e : table.entrySet())
        {
            p.ids.add(e.getKey());
            p.present.add(true);
            p.cosmetics.add(e.getValue());
        }
        return p;
    }

    /** A single player's override was set. */
    public static PacketFormCosmeticSync single(UUID id, FormCosmetic cosmetic)
    {
        PacketFormCosmeticSync p = new PacketFormCosmeticSync(false);
        p.ids.add(id);
        p.present.add(true);
        p.cosmetics.add(cosmetic);
        return p;
    }

    /** A single player's override was cleared (or they lapsed): remove it on the client. */
    public static PacketFormCosmeticSync remove(UUID id)
    {
        PacketFormCosmeticSync p = new PacketFormCosmeticSync(false);
        p.ids.add(id);
        p.present.add(false);
        p.cosmetics.add(new FormCosmetic());
        return p;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(full);
        int n = ids.size();
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++)
        {
            buf.writeUUID(ids.get(i));
            boolean p = present.get(i);
            buf.writeBoolean(p);
            if (p)
                cosmetics.get(i).encode(buf);
        }
    }

    public static PacketFormCosmeticSync decode(FriendlyByteBuf buf)
    {
        PacketFormCosmeticSync p = new PacketFormCosmeticSync();
        p.full = buf.readBoolean();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
        {
            p.ids.add(buf.readUUID());
            boolean present = buf.readBoolean();
            p.present.add(present);
            p.cosmetics.add(present ? FormCosmetic.decode(buf) : new FormCosmetic());
        }
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // client only: apply to the client store used by the render mixin
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.shuruisutilities.cosmetics.form.client.FormCosmeticClientStore.apply(this));
    }

    public static void handler(final PacketFormCosmeticSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
