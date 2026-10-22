package net.shurui.shuruisutilities.space.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.shurui.shuruisutilities.space.SpaceChunkTicking;

/** Skips the per-chunk tick (random ticks, weather) for an all-air SPACE chunk (see SpaceChunkTicking). */
@Mixin(ServerLevel.class)
public abstract class MixinSpaceChunkTick
{
    @Inject(method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void dmzr$skipEmptySpaceChunk(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci)
    {
        if (SpaceChunkTicking.skipChunkTick((ServerLevel) (Object) this, chunk))
        {
            ci.cancel();
        }
    }
}
