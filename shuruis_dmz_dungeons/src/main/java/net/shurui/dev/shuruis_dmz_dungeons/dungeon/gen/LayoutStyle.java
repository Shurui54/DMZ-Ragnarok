package net.shurui.dev.shuruis_dmz_dungeons.dungeon.gen;

// which grid layout algorithm a floor uses, chosen PER FLOOR (stored on DungeonFloorConfig). All are adapted from
// Mine Cells grid generators. Layout style is INDEPENDENT of the floor's theme (palette + dimension), so any
// archetype can run in any theme.
//
// enum NAMES are the persisted key on DungeonRoomLayout.style, so the two original constants keep their historical
// names (SPINE_AND_RIBS / ROADS_AND_BUILDINGS) to avoid orphaning already-rolled layouts; the added one uses its
// Mine Cells archetype name. (RAMPARTS and BLACK_BRIDGE were removed; a saved floor set to either is migrated on
// load, see DungeonFloorConfig.load and DungeonFloors.load.)
//
// sealsOutward decides whether the post-placement enclosure pass CLOSES this archetype's outward-facing openings.
// The enclosed types (prison, crypt) are sealed to read as tight labyrinths; the open type (promenade town roads) is
// left freestanding. The barrier box bounds every floor regardless, so an unsealed archetype is still escape-proof.
//
// THERE IS NO LONGER A CARVE. EnclosurePass stopped carving when the blind version was removed: rooms now join ONLY
// through authored doorways, so a piece with no doorway on the needed face is a genuine dead end, not something the
// pass rescues (that is how the crypt once shipped with a sealed boss room). Fix a non-connecting room in its
// template or its chosen rotation.
public enum LayoutStyle {
    // prison: the labyrinth feel. a straight main-corridor spine with ribs branching to dead ends, one rib capped by
    // the exit, and a second level one grid-Y down. from PrisonGridGenerator.
    // Natural size 256 blocks -> grid radius 7 (~15-cell span), enough for the ~13-cell prison after centring while
    // keeping the barrier box cheap (it scales with the square of the size).
    SPINE_AND_RIBS(true, 256, 192, 448),
    // promenade: the open town feel. a road with doglegs and a crossroads branch, buildings and posts along it, plus a
    // retry-limited scatter of larger buildings. from BetterPromenadeGridGenerator. Buildings stay freestanding, so
    // outward faces are NOT sealed.
    // Natural size 1056 blocks -> grid radius 32, exactly what the ~64-cell span needs after re-centring; a smaller
    // floor culled most of the town on one side (the reported lean).
    ROADS_AND_BUILDINGS(false, 1056, 1024, 1120),
    // insufferable crypt: an enclosed labyrinth of prison corridors and cells wrapping a central boss room, reached
    // down an elevator shaft. the tightest archetype.
    // Natural size 320 blocks -> grid radius 9 (~19-cell span): between prison and promenade, room for the corridor
    // run, side cells, shaft and boss room without overspending on the barrier box.
    INSUFFERABLE_CRYPT(true, 320, 256, 512);

    // whether the enclosure pass seals this archetype's outward-facing openings (true for the enclosed types).
    private final boolean sealsOutward;
    // natural floor edge length this archetype wants, derived from the grid span it grows to. Used as the floor's
    // default size when first rolled and the admin left the size at the generic config default (DungeonRoomGen.beginFloor).
    private final int defaultSizeBlocks;

    // the band a rolled floor size may land in for this archetype. Every floor used to come out at exactly
    // defaultSizeBlocks; a rolled size in this band is what makes one floor bigger than the next. Per archetype because
    // the enclosed types (prison, crypt) just grow a longer labyrinth, while the promenade CULLS its town when given
    // less than ~1024 blocks, so its band only reaches upward. Barrier box cost grows with the square of the size, so
    // no band runs away.
    private final int minSizeBlocks;
    private final int maxSizeBlocks;

    LayoutStyle(boolean sealsOutward, int defaultSizeBlocks, int minSizeBlocks, int maxSizeBlocks) {
        this.sealsOutward = sealsOutward;
        this.defaultSizeBlocks = defaultSizeBlocks;
        this.minSizeBlocks = minSizeBlocks;
        this.maxSizeBlocks = maxSizeBlocks;
    }

    public int minSizeBlocks() {
        return minSizeBlocks;
    }

    public int maxSizeBlocks() {
        return maxSizeBlocks;
    }

    /**
     * Roll a floor size for this archetype, in whole grid cells. Deterministic in the caller's random (seeded from
     * the floor number), so a floor keeps its first-rolled size for ever and a rebuild is identical.
     */
    public int rollSizeBlocks(net.minecraft.util.RandomSource random) {
        int span = Math.max(0, (maxSizeBlocks - minSizeBlocks) / (2 * GridModel.CELL));
        int steps = span <= 0 ? 0 : random.nextInt(span + 1);
        return minSizeBlocks + steps * 2 * GridModel.CELL;
    }

    public boolean sealsOutward() {
        return sealsOutward;
    }

    public int defaultSizeBlocks() {
        return defaultSizeBlocks;
    }

    public static LayoutStyle byName(String name, LayoutStyle fallback) {
        if (name != null) {
            for (LayoutStyle s : values()) {
                if (s.name().equalsIgnoreCase(name)) {
                    return s;
                }
            }
        }
        return fallback;
    }
}
