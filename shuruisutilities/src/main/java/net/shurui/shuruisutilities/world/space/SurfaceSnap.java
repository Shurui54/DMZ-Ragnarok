package net.shurui.shuruisutilities.world.space;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Ground-snap for the return trip, same technique as raid_bosses' RaidInstance.surfaceSnap: force-load the exact chunk
 * to FULL, then read the heightmap off the loaded chunk.
 *
 * <p>Force-load first because ServerLevel.getHeight() does NOT load the chunk: on an unloaded chunk it returns the
 * world floor, dropping a returning player into the void. Loading to FULL adds a ticket and drives generation via
 * managedBlock with no server-thread deadlock. MOTION_BLOCKING_NO_LEAVES gives the first empty block above the top
 * solid one; ChunkAccess.getHeight returns that minus one, so we +1.
 */
public final class SurfaceSnap
{
    private SurfaceSnap()
    {
    }

    // feet-Y just above the surface at x/z, force-loading the chunk. For a ceilinged (Nether-style) dim the heightmap
    // is useless, so scan the column down instead: the return trip should never target such a dim, but an
    // admin-teleported origin could.
    public static Vec3 snap(ServerLevel level, double x, double z)
    {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        ChunkAccess chunk = level.getChunk(SectionPos.blockToSectionCoord(bx), SectionPos.blockToSectionCoord(bz));

        if (level.dimensionType().hasCeiling())
        {
            return ceilingSnap(level, x, z, bx, bz);
        }

        int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx & 15, bz & 15) + 1;
        return new Vec3(x, surfaceY, z);
    }

    // Resolve the block a scattered dragon ball should occupy for the column at x/z: the first open block above the top
    // motion-blocking surface, GUARANTEED to be exposed to the sky. This is the one helper every place WE commit a ball
    // position must go through.
    //
    // It does the two things a bare ServerLevel.getHeight does not. First it forces the target chunk to FULL, the same
    // way snap() above and TravelToPlanetC2SMixin's ground-snap do, because getHeight does NOT drive generation: on an
    // ungenerated column it hands back the world floor, which is exactly how a ball ends up entombed at y=0. Forcing to
    // FULL populates the heightmap (and the lighting, since FULL is reached only after the light step) so the read is
    // real. Second it checks the resolved column can see the sky and returns null if it cannot, so a ball is never
    // committed under a roof, in a cave, or inside terrain.
    //
    // Forcing a chunk to FULL is expensive, so call this only at the moment a position is actually committed (a scatter,
    // or an apply as a chunk loads), never speculatively across many candidates in a tick loop. canSeeSky is light based
    // (skylight == max), which is why the FULL force matters: it guarantees the skylight is computed before we read it.
    //
    // Returns the sky-exposed surface position, or null if the column cannot see the sky and the ball must NOT be placed.
    public static BlockPos skyExposedSurface(ServerLevel level, int x, int z)
    {
        level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.canSeeSky(pos))
        {
            return null;
        }
        return pos;
    }

    // is (x, feetY, z) already standable (solid underfoot, feet + head clear)? Force-loads the chunk so the reads are
    // real. Lets the planet-landing path honour an explicit datapack Y rather than snapping up onto an enclosing roof
    // (Beerus sits under a full glass dome, so the heightmap would drag an interior landing onto the dome apex).
    public static boolean isStandable(ServerLevel level, double x, double feetY, double z)
    {
        int bx = Mth.floor(x);
        int by = Mth.floor(feetY);
        int bz = Mth.floor(z);
        level.getChunk(SectionPos.blockToSectionCoord(bx), SectionPos.blockToSectionCoord(bz));
        return isSafeStand(level, bx, by, bz);
    }

    // ceilinged-dim fallback: scan down from under the roof for solid ground with two blocks of headroom. Returns
    // world floor + 1 if nothing fits, so a player is never punched through the roof.
    private static Vec3 ceilingSnap(ServerLevel level, double x, double z, int bx, int bz)
    {
        int top = level.getMaxBuildHeight() - 2;
        int floor = level.getMinBuildHeight() + 1;
        for (int y = top; y > floor; y--)
        {
            if (isSafeStand(level, bx, y, bz))
            {
                return new Vec3(x, y, z);
            }
        }
        return new Vec3(x, floor, z);
    }

    // solid below the feet, feet + head clear. Assumes the chunk is loaded; rejects feet at or below the world floor.
    private static boolean isSafeStand(ServerLevel level, int bx, int feetY, int bz)
    {
        if (feetY <= level.getMinBuildHeight())
        {
            return false;
        }
        BlockPos feet = new BlockPos(bx, feetY, bz);
        BlockPos head = feet.above();
        BlockPos below = feet.below();
        boolean groundSolid = !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
        boolean feetClear = level.getBlockState(feet).getCollisionShape(level, feet).isEmpty();
        boolean headClear = level.getBlockState(head).getCollisionShape(level, head).isEmpty();
        return groundSolid && feetClear && headClear;
    }
}
