package net.shurui.dev.shuruis_raid_bosses.region;

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

    /**
     * Optional CYLINDER inscribed in the box: centre X/Z and radius, radius 0 = "just the box". A rift arena's
     * barrier is a ring at a fixed radius, but the region is the box that CIRCUMSCRIBES it, whose corners lie
     * outside the barrier: a fighter knocked into one was through the wall while every containment test still
     * said "inside". Rectangular arenas leave the radius at 0 and fall through to plain box behaviour.
     */
    private final double cylinderX;
    private final double cylinderZ;
    private final double cylinderRadius;

    public Region(ResourceKey<Level> dimension, BlockPos a, BlockPos b) {
        this(dimension, a, b, 0.0, 0.0, 0.0);
    }

    public Region(ResourceKey<Level> dimension, BlockPos a, BlockPos b,
                  double cylinderX, double cylinderZ, double cylinderRadius) {
        this.dimension = dimension;
        this.min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        this.cylinderX = cylinderX;
        this.cylinderZ = cylinderZ;
        this.cylinderRadius = Math.max(0.0, cylinderRadius);
    }

    /** This region's box, constrained to the cylinder of {@code radius} about {@code (cx, cz)}. */
    public Region withCylinder(double cx, double cz, double radius) {
        return new Region(dimension, min, max, cx, cz, radius);
    }

    /** True when this region is a cylinder rather than a plain box. */
    public boolean hasCylinder() {
        return cylinderRadius > 0.0;
    }

    /** Horizontal cylinder containment. Positive margin permits extra, negative demands clearance inside the wall. */
    private boolean withinCylinder(double x, double z, double margin) {
        if (!hasCylinder()) return true;
        double limit = cylinderRadius + margin;
        if (limit <= 0.0) return false;
        double dx = x - cylinderX;
        double dz = z - cylinderZ;
        return dx * dx + dz * dz <= limit * limit;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    /** Horizontal (X/Z) footprint of the region; Y extents are the real box Y (used for descriptive AABB only). */
    public AABB aabb() {
        return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
    }

    /**
     * Vertical-agnostic box: exact X/Z footprint, spanning the whole column. The arena is an X/Z area with Y
     * from the terrain surface, so a surface-standing entity is never "outside" because of Y.
     */
    private AABB columnAabb() {
        return new AABB(min.getX(), -2048.0, min.getZ(), max.getX() + 1, 2048.0, max.getZ() + 1);
    }

    // Dimension comparison MUST stay first (short-circuit): a region only contains things in its own
    // dimension. Do not drop or reorder it.
    public boolean contains(Entity entity) {
        return entity.level().dimension().equals(dimension)
                && columnAabb().contains(entity.position())
                && withinCylinder(entity.getX(), entity.getZ(), 0.0);
    }

    /** {@link #contains(Entity)} with a margin (blocks) inflated around the X/Z footprint. Y remains column-wide. */
    // Dimension comparison MUST stay first (short-circuit); do not drop it.
    public boolean containsWithMargin(Entity entity, double margin) {
        return entity.level().dimension().equals(dimension)
                && columnAabb().inflate(margin).contains(entity.position())
                && withinCylinder(entity.getX(), entity.getZ(), margin);
    }

    // Dimension comparison MUST stay first (short-circuit); do not drop it.
    public boolean contains(Level level, Vec3 pos) {
        return level.dimension().equals(dimension)
                && columnAabb().contains(pos)
                && withinCylinder(pos.x, pos.z, 0.0);
    }

    /**
     * Containment comparing the dimension by resource-location string, not a {@link ResourceKey} handle, for
     * reflection bridges (SU's npcregion spawn suppression) that only have the id string. Y is
     * vertical-agnostic, passed through only for the standard AABB check.
     */
    // Dimension comparison MUST stay first (short-circuit); do not drop it.
    public boolean contains(String dimensionId, double x, double y, double z) {
        return dimension.location().toString().equals(dimensionId)
                && columnAabb().contains(x, y, z)
                && withinCylinder(x, z, 0.0);
    }

    /** Clamp a position inside the X/Z footprint, {@code margin} blocks from each wall. Full-cell bounds (max
     *  inclusive, hence +1). Y is vertical-agnostic, passed through unchanged. */
    public Vec3 clampInside(Vec3 pos, double margin) {
        double loX = min.getX() + margin,      hiX = max.getX() + 1 - margin;
        double loZ = min.getZ() + margin,      hiZ = max.getZ() + 1 - margin;
        // guard against margin larger than the box on an axis
        double x = hiX >= loX ? Math.min(Math.max(pos.x, loX), hiX) : (loX + hiX) / 2.0;
        double z = hiZ >= loZ ? Math.min(Math.max(pos.z, loZ), hiZ) : (loZ + hiZ) / 2.0;
        if (hasCylinder()) {
            // Pull back along the bearing from centre, not per-axis, so a fighter recovered from a corner
            // lands on the nearest floor inside the ring, not flung across the arena. Dead centre is inside.
            double limit = Math.max(1.0, cylinderRadius - margin);
            double dx = x - cylinderX;
            double dz = z - cylinderZ;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > limit && d > 1.0e-4) {
                double k = limit / d;
                x = cylinderX + dx * k;
                z = cylinderZ + dz * k;
            }
        }
        return new Vec3(x, pos.y, z);
    }

    /** Center of the region, at the top surface (max Y), for teleport destinations. */
    public Vec3 center() {
        return new Vec3((min.getX() + max.getX()) / 2.0 + 0.5, max.getY() + 1, (min.getZ() + max.getZ()) / 2.0 + 0.5);
    }

    /** Center of the region on the floor (min Y), where the raid boss is spawned. */
    public Vec3 floorCenter() {
        return new Vec3((min.getX() + max.getX()) / 2.0 + 0.5, min.getY() + 1, (min.getZ() + max.getZ()) / 2.0 + 0.5);
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
     * A random standing position inside the region, on the floor (for scattering spectators).
     *
     * <p>Honours the cylinder. Rolling in the bounding box alone put roughly a fifth of the rolls in the corners
     * OUTSIDE a circular arena, which is how scattered enemies and players ended up beyond the ring they were
     * meant to be confined to. Rejection sampling keeps the distribution even across the disc; on repeated misses
     * the last roll is pulled back along its bearing by {@link #clampInside} rather than returned as drawn.
     */
    public Vec3 randomInside(net.minecraft.util.RandomSource random) {
        double x = 0, z = 0;
        for (int attempt = 0; attempt < 24; attempt++) {
            x = min.getX() + 0.5 + random.nextInt(Math.max(1, max.getX() - min.getX() + 1));
            z = min.getZ() + 0.5 + random.nextInt(Math.max(1, max.getZ() - min.getZ() + 1));
            if (withinCylinder(x, z, 0.0)) return new Vec3(x, min.getY() + 1, z);
        }
        return clampInside(new Vec3(x, min.getY() + 1, z), 0.0);
    }

    /**
     * A random spot inside this region a player can actually stand in. {@link #randomInside} gives the
     * selection floor plus one, which only stands up if the box was drawn on the ground; a low or
     * full-height selection buries whoever is teleported, and landing in bedrock is under the world.
     *
     * <p>So walk the column UP from the floor to the first real standing spot (two clear blocks over solid).
     * If none exists, fall back to the surface height inside the box, then the raw floor.
     */
    public Vec3 randomStandableInside(net.minecraft.server.level.ServerLevel level,
                                      net.minecraft.util.RandomSource random) {
        if (level == null) return randomInside(random);
        int bottom = Math.max(min.getY(), level.getMinBuildHeight() + 1);
        int top = Math.min(max.getY(), level.getMaxBuildHeight() - 2);
        for (int attempt = 0; attempt < 24; attempt++) {
            int x = min.getX() + random.nextInt(Math.max(1, max.getX() - min.getX() + 1));
            int z = min.getZ() + random.nextInt(Math.max(1, max.getZ() - min.getZ() + 1));
            // Same cylinder rejection as randomInside: a corner of the bounding box is not inside a round arena.
            if (!withinCylinder(x + 0.5, z + 0.5, 0.0)) continue;
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
        // Nothing standable in the box: put them on top of the world inside it rather than buried. Pulled back
        // inside the cylinder first, so this last resort cannot be the one that drops someone outside the ring.
        Vec3 loose = clampInside(new Vec3(
                min.getX() + 0.5 + random.nextInt(Math.max(1, max.getX() - min.getX() + 1)),
                min.getY() + 1,
                min.getZ() + 0.5 + random.nextInt(Math.max(1, max.getZ() - min.getZ() + 1))), 0.0);
        int x = net.minecraft.util.Mth.floor(loose.x);
        int z = net.minecraft.util.Mth.floor(loose.z);
        int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                x, z);
        if (surface > level.getMinBuildHeight() && surface < level.getMaxBuildHeight())
            return new Vec3(x + 0.5, surface, z + 0.5);
        return randomInside(random);
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
        // Written only when set; absent reads back as 0, so pre-cylinder regions still load as plain boxes.
        if (hasCylinder()) {
            tag.putDouble("cylX", cylinderX);
            tag.putDouble("cylZ", cylinderZ);
            tag.putDouble("cylR", cylinderRadius);
        }
        return tag;
    }

    public static Region load(CompoundTag tag) {
        ResourceKey<Level> dim = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                new ResourceLocation(tag.getString("dim")));
        return new Region(dim, BlockPos.of(tag.getLong("min")), BlockPos.of(tag.getLong("max")),
                tag.getDouble("cylX"), tag.getDouble("cylZ"), tag.getDouble("cylR"));
    }

    @Override
    public String toString() {
        return dimension.location() + " [" + min.toShortString() + " -> " + max.toShortString() + "]";
    }
}
