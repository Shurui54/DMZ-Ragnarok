package net.shurui.shuruisutilities.core.mixin.enchantment;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shurui.shuruisutilities.regions.RegionEventHandler;
import net.shurui.shuruisutilities.regions.RegionFlag;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.FrostWalkerEnchantment;
import net.minecraft.world.level.Level;

/** Enforces the region {@code frosted-ice-form} flag: stops Frost Walker from freezing water in a deny region. */
@Mixin(FrostWalkerEnchantment.class)
public class MixinFrostWalkerEnchantment
{
    @Inject(method = "onEntityMoved", at = @At("HEAD"), cancellable = true)
    private static void su$regionFrostForm(LivingEntity entity, Level level, BlockPos pos, int level2, CallbackInfo ci)
    {
        if (RegionEventHandler.hasRegions() && RegionEventHandler.worldFlagDenied(level, pos, RegionFlag.FROSTED_ICE_FORM))
            ci.cancel();
    }
}
