package net.shurui.shuruisutilities.cosmetics.wardrobe.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticDef;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticEffect;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticSlot;
import net.shurui.shuruisutilities.cosmetics.wardrobe.EquippedCosmetic;
import net.shurui.shuruisutilities.cosmetics.wardrobe.PlayerWardrobe;
import net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketCosmeticCatalogSync;
import net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketWardrobeSync;

/**
 * The client's copy of the catalogue, of who is wearing what, and of its own owned set.
 *
 * <p>CLIENT ONLY. Everything here is a cache of something the server decided; nothing in it is authority. An
 * equip request is re-checked against the ledger server side, so a client that edited this store would only be
 * lying to its own screen.
 *
 * <p>Read by the wardrobe screen today and by the render layer when it lands. Cleared on logout so a second
 * server's table cannot be seen through the first one's leftovers.
 */
public final class CosmeticClientStore
{
    private CosmeticClientStore()
    {
    }

    private static final Map<String, CosmeticDef> CATALOG = new LinkedHashMap<>();

    /** The MAGIC effect records, keyed by id. Read by the Magic presentation to know what to draw. */
    private static final Map<String, CosmeticEffect> EFFECTS = new LinkedHashMap<>();

    private static final Map<UUID, PlayerWardrobe> WARDROBES = new LinkedHashMap<>();

    private static final Set<String> OWNED = new LinkedHashSet<>();

    /** Bumped whenever the catalogue changes, so a screen can notice without comparing the whole table. */
    private static int catalogVersion;

    public static int catalogVersion()
    {
        return catalogVersion;
    }

    /** Full replacement. See the packet's note on why the catalogue is never sent as a delta. */
    public static void applyCatalog(PacketCosmeticCatalogSync packet)
    {
        CATALOG.clear();
        EFFECTS.clear();
        if (packet != null)
        {
            for (CosmeticDef d : packet.defs)
                if (d != null && d.id != null && !d.id.isBlank())
                    CATALOG.put(d.id, d);
            for (CosmeticEffect e : packet.effects)
                if (e != null && e.id != null && !e.id.isBlank())
                    EFFECTS.put(e.id, e);
        }
        catalogVersion++;
    }

    public static void applyWardrobe(PacketWardrobeSync packet)
    {
        if (packet == null)
            return;
        if (packet.full)
            WARDROBES.clear();
        for (int i = 0; i < packet.ids.size(); i++)
        {
            UUID id = packet.ids.get(i);
            PlayerWardrobe w = packet.wardrobes.get(i);
            // An empty wardrobe is a CLEAR, not a no-op: the server sends one when somebody takes everything off,
            // and keeping the old entry would leave them dressed on every other screen.
            if (w == null || w.isEmpty())
                WARDROBES.remove(id);
            else
                WARDROBES.put(id, w);
        }
        if (packet.carriesOwned)
        {
            OWNED.clear();
            OWNED.addAll(packet.ownedIds);
        }
    }

    /** Wipe on logout, so the next server is not read through this one's table. */
    public static void clear()
    {
        CATALOG.clear();
        EFFECTS.clear();
        WARDROBES.clear();
        OWNED.clear();
        catalogVersion++;
    }

    public static CosmeticDef def(String id)
    {
        return id == null ? null : CATALOG.get(id);
    }

    /** The MAGIC effect record for this id, or null. Read by the Magic presentation. */
    public static CosmeticEffect effect(String id)
    {
        return id == null ? null : EFFECTS.get(id);
    }

    /** Every definition the server sent, in the order it sent them. */
    public static List<CosmeticDef> catalog()
    {
        return new ArrayList<>(CATALOG.values());
    }

    /** This client's own owned catalogue ids. Display only; the server re-checks every equip. */
    public static Set<String> owned()
    {
        return new LinkedHashSet<>(OWNED);
    }

    public static boolean ownsLocally(String id)
    {
        return id != null && OWNED.contains(id);
    }

    /** What this player is wearing, as known here. Never null. */
    public static PlayerWardrobe wardrobe(UUID player)
    {
        PlayerWardrobe w = player == null ? null : WARDROBES.get(player);
        return w == null ? new PlayerWardrobe() : w;
    }

    /**
     * The definition this player has in this slot, or null.
     *
     * <p>Resolves the id through the catalogue, so a slot naming an entry this client has never heard of (an
     * admin created it while the client was mid-join) reads as nothing rather than throwing. The renderer must
     * treat that the same way: draw nothing and log once, never throw on the render thread.
     */
    public static CosmeticDef worn(UUID player, CosmeticSlot slot)
    {
        if (player == null || slot == null)
            return null;
        String id = wardrobe(player).in(slot);
        return id.isBlank() ? null : CATALOG.get(id);
    }

    /**
     * The WHOLE slot record, or null: which copy is worn, its quality, its rolled effect and its counter label.
     *
     * <p>This is the read a renderer wants, and it is why the slot value stopped being a bare id. Outfits are
     * broadcast to every client, so this answers "is that player wearing something Magic, and which effect" with
     * no packet and no lookup. Paired with {@link #worn} rather than replacing it: one gives the template, this
     * gives the copy.
     */
    public static EquippedCosmetic equipped(UUID player, CosmeticSlot slot)
    {
        return player == null || slot == null ? null : wardrobe(player).worn(slot);
    }
}
