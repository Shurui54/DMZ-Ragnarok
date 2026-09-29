package net.shurui.dev.shuruis_dmz_tournaments.region;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * An axis-aligned cuboid region bound to a specific dimension. Defined by two corners (typically a
 * WorldEdit selection). Used for the arena, the fighter waiting area and the spectator stands.
 */
public class Region {
    private final ResourceKey<Level> dimension;
    private final BlockPos min;
    private final BlockPos max;

    public Region(ResourceKey<Level> dimension, BlockPos a, BlockPos b) {
        this.dimension = dimension;
        this.min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public AABB aabb() {
        return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
    }

    /**
     * Containment for ring-out and PvP-zone checks: a full-height COLUMN, only the X/Z footprint is bounded
     * (with a generous +/-1000 Y sanity band), so flying or being launched upward never counts as leaving.
     * The stored corners keep the admin-selected floor Y, used by the spawn/teleport helpers below.
     */
    private static final int Y_MIN = -1000;
    private static final int Y_MAX = 1000;

    public boolean contains(Entity entity) {
        return entity.level().dimension().equals(dimension) && containsColumn(entity.position());
    }

    public boolean contains(Level level, Vec3 pos) {
        return level.dimension().equals(dimension) && containsColumn(pos);
    }

    /** X/Z footprint check with a full-height (+/-1000) Y band. */
    private boolean containsColumn(Vec3 pos) {
        return pos.x >= min.getX() && pos.x < max.getX() + 1
                && pos.z >= min.getZ() && pos.z < max.getZ() + 1
                && pos.y >= Y_MIN && pos.y <= Y_MAX;
    }

    /** Center of the region, at the top surface (max Y), for teleport destinations. */
    public Vec3 center() {
        return new Vec3((min.getX() + max.getX()) / 2.0 + 0.5, max.getY() + 1, (min.getZ() + max.getZ()) / 2.0 + 0.5);
    }

    /** First fighter's starting spot: centered on X, two blocks in from the min-Z wall, on the floor. */
    public Vec3 fighterSpawnA() {
        return new Vec3((min.getX() + max.getX()) / 2.0 + 0.5, min.getY() + 1, min.getZ() + 2.5);
    }

    /** Second fighter's starting spot: opposite side, two blocks in from the max-Z wall. */
    public Vec3 fighterSpawnB() {
        return new Vec3((min.getX() + max.getX()) / 2.0 + 0.5, min.getY() + 1, max.getZ() - 1.5);
    }

    /**
     * Starting spot for team member {@code index} of {@code count}, on the given side (side A = min-Z, side B
     * = max-Z), spread evenly across X near the wall. For {@code count == 1} matches
     * {@link #fighterSpawnA()}/{@link #fighterSpawnB()}.
     */
    public Vec3 sideSpawn(boolean sideA, int index, int count) {
        double z = sideA ? min.getZ() + 2.5 : max.getZ() - 1.5;
        int span = Math.max(1, max.getX() - min.getX());
        double x;
        if (count <= 1) {
            x = (min.getX() + max.getX()) / 2.0 + 0.5;
        } else {
            // spread across the interior width, off the walls
            double step = span / (double) (count + 1);
            x = min.getX() + 0.5 + step * (index + 1);
        }
        return new Vec3(x, min.getY() + 1, z);
    }

    /** A random standing position inside the region, on the floor (for scattering spectators). */
    public Vec3 randomInside(net.minecraft.util.RandomSource random) {
        double x = min.getX() + 0.5 + random.nextInt(Math.max(1, max.getX() - min.getX() + 1));
        double z = min.getZ() + 0.5 + random.nextInt(Math.max(1, max.getZ() - min.getZ() + 1));
        return new Vec3(x, min.getY() + 1, z);
    }

    /**
     * A random spot inside this region a player can actually stand in.
     *
     * <p>{@link #randomInside} gives the selection floor plus one, which only stands if the admin drew the box
     * on the ground. A low corner, or a full-height selection with its bottom at the world floor, drops whoever
     * is teleported inside the terrain, and landing in bedrock is under the world.
     *
     * <p>So walk the column UP from the floor to the first genuine standing spot: two clear blocks over
     * something solid. Falls back to the surface height inside the box, then the raw floor.
     */
    public Vec3 randomStandableInside(net.minecraft.server.level.ServerLevel level,
                                      net.minecraft.util.RandomSource random) {
        if (level == null) return randomInside(random);
        int bottom = Math.max(min.getY(), level.getMinBuildHeight() + 1);
        int top = Math.min(max.getY(), level.getMaxBuildHeight() - 2);
        for (int attempt = 0; attempt < 24; attempt++) {
            int x = min.getX() + random.nextInt(Math.max(1, max.getX() - min.getX() + 1));
            int z = min.getZ() + random.nextInt(Math.max(1, max.getZ() - min.getZ() + 1));
            net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
            for (int y = bottom; y <= top; y++) {
                m.set(x, y, z);
                if (!level.getBlockState(m).getCollisionShape(level, m).isEmpty()) continue;
                m.set(x, y + 1, z);
                if (!level.getBlockState(m).getCollisionShape(level, m).isEmpty()) continue;
                m.set(x, y - 1, z);
                if (!level.getBlockState(m).blocksMotion()) continue;
                return new Vec3(x + 0.5, y, z + 0.5);
            }
        }
        // nothing standable in the box: put them on top of the world inside it
        int x = min.getX() + random.nextInt(Math.max(1, max.getX() - min.getX() + 1));
        int z = min.getZ() + random.nextInt(Math.max(1, max.getZ() - min.getZ() + 1));
        int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                x, z);
        if (surface > level.getMinBuildHeight() && surface < level.getMaxBuildHeight())
            return new Vec3(x + 0.5, surface, z + 0.5);
        return randomInside(random);
    }

