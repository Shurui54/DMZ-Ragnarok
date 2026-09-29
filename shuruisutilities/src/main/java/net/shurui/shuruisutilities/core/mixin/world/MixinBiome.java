package net.shurui.shuruisutilities.core.mixin.world;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.shurui.shuruisutilities.regions.RegionEventHandler;
import net.shurui.shuruisutilities.regions.RegionFlag;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;

/**
 * Enforces the region {@code ice-form} / {@code snow-fall} flags. Weather ice/snow placement in
 * {@code ServerLevel.tickChunk} gates on {@link Biome#shouldFreeze}/{@code shouldSnow}; forcing those to
 * {@code false} inside a deny region stops the formation without touching the (large) tickChunk method.
 */
@Mixin(Biome.class)
public class MixinBiome
{
    @Inject(method = "shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true)
    private void su$regionIceForm(LevelReader reader, BlockPos pos, CallbackInfoReturnable<Boolean> cir)
    {
        if (RegionEventHandler.hasRegions() && reader instanceof Level lvl
                && RegionEventHandler.worldFlagDenied(lvl, pos, RegionFlag.ICE_FORM))
            cir.setReturnValue(false);
    }

    @Inject(method = "shouldSnow", at = @At("HEAD"), cancellable = true)
    private void su$regionSnowFall(LevelReader reader, BlockPos pos, CallbackInfoReturnable<Boolean> cir)
    {
        if (RegionEventHandler.hasRegions() && reader instanceof Level lvl
                && RegionEventHandler.worldFlagDenied(lvl, pos, RegionFlag.SNOW_FALL))
            cir.setReturnValue(false);
    }
}
