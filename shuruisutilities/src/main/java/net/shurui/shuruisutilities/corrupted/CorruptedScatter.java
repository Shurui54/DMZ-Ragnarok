package net.shurui.shuruisutilities.corrupted;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.world.space.SurfaceSnap;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.shurui.shuruisutilities.worldborder.BorderClamp;

/**
 * Places the seven swap blocks at random surface positions around world spawn. Mirrors DMZ's own two phase ball
 * scatter: a random X/Z inside a radius of the shared spawn is chosen per star, and a star whose target chunk is
 * already loaded is placed now (surface Y from MOTION_BLOCKING_NO_LEAVES) and recorded in {@link
 * ShadowDragonStorage} as placed. A star whose chunk is not loaded is recorded as pending instead of dropped, so
 * nothing forces chunk generation yet every one of the seven is accounted for. {@link #drain} finishes the
 * pending placements later, as their chunks load, resolving Y at that point.
 */
public final class CorruptedScatter
{
    private CorruptedScatter() {}

    // radius in blocks around spawn the swap blocks scatter within, matching DMZ's default spawn range order
    private static final int SCATTER_RANGE = 200;

    // how many fresh X/Z a star will try when a loaded column turns out not to be sky-exposed, before giving up and
    // deferring it to drain(). A roofed or cave-topped surface near spawn is rare, so this is almost never exhausted.
    private static final int SKY_REROLL_ATTEMPTS = 8;

    // how far inside the world border a swap block must land, in blocks. Matches the dragon ball scatter margin.
    private static final int BORDER_MARGIN = 16;

    public static void scatter(ServerLevel level, ShadowDragonStorage storage)
    {
        BlockPos spawn = level.getSharedSpawnPos();
        Random random = new Random();
        for (int star = 1; star <= CorruptedBalls.COUNT; star++)
        {
            boolean settled = false;
            int lastX = 0;
            int lastZ = 0;
            for (int attempt = 0; attempt < SKY_REROLL_ATTEMPTS && !settled; attempt++)
            {
                int x = spawn.getX() + random.nextInt(SCATTER_RANGE * 2) - SCATTER_RANGE;
                int z = spawn.getZ() + random.nextInt(SCATTER_RANGE * 2) - SCATTER_RANGE;
                // Keep the swap block inside the world border (vanilla and the enabled suite border, whichever is
                // tighter), 16 blocks in. Pure arithmetic, loads no chunk, so it is safe on the server thread.
                int[] clamped = BorderClamp.clampInside(level, x, z, BORDER_MARGIN);
                x = clamped[0];
                z = clamped[1];
                lastX = x;
                lastZ = z;
                // hasChunkAt only cares about the column, so any Y works for the loaded check here
                if (!level.hasChunkAt(new BlockPos(x, level.getMinBuildHeight(), z)))
                {
                    // chunk not loaded: record the chosen X/Z as pending so drain() can finish it once the chunk loads,
                    // rather than dropping the star and leaving the set uncompletable. Y (and the sky check) resolve at
                    // placement time; we never force generation here just to pick a spot.
                    storage.addPending(new ShadowDragonStorage.Pending(star, x, z));
                    LoggingHandler.sulog.debug("[wishtracking] deferred star {} at x={} z={} (chunk not loaded)", star, x, z);
                    settled = true;
                    break;
                }
                // Loaded column: resolve a sky-exposed surface. The chunk is already FULL (hasChunkAt confirmed it), so
                // the helper's force-to-FULL is a cheap ticket, and the read is real, never the world floor. A column
                // that cannot see the sky (roofed, overhang) is rejected and we reroll a fresh X/Z rather than bury it.
                BlockPos surface = SurfaceSnap.skyExposedSurface(level, x, z);
                if (surface == null)
                {
                    continue;
                }
                level.setBlockAndUpdate(surface, CorruptedBalls.BALLS[star].get().defaultBlockState());
                storage.addPlaced(surface);
                settled = true;
            }
            if (!settled)
            {
                // Every reroll landed on a loaded but sky-less column: defer the last pick so drain() can retry it later
                // rather than drop the star and leave the set uncompletable.
                storage.addPending(new ShadowDragonStorage.Pending(star, lastX, lastZ));
                LoggingHandler.sulog.debug("[wishtracking] deferred star {} at x={} z={} (no sky-exposed column found)",
                        star, lastX, lastZ);
            }
        }
    }

    /**
     * Finish pending placements whose chunks have since loaded. For each pending star whose column is loaded, the
     * surface Y is resolved now (the heightmap is only meaningful once the chunk exists), the block is placed and
     * recorded as placed, and the entry drops out of pending. Entries whose chunk is still not loaded stay pending
     * for the next pass. Never forces chunk generation. Callers must confirm {@link ShadowDragonStorage#hasPending()}
     * first so the no-pending case stays free.
     */
    public static void drain(ServerLevel level, ShadowDragonStorage storage)
    {
        List<ShadowDragonStorage.Pending> stillPending = new ArrayList<>();
        for (ShadowDragonStorage.Pending p : storage.getPending())
        {
            if (!level.hasChunkAt(new BlockPos(p.x(), level.getMinBuildHeight(), p.z())))
            {
                // chunk still not loaded: keep it pending for a later pass
                stillPending.add(p);
                continue;
            }
            // Loaded column: resolve a sky-exposed surface (the helper's force-to-FULL is cheap here, the chunk is
            // already FULL). If the column cannot see the sky, keep it pending rather than bury it; a fixed pending X/Z
            // cannot be rerolled the way scatter() rerolls, so it simply waits, which never places a buried ball.
            BlockPos surface = SurfaceSnap.skyExposedSurface(level, p.x(), p.z());
            if (surface == null)
            {
                stillPending.add(p);
                LoggingHandler.sulog.debug("[wishtracking] deferred star {} at x={} z={} still (no sky here)",
                        p.star(), p.x(), p.z());
                continue;
            }
            level.setBlockAndUpdate(surface, CorruptedBalls.BALLS[p.star()].get().defaultBlockState());
            storage.addPlaced(surface);
            LoggingHandler.sulog.debug("[wishtracking] placed deferred star {} at {}", p.star(), surface);
        }
        // keep only the entries we could not finish; the rest are now placed
        storage.setPending(stillPending);
    }

    // remove all recorded swap blocks and clear the record. only clears blocks whose chunk is loaded; positions
    // in unloaded chunks stay recorded so a later pass can finish the job. also drops every pending placement so a
    // disarm cannot leave deferred stars that would still spawn later.
    public static void clear(ServerLevel level, ShadowDragonStorage storage)
    {
        // pending stars were never placed, so there is nothing in the world to remove; just forget them
        storage.clearPending();
        List<BlockPos> remaining = new ArrayList<>();
        for (BlockPos pos : storage.getPlaced())
        {
            if (!level.hasChunkAt(pos))
            {
                // chunk not loaded: leave it recorded so a later pass can finish the job
                remaining.add(pos);
                continue;
            }
            if (level.getBlockState(pos).getBlock() instanceof CorruptedBallBlock)
                level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
        // keep only the positions we could not handle; drop everything actually cleared
        storage.setPlaced(remaining);
    }

    public static boolean isOverworld(ServerLevel level)
    {
        return level.dimension() == Level.OVERWORLD;
    }
}
