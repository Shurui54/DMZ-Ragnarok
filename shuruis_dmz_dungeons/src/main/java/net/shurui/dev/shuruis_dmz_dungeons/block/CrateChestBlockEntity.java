package net.shurui.dev.shuruis_dmz_dungeons.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.shurui.dev.shuruis_dmz_dungeons.registry.ModBlockEntities;

// the chest form of the animated crate. All behaviour lives in CrateBlockEntity; this exists only so the chest and
// the barrel can be two block entity TYPES (a type is bound to its block) sharing one implementation.
public class CrateChestBlockEntity extends CrateBlockEntity {

    public CrateChestBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CRATE_CHEST.get(), pos, state);
    }
}
