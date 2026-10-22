package net.shurui.shuruisutilities.space.mixin.server;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.shurui.shuruisutilities.space.SpaceEmptyChunks;
import net.shurui.shuruisutilities.world.space.SpaceKeys;

/**
 * Never writes an all-air space chunk to disk.
 *
 * <p>Space is a flat world of pure air; asteroids are stamped in later as real blocks by {@code AsteroidStamp}. Vanilla
 * saves every chunk it generates, so each pod trip left a strip of empty chunks the width of the view distance along
 * its whole route, about 4 KB each: SMP's space folder reached 66 GB in two days of 1.5.1 (2026-10-01), 97% of it air.
 * An all-air chunk regenerates as the identical all-air chunk, so not saving it loses nothing.
 *
 * <p>Two guards keep it lossless. A chunk holding ANY block, block entity or structure start always saves (an asteroid
 * is stamped once and flagged, so it must persist). And a chunk that already has a full copy on disk
 * ({@code chunkTypeCache} == 1, set when it was loaded or saved as a full chunk) always saves, so a mined-out asteroid
 * writes its now-empty state instead of reloading the old asteroid from disk. Only the space dimension is touched.
 */
@Mixin(ChunkMap.class)
public abstract class MixinSpaceEmptyChunkSave
{
    @Shadow
    @Final
    ServerLevel level;
    @Shadow
    @Final
    private Long2ByteMap chunkTypeCache;

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("HEAD"), cancellable = true,
            require = 0)
    private void su$skipEmptySpaceChunk(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir)
    {
        try
        {
            if (this.level == null || this.level.dimension() != SpaceKeys.SPACE || !chunk.isUnsaved())
            {
                return;
            }
            if (this.chunkTypeCache.get(chunk.getPos().toLong()) == 1)
            {
                return;
            }
            if (!SpaceEmptyChunks.isEmpty(chunk))
            {
                return;
            }
            chunk.setUnsaved(false);
            cir.setReturnValue(false);
        }
        catch (Throwable ignored)
        {
            // on any doubt, let vanilla save it.
        }
    }
}
