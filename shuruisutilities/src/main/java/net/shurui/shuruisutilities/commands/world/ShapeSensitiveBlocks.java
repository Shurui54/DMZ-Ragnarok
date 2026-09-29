package net.shurui.shuruisutilities.commands.world;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Whether a block state can visibly change when its neighbours are recomputed.
 *
 * <p>This exists purely so an area sweep can skip whole chunk sections without reading them position by position. A
 * section of nothing but stone, dirt and air cannot gain or lose a connection no matter what is placed beside it, and
 * an authored world is overwhelmingly made of exactly that. Testing the section's PALETTE (a handful of entries) and
 * skipping the 4096 positions behind it is the difference between a sweep that finishes and one that does not: on the
 * imported overworld the built areas are a thin surface band inside a very large mostly-solid volume.
 *
 * <p>The test is on PROPERTIES rather than a list of blocks, because the property is what {@code updateShape} actually
 * rewrites and because a property test covers modded blocks for free. A fence added by any mod still carries the four
 * directional booleans, so it is caught here without this class ever hearing about that mod.
 *
 * <p><b>This is a filter, not a decision.</b> It only decides whether a section is worth walking. Every position in a
 * section that passes is then handed to the real recompute, which is authoritative. So a false POSITIVE costs a little
 * wasted work and nothing else. A false NEGATIVE is the one that matters, which is why the property list below is
 * deliberately generous and why anything unrecognised is treated as sensitive when in doubt.
 */
public final class ShapeSensitiveBlocks
{
    private ShapeSensitiveBlocks()
    {
    }

    // Every property whose value is derived from a neighbour and therefore recomputed by updateShape. Grouped by what
    // owns them so a future addition lands in the right place rather than at the end of an anonymous list.
    private static final Property<?>[] SENSITIVE = {
            // fences, glass panes, iron bars, tripwire, fire, chorus plant, mushroom blocks: four sides plus, for the
            // ones that grow in three dimensions, up and down.
            BlockStateProperties.NORTH, BlockStateProperties.EAST,
            BlockStateProperties.SOUTH, BlockStateProperties.WEST,
            BlockStateProperties.UP, BlockStateProperties.DOWN,
            // walls, which use a three-value side (none/low/tall) rather than a boolean.
            BlockStateProperties.NORTH_WALL, BlockStateProperties.EAST_WALL,
            BlockStateProperties.SOUTH_WALL, BlockStateProperties.WEST_WALL,
            // redstone dust, whose sides carry an up/side/none of their own.
            BlockStateProperties.NORTH_REDSTONE, BlockStateProperties.EAST_REDSTONE,
            BlockStateProperties.SOUTH_REDSTONE, BlockStateProperties.WEST_REDSTONE,
            // stairs (inner/outer corners), chests (single vs double), rails (corners and slopes).
            BlockStateProperties.STAIRS_SHAPE, BlockStateProperties.CHEST_TYPE,
            BlockStateProperties.RAIL_SHAPE, BlockStateProperties.RAIL_SHAPE_STRAIGHT,
            // grass and podzol tracking whether something above them is snow.
            BlockStateProperties.SNOWY,
            // leaves recomputing their distance from the nearest log, and the flag that exempts placed ones.
            BlockStateProperties.DISTANCE, BlockStateProperties.PERSISTENT,
            // tripwire noticing its hook, and the two halves of a door or a tall plant checking each other.
            BlockStateProperties.ATTACHED, BlockStateProperties.DOUBLE_BLOCK_HALF,
    };

    /**
     * True if this state carries any property that a neighbour change could rewrite.
     *
     * <p>Air is excluded first because it is the single most common state in any section and can never connect to
     * anything, so rejecting it up front is what makes scanning a palette cheap.
     */
    public static boolean isSensitive(BlockState state)
    {
        if (state.isAir())
            return false;
        for (Property<?> property : SENSITIVE)
        {
            if (state.hasProperty(property))
                return true;
        }
        return false;
    }
}
