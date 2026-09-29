package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import net.minecraft.core.BlockPos;

// deterministic, non-overlapping placement of dungeon floors. A floor's THEME picks its dimension
// (DungeonDimensions.levelForTheme); this class only decides WHERE inside that dimension a floor sits. Floors that
// share a theme share a dimension, so the spacing must keep them from ever seeing each other.
//
// The floor NUMBER indexes a column along +X, a per-floor RELOCATION EPOCH indexes a row along +Z: floor N (1-based)
// at epoch E is centred at (N * CELL_SPACING, y, E * CELL_SPACING), y resolved at generation time.
//   * floors 1..N map directly to a column index, no hashing, and floor N always resolves to the same X forever.
//     Everything that maps a world position back to a floor (crate colours, the portal's "which floor") reads only X
//     (floorNumberForX), so the Z epoch is invisible to it and never has to change.
//   * epoch starts at 0, so a floor built before relocation existed stays at Z = 0. Removing or RETYPING a floor
//     (theme, type or layout style changed) bumps its epoch by one, moving its next build a full CELL_SPACING along
//     +Z onto fresh ground; the old build is left behind, not re-stamped. Epoch is persisted per floor and carried in
//     the shared config, so every shard agrees where floor N lives (see DungeonFloors).
//   * cell 0 on each axis is never used by a floor column, leaving the dimension's spawn area clear. Epoch 0 does sit
//     on the Z = 0 row, which is fine: a column at X = N * CELL_SPACING is already far from spawn.
//   * CELL_SPACING (100,000) is far larger than Mine Cells' 1,024 gap ON PURPOSE. Distant Horizons renders terrain
//     far past vanilla view distance, so two floors of one theme must be tens of thousands of blocks apart to be
//     invisible to each other. Even the largest floor (1120 wide, half-extent 560) leaves 100,000 - 560 - 560 =
//     98,880 blocks of gap between adjacent floor discs on either axis.
public final class DungeonFloorLayout {

    // blocks between adjacent floor cell centres on BOTH axes (floor number spaces +X columns, relocation epoch spaces
    // +Z rows). Wide enough that even a Distant Horizons client cannot see one floor from another.
    public static final int CELL_SPACING = 100000;

    // Event dungeon floors are VIRTUAL floors numbered from this base up, kept off the ordinary floor grid entirely.
    // A virtual floor n (>= EVENT_FLOOR_BASE) sits at x = (n - EVENT_FLOOR_BASE) * CELL_SPACING on a dedicated
    // NEGATIVE-z lane (EVENT_LANE_Z), so it can never collide with an ordinary floor (whose rows are always z >= 0,
    // see MAX_RELOCATION_EPOCH) nor with the rift arena lane (RiftArenaSlots.LANE_Z = -1,000,000). The event floor
    // store is LOCAL and never synced, so these coordinates never enter the shared floor list.
    public static final int EVENT_FLOOR_BASE = 1000;

    // the fixed z of the event-floor lane: -2,000,000, i.e. epoch-equivalent -20, a full CELL_SPACING clear of the
    // rift lane at -1,000,000 (epoch -10) and far below any dungeon row (z >= 0). floorNumberForPos reads it back.
    public static final int EVENT_LANE_Z = -2000000;

    // hard cap on the floor index used for X. The world border sits at +/-29,999,984, so a column must stay inside it:
    // floor N at N * 100,000 makes 299 the largest safe index (299 * 100,000 = 29,900,000; 300 would hit 30,000,000).
    // The floor COUNT is capped at 256 by config, so this is a belt-and-braces guard against a hand-edited NBT count.
    public static final int MAX_FLOOR_INDEX = 299;

    // hard cap on the relocation epoch used for Z, same world-border reason: epoch E at Z = E * 100,000, so 299 keeps
    // Z at 29,900,000, inside the border. Unreachable in practice; a belt-and-braces guard.
    //
    // Lane co-tenancy: these themed dimensions are shared with the Raid Bosses rift arenas (via DungeonArenaHook).
    // Dungeon rows are always at Z >= 0 (epoch is non-negative). Every OTHER lane in these dimensions now sits at
    // NEGATIVE Z (rift arenas at RiftArenaSlots.LANE_Z = -1,000,000, and the planned event-floor lane at -2,000,000),
    // so no dungeon row can ever collide with them and this cap needs no per-lane carve-out. We deliberately do NOT
    // skip or cap any epoch to dodge the rift lane: epoch E's Z is baked into where floor builds already sit on disk,
    // so remapping an epoch would orphan an existing floor's terrain (a data break). The one historical overlap,
    // the OLD rift lane at +1,000,000 (= epoch 10) where rifts persisted before the move still live, is resolved on
    // the RIFT side: RiftRun.offLegacyStamp detects a dungeon stamp on such a legacy cell and migrates that rift to
    // the new negative lane. If a future feature ever adds a POSITIVE-Z lane in these dimensions, add its guard here.
    public static final int MAX_RELOCATION_EPOCH = MAX_FLOOR_INDEX;