    /**
     * Snap a chosen spawn to a safe standing spot, keeping its X/Z and only correcting the Y.
     *
     * <p>The fighter spawns ({@link #sideSpawn}, {@link #fighterSpawnA()}, {@link #fighterSpawnB()}) answer the
     * selection floor plus one, which only stands if the admin drew the box exactly on the arena floor. Drawn a few
     * blocks low, that spot is inside or under the floor, and a fighter placed there is stuck below the arena (bug
     * 640). Walk the column UP from the region floor to the first genuine standing spot (two clear blocks over
     * something solid), preserving the intended X/Z so the two sides still face each other. Falls back to the column's
     * surface height, then to the desired point unchanged.
     */
    public Vec3 snapToStand(net.minecraft.server.level.ServerLevel level, Vec3 desired) {
        if (level == null) return desired;
        int x = net.minecraft.util.Mth.floor(desired.x);
        int z = net.minecraft.util.Mth.floor(desired.z);
        int bottom = Math.max(min.getY(), level.getMinBuildHeight() + 1);
        int top = Math.min(max.getY(), level.getMaxBuildHeight() - 2);
        net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int y = bottom; y <= top; y++) {
            m.set(x, y, z);
            if (!level.getBlockState(m).getCollisionShape(level, m).isEmpty()) continue;
            m.set(x, y + 1, z);
            if (!level.getBlockState(m).getCollisionShape(level, m).isEmpty()) continue;
            m.set(x, y - 1, z);
            if (!level.getBlockState(m).blocksMotion()) continue;
            return new Vec3(desired.x, y, desired.z);
        }
        int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                x, z);
        if (surface > level.getMinBuildHeight() && surface < level.getMaxBuildHeight())
            return new Vec3(desired.x, surface, desired.z);
        return desired;
    }

    public BlockPos min() {
        return min;
    }

    public BlockPos max() {
        return max;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", dimension.location().toString());
        tag.putLong("min", min.asLong());
        tag.putLong("max", max.asLong());
        return tag;
    }

    public static Region load(CompoundTag tag) {
        ResourceKey<Level> dim = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                new ResourceLocation(tag.getString("dim")));
        return new Region(dim, BlockPos.of(tag.getLong("min")), BlockPos.of(tag.getLong("max")));
    }

    @Override
    public String toString() {
        return dimension.location() + " [" + min.toShortString() + " -> " + max.toShortString() + "]";
    }
}
