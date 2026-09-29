package net.shurui.dev.shuruis_raid_bosses.rift;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Which arena cell belongs to which rift.
 *
 * <p>An arena is a place: a rift keeps the SAME cell for the life of the world, so anything built in it
 * (admin dressing or a player mid-fight) is there next time. Persisted, not in memory like the old claim
 * table, because a cell index that changed across a restart would send the rift to an empty patch and
 * orphan the build.
 *
 * <p>No "has it been built" record on purpose: the builder never writes inside the arena, so a build
 * survives by construction rather than by being remembered.
 */
public class RiftArenaData extends SavedData {

    private static final String NAME = "shuruis_raid_bosses_rift_arenas";

    /** Rift id -> the cell index that rift's tears always lead to. */
    private final Map<String, Integer> cells = new LinkedHashMap<>();

    /**
     * Rift id -> the Z lane that rift's cell sits in. Written when a cell is assigned or moved. A rift persisted
     * before the lane moved has no entry here and defaults to {@link RiftArenaSlots#LEGACY_LANE_Z}, so its existing
     * build stays exactly where it is; NEW cells (and any move off a stamped cell) use {@link RiftArenaSlots#LANE_Z}.
     */
    private final Map<String, Integer> lanes = new LinkedHashMap<>();

    /** The lane a rift's cell sits in. Defaults to the OLD lane for rifts persisted before this field existed. */
    public int laneFor(String riftId) {
        if (riftId == null || riftId.isBlank()) {
            return RiftArenaSlots.LANE_Z;
        }
        return lanes.getOrDefault(riftId.toLowerCase(Locale.ROOT), RiftArenaSlots.LEGACY_LANE_Z);
    }

    public static RiftArenaData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(RiftArenaData::load, RiftArenaData::new, NAME);
    }

    /**
     * The cell this rift owns, assigning the lowest free one on first ask.
     *
     * @return cell index, or -1 when every cell is spoken for
     */
    public int cellFor(String riftId) {
        if (riftId == null || riftId.isBlank()) {
            return -1;
        }
        String key = riftId.toLowerCase(Locale.ROOT);
        Integer existing = cells.get(key);
        if (existing != null) {
            return existing;
        }
        for (int index = 0; index < RiftArenaSlots.MAX_CELLS; index++) {
            if (!cells.containsValue(index)) {
                cells.put(key, index);
                lanes.put(key, RiftArenaSlots.LANE_Z); // a freshly assigned cell lives in the new lane
                setDirty();
                return index;
            }
        }
        return -1;
    }

    /**
     * Move a rift onto a different cell, freeing the one it has now. Only used to get a rift OFF an arena
     * an old build stamped a flat disc into (see the key's {@code RiftLegacyArena}). A move, not a repair, because
     * the stamp deleted the terrain it covered and cannot be given its landscape back from here; a fresh
     * cell generates one for free. Anything built in the OLD arena stays there, unreachable.
     *
     * @return new cell index, or -1 when there is no free cell to move to (the rift keeps the old one)
     */
    public int reassignCell(String riftId, int currentCell) {
        if (riftId == null || riftId.isBlank()) {
            return -1;
        }
        String key = riftId.toLowerCase(Locale.ROOT);
        for (int index = 0; index < RiftArenaSlots.MAX_CELLS; index++) {
            if (index != currentCell && !cells.containsValue(index)) {
                cells.put(key, index);
                // A move abandons the old build anyway, so take the new (collision-free) lane: a rift that had to
                // leave an old-lane cell a dungeon epoch stamped ends up where no epoch can reach it again.
                lanes.put(key, RiftArenaSlots.LANE_Z);
                setDirty();
                return index;
            }
        }
        return -1;
    }

    /** forget a rift's cell, freeing it for reuse; called when a rift is deleted */
    public void releaseCell(String riftId) {
        if (riftId != null) {
            String key = riftId.toLowerCase(Locale.ROOT);
            boolean changed = cells.remove(key) != null;
            changed |= lanes.remove(key) != null;
            if (changed) {
                setDirty();
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag cellTag = new CompoundTag();
        cells.forEach(cellTag::putInt);
        tag.put("cells", cellTag);
        CompoundTag laneTag = new CompoundTag();
        lanes.forEach(laneTag::putInt);
        tag.put("lanes", laneTag);
        return tag;
    }

    public static RiftArenaData load(CompoundTag tag) {
        RiftArenaData data = new RiftArenaData();
        CompoundTag cellTag = tag.getCompound("cells");
        for (String key : cellTag.getAllKeys()) {
            data.cells.put(key, cellTag.getInt(key));
        }
        // A save written before lanes existed has no "lanes" tag: every stored cell defaults to the OLD lane via
        // laneFor, so an existing arena (and any admin dressing in it) stays exactly where its build is. A newer
        // save carries the explicit lane per rift.
        CompoundTag laneTag = tag.getCompound("lanes");
        for (String key : laneTag.getAllKeys()) {
            data.lanes.put(key, laneTag.getInt(key));
        }
        return data;
    }
}
