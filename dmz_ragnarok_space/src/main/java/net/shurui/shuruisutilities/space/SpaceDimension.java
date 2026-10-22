package net.shurui.shuruisutilities.space;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

import net.shurui.shuruisutilities.world.space.SpaceKeys;

// Keys for the space dim, a datapack void world (dimension[_type]/space.json + worldgen/biome/space.json).
// Everything space-scoped gates on isSpace(). The identity constants are ALIASES of the core-owned SpaceKeys, so this
// class can move to the space module while core still reads the ids from SpaceKeys. Values are unchanged.
public final class SpaceDimension
{
    public static final ResourceLocation ID = SpaceKeys.SPACE_ID;

    public static final ResourceKey<Level> SPACE = SpaceKeys.SPACE;

    public static final ResourceKey<DimensionType> SPACE_TYPE = SpaceKeys.SPACE_TYPE;

    // Two different vertical ranges live here, and they must not be confused.
    //
    // The BUILD column mirrors dimension_type/space.json (both namespaces): min_y -64, height 384, so blocks exist only
    // in -64..319. It is deliberately a normal-height column: the client allocates a render section (with a vertex
    // buffer per render layer) for every section of the column in view, so a tall column cost hundreds of thousands of
    // GL objects even though space is empty. Anything that places real blocks in space (asteroids, wreck debris) must
    // stay inside it. Change these together with the JSON.
    public static final int BUILD_MIN_Y = -64;
    public static final int BUILD_TOP_Y = 320;

    // The LAYOUT band: where bodies are drawn and where players and pods fly, as entities. It is far taller than the
    // build column; outside the column there are simply no blocks. The generated system band below is derived from it.
    public static final double LAYOUT_MIN_Y = SpaceKeys.LAYOUT_MIN_Y;   // -12000
    public static final double LAYOUT_MAX_Y = SpaceKeys.LAYOUT_MAX_Y;   //  12000

    // The default descend-out floor (SpaceTravelModule.returnAltitude): 192 blocks above the bottom of the layout band.
    public static final double DEFAULT_RETURN_ALTITUDE = LAYOUT_MIN_Y + 192.0;   // -11808

    // The band every landable body (centre, orbit lift and body radius) is kept inside: 64 blocks clear of the
    // descend-out floor below and of the top of the layout band above, so a pod can always reach and land on it.
    public static final double BODY_FLOOR_Y = DEFAULT_RETURN_ALTITUDE + 64.0;     // -11744
    public static final double BODY_CEILING_Y = LAYOUT_MAX_Y - 64.0;              //  11936

    // Below this an entity in space is past anything the layout uses. Vanilla's void kill is off in space (see
    // MixinSpaceNoVoidKill); a player down here is sent out through the descend-out path even during its cooldown.
    public static final double SAFETY_FLOOR_Y = LAYOUT_MIN_Y - 512.0;           // -12512

    private SpaceDimension()
    {
    }

    /** CREATED if this world never had one: a world made before this dimension shipped has no level for it. */
    public static ServerLevel level(MinecraftServer server)
    {
        return net.shurui.shuruisutilities.util.DynamicLevels.getOrCreate(server, SPACE);
    }

    public static boolean isSpace(Level level)
    {
        return SpaceKeys.isSpace(level);
    }
}
