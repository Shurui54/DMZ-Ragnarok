package net.shurui.shuruisutilities.space;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.util.DynamicLevels;
import net.shurui.shuruisutilities.world.space.SpaceKeys;

/**
 * Keys and cell geometry for the ONE shared surface dimension the generated planets live on. Every generated planet
 * ({@link GeneratedPlanets}) maps to a deterministic square cell in this single dimension: we never make one
 * dimension per planet. The dimension itself is a datapack void world defined in
 * data/shuruisutilities/dimension[_type]/planet_surface.json plus worldgen/biome/planet_surface.json, mirroring the
 * space dimension's void treatment and doubled-up spawn suppression (dimension_type monster_spawn_light_level 0 AND
 * an empty-spawner biome). It gets the same procedural star sky as space so a planet reads as a rock floating in
 * space (see the client SpaceDimensionEffects, registered for this id too), with dense black fog added so a player on
 * their planet cannot see the neighbouring cells.
 *
 * <h3>Cell spacing (sized for Distant Horizons, not just fog)</h3>
 * Cells are laid out on a flat grid at {@link #CELL_SPACING} blocks between centres, on the X/Z plane, at a fixed play
 * altitude {@link #SURFACE_Y}. Spacing, not fog, is what actually hides neighbouring guild planets: Distant Horizons
 * renders LOD terrain far past vanilla render distance and is NOT occluded by fog, so a merely-foggy gap would still
 * show adjacent cells as LOD terrain on the horizon. DH's LOD distance tops out around 512..1024 chunks
 * (~8,192..16,384 blocks), so {@link #CELL_SPACING} is 65,536 blocks, roughly a 4x margin over the most aggressive
 * realistic DH setting, putting a neighbour genuinely out of LOD range rather than just fogged.
 *
 * <p>The largest possible surface is 500x500 ({@link GeneratedPlanets#MAX_SURFACE} across), a half-extent of 250
 * blocks from its cell centre. At 65,536 spacing the nearest edges of two adjacent maximum surfaces are
 * 65,536 - 250 - 250 = 65,036 blocks apart, so the largest surface is trivially clear of its neighbour and no LOD
 * setting brings one into view of the other. The star sky and dense fog stay: the fog hides the empty void right
 * around a small planet so the horizon reads as space rather than an abrupt terrain edge, it is simply no longer what
 * separates neighbours. The horizontal boundary in SpaceTravelModule still stops a player at their own planet's edge
 * so they cannot fly off into the (now very large) void between cells and get lost.
 *
 * <p>Budget: the dimension has ~+/-30,000,000 usable blocks per axis, so 65,536 spacing gives ~+/-450 cells per axis
 * (see {@link #GRID_HALF}), on the order of 800,000 addressable cells, far more claimable planets than will ever be
 * used. The wide spacing therefore costs nothing.
 *
 * <p>A cell is addressed by a signed grid index derived from the planet id hash, so distinct planets map to distinct
 * cells and the mapping is a pure function of the id: nothing about a cell's location is stored. All cell arithmetic
 * is done in long/double, never float, because a cell centre near +/-29 million blocks would lose metres of precision
 * in a 32-bit float.
 */
public final class SurfaceDimension
{
    // Dimension identity constants are ALIASES of the core-owned SpaceKeys (same values), so this class can move to
    // the space module while core reads the ids from SpaceKeys. The cell geometry below stays here.
    public static final ResourceLocation ID = SpaceKeys.SURFACE_ID;

    public static final ResourceKey<Level> SURFACE = SpaceKeys.SURFACE;

    public static final ResourceKey<DimensionType> SURFACE_TYPE = SpaceKeys.SURFACE_TYPE;

    // Distance in blocks between adjacent cell centres on both axes. See the class-doc spacing proof: 65,536 puts a
    // neighbouring surface genuinely out of Distant Horizons LOD range (not merely behind fog), and leaves 65,036
    // blocks of empty gap between the nearest edges of two maximum 500x500 surfaces.
    public static final int CELL_SPACING = 65536;

    // How many cells wide the addressable grid is on each axis, centred on 0. 65,536-block cells over a +/-450-cell
    // range span +/-29.49 million blocks, comfortably inside the +/-30 million world border, so a cell centre is
    // always a legal coordinate. The planet-id hash is folded into this range to pick a cell.
    public static final int GRID_HALF = 450;

    // Fixed play altitude the stamped surface sits at, kept well inside the dimension height (min_y -64, height 384,
    // ceiling 320) so the terrain and its little height variation never approach either build limit.
    public static final double SURFACE_Y = 96.0;

    private SurfaceDimension()
    {
    }

    /**
     * The surface level, CREATED if this world never had one.
     *
     * <p>The plain {@code getLevel} this used to be is why players could not land on generated planets: this
     * dimension shipped after their world was made, so the server never created a level for it, every
     * {@code getLevel} returned null, and the landing code's null check returned quietly. The result was a planet
     * you could fly into and simply pass through, with nothing logged. {@link DynamicLevels#getOrCreate} builds the
     * level from the datapack stem the same way MinecraftServer would have.
     */
    public static ServerLevel level(MinecraftServer server)
    {
        return DynamicLevels.getOrCreate(server, SURFACE);
    }

    public static boolean isSurface(Level level)
    {
        return SpaceKeys.isSurface(level);
    }

    // grid cell (gx, gz) for a planet id: fold the id hash into the +/-GRID_HALF range on each axis independently, so
    // two ids collide only if they share both folded coordinates (vanishingly rare, and harmless: two planets sharing
    // a cell would simply overlap, which the generated-planet layout in space already keeps far apart by id). Pure
    // function of the id.
    public static long cellX(String planetId)
    {
        long h = GeneratedPlanets.hashOf(planetId);
        return Math.floorMod(h, (long) GRID_HALF * 2) - GRID_HALF;
    }

    public static long cellZ(String planetId)
    {
        long h = GeneratedPlanets.hashOf(planetId + "#z");
        return Math.floorMod(h, (long) GRID_HALF * 2) - GRID_HALF;
    }

    // the signed grid index of the cell a world coordinate falls in, on one axis. Cell centres sit at index * CELL_SPACING
    // (plus a half-block), and a player never strays more than the surface half-extent (<= 250) from a centre, so rounding
    // the quotient recovers the cell reliably for both a player position and a ball placed at a cell centre. Used to turn a
    // planet_surface coordinate back into its cell for the radar HUD's planet-aware mapping.
    public static long cellIndexOf(double world)
    {
        return Math.round(world / (double) CELL_SPACING);
    }

    // pack a signed (gx, gz) cell index pair into one long (gx in the high 32 bits, gz in the low 32), the key both the
    // server (building the radar anchor map) and the client (looking a ball's cell up in it) use, so the two pack
    // identically. The indices are bounded by +/-GRID_HALF, well inside an int, so the cast never loses information.
    public static long packCell(long gx, long gz)
    {
        return ((long) (int) gx << 32) | ((long) (int) gz & 0xFFFFFFFFL);
    }

    // world-space centre of a planet id's surface cell, at the fixed play altitude. This is where a player lands when
    // they touch down on a generated planet and where the surface disc is stamped. Pure function of the id.
    public static Vec3 cellCentre(String planetId)
    {
        double x = cellX(planetId) * (double) CELL_SPACING + 0.5;
        double z = cellZ(planetId) * (double) CELL_SPACING + 0.5;
        return new Vec3(x, SURFACE_Y, z);
    }
}
