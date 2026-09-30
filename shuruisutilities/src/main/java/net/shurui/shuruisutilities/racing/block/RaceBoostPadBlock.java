package net.shurui.shuruisutilities.racing.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A speed boost pad: a thin (1/16) facing slab whose emissive arrow points the boost direction. In R0 it is a
 * plain placeable, non-collidable marker; the client detects it under a racing bike (R4) and the key applies the
 * boost, so the block carries no behaviour itself. {@link #FACING} is the arrow / boost heading.
 *
 * <p>{@link #LOWERED}: a pad sits at the bottom of its own block cell, so on top of a bottom slab it would float
 * half a block above the slab surface. When the block directly below has a top surface at half height (a bottom
 * slab, or any half-height block), the pad drops half a block so it rests ON that surface. It is recomputed on
 * placement and whenever the block below changes. The kart detector reads the block at the rider's feet AND the
 * block just below (see {@code HoverbikeEntity.driveRaceMovement}), so a lowered pad is still detected.
 */
public class RaceBoostPadBlock extends HorizontalDirectionalBlock
{
    public static final BooleanProperty LOWERED = BooleanProperty.create("lowered");

    public static final VoxelShape SHAPE = box(0.0D, 0.0D, 0.0D, 16.0D, 1.0D, 16.0D);
    /** The same thin pad, dropped half a block so it rests on a slab (or half-height) surface below. */
    public static final VoxelShape SHAPE_LOWERED = SHAPE.move(0.0D, -0.5D, 0.0D);

    public RaceBoostPadBlock(Properties properties)
    {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LOWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder)
    {
        builder.add(FACING, LOWERED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context)
    {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection())
                .setValue(LOWERED, shouldLower(context.getLevel(), context.getClickedPos()));
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                  BlockPos pos, BlockPos neighbourPos)
    {
        // Only the block underneath changes whether the pad rests on a raised or a lowered surface.
        if (dir == Direction.DOWN)
            return state.setValue(LOWERED, shouldLower(level, pos));
        return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        return state.getValue(LOWERED) ? SHAPE_LOWERED : SHAPE;
    }

    /** True when the block directly below has a top surface at half height (a bottom slab or any half-height block). */
    private static boolean shouldLower(BlockGetter level, BlockPos pos)
    {
        BlockPos below = pos.below();
        BlockState state = level.getBlockState(below);
        VoxelShape shape = state.getCollisionShape(level, below);
        if (shape.isEmpty())
            return false;
        double top = shape.max(Direction.Axis.Y);
        return top > 0.0D && top <= 0.5D + 1.0e-4D;
    }
}
