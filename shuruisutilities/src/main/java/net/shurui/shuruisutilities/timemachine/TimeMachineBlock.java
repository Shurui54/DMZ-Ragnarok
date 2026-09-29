package net.shurui.shuruisutilities.timemachine;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Anchor block of the life-size time machine multiblock (see {@link TimeMachineStructure} for the shape rationale).
 * The anchor is the centre-bottom cell: it carries the GeckoLib block entity that draws the whole life-size model,
 * and its 4-way {@code FACING} turns that model (GeckoLib's GeoBlockRenderer rotates by HORIZONTAL_FACING on its own).
 * Every other occupied cell is a {@link TimeMachinePartBlock}.
 *
 * <p>Solid, full-cube collision so the machine is walkable-around and cannot be fallen through. Render shape is
 * {@code ENTITYBLOCK_ANIMATED} (the block entity renderer draws the model, the vanilla model is suppressed), and like
 * any GeckoLib/animated block it must not read as a full opaque cube for occlusion, so {@code noOcclusion()} (set in
 * {@link TimeMachineBlocks}) plus the visual/skylight/shade overrides. Breaking any cell tears the whole structure
 * down through {@link TimeMachineStructure}; both blocks use {@code noLootTable}, so the single item drop comes from
 * the teardown alone.
 */
public final class TimeMachineBlock extends BaseEntityBlock
{
    public TimeMachineBlock(Properties properties)
    {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(HorizontalDirectionalBlock.FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder)
    {
        builder.add(HorizontalDirectionalBlock.FACING);
    }

    @Override
    public RenderShape getRenderShape(BlockState state)
    {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    // solid full cube: real walkable-around collision. Full cube outline too.
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        return Shapes.block();
    }

    // occlusion belt-and-braces (past noOcclusion) so the animated anchor never culls a neighbour or shades it.
    @Override
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        return Shapes.empty();
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos)
    {
        return true;
    }

    @Override
    public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos)
    {
        return 1.0F;
    }

    // break the anchor -> tear down the whole structure and drop one item (unless creative). Runs before the block is
    // removed, so the guarded teardown clears every cell (including this one) exactly once.
    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player)
    {
        if (!level.isClientSide && !TimeMachineStructure.isTearingDown())
        {
            TimeMachineStructure.destroy(level, pos, !player.getAbilities().instabuild);
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    // safety net for non-player removal (explosion, piston, /setblock air): tear the rest down. Guard skips the
    // cascade the teardown itself causes, and skips ordinary state changes that keep the block ours.
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston)
    {
        if (!level.isClientSide && !TimeMachineStructure.isTearingDown()
                && !newState.is(this))
        {
            TimeMachineStructure.destroy(level, pos, true);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public net.minecraft.world.item.ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state)
    {
        return new net.minecraft.world.item.ItemStack(TimeMachineBlocks.TIME_MACHINE_ITEM.get());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new TimeMachineBlockEntity(pos, state);
    }
}
