package net.shurui.shuruisutilities.timemachine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A non-anchor cell of the time machine multiblock: solid full-cube collision so the machine is walkable-around, but
 * INVISIBLE (the anchor's block entity renderer draws the whole model, so the parts must not draw a vanilla model).
 * Carries no state: it locates its anchor by a bounded scan ({@link TimeMachineStructure#findAnchor}), so there is no
 * per-part data to persist or sync.
 *
 * <p>Breaking a part tears down the whole structure via the anchor, so you cannot leave a half-broken machine. Uses
 * {@code noLootTable} (set in {@link TimeMachineBlocks}); the single item drop comes from the teardown.
 */
public final class TimeMachinePartBlock extends Block
{
    public TimeMachinePartBlock(Properties properties)
    {
        super(properties);
    }

    // invisible: the anchor's GeckoLib renderer draws the model; a part drawing the vanilla model would double it.
    @Override
    public RenderShape getRenderShape(BlockState state)
    {
        return RenderShape.INVISIBLE;
    }

    // break a part -> find the anchor and tear the whole structure down, dropping one item (unless creative).
    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player)
    {
        if (!level.isClientSide && !TimeMachineStructure.isTearingDown())
        {
            BlockPos anchor = TimeMachineStructure.findAnchor(level, pos);
            if (anchor != null)
            {
                TimeMachineStructure.destroy(level, anchor, !player.getAbilities().instabuild);
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    // non-player removal safety net (explosion, piston, /setblock air).
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston)
    {
        if (!level.isClientSide && !TimeMachineStructure.isTearingDown() && !newState.is(this))
        {
            BlockPos anchor = TimeMachineStructure.findAnchor(level, pos);
            if (anchor != null)
            {
                TimeMachineStructure.destroy(level, anchor, true);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public net.minecraft.world.item.ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state)
    {
        return new net.minecraft.world.item.ItemStack(TimeMachineBlocks.TIME_MACHINE_ITEM.get());
    }
}
