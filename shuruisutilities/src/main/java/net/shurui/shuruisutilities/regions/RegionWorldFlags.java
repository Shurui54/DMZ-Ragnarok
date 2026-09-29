package net.shurui.shuruisutilities.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseCoralFanBlock;
import net.minecraft.world.level.block.BaseCoralPlantTypeBlock;
import net.minecraft.world.level.block.BaseCoralWallFanBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CoralBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FrostedIceBlock;
import net.minecraft.world.level.block.GrassBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.MyceliumBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.VineBlock;

/**
 * Maps vanilla blocks to the {@link RegionFlag} world-generation state flag their random/scheduled tick drives,
 * for the tick mixins ({@code MixinBlockStateBaseTick} etc.) to consult. Kept out of the mixins themselves so the
 * mixin bodies stay tiny and this logic is testable/normal-classloaded.
 */
public final class RegionWorldFlags
{
    private RegionWorldFlags() {}

    /**
     * The world flag whose {@code deny} should cancel this block's <b>random</b> tick, or {@code null}. Order
     * matters: {@link FrostedIceBlock} extends {@link IceBlock}, so it must be tested first.
     */
    public static String randomTickFlag(Block b)
    {
        if (b instanceof LeavesBlock)     return RegionFlag.LEAF_DECAY;
        if (b instanceof FrostedIceBlock) return RegionFlag.FROSTED_ICE_MELT;
        if (b instanceof IceBlock)        return RegionFlag.ICE_MELT;
        if (b instanceof SnowLayerBlock)  return RegionFlag.SNOW_MELT;
        if (b instanceof GrassBlock)      return RegionFlag.GRASS_GROWTH;
        if (b instanceof MyceliumBlock)   return RegionFlag.MYCELIUM_SPREAD;
        if (b instanceof VineBlock)       return RegionFlag.VINE_GROWTH;
        if (b instanceof FarmBlock)       return RegionFlag.SOIL_DRY;
        if (b instanceof MushroomBlock)   return RegionFlag.MUSHROOM_GROWTH;
        return null;
    }

    /** The world flag whose {@code deny} should cancel this block's <b>scheduled</b> tick, or {@code null}. */
    public static String scheduledTickFlag(Block b)
    {
        if (b instanceof CoralBlock || b instanceof BaseCoralPlantTypeBlock
                || b instanceof BaseCoralFanBlock || b instanceof BaseCoralWallFanBlock)
            return RegionFlag.CORAL_FADE;
        return null;
    }

    // any of the six neighbours lava, used to blame a fire on lava
    public static boolean hasLavaNeighbor(Level level, BlockPos pos)
    {
        for (Direction d : Direction.values())
            if (level.getFluidState(pos.relative(d)).is(FluidTags.LAVA))
                return true;
        return false;
    }
}
