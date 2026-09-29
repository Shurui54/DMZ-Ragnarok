package net.shurui.shuruisutilities.commands.player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Overworld-attached saved data holding the UUIDs of vanished players so admin invisibility survives a server
 * restart. Mirrors SU's existing PrestigeSettings SavedData pattern (persisted with the overworld). The
 * in-memory Set in VanishState stays the fast path; this is the durable backing store written on every toggle
 * and read at login time (when the in-memory set may not be populated yet).
 *
 * <h2>Why a timestamp per player</h2>
 * Vanish travels between servers through {@code ShardStateSync}, and a whole-set last-write-wins would clobber a
 * toggle made for one admin with a toggle made for another on a sibling server inside the same interval, while a
 * union-only merge could never carry an UN-vanish (a removal). So every toggle records WHEN it happened, per
 * player, and the merge keeps the later toggle per player: that carries both vanish and un-vanish, and one admin's
 * state is never overwritten by another's. A player is on one server at a time, so the most recently stamped copy
 * is always the live one.
 */
public final class VanishStorage extends SavedData
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

    private static final String NAME = "shuruisutilities_vanish";

    private final Set<UUID> vanished = new HashSet<>();

    // Last time each player's vanish state was toggled, kept for BOTH vanished and un-vanished players so the merge
    // can carry a removal. Rare, staff-only writes, so the map staying small is not a concern.
    private final Map<UUID, Long> stampedAt = new HashMap<>();

    public static VanishStorage get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(VanishStorage::load, VanishStorage::new, NAME);
    }

    private static VanishStorage load(CompoundTag tag)
    {
        VanishStorage s = new VanishStorage();
        ListTag list = tag.getList("vanished", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < list.size(); i++)
            s.vanished.add(net.minecraft.nbt.NbtUtils.loadUUID(list.get(i)));
        // Stamps were added after the first release; an older .dat has none, so those players load with no stamp and
        // any incoming sync state wins for them once, which is the safe direction on a one-time upgrade.
        CompoundTag stamps = tag.getCompound("stamps");
        for (String key : stamps.getAllKeys())
        {
            try
            {
                s.stampedAt.put(UUID.fromString(key), stamps.getLong(key));
            }
            catch (IllegalArgumentException ignored)
            {
                // skip malformed uuid keys rather than failing the whole load
            }
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        ListTag list = new ListTag();
        for (UUID uuid : vanished)
            list.add(net.minecraft.nbt.NbtUtils.createUUID(uuid));
        tag.put("vanished", list);
        CompoundTag stamps = new CompoundTag();
        for (Map.Entry<UUID, Long> e : stampedAt.entrySet())
            stamps.putLong(e.getKey().toString(), e.getValue());
        tag.put("stamps", stamps);
        return tag;
    }

    public Set<UUID> all()
    {
        return new HashSet<>(vanished);
    }

    public boolean contains(UUID uuid)
    {
        return vanished.contains(uuid);
    }

    public void set(UUID uuid, boolean vanished)
    {
        boolean changed = vanished ? this.vanished.add(uuid) : this.vanished.remove(uuid);
        if (changed)
        {
            // Stamp every real toggle, so the merge on another server can tell this un-vanish from a stale vanish.
            stampedAt.put(uuid, System.currentTimeMillis());
            setDirty();
        }
    }

    // cross-server state sync write path: adopt a sibling server's state for a player only when it is NEWER than
    // ours, per player. That carries both a vanish and an un-vanish across servers, and cannot clobber a toggle
    // made here for a different player. See the class note for why a plain set replace would be wrong.
    public void mergeInto(CompoundTag tag)
    {
        boolean changed = false;
        Set<UUID> incomingVanished = new HashSet<>();
        ListTag list = tag.getList("vanished", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < list.size(); i++)
            incomingVanished.add(net.minecraft.nbt.NbtUtils.loadUUID(list.get(i)));
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
            boolean incomingIsVanished = incomingVanished.contains(uuid);
            boolean localIsVanished = vanished.contains(uuid);
            if (incomingIsVanished != localIsVanished)
            {
                if (incomingIsVanished)
                    vanished.add(uuid);
                else
                    vanished.remove(uuid);
                // Keep VanishState's in-memory fast path (what every visibility check reads) in step with this
                // durable store, so a remote toggle is honoured for the count, the tab list and chat on this shard
                // without waiting for a restart. See VanishState#applyReplicated.
                VanishState.applyReplicated(uuid, incomingIsVanished);
            }
            stampedAt.put(uuid, incomingStamp);
            changed = true;
        }
        if (changed)
            setDirty();
    }
}
