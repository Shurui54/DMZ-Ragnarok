package net.shurui.shuruisutilities.commands.world;

/**
 * The block update sweep budget that core's {@link TickTaskBlockUpdater} and {@link TickTaskAreaBlockUpdater} read
 * (S18b: the values half of {@code UpdateBlocksConfig}, whose {@code @SUModule "UpdateBlocks"} and toml now live in
 * the Ragnarok Key with {@code /updateblocks}). The public seeded-planet sweep runs keyless on these updaters, so the
 * numbers stay in core: the key's config bakes UpdateBlocks.toml into them, and keyless they keep the defaults.
 */
public final class UpdateBlocksSettings
{
    /** Hard ceiling on the /updateblocks radius argument. */
    public static final int MAX_RADIUS = 160;

    private static volatile int blocksPerTick = 8192;
    private static volatile int maxRadius = MAX_RADIUS;

    private UpdateBlocksSettings() {}

    public static int blocksPerTick()
    {
        return blocksPerTick;
    }

    public static int maxRadius()
    {
        return maxRadius;
    }

    /** Bake the configured values (from the key's UpdateBlocksConfig). */
    public static void set(int perTick, int radius)
    {
        blocksPerTick = perTick;
        maxRadius = radius;
    }
}
