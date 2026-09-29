package net.shurui.dev.shuruis_dmz_dungeons.dungeon.gen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

// the Mine Cells grid, ported and verified against a decompile of GridPiece.getStartingPos(). a floor is a grid of
// 16x16x16 cells (one X/Z/Y step is 16 blocks); a room occupies one or more cells at a grid position with a rotation.
//
// the load-bearing detail is the ROTATION ANCHOR. rotating a room's local blocks about the origin sends them into
// negative space (a clockwise-90 turn maps local (x,z) to (-z,x)); left uncorrected the room sits one cell off and
// gaps or overlaps its neighbour. Mine Cells corrects this in getStartingPos with a per-rotation corner offset from
// the room's footprint. this class reproduces that offset exactly (anchorOffset) plus the matching about-origin
// rotation (rotate), so a placed room's transformed AABB always starts at its cell's minimum corner.
public final class GridModel {

    // edge length in blocks of one grid cell, on all three axes. Mine Cells uses 16 (a chunk-section slice).
    public static final int CELL = 16;

    private GridModel() {
    }

    // rotate a template-local block about the origin (0,0,0), matching StructureTemplate.transform with Mirror.NONE
    // and pivot (0,0,0). the anchorOffset() must be added afterwards to bring the footprint into non-negative cell space.
    public static BlockPos rotate(int x, int y, int z, Rotation rotation) {
        switch (rotation) {
            case CLOCKWISE_90:
                return new BlockPos(-z, y, x);
            case CLOCKWISE_180:
                return new BlockPos(-x, y, -z);
            case COUNTERCLOCKWISE_90:
                return new BlockPos(z, y, -x);
            case NONE:
            default:
                return new BlockPos(x, y, z);
        }
    }

    // the corner correction added to every about-origin-rotated block so the room's transformed AABB starts at (0,0).
    // sizeX/sizeZ are the UNROTATED block dimensions. Mine Cells' getStartingPos maths: a 90 rotation swaps the
    // footprint, so the axis that gained the length shifts by (other-1).
    public static BlockPos anchorOffset(Rotation rotation, int sizeX, int sizeZ) {
        switch (rotation) {
            case CLOCKWISE_90:
                return new BlockPos(sizeZ - 1, 0, 0);
            case CLOCKWISE_180:
                return new BlockPos(sizeX - 1, 0, sizeZ - 1);
            case COUNTERCLOCKWISE_90:
                return new BlockPos(0, 0, sizeX - 1);
            case NONE:
            default:
                return BlockPos.ZERO;
        }
    }

    // rotated footprint width in X, in cells, of a room whose unrotated block dimensions are sizeX by sizeZ. a 90
    // rotation swaps the two axes.
    public static int footprintCellsX(Rotation rotation, int sizeX, int sizeZ) {
        int blocks = (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) ? sizeZ : sizeX;
        return Math.max(1, ceilDiv(blocks, CELL));
    }

    // rotated footprint depth in Z, in cells.
    public static int footprintCellsZ(Rotation rotation, int sizeX, int sizeZ) {
        int blocks = (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) ? sizeX : sizeZ;
        return Math.max(1, ceilDiv(blocks, CELL));
    }

    // world minimum corner of a cell, given the floor's grid origin and a signed grid position.
    // floorOrigin + gridPos * CELL + rotatedLocal (anchor folded into rotatedLocal by the placer).
    public static BlockPos cellMinCorner(BlockPos floorOrigin, int gx, int gy, int gz) {
        return new BlockPos(
                floorOrigin.getX() + gx * CELL,
                floorOrigin.getY() + gy * CELL,
                floorOrigin.getZ() + gz * CELL);
    }

    // world position of one template-local block, applying rotation, anchor correction and cell offset in one place so
    // the layout generator (harvesting markers) and the placer (writing blocks) can never disagree. formula:
    // floorOrigin + gridPos * 16 + (rotated local + anchor).
    public static BlockPos worldPos(BlockPos origin, int gx, int gy, int gz, Rotation rotation,
                                    int sizeX, int sizeZ, int lx, int ly, int lz) {
        BlockPos r = rotate(lx, ly, lz, rotation);
        BlockPos a = anchorOffset(rotation, sizeX, sizeZ);
        return new BlockPos(
                origin.getX() + gx * CELL + r.getX() + a.getX(),
                origin.getY() + gy * CELL + r.getY() + a.getY(),
                origin.getZ() + gz * CELL + r.getZ() + a.getZ());
    }

    // pack a 3D cell coordinate into one long for the used-cell set. 21 bits per axis (signed, +/-1M cells, far larger
    // than any floor), so two distinct cells never collide.
    public static long cellKey(int gx, int gy, int gz) {
        return ((long) (gx & 0x1FFFFF)) | ((long) (gy & 0x1FFFFF) << 21) | ((long) (gz & 0x1FFFFF) << 42);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
