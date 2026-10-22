package net.shurui.shuruisutilities.space.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import net.shurui.shuruisutilities.space.SpaceChunkTicking;

/** Skips natural spawning for an all-air SPACE chunk (nothing can spawn in pure air anyway; see SpaceChunkTicking). */
@Mixin(NaturalSpawner.class)
public abstract class MixinSpaceChunkSpawn
{
    @Inject(method = "spawnForChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;"
            + "Lnet/minecraft/world/level/NaturalSpawner$SpawnState;ZZZ)V", at = @At("HEAD"), cancellable = true, require = 0)
    private static void dmzr$skipEmptySpaceChunk(ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnState state,
                                                 boolean friendlies, boolean enemies, boolean persistent,
                                                 CallbackInfo ci)
    {
        if (SpaceChunkTicking.skipChunkTick(level, chunk))
        {
            ci.cancel();
        }
    }
}
