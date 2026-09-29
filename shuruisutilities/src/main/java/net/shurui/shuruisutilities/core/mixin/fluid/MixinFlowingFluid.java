package net.shurui.shuruisutilities.core.mixin.fluid;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shurui.shuruisutilities.regions.RegionEventHandler;
import net.shurui.shuruisutilities.regions.RegionFlag;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

/** Enforces the region {@code water-flow} / {@code lava-flow} flags by cancelling fluid spread into a deny region. */
@Mixin(FlowingFluid.class)
public class MixinFlowingFluid
{
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void su$regionFluidSpread(LevelAccessor level, BlockPos pos, BlockState state, Direction direction,
            FluidState fluidState, CallbackInfo ci)
    {
        if (!(level instanceof Level lvl))
            return;
        // The built overworld holds its fluids still: a decorative pool was placed where a builder wanted it,
        // and a block-update sweep would otherwise send it down the street. Checked before the region flags so
        // it applies with no region defined at all.
        if (net.shurui.shuruisutilities.world.WorldPhysicsModule.holdFluids(lvl))
        {
            ci.cancel();
            return;
        }
        if (!RegionEventHandler.hasRegions())
            return;
        String flag = fluidState.is(FluidTags.LAVA) ? RegionFlag.LAVA_FLOW : RegionFlag.WATER_FLOW;
        if (RegionEventHandler.worldFlagDenied(lvl, pos, flag))
            ci.cancel();
    }
}
