package net.shurui.shuruisutilities.core.mixin.block;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shurui.shuruisutilities.regions.RegionEventHandler;
import net.shurui.shuruisutilities.regions.RegionFlag;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.SculkSpreader;

/** Enforces the region {@code sculk-growth} flag: cancels sculk charge spreading originating in a deny region. */
@Mixin(SculkSpreader.class)
public class MixinSculkSpreader
{
    @Inject(method = "updateCursors", at = @At("HEAD"), cancellable = true)
    private void su$regionSculkGrowth(LevelAccessor level, BlockPos pos, RandomSource random, boolean spread, CallbackInfo ci)
    {
        if (RegionEventHandler.hasRegions() && level instanceof Level lvl
                && RegionEventHandler.worldFlagDenied(lvl, pos, RegionFlag.SCULK_GROWTH))
            ci.cancel();
    }
}
