package net.shurui.shuruisutilities.timemachine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The life-size time machine multiblock: its footprint, placement, and orphan-proof teardown.
 *
 * <h3>Why a multiblock (the shape decision)</h3>
 * The decorative block draws the machine life-size, about 3.43 x 5.85 x 3.19 blocks, and it must be that size and
 * walkable-around. A SINGLE oversized block cannot do this: vanilla only tests a block's shape while a ray (or an
 * entity's collision sweep) is within that block's OWN cell (see the oversized-block reference and
 * {@code SuperDragonBallPicker}), so parts of a 5.5-block model outside the anchor cell are neither clickable nor
 * collidable, i.e. exactly the "looks solid, you fall through it" failure. Occupying REAL cells removes that problem
 * at the root: every cell is an ordinary solid block, so vanilla clicking, breaking and collision all just work with
 * no client hit-result mixin and no server reach/centre-clamp fragility. The existing pick machinery
 * ({@code SuperDragonBallPicker} + {@code MixinGameRendererSuperBallPick}) was reviewed and is deliberately NOT used
 * here for that reason.
 *
 * <h3>Footprint</h3>
 * The anchor is the CENTRE-BOTTOM cell (the model geo is centred on x/z and feet-at-origin, and GeoBlockRenderer
 * draws from the anchor cell centre, so the model sits centred over the anchor). The footprint is a per-layer square
 * half-extent {@link #HALF} chosen to CONTAIN the silhouette measured off the GeckoLib-transformed geometry (a
 * roughly 5x5 body and skirt tapering to a 3x3 canopy top), so collision matches what you see and nothing visible
 * pokes out of a solid cell. It is horizontally SYMMETRIC, so
 * the 4-way facing only rotates the rendered model and the occupied cell set is identical at every facing (no
 * footprint-rotation maths, which is where multiblocks usually leave orphans).
 *
 * <h3>Teardown is orphan-proof</h3>
 * The occupied set is a deterministic function of the anchor, so breaking ANY cell removes ALL of them: the anchor
 * knows its own footprint, and a part finds its anchor by a bounded scan ({@link #findAnchor}). A re-entrancy guard
 * ({@link #isTearingDown}) stops the cascade of {@code onRemove} calls the teardown itself triggers, and exactly one
 * item is dropped by the teardown, never by the blocks' loot tables (both use {@code noLootTable}).
 */
public final class TimeMachineStructure
{
    private TimeMachineStructure() {}

    // per-layer square half-extent (cells) for y-layers 0..5, sized to contain the model silhouette MEASURED OFF THE
    // GECKOLIB-TRANSFORMED geometry (not the raw cube extents): the rendered hull is ~3.43 x 5.85 x 3.19 blocks and
    // its widest horizontal half-extent is ~1.72 blocks (layers 0-4, the body and skirt), tapering to ~1.04 at the
    // canopy top (layer 5). Centred on the anchor cell, a 1.72 half-extent reaches into the second ring of cells, so
    // the body layers are half 2 (5x5) and the top is half 1 (3x3). Height is HALF.length layers.
    private static final int[] HALF = { 2, 2, 2, 2, 2, 1 };
    private static final int MAX_HALF = 2;

    // offsets from the anchor (centre-bottom) to every occupied cell, anchor (0,0,0) included. Built once.
    private static final List<BlockPos> OFFSETS = new ArrayList<>();
    private static final Set<BlockPos> OFFSET_SET = new HashSet<>();

    static
    {
        for (int y = 0; y < HALF.length; y++)
        {
            int h = HALF[y];
            for (int dx = -h; dx <= h; dx++)
            {
                for (int dz = -h; dz <= h; dz++)
                {
                    BlockPos off = new BlockPos(dx, y, dz);
                    OFFSETS.add(off);
                    OFFSET_SET.add(off);
                }
            }
        }
    }

    // re-entrancy guard: while a teardown is running, the onRemove that each removed cell fires is ignored, so the
    // cascade removes the set exactly once. Single-threaded (server block edits), so a plain field is enough.
    private static boolean isTearingDown = false;

    public static boolean isTearingDown()
    {
        return isTearingDown;
    }

    /** How many blocks the structure rises above its anchor, for the placement fit message and build-height check. */
    public static int height()
    {
        return HALF.length;
    }

    /** True when every cell of the footprint anchored at {@code anchor} is inside the world and replaceable. */
    public static boolean canPlace(Level level, BlockPos anchor)
    {
        for (BlockPos off : OFFSETS)
        {
            BlockPos cell = anchor.offset(off);
            if (level.isOutsideBuildHeight(cell))
            {
                return false;
            }
            if (!level.getBlockState(cell).canBeReplaced())
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Place the whole structure with its anchor at {@code anchor}, facing {@code facing}. Assumes {@link #canPlace}
     * already passed. The anchor carries the {@link TimeMachineBlock} (block entity + rendered model); every other
     * cell is a {@link TimeMachinePartBlock}.
     */
    public static void place(Level level, BlockPos anchor, Direction facing)
    {
        BlockState anchorState = TimeMachineBlocks.TIME_MACHINE_BLOCK.get().defaultBlockState()
                .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, facing);
        BlockState partState = TimeMachineBlocks.TIME_MACHINE_PART.get().defaultBlockState();
        // parts first, anchor last, so the anchor's block entity is created into a fully occupied footprint.
        for (BlockPos off : OFFSETS)
        {
            if (off.getX() == 0 && off.getY() == 0 && off.getZ() == 0)
            {
                continue;
            }
            level.setBlock(anchor.offset(off), partState, Block.UPDATE_ALL);
        }
        level.setBlock(anchor, anchorState, Block.UPDATE_ALL);
    }

    /**
     * Remove the entire structure anchored at {@code anchor}, dropping ONE item at the anchor when {@code dropItem}.
     * Re-entrancy guarded so the {@code onRemove} each removed cell fires does not recurse. Idempotent: a second call
     * while the first is running is a no-op.
     */
    public static void destroy(Level level, BlockPos anchor, boolean dropItem)
    {
        if (isTearingDown)
        {
            return;
        }
        isTearingDown = true;
        try
        {
            for (BlockPos off : OFFSETS)
            {
                BlockPos cell = anchor.offset(off);
                Block block = level.getBlockState(cell).getBlock();
                if (block instanceof TimeMachineBlock || block instanceof TimeMachinePartBlock)
                {
                    level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            if (dropItem && !level.isClientSide)
            {
                Block.popResource(level, anchor, new ItemStack(TimeMachineBlocks.TIME_MACHINE_ITEM.get()));
            }
        }
        finally
        {
            isTearingDown = false;
        }
    }

    /**
     * The anchor whose footprint contains {@code pos}, or null if none. Bounded scan: the anchor is centre-bottom, so
     * it sits within {@code +-MAX_HALF} horizontally and {@code height()-1} below {@code pos}. Used by a broken PART to
     * locate its anchor with no per-part stored state.
     */
    public static BlockPos findAnchor(Level level, BlockPos pos)
    {
        for (int ay = pos.getY() - (HALF.length - 1); ay <= pos.getY(); ay++)
        {
            for (int ax = pos.getX() - MAX_HALF; ax <= pos.getX() + MAX_HALF; ax++)
            {
                for (int az = pos.getZ() - MAX_HALF; az <= pos.getZ() + MAX_HALF; az++)
                {
                    BlockPos candidate = new BlockPos(ax, ay, az);
                    if (!(level.getBlockState(candidate).getBlock() instanceof TimeMachineBlock))
                    {
                        continue;
                    }
                    if (OFFSET_SET.contains(pos.subtract(candidate)))
                    {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }
}
