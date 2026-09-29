package net.shurui.shuruisutilities.core.mixin.block;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shurui.shuruisutilities.regions.RegionEventHandler;
import net.shurui.shuruisutilities.regions.RegionFlag;
import net.shurui.shuruisutilities.regions.RegionWorldFlags;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Region world-gen flags vanilla drives from block ticks, with no Forge event: leaf-decay, ice/snow/frosted-ice
 * melt, grass & mycelium spread, vine & mushroom growth, soil dry (random tick); coral fade (scheduled tick);
 * lava-fire (scheduled tick). Dispatch runs through {@code BlockStateBase.randomTick/tick} for every block, so one
 * HEAD hook covers them all; {@link RegionEventHandler#hasRegions()} keeps it free when no regions exist.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public class MixinBlockStateBase
{
    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void su$regionRandomTick(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci)
    {
        if (!RegionEventHandler.hasRegions())
            return;
        Block b = ((BlockState) (Object) this).getBlock();
        String flag = RegionWorldFlags.randomTickFlag(b);
        if (flag != null && RegionEventHandler.worldFlagDenied(level, pos, flag))
            ci.cancel();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void su$regionScheduledTick(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci)
    {
        if (!RegionEventHandler.hasRegions())
            return;
        Block b = ((BlockState) (Object) this).getBlock();
        String flag = RegionWorldFlags.scheduledTickFlag(b);
        if (flag != null && RegionEventHandler.worldFlagDenied(level, pos, flag))
        {
            ci.cancel();
            return;
        }
        // lava-fire: fire has no ignite event on Forge, so attribute it to lava by an adjacent-lava check and
        // extinguish it (which also stops this spread tick) when the region denies lava-fire.
        if (b instanceof BaseFireBlock && RegionEventHandler.worldFlagDenied(level, pos, RegionFlag.LAVA_FIRE)
                && RegionWorldFlags.hasLavaNeighbor(level, pos))
        {
            level.removeBlock(pos, false);
            ci.cancel();
        }
    }
}
