package net.shurui.dev.shuruis_raid_bosses.rift;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * Hands each open tear its own arena, an exclusive cell in the shared boss-arena dimension.
 *
 * <p>A tear is per player, so two fights must not see each other. A dimension per run is out (each costs
 * a full level, storage folder and chunk budget for a fight lasting minutes), so this borrows the dungeon
 * addon's approach: one dimension carved into a grid of cells spaced far enough apart that runs never
 * interact. Spacing matches the dungeon floors' {@code CELL_SPACING} (100,000 blocks), wider than any
 * view distance or ki attack and wide enough that two runs share no loaded chunk.
 *
 * <p>A cell belongs to a RIFT, not a run: {@link RiftArenaData} remembers which cell each rift owns for
 * the life of the world, so a build survives to the next tear. The only thing in memory here is live
 * occupancy (which run is in which cell now), correctly transient since no run survives a restart.
 */
public final class RiftArenaSlots {

    /** blocks between cells along X; matches the dungeon addon so two runs share no loaded chunk */
    public static final int CELL_SPACING = 100_000;

    /** arena floor Y, matching the dungeon floors' surface level */
    public static final int SURFACE_Y = 128;

    /**
     * The Z lane NEW tear arenas sit in, kept off every other lane in these shared themed dimensions.
     *
     * <p>Dungeon floors occupy {@code x = N * CELL_SPACING, z = EPOCH * CELL_SPACING} in these SAME themed
     * dimensions, with EPOCH in {@code [0, 299]}. The old lane was {@code +1,000,000}, which is
     * {@code 10 * CELL_SPACING}, i.e. exactly dungeon relocation epoch 10: a floor relocated ten times stamped a
     * flat arena disc on top of the tear cells and sealed part of it with the barrier shell. A NEGATIVE lane is
     * clear of every dungeon epoch (all non-negative), of the planned event-floor lane ({@code z = -2,000,000}),
     * and of everything else in these dimensions (space and guild raids live in their OWN planet dimension via
     * {@code SpaceHook}, and tournaments use operator-picked regions, never this grid). {@code -1,000,000} is well
     * inside the world border and the X grid reuses the same spacing.
     */
    public static final int LANE_Z = -1_000_000;

    /**
     * The OLD tear lane ({@code 10 * CELL_SPACING}). Kept ONLY so an arena a rift already owns here stays exactly
     * where its build is: {@link RiftArenaData} defaults any rift persisted before this change to this lane, so its
     * dressing is not orphaned, while NEW cells (and any rift that has to move off a stamped cell) get {@link #LANE_Z}.
     */
    public static final int LEGACY_LANE_Z = 1_000_000;

    /**
     * Max live cells at once. A run that cannot get one is refused entry rather than dropped into
     * somebody else's arena. Per-rift timers make hitting this unlikely.
     */
    public static final int MAX_CELLS = 64;

    /** run id -> cell index, for runs happening now; the rift's owned cell is persisted elsewhere */
    private static final Map<UUID, Integer> claimed = new HashMap<>();

    private RiftArenaSlots() {
    }

    /**
     * Take the cell a rift OWNS for this run. Since a rift keeps one cell for the life of the world, it
     * can only have one run at a time: a second tear of the same rift is refused rather than colliding in
     * the shared arena. Different rifts have different cells and run side by side.
     *
     * @return cell index, or -1 when the rift has no cell to give or its own is already in use
     */
    public static synchronized int claim(java.util.function.IntSupplier ownedCell, UUID runId) {
        if (runId == null) {
            return -1;
        }
        Integer existing = claimed.get(runId);
        if (existing != null) {
            return existing;
        }
        int cell = ownedCell.getAsInt();
        if (cell < 0 || cell >= MAX_CELLS || claimed.containsValue(cell)) {
            return -1;
        }
        claimed.put(runId, cell);
        return cell;
    }

    /** release runId's cell, if any; safe to call more than once */
    public static synchronized void release(UUID runId) {
        if (runId != null) {
            claimed.remove(runId);
        }
    }

    /** drop every claim, on server stop where no run survives */
    public static synchronized void clear() {
        claimed.clear();
    }

    public static synchronized int inUse() {
        return claimed.size();
    }

    /** centre of cell {@code index} in the CURRENT lane; cells march along +X only, so Z and Y are fixed */
    public static BlockPos cellCentre(int index) {
        return cellCentre(index, LANE_Z);
    }

    /**
     * Centre of cell {@code index} in an explicit Z lane. A rift owns a lane (its own {@link #LANE_Z} for a fresh
     * cell, or {@link #LEGACY_LANE_Z} for one it already had before the lane moved), so the caller passes the
     * rift's lane rather than assuming the current one.
     */
    public static BlockPos cellCentre(int index, int laneZ) {
        return new BlockPos(index * CELL_SPACING, SURFACE_Y, laneZ);
    }

    /**
     * Which cell a coordinate falls in, for the "still inside their own arena" test. Returns -1 for
     * anything outside the range, including negative X, so a player west of cell 0 is treated as outside
     * rather than wrapping into a valid index.
     */
    public static int cellIndexForX(double x) {
        if (x < -(CELL_SPACING / 2.0)) {
            return -1;
        }
        int index = (int) Math.floor((x + CELL_SPACING / 2.0) / CELL_SPACING);
        return index >= 0 && index < MAX_CELLS ? index : -1;
    }
}
