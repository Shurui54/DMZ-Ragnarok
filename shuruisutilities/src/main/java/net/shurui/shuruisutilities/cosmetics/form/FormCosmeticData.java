package net.shurui.shuruisutilities.cosmetics.form;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Overworld-attached saved data holding each player's single form cosmetic override, keyed by UUID. Chosen over
 * SU's {@code PlayerInfo} deliberately: this record is self-contained, is only read on the server and pushed to
 * clients through one sync packet, and has its own on/off-with-entitlement lifecycle, so keeping it out of the
 * large shared PlayerInfo blob avoids coupling an optional cosmetic to core player state and keeps its wire format
 * under this feature's sole control. It survives relog and restart like any SavedData.
 *
 * <p>The record is retained even when a supporter lapses (so it returns intact if they resubscribe); whether it is
 * currently rendered is decided at broadcast time by {@link FormCosmeticManager}, never by deleting it here.
 */
public final class FormCosmeticData extends SavedData
{
    // Cross-shard state sync change signal: a monotonic counter bumped on every mutation. NEVER reset (unlike
    // SavedData's own dirty flag, which the autosave clears), so ShardStateSync can skip rebuilding this store's NBT
    // while it has not moved and can never miss a change. See ShardStateSync.register.
    private long shardDirtyVersion;

    @Override
    public void setDirty()
    {
        shardDirtyVersion++;
        super.setDirty();
    }

    /** Monotonic mutation counter for the cross-shard state sync; see the field note. */
    public long shardDirtyVersion()
    {
        return shardDirtyVersion;
    }

    // NOTE: never rename (SavedData file name is a stable id).
    private static final String NAME = "shuruisutilities_form_cosmetics";

    private final Map<UUID, FormCosmetic> byPlayer = new HashMap<>();

    // Last time each player's override was set or cleared, kept for BOTH states so the cross-server merge can carry a
    // clear as well as a set. A cosmetic preference is last-write-wins per player, and a stamp is what makes "last"
    // mean the same thing on every server: a plain union merge could never propagate a clear, so a cosmetic turned off
    // on one server would come back on the next hop. Same pattern as VanishStorage.
    private final Map<UUID, Long> stampedAt = new HashMap<>();

    public static FormCosmeticData get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(FormCosmeticData::load, FormCosmeticData::new, NAME);
    }

    private static FormCosmeticData load(CompoundTag tag)
    {
        FormCosmeticData d = new FormCosmeticData();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag e = list.getCompound(i);
            if (!e.hasUUID("uuid"))
                continue;
            d.byPlayer.put(e.getUUID("uuid"), FormCosmetic.fromNbt(e.getCompound("cosmetic")));
        }
        // Stamps were added after the first release; an older .dat has none, so those players load with no stamp and
        // any incoming sync state wins for them once, which is the safe direction on a one-time upgrade.
        CompoundTag stamps = tag.getCompound("stamps");
        for (String key : stamps.getAllKeys())
        {
            try
            {
                d.stampedAt.put(UUID.fromString(key), stamps.getLong(key));
            }
            catch (IllegalArgumentException ignored)
            {
                // skip malformed uuid keys rather than failing the whole load
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, FormCosmetic> en : byPlayer.entrySet())
        {
            CompoundTag e = new CompoundTag();
            e.putUUID("uuid", en.getKey());
            e.put("cosmetic", en.getValue().toNbt());
            list.add(e);
        }
        tag.put("entries", list);
        CompoundTag stamps = new CompoundTag();
        for (Map.Entry<UUID, Long> e : stampedAt.entrySet())
            stamps.putLong(e.getKey().toString(), e.getValue());
        tag.put("stamps", stamps);
        return tag;
    }

    /**
     * Cross-server state sync write path: adopt a sibling server's override for a player only when it is NEWER than
     * ours, per player. That carries both a set and a clear across a hop, and cannot clobber a change made here for a
     * different player, which a whole-table replace would. Last-write-wins per player: a cosmetic preference has no
     * cheat direction, so the later toggle simply wins. See the class note and VanishStorage for the same pattern.
     */
    public void mergeInto(CompoundTag tag)
    {
        boolean changed = false;
        Map<UUID, FormCosmetic> incoming = new HashMap<>();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag e = list.getCompound(i);
            if (!e.hasUUID("uuid"))
                continue;
            incoming.put(e.getUUID("uuid"), FormCosmetic.fromNbt(e.getCompound("cosmetic")));
        }
        CompoundTag stamps = tag.getCompound("stamps");
        for (String key : stamps.getAllKeys())
        {
            UUID uuid;
            try
            {
                uuid = UUID.fromString(key);
            }
            catch (IllegalArgumentException ignored)
            {
                continue;
            }
            long incomingStamp = stamps.getLong(key);
            long localStamp = stampedAt.getOrDefault(uuid, 0L);
            if (incomingStamp <= localStamp)
                continue;
            FormCosmetic in = incoming.get(uuid);
            if (in != null)
                byPlayer.put(uuid, in);
            else
                byPlayer.remove(uuid);
            stampedAt.put(uuid, incomingStamp);
            changed = true;
        }
        if (changed)
            setDirty();
    }

    /** The stored override for this player, or null. Never mutate the returned instance in place. */
    public FormCosmetic get(UUID uuid)
    {
        return uuid == null ? null : byPlayer.get(uuid);
    }

    /** Store (a sanitized copy of) this player's override and persist. */
    public void set(UUID uuid, FormCosmetic cosmetic)
    {
        if (uuid == null || cosmetic == null)
            return;
        byPlayer.put(uuid, cosmetic.copy().sanitize());
        // Stamp every real change, so the merge on another server can tell this set from a stale clear.
        stampedAt.put(uuid, System.currentTimeMillis());
        setDirty();
    }

    /** Remove this player's override and persist. Returns true if there was one. */
    public boolean clear(UUID uuid)
    {
        if (uuid == null)
            return false;
        boolean had = byPlayer.remove(uuid) != null;
        if (had)
        {
            // Retain a stamp with no cosmetic (a tombstone) so the merge on another server can tell this clear from a
            // stale set and carry the removal across a hop.
            stampedAt.put(uuid, System.currentTimeMillis());
            setDirty();
        }
        return had;
    }

    /** A snapshot copy of every stored override, for building a full-table sync. */
    public Map<UUID, FormCosmetic> all()
    {
        Map<UUID, FormCosmetic> out = new HashMap<>();
        for (Map.Entry<UUID, FormCosmetic> e : byPlayer.entrySet())
            out.put(e.getKey(), e.getValue().copy());
        return out;
    }
}