    // fallback surface altitude, used only if a floor's real generator surface cannot be resolved. The themed
    // dimensions cap their solid body well below this, so it is a safe "sky" default.
    public static final int SURFACE_Y = 128;

    private DungeonFloorLayout() {
    }

    // clamped floor index used for coordinates, so a column can never leave the world border.
    public static int clampedIndex(int floorNumber) {
        return Math.max(1, Math.min(MAX_FLOOR_INDEX, floorNumber));
    }

    // clamped relocation epoch for Z. Epoch 0 maps to Z = 0, keeping every pre-relocation floor where it was built.
    public static int clampedEpoch(int relocationEpoch) {
        return Math.max(0, Math.min(MAX_RELOCATION_EPOCH, relocationEpoch));
    }

    // which floor a world X belongs to. Floors sit at x = N * CELL_SPACING and a disc is far narrower than the spacing,
    // so the nearest multiple is unambiguous. Pure math with no Minecraft server types, so the client colour handler
    // can resolve a crate's floor from its block position without classloading server-side DungeonCrates.
    public static int floorNumberForX(int x) {
        return (int) Math.round((double) x / CELL_SPACING);
    }

    // true when this z sits on the event-floor lane. Ordinary floor rows are always at z >= 0 and the rift lane is at
    // z = -1,000,000, so only the event lane rounds to EVENT_LANE_Z / CELL_SPACING (= -20). Used by floorNumberForPos
    // to tell an event floor's blocks apart from an ordinary floor that shares the same X column.
    public static boolean isEventLane(int z) {
        return Math.round((double) z / CELL_SPACING) == (EVENT_LANE_Z / CELL_SPACING);
    }

    // which floor a world position belongs to, reading BOTH axes. An event floor and an ordinary floor can share an X
    // column (both at multiples of CELL_SPACING), so X alone is ambiguous; the z lane disambiguates. On the event lane
    // the column index maps back to the virtual floor number (EVENT_FLOOR_BASE + column); everywhere else it is the
    // ordinary floor number. Pure math with no Minecraft server types, so the client colour handler can use it too.
    public static int floorNumberForPos(int x, int z) {
        if (isEventLane(z)) {
            return EVENT_FLOOR_BASE + (int) Math.round((double) x / CELL_SPACING);
        }
        return floorNumberForX(x);
    }

    // world-space centre column of a floor at a given epoch (X from the floor number, Z from the epoch), at the
    // fallback surface altitude. floorNumber is 1-based. There is deliberately no epoch-less overload: every caller
    // must ask the floor's CURRENT epoch, or it addresses the wrong (possibly orphaned) build after a relocation.
    public static BlockPos cellCentre(int floorNumber, int relocationEpoch) {
        // event floors (virtual numbers from EVENT_FLOOR_BASE) live on the fixed negative-z lane, their column indexed
        // by (n - base). They never relocate (the epoch is meaningless for them: the store is dropped and rebuilt), so
        // the epoch argument is ignored here. The column is clamped like an ordinary floor so it stays inside the border.
        if (floorNumber >= EVENT_FLOOR_BASE) {
            int column = Math.max(0, Math.min(MAX_FLOOR_INDEX, floorNumber - EVENT_FLOOR_BASE));
            return new BlockPos(column * CELL_SPACING, SURFACE_Y, EVENT_LANE_Z);
        }
        return new BlockPos(clampedIndex(floorNumber) * CELL_SPACING, SURFACE_Y,
                clampedEpoch(relocationEpoch) * CELL_SPACING);
    }

    // stable per-floor seed for the deterministic room roll, so a floor's layout is the same every (re)generation.
    public static long floorSeed(int floorNumber) {
        // splitmix-style mix so adjacent floors get well-separated seeds.
        long z = floorNumber * 0x9E3779B97F4A7C15L + 0x2545F4914F6CDD1DL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // unique build key for a floor, so concurrent builds of different floors never collide in a task registry.
    public static String floorKey(int floorNumber) {
        return "dungeon_floor_" + floorNumber;
    }
}
