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

    // The space column, mirroring dimension_type/space.json (both namespaces): the vanilla maximum, min_y -2032 and
    // height 4064, so the top build Y is 2031. Every Y band in the space layout is derived from these two numbers;
    // change them together with the JSON or bodies end up outside the world.
    public static final double COLUMN_MIN_Y = -2032.0;
    public static final double COLUMN_TOP_Y = 2032.0;

    // The default descend-out floor (SpaceTravelModule.returnAltitude): 192 blocks above the bottom of the column.
    public static final double DEFAULT_RETURN_ALTITUDE = COLUMN_MIN_Y + 192.0;   // -1840

    // The band every landable body (centre, orbit lift and body radius) is kept inside: 64 blocks clear of the
    // descend-out floor below and of the top of the column above, so a pod can always reach and land on it.
    public static final double BODY_FLOOR_Y = DEFAULT_RETURN_ALTITUDE + 64.0;     // -1776
    public static final double BODY_CEILING_Y = COLUMN_TOP_Y - 64.0;              // 1968

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
