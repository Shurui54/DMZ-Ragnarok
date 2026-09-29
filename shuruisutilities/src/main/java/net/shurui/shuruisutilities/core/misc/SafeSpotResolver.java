package net.shurui.shuruisutilities.core.misc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import net.shurui.shuruisutilities.world.space.SpaceKeys;

/**
 * Turns a requested teleport destination into the nearest SAFE standing spot, force-loading the target chunk
 * first so the block/heightmap reads are trustworthy. This exists so no teleport in the suite ever REFUSES for
 * "obstructed" or "unloaded": we relocate instead of failing.
 *
 * <p>Same core technique as raid_bosses' {@code RaidInstance.surfaceSnap} and {@code space.SurfaceSnap}: load
 * the exact chunk to FULL, then read blocks off the loaded world. A shared helper across the three mods would be
 * cleaner, but those two already differ (arena-aware fallbacks, open-sky-only assumptions) and live in
 * separate compile units with no shared module, so this stays a third small copy of the shared idea rather than
 * a forced dependency edge. See the report note.
 *
 * <p>Why force-load first: {@link ServerLevel#getHeight} and even {@code getBlockState} do NOT drive chunk
 * generation. On an unloaded column the heightmap returns the world floor and blocks read as air, which is the
 * whole reason "that area is unloaded" refusals existed. {@code level.getChunk(int, int)} here resolves to
 * {@code getChunk(x, z, ChunkStatus.FULL, true)}: it adds a ticket and pumps generation via managedBlock on the
 * server thread (no deadlock), so afterwards the reads are real.
 */
public final class SafeSpotResolver
{
    private SafeSpotResolver()
    {
    }

    /** Horizontal search bound: we probe columns out to this many blocks from the requested x/z (a 17x17 box). */
    public static final int HORIZONTAL_RADIUS = 8;

    /** How far ABOVE the requested Y we look before scanning downward, so a spot buried one block deep still lands close. */
    private static final int UP_WINDOW = 16;

    /** Result of a resolve: the safe feet position, and whether we had to move it away from the request. */
    public static final class Result
    {
        public final double x;
        public final double y;
        public final double z;
        public final boolean moved;
        // true only for the vanishingly-rare last resort (nothing safe found, fell back to world spawn)
        public final boolean fellBackToSpawn;

        Result(double x, double y, double z, boolean moved, boolean fellBackToSpawn)
        {
            this.x = x;
            this.y = y;
            this.z = z;
            this.moved = moved;
            this.fellBackToSpawn = fellBackToSpawn;
        }
    }

