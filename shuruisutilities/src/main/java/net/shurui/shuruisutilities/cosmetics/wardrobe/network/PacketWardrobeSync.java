package net.shurui.shuruisutilities.cosmetics.wardrobe.network;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.cosmetics.wardrobe.PlayerWardrobe;

/**
 * Server to client: who is wearing what, and (for the receiving player only) what they own.
 *
 * <p>Two shapes, following {@code PacketFormCosmeticSync}: a FULL snapshot sent to a joining player, which
 * replaces the client's whole table, or an incremental update naming one player who changed. A player wearing
 * nothing is sent as an entry with an empty wardrobe rather than being left out, so the client drops their old
 * outfit instead of keeping it; that is the same reason the server-side store keeps a stamp for a cleared player.
 *
 * <h2>Ownership rides along, but only your own</h2>
 * {@link #ownedIds} is the RECEIVING player's own owned set, and is only ever populated on a packet addressed to
 * them. Everybody's outfit is public (you can see it on them), but what somebody owns and has not put on is not,
 * and broadcasting it would let any client enumerate the whole server's purchases. The wardrobe screen is the
 * only thing that reads it.
 *
 * <p>Ownership is authoritative on the server regardless: this list is what the screen draws, never what an
 * equip request is checked against. {@code WardrobeManager} re-reads the ledger for that, so a client that
 * forges an owned set gets a refusal.
 *
 * <h2>The PAYLOAD grew, the id did not</h2>
 * A slot now carries an {@link net.shurui.shuruisutilities.cosmetics.wardrobe.EquippedCosmetic} rather than a
 * bare catalogue id, so this packet tells every client which COPY each player is wearing, its quality, its
 * rolled effect and the number on its counter. That is a wire format change on id 103 rather than a new id,
 * which is safe here and only here because the client and the server ship in the same jar and can never be a
 * version apart on this channel. The cost of the extra fields is a UUID and three short strings per worn slot,
 * paid once per outfit change, and it is what lets the renderer draw a Magic effect with nothing sent per frame.
 */
public class PacketWardrobeSync implements ISUPacket
{
    /** True when this replaces the client's whole table rather than updating the named players. */
    public boolean full;

    public List<UUID> ids = new ArrayList<>();

    public List<PlayerWardrobe> wardrobes = new ArrayList<>();

    /** Present only on a packet addressed to the owner of these ids. See the class note. */
    public boolean carriesOwned;

    public Set<String> ownedIds = new LinkedHashSet<>();

    public PacketWardrobeSync()
    {
    }

    /** The whole table, for a joining player. */
    public static PacketWardrobeSync fullTable(Map<UUID, PlayerWardrobe> table, Set<String> owned)
    {
        PacketWardrobeSync p = new PacketWardrobeSync();
        p.full = true;
        if (table != null)
            for (Map.Entry<UUID, PlayerWardrobe> e : table.entrySet())
            {
                p.ids.add(e.getKey());
                p.wardrobes.add(e.getValue());
            }
        if (owned != null)
        {
            p.carriesOwned = true;
            p.ownedIds.addAll(owned);
        }
        return p;
    }

    /** One player's outfit changed. An empty wardrobe is a clear, not an omission. */
    public static PacketWardrobeSync single(UUID id, PlayerWardrobe wardrobe)
    {
        PacketWardrobeSync p = new PacketWardrobeSync();
        p.ids.add(id);
        p.wardrobes.add(wardrobe == null ? new PlayerWardrobe() : wardrobe);
        return p;
    }

    /** One player's outfit changed AND this is going to that player, so their owned set rides along. */
    public static PacketWardrobeSync selfUpdate(UUID id, PlayerWardrobe wardrobe, Set<String> owned)
    {
        PacketWardrobeSync p = single(id, wardrobe);
        p.carriesOwned = true;
        if (owned != null)
            p.ownedIds.addAll(owned);
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
            wardrobes.get(i).encode(buf);
        }
        buf.writeBoolean(carriesOwned);
        if (carriesOwned)
        {
            buf.writeVarInt(ownedIds.size());
            for (String id : ownedIds)
                buf.writeUtf(id);
        }
    }

    public static PacketWardrobeSync decode(FriendlyByteBuf buf)
    {
        PacketWardrobeSync p = new PacketWardrobeSync();
        p.full = buf.readBoolean();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
        {
            p.ids.add(buf.readUUID());
            p.wardrobes.add(PlayerWardrobe.decode(buf));
        }
        p.carriesOwned = buf.readBoolean();
        if (p.carriesOwned)
        {
            int m = buf.readVarInt();
            for (int i = 0; i < m; i++)
                p.ownedIds.add(buf.readUtf());
        }
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.shuruisutilities.cosmetics.wardrobe.client.CosmeticClientStore.applyWardrobe(this));
    }

    public static void handler(final PacketWardrobeSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
