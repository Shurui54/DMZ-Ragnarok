package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

// which dungeon floors each player has unlocked by redeeming a floor ticket.
//
// Redeeming a ticket grants PERMANENT access to that floor's ticket-locked portals for that player, and spends the
// ticket. A possession check needs no storage but makes the ticket a keycard you keep forever (lent, duplicated,
// never spent); recording the unlock means the ticket is consumed once and the access it bought outlives it.
//
// Stored on the OVERWORLD data storage, like DungeonCooldowns: per player, dimension-independent, and readable while
// the player stands at a portal anywhere.
public class DungeonTicketUnlocks extends SavedData {

    // Pinned literal: the on-disk data/<NAME>.dat filename. Deriving it from MODID would silently orphan every
    // player's unlocks if MODID is renamed.
    public static final String NAME = "shuruis_dmz_dungeons_ticket_unlocks";

    private final Map<UUID, Set<Integer>> unlocked = new HashMap<>();

    public DungeonTicketUnlocks() {
    }

    public static DungeonTicketUnlocks get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(DungeonTicketUnlocks::load, DungeonTicketUnlocks::new, NAME);
    }

    // true when this player has redeemed a ticket for this floor.
    public boolean isUnlocked(UUID id, int floor) {
        if (id == null || floor <= 0) {
            return false;
        }
        Set<Integer> floors = unlocked.get(id);
        return floors != null && floors.contains(floor);
    }

    // record an unlock. false if the player already had it, so the caller can decline to spend a second ticket.
    public boolean unlock(UUID id, int floor) {
        if (id == null || floor <= 0) {
            return false;
        }
        if (!unlocked.computeIfAbsent(id, k -> new HashSet<>()).add(floor)) {
            return false;
        }
        setDirty();
        return true;
    }

    // take an unlock back, for an operator undoing a mistake. returns whether anything was actually removed.
    public boolean lock(UUID id, int floor) {
        Set<Integer> floors = id == null ? null : unlocked.get(id);
        if (floors == null || !floors.remove(floor)) {
            return false;
        }
        if (floors.isEmpty()) {
            unlocked.remove(id);
        }
        setDirty();
        return true;
    }

    // every floor this player has unlocked, ascending, for reporting.
    public Set<Integer> floorsFor(UUID id) {
        Set<Integer> floors = id == null ? null : unlocked.get(id);
        return floors == null ? Set.of() : new TreeSet<>(floors);
    }

    // cross-server state sync write path: UNION the unlocks a sibling holds into this one rather than replacing. An
    // unlock is EARNED by spending a ticket, so never drop one: two players redeeming on two servers in a sync window
    // both keep their floor. The cost: a rare operator lock() (undoing a mistake) can be re-added by a server that
    // still remembers the unlock. That is the right way to lose the tie: resurrecting a paid-for unlock beats deleting one.
    public void mergeInto(CompoundTag tag) {
        boolean changed = false;
        CompoundTag map = tag.getCompound("unlocks");
        for (String key : map.getAllKeys()) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            ListTag list = map.getList(key, Tag.TAG_INT);
            for (int i = 0; i < list.size(); i++) {
                int floor = list.getInt(i);
                if (floor > 0 && unlocked.computeIfAbsent(id, k -> new HashSet<>()).add(floor)) {
                    changed = true;
                }
            }
        }
        if (changed) {
            setDirty();
        }
    }

    public static DungeonTicketUnlocks load(CompoundTag tag) {
        DungeonTicketUnlocks u = new DungeonTicketUnlocks();
        CompoundTag map = tag.getCompound("unlocks");
        for (String key : map.getAllKeys()) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue; // skip malformed uuid keys rather than failing the whole load
            }
            Set<Integer> floors = new HashSet<>();
            ListTag list = map.getList(key, Tag.TAG_INT);
            for (int i = 0; i < list.size(); i++) {
                int floor = list.getInt(i);
                if (floor > 0) {
                    floors.add(floor);
                }
            }
            if (!floors.isEmpty()) {
                u.unlocked.put(id, floors);
            }
        }
        return u;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag map = new CompoundTag();
        for (Map.Entry<UUID, Set<Integer>> e : unlocked.entrySet()) {
            ListTag list = new ListTag();
            for (int floor : new TreeSet<>(e.getValue())) {
                list.add(net.minecraft.nbt.IntTag.valueOf(floor));
            }
            map.put(e.getKey().toString(), list);
        }
        tag.put("unlocks", map);
        return tag;
    }
}
