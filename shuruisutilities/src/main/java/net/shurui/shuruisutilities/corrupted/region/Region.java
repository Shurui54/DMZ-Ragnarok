package net.shurui.shuruisutilities.corrupted.region;

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
 * WorldEdit selection). Used for the shadow dragon boss arenas. Copied from the raid-bosses addon so SU
 * carries no hard dependency on it; the column-based containment (Y-agnostic) is intentional for arenas.
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

    /** Horizontal (X/Z) footprint of the region; Y extents are the real box Y (used for descriptive AABB only). */
    public AABB aabb() {
        return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
    }

    /**
     * Vertical-agnostic containment box: keeps the exact X/Z footprint but spans effectively the whole vertical
     * column (world build-height min to max). The arena is treated as an X/Z area with Y derived from the
     * terrain surface at spawn time, so a surface-standing entity is never treated as "outside" because of Y.
     */
    private AABB columnAabb() {
        return new AABB(min.getX(), -2048.0, min.getZ(), max.getX() + 1, 2048.0, max.getZ() + 1);
    }

    // Dimension comparison is mandatory and MUST stay first (short-circuit): a region only ever contains
    // entities/positions in its own dimension. Do not drop or reorder it in a future edit.
    public boolean contains(Entity entity) {
        return entity.level().dimension().equals(dimension) && columnAabb().contains(entity.position());
    }

    /** {@link #contains(Entity)} with a margin (blocks) inflated around the X/Z footprint. Y remains column-wide. */
    // Dimension comparison is mandatory and MUST stay first (short-circuit); do not drop it in a future edit.
    public boolean containsWithMargin(Entity entity, double margin) {
        return entity.level().dimension().equals(dimension) && columnAabb().inflate(margin).contains(entity.position());
    }

    // Dimension comparison is mandatory and MUST stay first (short-circuit); do not drop it in a future edit.
    public boolean contains(Level level, Vec3 pos) {
        return level.dimension().equals(dimension) && columnAabb().contains(pos);
    }

    /**
     * Cross-mod-friendly containment test that compares the dimension by its resource-location string rather than
     * a {@link ResourceKey} handle. Used by reflection-based bridges that only have the dimension id string on hand.
     * Y is vertical-agnostic (the arena is an X/Z column), so the Y arg never excludes a point; it's passed through
     * only for the standard AABB check.
     */
    // Dimension comparison is mandatory and MUST stay first (short-circuit); do not drop it in a future edit.
    public boolean contains(String dimensionId, double x, double y, double z) {
        return dimension.location().toString().equals(dimensionId) && columnAabb().contains(x, y, z);
    }

    /** Clamp a position to strictly inside the arena's X/Z footprint, keeping {@code margin} blocks away from each
     *  wall. Uses full-cell bounds (max is inclusive, so the +1). Y is vertical-agnostic and passed through
     *  unchanged; the arena is an X/Z area with Y driven by the terrain surface, not the box. */
    public Vec3 clampInside(Vec3 pos, double margin) {
        double loX = min.getX() + margin,      hiX = max.getX() + 1 - margin;
        double loZ = min.getZ() + margin,      hiZ = max.getZ() + 1 - margin;
        // guard against margin larger than the box on an axis
        double x = hiX >= loX ? Math.min(Math.max(pos.x, loX), hiX) : (loX + hiX) / 2.0;
        double z = hiZ >= loZ ? Math.min(Math.max(pos.z, loZ), hiZ) : (loZ + hiZ) / 2.0;
        return new Vec3(x, pos.y, z);
    }

    /** Center of the region, at the top surface (max Y), for teleport destinations. */
    public Vec3 center() {
        return new Vec3((min.getX() + max.getX()) / 2.0 + 0.5, max.getY() + 1, (min.getZ() + max.getZ()) / 2.0 + 0.5);
    }

    /** Center of the region on the floor (min Y), where a boss is spawned. */
    public Vec3 floorCenter() {
        return new Vec3((min.getX() + max.getX()) / 2.0 + 0.5, min.getY() + 1, (min.getZ() + max.getZ()) / 2.0 + 0.5);
    }

    /** A random standing position inside the region, on the floor. */
    public Vec3 randomInside(net.minecraft.util.RandomSource random) {
        double x = min.getX() + 0.5 + random.nextInt(Math.max(1, max.getX() - min.getX() + 1));
        double z = min.getZ() + 0.5 + random.nextInt(Math.max(1, max.getZ() - min.getZ() + 1));
        return new Vec3(x, min.getY() + 1, z);
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
