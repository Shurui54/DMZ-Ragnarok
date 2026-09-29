package net.shurui.dev.sdu.waypoint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// world-save persistence for manual waypoints, keyed by player UUID, on the overworld's data storage as
// sdu_waypoints. quest waypoints aren't kept here (recomputed live by WaypointTracker). names unique per
// player (case-insensitive): re-setting a name replaces it, keeping /rg npc waypoint set idempotent.
public class WaypointStore extends SavedData {

    // Cross-shard state sync change signal: a monotonic counter bumped on every mutation. NEVER reset (unlike
    // SavedData's own dirty flag, which the autosave clears), so ShardStateSync can skip rebuilding this store's NBT
    // while it has not moved and can never miss a change. See ShardStateSync.register.
    private long shardDirtyVersion;

    @Override
    public void setDirty() {
        shardDirtyVersion++;
        super.setDirty();
    }

    /** Monotonic mutation counter for the cross-shard state sync; see the field note. */
    public long shardDirtyVersion() {
        return shardDirtyVersion;
    }

    private static final String NAME = "sdu_waypoints";

    private final Map<UUID, List<Waypoint>> byPlayer = new ConcurrentHashMap<>();

    // Last time each player's list changed, for a per-player last-write-wins cross-server merge. A set belongs
    // to one player who is on one server at a time, so the active server holds the newest stamp. Kept even after
    // a clear (empty list, fresh stamp) so a removal carries across, like FormCosmeticData. See mergeInto.
    private final Map<UUID, Long> stampedAt = new ConcurrentHashMap<>();

    public static WaypointStore get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(WaypointStore::load, WaypointStore::new, NAME);
    }

    public List<Waypoint> get(UUID player) {
        List<Waypoint> list = byPlayer.get(player);
        return list == null ? List.of() : new ArrayList<>(list);
    }

    // add or replace by case-insensitive name.
    public void set(UUID player, Waypoint wp) {
        List<Waypoint> list = byPlayer.computeIfAbsent(player, k -> new ArrayList<>());
        list.removeIf(w -> w.name.equalsIgnoreCase(wp.name));
        list.add(wp);
        stampedAt.put(player, System.currentTimeMillis());
        setDirty();
    }

    // true if one was removed.
    public boolean remove(UUID player, String name) {
        List<Waypoint> list = byPlayer.get(player);
        if (list == null) {
            return false;
        }
        boolean removed = list.removeIf(w -> w.name.equalsIgnoreCase(name));
        if (list.isEmpty()) {
            byPlayer.remove(player);
        }
        if (removed) {
            stampedAt.put(player, System.currentTimeMillis());
            setDirty();
        }
        return removed;
    }

    // returns how many were removed.
    public int clear(UUID player) {
        List<Waypoint> list = byPlayer.remove(player);
        if (list == null || list.isEmpty()) {
            return 0;
        }
        stampedAt.put(player, System.currentTimeMillis());
        setDirty();
        return list.size();
    }

    /**
     * Cross-server merge write path: adopt a sibling server's list for a player only when its stamp is NEWER than
     * ours, per player. Carries a set, rename and full clear across a hop, and never drops another player's list
     * the way a whole-table replace would. Absence is not a tombstone: a player the payload does not mention is
     * left alone.
     */
    public void mergeInto(CompoundTag tag) {
        boolean changed = false;
        Map<UUID, List<Waypoint>> incoming = new java.util.HashMap<>();
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag entry = players.getCompound(i);
            if (!entry.hasUUID("uuid")) {
                continue;
            }
            UUID id = entry.getUUID("uuid");
            List<Waypoint> list = new ArrayList<>();
            ListTag wps = entry.getList("waypoints", Tag.TAG_COMPOUND);
            for (int j = 0; j < wps.size(); j++) {
                list.add(Waypoint.fromNbt(wps.getCompound(j)));
            }
            incoming.put(id, list);
        }
        CompoundTag stamps = tag.getCompound("stamps");
        for (String key : stamps.getAllKeys()) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            long incomingStamp = stamps.getLong(key);
            long localStamp = stampedAt.getOrDefault(id, 0L);
            if (incomingStamp <= localStamp) {
                continue;
            }
            List<Waypoint> list = incoming.get(id);
            if (list == null || list.isEmpty()) {
                byPlayer.remove(id);
            } else {
                byPlayer.put(id, list);
            }
            stampedAt.put(id, incomingStamp);
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    public static WaypointStore load(CompoundTag tag) {
        WaypointStore store = new WaypointStore();
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag entry = players.getCompound(i);
            UUID id = entry.getUUID("uuid");
            List<Waypoint> list = new ArrayList<>();
            ListTag wps = entry.getList("waypoints", Tag.TAG_COMPOUND);
            for (int j = 0; j < wps.size(); j++) {
                list.add(Waypoint.fromNbt(wps.getCompound(j)));
            }
            if (!list.isEmpty()) {
                store.byPlayer.put(id, list);
            }
        }
        // Stamps were added after the first release; an older .dat has none, so those players load unstamped and
        // any incoming sync state wins once, the safe direction on a one-time upgrade.
        CompoundTag stamps = tag.getCompound("stamps");
        for (String key : stamps.getAllKeys()) {
            try {
                store.stampedAt.put(UUID.fromString(key), stamps.getLong(key));
            } catch (IllegalArgumentException ignored) {
                // skip malformed uuid keys rather than failing the whole load
            }
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, List<Waypoint>> e : byPlayer.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("uuid", e.getKey());
            ListTag wps = new ListTag();
            for (Waypoint w : e.getValue()) {
                wps.add(w.toNbt());
            }
            entry.put("waypoints", wps);
            players.add(entry);
        }
        tag.put("players", players);
        CompoundTag stamps = new CompoundTag();
        for (Map.Entry<UUID, Long> e : stampedAt.entrySet()) {
            stamps.putLong(e.getKey().toString(), e.getValue());
        }
        tag.put("stamps", stamps);
        return tag;
    }
}
