package net.shurui.shuruisutilities.guilds.model;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;

/**
 * String identity for a claimed chunk: {@code "<dimension>;<chunkX>;<chunkZ>"}. Kept as a plain String
 * so it round-trips cleanly through Gson map keys and the flat claim index.
 */
public final class ChunkKey
{
    private ChunkKey() {}

    public static String of(String dimension, int chunkX, int chunkZ)
    {
        return dimension + ';' + chunkX + ';' + chunkZ;
    }

    public static String of(Level level, BlockPos pos)
    {
        return of(level.dimension().location().toString(), pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static String of(Level level, ChunkPos pos)
    {
        return of(level.dimension().location().toString(), pos.x, pos.z);
    }

    /**
     * The dimension part of a chunk key as a level key, or null when it is missing or malformed.
     *
     * <p>Added for the claim restriction, which has to ask whether a chunk is in an SMP dimension and only ever
     * has the string key to work from.
     */
    public static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimensionKeyOf(String key)
    {
        String dimension = dimensionOf(key);
        if (dimension == null || dimension.isBlank())
            return null;
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(dimension);
        return id == null ? null
                : net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id);
    }

    public static String dimensionOf(String key)
    {
        int i = key.indexOf(';');
        return i < 0 ? key : key.substring(0, i);
    }

    /**
     * The chunk X of a key, or {@link Integer#MIN_VALUE} when it is missing or malformed. Added for the dragon ball
     * claim guard, which only ever has the string key and must know which chunk to test against ball positions.
     */
    public static int chunkXOf(String key)
    {
        return part(key, 1);
    }

    /** The chunk Z of a key, or {@link Integer#MIN_VALUE} when it is missing or malformed. */
    public static int chunkZOf(String key)
    {
        return part(key, 2);
    }

    // The n-th ';'-separated field parsed as an int, or Integer.MIN_VALUE on any malformed key.
    private static int part(String key, int index)
    {
        if (key == null)
            return Integer.MIN_VALUE;
        String[] fields = key.split(";");
        if (fields.length <= index)
            return Integer.MIN_VALUE;
        try
        {
            return Integer.parseInt(fields[index]);
        }
        catch (NumberFormatException bad)
        {
            return Integer.MIN_VALUE;
        }
    }
}
