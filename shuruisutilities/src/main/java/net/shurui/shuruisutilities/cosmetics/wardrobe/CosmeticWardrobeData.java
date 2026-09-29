package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 * What every player is wearing, keyed by UUID.
 *
 * <h2>DO NOT RENAME THIS CLASS, AND DO NOT RENAME {@link #NAME}</h2>
 * Same rule as {@link CosmeticLedgerData}: {@link #NAME} is the SavedData file's stable id and renaming it
 * silently orphans every player's outfit. The class name matters too, because {@code DataManager.getTypePath}
 * names folders after Java simple class names and a future contributor may reach for it.
 *
 * <p>Kept out of SU's shared {@code PlayerInfo} deliberately, for the reasons {@code FormCosmeticData} gives for
 * the same decision: this record is self-contained, is read on the server and pushed to clients through one sync
 * packet, and has its own lifecycle, so keeping it out of the large shared blob avoids coupling a cosmetic to
 * core player state and keeps its wire format under this feature's sole control.
 *
 * <h2>Stamped per player, so a CLEAR propagates</h2>
 * A stamp is kept for a player whether or not they are wearing anything, which is the whole point:
 * {@code FormCosmeticData} records that "a plain union merge could never propagate a clear, so a cosmetic turned
 * off on one server would come back on the next hop". Unequipping is exactly that problem, so it gets exactly
 * that answer. Last-write-wins per player, which for what somebody has on has no cheat direction, because only
 * the server they are standing on ever stamps a change for them.
 */
public final class CosmeticWardrobeData extends SavedData
{
    // NOTE: never rename (SavedData file name is a stable id). See the class note.
    private static final String NAME = "shuruisutilities_cosmetic_wardrobe";

    /**
     * Cross-shard state sync change signal: a monotonic counter bumped on every mutation. NEVER reset (unlike
     * SavedData's own dirty flag, which the autosave clears), so ShardStateSync can skip rebuilding this store's
     * NBT while it has not moved and can never miss a change.
     */
    private long shardDirtyVersion;

    @Override
    public void setDirty()
    {
        shardDirtyVersion++;
        super.setDirty();
    }

    public long shardDirtyVersion()
    {
        return shardDirtyVersion;
    }

    private final Map<UUID, PlayerWardrobe> byPlayer = new LinkedHashMap<>();

    /**
     * Whether the one-time fold of the four retired single-direction triggered slots into the two paired slots has
     * run on THIS file. Persisted so it runs once per shard rather than every start. Not part of the cross-shard
     * content hash: each shard folds its own copy, and {@link PlayerWardrobe#worn} falls back for any un-migrated
     * record that arrives from a slower sibling, so the flag is a local optimisation, not authority.
     */
    private boolean pairedSlotMigrationDone;

    /**
     * Last time each player's outfit was changed, kept for BOTH states so the merge can carry a clear as well as
     * a set. See the class note.
     */
    private final Map<UUID, Long> stampedAt = new HashMap<>();

    public static CosmeticWardrobeData get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(CosmeticWardrobeData::load, CosmeticWardrobeData::new,
                NAME);
    }

    private static CosmeticWardrobeData load(CompoundTag tag)
    {
        CosmeticWardrobeData d = new CosmeticWardrobeData();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag e = list.getCompound(i);
            if (!e.hasUUID("uuid"))
                continue;
            d.byPlayer.put(e.getUUID("uuid"), PlayerWardrobe.fromNbt(e.getCompound("wardrobe")));
        }
        CompoundTag stamps = tag.getCompound("stamps");
        for (String key : stamps.getAllKeys())
        {
            try
            {
                d.stampedAt.put(UUID.fromString(key), stamps.getLong(key));
            }
            catch (IllegalArgumentException ignored)
            {
                // skip a malformed uuid key rather than failing the whole load
            }
        }
        d.pairedSlotMigrationDone = tag.getBoolean("pairedSlotMigrationDone");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        writeTo(tag, null);
        // Local-file only, deliberately NOT in writeTo: the cross-shard state tags (saveState / saveStateFor) must
        // not carry it, so it never moves the content hash. See the field note.
        tag.putBoolean("pairedSlotMigrationDone", pairedSlotMigrationDone);
        return tag;
    }

    /**
     * Fold every stored player's retired single-direction triggered slots into the two paired slots, once per file.
     * Each player whose outfit actually changes is RE-STAMPED with the current wall clock so the fold propagates to
     * sibling shards exactly as an ordinary equip edit would, through the same {@link #mergeInto} path. Runs from
     * {@code ServerStartedEvent}; a no-op after the first time it completes on this file.
     */
    public void migratePairedSlots()
    {
        if (pairedSlotMigrationDone)
            return;
        int changed = 0;
        for (Map.Entry<UUID, PlayerWardrobe> e : byPlayer.entrySet())
        {
            if (e.getValue() != null && e.getValue().migratePairedSlots())
            {
                stampedAt.put(e.getKey(), System.currentTimeMillis());
                changed++;
            }
        }
        pairedSlotMigrationDone = true;
        // setDirty even with no change, so the completion flag is persisted and the pass does not re-run next boot.
        setDirty();
        if (changed > 0)
            net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.info(
                    "[Cosmetics] Folded {} player outfit(s) from the retired join/leave/tp slots into join_leave/teleport.",
                    changed);
    }

    /** The whole table as one tag, for the shard state sync. */
    public CompoundTag saveState()
    {
        return writeTo(new CompoundTag(), null);
    }

    /** Only ONE player's entry, for the snapshot that travels with a hopping player. */
    public CompoundTag saveStateFor(UUID player)
    {
        return writeTo(new CompoundTag(), player);
    }

    /**
     * @param only when non-null, write just this player's entry and stamp
     */
    private CompoundTag writeTo(CompoundTag tag, UUID only)
    {
        // Sorted by uuid so two shards holding the same table produce byte-identical NBT whatever order the
        // entries were inserted in; otherwise the state sync's content hash differs between converged servers and
        // they republish the same state forever.
        List<UUID> ordered = new ArrayList<>(byPlayer.keySet());
        ordered.sort(java.util.Comparator.comparing(UUID::toString));
        ListTag list = new ListTag();
        for (UUID id : ordered)
        {
            if (only != null && !only.equals(id))
                continue;
            CompoundTag e = new CompoundTag();
            e.putUUID("uuid", id);
            e.put("wardrobe", byPlayer.get(id).toNbt());
            list.add(e);
        }
        tag.put("entries", list);
        CompoundTag stamps = new CompoundTag();
        List<UUID> stampIds = new ArrayList<>(stampedAt.keySet());
        stampIds.sort(java.util.Comparator.comparing(UUID::toString));
        for (UUID id : stampIds)
        {
            if (only != null && !only.equals(id))
                continue;
            stamps.putLong(id.toString(), stampedAt.get(id));
        }
        tag.put("stamps", stamps);
        return tag;
    }

    /**
     * Adopt a sibling server's outfits, per player, only when theirs is NEWER than ours.
     *
     * <p>Carries both a set and a clear across a hop, and cannot clobber a change made here for a different
     * player, which a whole-table replace would. Idempotent: replaying the same tag changes nothing the second
     * time, which is what lets the periodic state sync and the per-player vault snapshot share this one function
     * instead of needing a second sync path.
     */
    public void mergeInto(CompoundTag tag)
    {
        if (tag == null)
            return;
        boolean changed = false;
        Map<UUID, PlayerWardrobe> incoming = new HashMap<>();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag e = list.getCompound(i);
            if (!e.hasUUID("uuid"))
                continue;
            incoming.put(e.getUUID("uuid"), PlayerWardrobe.fromNbt(e.getCompound("wardrobe")));
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
            PlayerWardrobe in = incoming.get(uuid);
            // A newer stamp with no entry is a deliberate "wearing nothing", not an omission, which is what makes
            // an unequip survive a hop.
            if (in != null && !in.isEmpty())
                byPlayer.put(uuid, in);
            else
                byPlayer.remove(uuid);
            stampedAt.put(uuid, incomingStamp);
            changed = true;
        }
        if (changed)
            setDirty();
    }

    /** This player's outfit, as a COPY. Never null: an unknown player is simply wearing nothing. */
    public PlayerWardrobe get(UUID uuid)
    {
        if (uuid == null)
            return new PlayerWardrobe();
        PlayerWardrobe w = byPlayer.get(uuid);
        return w == null ? new PlayerWardrobe() : w.copy();
    }

    /** Store this player's outfit and stamp it. An empty outfit is stored as an absence with a live stamp. */
    public void set(UUID uuid, PlayerWardrobe wardrobe)
    {
        if (uuid == null)
            return;
        if (wardrobe == null || wardrobe.isEmpty())
            byPlayer.remove(uuid);
        else
            byPlayer.put(uuid, wardrobe.copy());
        // Stamp every real change, including a clear, so the merge on another server can tell this from a stale
        // set and carry the removal across a hop.
        stampedAt.put(uuid, System.currentTimeMillis());
        setDirty();
    }

    /** A snapshot copy of every stored outfit, for building a full-table client sync. */
    public Map<UUID, PlayerWardrobe> all()
    {
        Map<UUID, PlayerWardrobe> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, PlayerWardrobe> e : byPlayer.entrySet())
            out.put(e.getKey(), e.getValue().copy());
        return out;
    }
}
