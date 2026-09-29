package net.shurui.shuruisutilities.racing.block;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import net.shurui.shuruisutilities.racing.RaceRegistries;

/**
 * A manually placeable item spawner: a pedestal that holds a race item box, letting an admin author box locations
 * beyond a track's configured item points. The behaviour is entirely the Ragnarok Key's, reached each server tick
 * through {@link RaceItemSpawnerBlockEntity#serverTick}; keyless the block is inert decoration.
 */
public class RaceItemSpawnerBlock extends Block implements EntityBlock
{
    public RaceItemSpawnerBlock(Properties properties)
    {
        super(properties);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new RaceItemSpawnerBlockEntity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type)
    {
        // Only the server ticks; the client has nothing to do (the box is a separate entity).
        if (level.isClientSide)
            return null;
        return createTickerHelper(type, RaceRegistries.RACE_ITEM_SPAWNER_BE.get(),
                RaceItemSpawnerBlockEntity::serverTick);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static <A extends BlockEntity, E extends BlockEntity> BlockEntityTicker<A> createTickerHelper(
            BlockEntityType<A> given, BlockEntityType<E> expected, BlockEntityTicker<? super E> ticker)
    {
        return expected == given ? (BlockEntityTicker<A>) ticker : null;
    }
}
