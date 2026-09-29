package net.shurui.shuruisutilities.ritual;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The idol's block entity. It carries no state and drives no rendering: the idol draws from an ordinary block model.
 *
 * <p>It stays because it is already attached to every idol in every saved world. Unregistering a block entity type
 * that exists on disk is how you lose the blocks that carry it, and the cost of keeping an empty one is nothing.
 * It used to implement GeckoLib's {@code GeoBlockEntity} to feed a renderer that borrowed DragonMineZ's Shenron rig;
 * that renderer is gone, and with it a per-block animation cache kept alive for a statue that never moved.
 */
public final class ShenronIdolBlockEntity extends BlockEntity
{
    public ShenronIdolBlockEntity(BlockPos pos, BlockState state)
    {
        super(ShenronIdol.BLOCK_ENTITY.get(), pos, state);
    }
}
