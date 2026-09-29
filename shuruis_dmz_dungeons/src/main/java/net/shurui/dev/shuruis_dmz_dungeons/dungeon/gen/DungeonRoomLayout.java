package net.shurui.dev.shuruis_dmz_dungeons.dungeon.gen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

// the persisted room layout for ONE floor: the seed it was rolled with, the ordered placement list, and whether the
// blocks have finished being written. This is the "run once, never re-roll" record (hard user requirement, floors are
// hand-detailed):
//   * once placements is non-empty it is NEVER regenerated, so the exact same rooms sit in the exact same cells
//     forever, across a floor-count change or a config edit.
//   * placed is set only when the tick-budgeted writer finishes, so a placement interrupted by a server stop resumes
//     from its persisted layout on the next visit (idempotent writes) rather than re-rolling.
// Stored inside DungeonFloors (keyed by floor number) so it travels with the dungeon save.
public final class DungeonRoomLayout {

    // sentinel for "surface Y not resolved yet"; a real generator surface is always inside [min_y, ceiling].
    public static final int NO_ORIGIN_Y = Integer.MIN_VALUE;

    public final long seed;
    public final String style;
    public final List<GridPlacement> placements;
    public boolean placed;
    // set only when the enclosure pass finishes (outward-face sealing on enclosed archetypes, then a verify; no longer
    // carves, see EnclosurePass). Separate from `placed` so an old-build floor gets the pass on its next visit and an
    // interrupted pass re-runs (writes are idempotent).
    public boolean sealed;
    // set only when the block-connection pass finishes. Separate from placed/sealed for the same upgrade-on-revisit
    // reason (idempotent: it recomputes connection state from the finished neighbours).
    public boolean connected;

    /**
     * The ground pass has run: open columns between rooms are covered, so a player cannot walk off the street into
     * whatever the dimension generated below. Absent on an older save, so the floor gets the pass on its next visit.
     */
    public boolean grounded;
    // set only when the crate-conversion pass finishes (placed vanilla chests -> crate_chest, a promoted share of
    // barrels -> crate_barrel). Separate for the same upgrade-on-revisit reason (idempotent: only real vanilla
    // chests/barrels are touched).
    public boolean crated;
    // set only when the spawner-placement pass finishes (an advanced spawn block under each harvested enemy marker,
    // disguised as the floor block). Separate for the same reason (idempotent: a marker already backed by a spawner is
    // skipped). A floor with enemies disabled or an empty preset list never queues the pass, so this stays false and
    // it runs later if enemies are enabled on a revisit.
    public boolean spawned;
    // world Y of the floor's grid origin, resolved from the generator surface the first time the floor is built then
    // FROZEN here. Persisting it keeps the room layer anchored across a restart mid-build (the surface heightmap would
    // otherwise shift once rooms carve into it).
    public int originY;

    public DungeonRoomLayout(long seed, String style, List<GridPlacement> placements, boolean placed) {
        this(seed, style, placements, placed, NO_ORIGIN_Y);
    }

    public DungeonRoomLayout(long seed, String style, List<GridPlacement> placements, boolean placed, int originY) {
        this.seed = seed;
        this.style = style;
        this.placements = placements;
        this.placed = placed;
        this.originY = originY;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("seed", seed);
        tag.putString("style", style == null ? "" : style);
        tag.putBoolean("placed", placed);
        tag.putBoolean("sealed", sealed);
        tag.putBoolean("connected", connected);
        tag.putBoolean("grounded", grounded);
        tag.putBoolean("crated", crated);
        tag.putBoolean("spawned", spawned);
        tag.putInt("originY", originY);
        ListTag list = new ListTag();
        for (GridPlacement p : placements) {
            list.add(p.save());
        }
        tag.put("placements", list);
        return tag;
    }

    public static DungeonRoomLayout load(CompoundTag tag) {
        List<GridPlacement> placements = new ArrayList<>();
        ListTag list = tag.getList("placements", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            placements.add(GridPlacement.load(list.getCompound(i)));
        }
        int originY = tag.contains("originY") ? tag.getInt("originY") : NO_ORIGIN_Y;
        DungeonRoomLayout layout = new DungeonRoomLayout(tag.getLong("seed"), tag.getString("style"), placements,
                tag.getBoolean("placed"), originY);
        layout.sealed = tag.getBoolean("sealed");
        layout.connected = tag.getBoolean("connected");
        layout.grounded = tag.getBoolean("grounded"); // absent on old saves -> false
        layout.crated = tag.getBoolean("crated"); // absent on old saves -> false
        layout.spawned = tag.getBoolean("spawned"); // absent on old saves -> false
        return layout;
    }
}
