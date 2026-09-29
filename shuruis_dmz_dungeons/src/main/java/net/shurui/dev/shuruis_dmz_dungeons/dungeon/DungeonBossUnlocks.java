package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

// Per-PLAYER record of which BOSS floors a player has personally cleared, stored in Forge's PlayerPersisted sub-tag
// so it survives death AND travels with the player across shards through the shard vault. This is the SAME tag
// DungeonTimeEvents.runData writes to: the ROOT persistent data is local to one server's playerdata file and is
// dropped on death, while PlayerPersisted (Player.PERSISTED_NBT_TAG) is what the vault carries between shards.
//
// WHY this exists alongside the per-shard DungeonFloors.bossDefeated set: boss kills are deliberately PER SHARD (ow1
// and ow2 are independent, and stay so). But a player who legitimately cleared a boss floor must never be re-blocked
// by a portal later just because THIS shard's boss is still standing, which happens on a different shard, on a
// regenerated / relocated floor, or against a respawned guardian. This record is that personal proof.
//
// It only ever GRANTS access. The portal gate (UtilitiesPortalCompat) opens a floor if the shard's boss requirement
// is met OR the player personally holds the clear, so the shard's own boss state is never weakened, only
// supplemented. Nothing here reads or writes DungeonFloors, so shard independence is preserved.
public final class DungeonBossUnlocks {

    // int-array in the PlayerPersisted sub-tag: the 1-based BOSS floor numbers this player has personally cleared.
    // Namespaced so it never collides with DungeonTimeEvents' own keys in the same sub-tag.
    private static final String KEY = "sdd_boss_floor_clears";

    private DungeonBossUnlocks() {
    }

    // Forge's PlayerPersisted sub-tag, created on demand. Returns the live nested compound (not a copy), so a
    // putIntArray on it is stored under the root and travels with the player.
    private static CompoundTag persisted(Player player) {
        CompoundTag root = player.getPersistentData();
        String key = Player.PERSISTED_NBT_TAG;
        if (!root.contains(key, Tag.TAG_COMPOUND)) {
            root.put(key, new CompoundTag());
        }
        return root.getCompound(key);
    }

    // has this player personally cleared the given boss floor?
    public static boolean hasCleared(Player player, int floor) {
        if (player == null || floor <= 0) {
            return false;
        }
        for (int n : persisted(player).getIntArray(KEY)) {
            if (n == floor) {
                return true;
            }
        }
        return false;
    }

    // record a personal clear of a boss floor. returns true if it was newly added (already-held is a no-op). Idempotent,
    // so it is safe to call on every gate pass and every boss kill the player witnesses.
    public static boolean recordCleared(Player player, int floor) {
        if (player == null || floor <= 0) {
            return false;
        }
        CompoundTag data = persisted(player);
        int[] existing = data.getIntArray(KEY);
        for (int n : existing) {
            if (n == floor) {
                return false;
            }
        }
        int[] next = Arrays.copyOf(existing, existing.length + 1);
        next[existing.length] = floor;
        data.putIntArray(KEY, next);
        return true;
    }

    // take a personal clear back, for an operator undoing a mistake. returns whether anything was removed.
    public static boolean removeCleared(Player player, int floor) {
        if (player == null || floor <= 0) {
            return false;
        }
        CompoundTag data = persisted(player);
        int[] existing = data.getIntArray(KEY);
        int[] next = new int[existing.length];
        int len = 0;
        for (int n : existing) {
            if (n != floor) {
                next[len++] = n;
            }
        }
        if (len == existing.length) {
            return false;
        }
        data.putIntArray(KEY, Arrays.copyOf(next, len));
        return true;
    }

    // every boss floor this player has personally cleared, ascending, for reporting.
    public static Set<Integer> clearedFloors(Player player) {
        Set<Integer> floors = new TreeSet<>();
        if (player != null) {
            for (int n : persisted(player).getIntArray(KEY)) {
                if (n > 0) {
                    floors.add(n);
                }
            }
        }
        return floors;
    }
}