    /**
     * Resolve (reqX, reqY, reqZ) feet position to the nearest safe standing spot in {@code level}, force-loading
     * only the destination chunk; neighbouring ring columns are read only when their chunk is already loaded, never
     * force-generated on the server thread. The returned spot keeps the requested x/z and y exactly when that is already safe,
     * so an explicit coordinate teleport still lands where asked; it only moves when the request is obstructed,
     * in a fluid, or in the void.
     *
     * <p>Search order, all after the chunk load: the requested column first (Y as close to reqY as possible),
     * then columns in growing horizontal rings out to {@link #HORIZONTAL_RADIUS}, each ring's column scanned the
     * same way. On a ceilinged dimension (Nether-style solid roof) the heightmap is useless, so we scan the
     * column down from just under the roof instead of trusting it. If no ring yields a spot we fall back to the
     * open-sky heightmap surface at the exact x/z, and only if THAT is void do we use the world spawn as an
     * absolute last resort (flagged so the caller can say so in chat). That last resort should essentially never
     * happen.
     */
    public static Result resolve(ServerLevel level, double reqX, double reqY, double reqZ)
    {
        int bx = Mth.floor(reqX);
        int bz = Mth.floor(reqZ);
        // force-load the requested column's chunk to FULL so every read below is real, not unloaded-air.
        level.getChunk(SectionPos.blockToSectionCoord(bx), SectionPos.blockToSectionCoord(bz));

        int reqY0 = Mth.floor(reqY);
        boolean ceiling = level.dimensionType().hasCeiling();

        // ring 0..R: the exact column first, then each larger ring. first safe hit wins (nearest by ring).
        for (int r = 0; r <= HORIZONTAL_RADIUS; r++)
        {
            for (int dx = -r; dx <= r; dx++)
            {
                for (int dz = -r; dz <= r; dz++)
                {
                    // only the outer shell of this ring; inner cells were covered by smaller r already.
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r)
                    {
                        continue;
                    }
                    int cx = bx + dx;
                    int cz = bz + dz;
                    // Only the destination chunk is force-loaded (above). A ring column that falls in a NEIGHBOUR
                    // chunk is scanned only if that chunk is ALREADY loaded: we never block the server thread
                    // generating a neighbour just to widen the search. getChunkNow returns null for an unloaded or
                    // partially-loaded chunk, in which case the column is skipped. Every column we do scan is a real
                    // loaded chunk, so any spot accepted from it is still fully safety-checked (never a block or void).
                    if (level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(cx),
                            SectionPos.blockToSectionCoord(cz)) == null)
                    {
                        continue;
                    }
                    Integer y = ceiling ? scanCeilingColumn(level, cx, cz)
                            : scanColumn(level, cx, reqY0, cz);
                    if (y != null)
                    {
                        boolean moved = (cx != bx || cz != bz || y != reqY0);
                        // keep the requested fractional x/z when we did not move horizontally, so an exact tp
                        // lands on the requested spot and not snapped to a block centre.
                        double outX = (cx == bx) ? reqX : cx + 0.5D;
                        double outZ = (cz == bz) ? reqZ : cz + 0.5D;
                        return new Result(outX, y, outZ, moved, false);
                    }
                }
            }
        }

        // nothing in the box. fall back to the open-sky surface at the exact x/z. The destination chunk was
        // force-loaded at the top, so getChunkNow returns it without any further load.
        if (!ceiling)
        {
            ChunkAccess chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(bx),
                    SectionPos.blockToSectionCoord(bz));
            if (chunk != null)
            {
                int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx & 15, bz & 15) + 1;
                if (isSafeStand(level, bx, surfaceY, bz))
                {
                    return new Result(reqX, surfaceY, reqZ, true, false);
                }
            }
        }

        // SPACE (a no-terrain void dimension SU owns): there is nothing solid to suffocate in, so the requested
        // spot is inherently safe and must be honoured EXACTLY. We must NEVER fall through to the world-spawn last
        // resort here: the space dimension's own shared spawn is itself an empty void coordinate, so yanking a
        // player there is precisely the "teleport to a friend floating in space just dumps me at spawn" bug. Real
        // stamped blocks in space (asteroids, moons) are already handled above by the ring scan, which lands a
        // player who flew into them; this branch only catches the common case of a target floating in open space,
        // where keeping the requested position is the correct answer. The planet SURFACE dimension is deliberately
        // NOT covered (SpaceKeys.isSpace is false for it): it has real terrain and keeps the normal ground snap.
        if (SpaceKeys.isSpace(level))
        {
            return new Result(reqX, reqY, reqZ, false, false);
        }

        // absolute last resort: world spawn. flagged so the caller tells the player. should be vanishingly rare.
        BlockPos spawn = level.getSharedSpawnPos();
        int spawnY = spawn.getY();
        // Spawn chunks are normally kept resident, so this reads without any force-load. If spawn somehow is not
        // loaded we do NOT block to generate it: the shared spawn Y is itself a valid coordinate to fall back to.
        ChunkAccess spawnChunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(spawn.getX()),
                SectionPos.blockToSectionCoord(spawn.getZ()));
        if (spawnChunk != null && !isSafeStand(level, spawn.getX(), spawnY, spawn.getZ()))
        {
            spawnY = spawnChunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX() & 15,
                    spawn.getZ() & 15) + 1;
        }
        return new Result(spawn.getX() + 0.5D, spawnY, spawn.getZ() + 0.5D, true, true);
    }

    /**
     * True when (x, y, z) is already a safe STANDING spot: solid ground directly below the feet, feet and head clear
     * of collision, and no dangerous fluid. Force-loads the target chunk first so the reads are real, not
     * unloaded-air. Unlike {@link TeleportHelper#canTeleportTo}, this REQUIRES ground below, so open air / a mid-air
     * position returns false, which is what lets a caller decide to grant flight there instead of dropping the
     * player.
     */
    public static boolean isStandable(ServerLevel level, double x, double y, double z)
    {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        level.getChunk(SectionPos.blockToSectionCoord(bx), SectionPos.blockToSectionCoord(bz));
        return isSafeStand(level, bx, Mth.floor(y), bz);
    }

    /**
     * Find the safe feet-Y in the column (cx, cz) whose value is closest to {@code reqY}: probe reqY, then reqY+1,
     * reqY-1, reqY+2, reqY-2 ... alternating out, scanning up to {@link #UP_WINDOW} above and all the way to the
     * world floor below. Returns null if the whole probed column is unsafe. Assumes the column's chunk is loaded.
     */
    private static Integer scanColumn(ServerLevel level, int cx, int reqY, int cz)
    {
        int floor = level.getMinBuildHeight() + 1;
        int roof = level.getMaxBuildHeight() - 1;
        int maxUp = Math.min(UP_WINDOW, roof - reqY);
        int maxDown = reqY - floor;
        int reach = Math.max(maxUp, maxDown);
        for (int d = 0; d <= reach; d++)
        {
            if (d <= maxUp && isSafeStand(level, cx, reqY + d, cz))
            {
                return reqY + d;
            }
            if (d > 0 && d <= maxDown && isSafeStand(level, cx, reqY - d, cz))
            {
                return reqY - d;
            }
        }
        return null;
    }

    /**
     * Ceilinged-dimension column scan (Nether-style): the MOTION_BLOCKING heightmap sits on the bedrock roof, so
     * a naive surface snap would drop a player ON the roof. Instead scan DOWN from just under the roof for the
     * first safe standing spot. Returns null if the column has none. Assumes the column's chunk is loaded.
     */
    private static Integer scanCeilingColumn(ServerLevel level, int cx, int cz)
    {
        int top = level.getMaxBuildHeight() - 2;
        int floor = level.getMinBuildHeight() + 1;
        for (int y = top; y > floor; y--)
        {
            if (isSafeStand(level, cx, y, cz))
            {
                return y;
            }
        }
        return null;
    }

    /**
     * A safe standing spot at feet position (bx, feetY, bz): feet above the world floor and below the roof, a
     * solid (non-empty-collision) block directly below the feet, both the feet and head blocks clear of
     * collision, and neither the floor, feet, nor head a fluid the player would drown or burn in. Lava is always
     * rejected; standing water is rejected as a landing because a player cannot breathe there indefinitely.
     * Assumes the chunk is loaded.
     */
    private static boolean isSafeStand(ServerLevel level, int bx, int feetY, int bz)
    {
        if (feetY <= level.getMinBuildHeight() || feetY >= level.getMaxBuildHeight() - 1)
        {
            return false;
        }
        BlockPos feet = new BlockPos(bx, feetY, bz);
        BlockPos head = feet.above();
        BlockPos below = feet.below();

        BlockState belowState = level.getBlockState(below);
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(head);

        boolean groundSolid = !belowState.getCollisionShape(level, below).isEmpty();
        boolean feetClear = feetState.getCollisionShape(level, feet).isEmpty();
        boolean headClear = headState.getCollisionShape(level, head).isEmpty();
        if (!groundSolid || !feetClear || !headClear)
        {
            return false;
        }
        // reject fluids: any lava contact, or water in the feet/head space (a submerged landing would drown).
        if (isDangerousFluid(belowState.getFluidState(), true) || isDangerousFluid(feetState.getFluidState(), false)
                || isDangerousFluid(headState.getFluidState(), false))
        {
            return false;
        }
        return true;
    }

    // lava is always dangerous (contact burns); water is dangerous only in the body space (feet/head), where it
    // would drown the player. floorContact=true means "a lava floor still burns", so only lava is rejected there.
    private static boolean isDangerousFluid(FluidState fluid, boolean floorContact)
    {
        if (fluid.isEmpty())
        {
            return false;
        }
        boolean lava = fluid.is(net.minecraft.tags.FluidTags.LAVA);
        if (floorContact)
        {
            return lava;
        }
        return lava || fluid.is(net.minecraft.tags.FluidTags.WATER);
    }
}
