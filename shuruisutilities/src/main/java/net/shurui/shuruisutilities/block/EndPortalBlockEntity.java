package net.shurui.shuruisutilities.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Marker block entity for the vertical colored end portal, so a {@code BlockEntityRenderer} can draw the
 * real end-portal starfield effect on it (see {@code EndPortalPlaneRenderer}). Holds no data.
 */
public class EndPortalBlockEntity extends BlockEntity
{
    public EndPortalBlockEntity(BlockPos pos, BlockState state)
    {
        super(SUBlockEntities.END_PORTAL.get(), pos, state);
    }
}
