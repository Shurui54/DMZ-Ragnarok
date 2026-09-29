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
