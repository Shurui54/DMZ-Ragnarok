package net.shurui.shuruisutilities.space.mixin.server;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.shurui.shuruisutilities.space.SpaceChunkTicking;

/** Clamps the SPACE level's simulation distance (see SpaceChunkTicking). Every other level is passed through unchanged. */
@Mixin(ServerChunkCache.class)
public abstract class MixinSpaceSimulationDistance
{
    @Shadow
    @Final
    ServerLevel level;

    @ModifyVariable(method = "setSimulationDistance(I)V", at = @At("HEAD"), argsOnly = true, require = 0)
    private int dmzr$clampSpaceSimulation(int requested)
    {
        return SpaceChunkTicking.clamp(this.level, requested);
    }
}
