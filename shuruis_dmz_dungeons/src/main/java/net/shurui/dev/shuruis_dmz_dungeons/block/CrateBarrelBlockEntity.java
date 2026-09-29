package net.shurui.dev.shuruis_dmz_dungeons.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.shurui.dev.shuruis_dmz_dungeons.registry.ModBlockEntities;

// the barrel form of the animated crate. The barrel had no block entity while it was a plain tinted cube; it needs
// one now for the same reason the chest does, because a GeckoLib animation is driven from a block entity.
public class CrateBarrelBlockEntity extends CrateBlockEntity {

    public CrateBarrelBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CRATE_BARREL.get(), pos, state);
    }
}
