package net.shurui.shuruisutilities.core.mixin.block;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;

@Mixin(BaseFireBlock.class)
public class MixinBaseFireBlock
{
    // Which smp dim allows portals is asked of SmpWorld, not hardcoded: a literal "shuruisutilities:smp" was left
    // stale by the multiworld namespace rename, so flint-and-steel silently stopped working in smp on migrated servers.

    // vanilla inPortalDimension only allows overworld/nether, so smp returns false and no portal forms. force it
    // true in smp so lighting an obsidian frame works.
    @Inject(method = "inPortalDimension(Lnet/minecraft/world/level/Level;)Z", at = @At("HEAD"), cancellable = true)
    private static void su$allowSmpPortalIgnition(Level level, CallbackInfoReturnable<Boolean> cir)
    {
        // fail closed to vanilla on any hiccup; must never crash block placement
        try
        {
            if (level == null || level.dimension() == null)
                return;
            if (net.shurui.shuruisutilities.multiworld.v2.SmpWorld.is(level.dimension().location()))
                cir.setReturnValue(true);
        }
        catch (Throwable ignored)
        {
        }
    }
}
