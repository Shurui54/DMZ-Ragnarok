package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// per-player dungeon re-entry cooldown expiries, keyed by UUID, value = epoch millis the cooldown ends. Stored on the
// OVERWORLD data storage (like DungeonWarps) so it persists across restarts and is dimension-independent (the player
// is not in the dungeon dim while on cooldown).
public class DungeonCooldowns extends SavedData {

    // Pinned literal: the on-disk data/<NAME>.dat filename. Deriving it from MODID would silently orphan saved cooldowns if MODID is renamed.
    public static final String NAME = "shuruis_dmz_dungeons_dungeon_cooldowns";

    private final Map<UUID, Long> expiry = new HashMap<>();

    public DungeonCooldowns() {
    }

    public static DungeonCooldowns get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(DungeonCooldowns::load, DungeonCooldowns::new, NAME);
    }

    // true if the player still has an active cooldown at the given epoch-millis instant
    public boolean onCooldown(UUID id, long now) {
        Long end = expiry.get(id);
        if (end == null) {
            return false;
        }
        if (now >= end) {
            expiry.remove(id);
            setDirty();
            return false;
        }
        return true;
    }

    // remaining cooldown in whole seconds, or 0 if none
    public long remainingSeconds(UUID id, long now) {
        Long end = expiry.get(id);
        if (end == null || now >= end) {
            return 0;
        }
        return (end - now + 999) / 1000;
    }

    public void stamp(UUID id, long expiryMillis) {
        expiry.put(id, expiryMillis);
        setDirty();
    }

    // cross-server state sync write path: keep the LATER expiry per player rather than replacing the whole table. A
    // cooldown is a gate a player must WAIT OUT, so never shorten one: a player who entered on one server and hopped
    // to another must carry the wait, or the hop is a free reset. Max can only hold a cooldown open, never end it
    // early, and a player is on one server at a time. A stale past expiry is harmless: onCooldown prunes it.
    public void mergeInto(CompoundTag tag) {
        boolean changed = false;
        CompoundTag map = tag.getCompound("cooldowns");
        for (String key : map.getAllKeys()) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            long incoming = map.getLong(key);
            Long current = expiry.get(id);
            if (current == null || incoming > current) {
                expiry.put(id, incoming);
                changed = true;
            }
        }
        if (changed) {
            setDirty();
        }
    }

    public static DungeonCooldowns load(CompoundTag tag) {
        DungeonCooldowns c = new DungeonCooldowns();
        CompoundTag map = tag.getCompound("cooldowns");
        for (String key : map.getAllKeys()) {
            try {
                c.expiry.put(UUID.fromString(key), map.getLong(key));
            } catch (IllegalArgumentException ignored) {
                // skip malformed uuid keys
            }
        }
        return c;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag map = new CompoundTag();
        for (Map.Entry<UUID, Long> e : expiry.entrySet()) {
            map.putLong(e.getKey().toString(), e.getValue());
        }
        tag.put("cooldowns", map);
        return tag;
    }
}
