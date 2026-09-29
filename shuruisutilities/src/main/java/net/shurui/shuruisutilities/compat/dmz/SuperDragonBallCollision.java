package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Collision geometry for Shurui's oversized {@code super} dragon ball set, kept out of the mixin so the mixin stays
 * a thin gate and the shape math is plain, readable code with no DMZ imports (it deals only in a set id string and a
 * {@link VoxelShape}).
 *
 * <p>WHY the super set needs its own shape. Every DMZ ball set shares one block class,
 * {@code com.dragonminez.common.init.block.custom.DragonBallBlock}, whose collision shape is a tiny 0.5-block nub in
 * the centre of the cell. That reads fine for the normal balls, whose model fits inside one block, but our Super set
 * is drawn from {@code dball_super4x.geo.json}, a sphere about 2.9 blocks across and 2.8 tall rising from the cell
 * floor, so with the nub shape a player walks straight through the whole visible ball. We give ONLY the super set a
 * near-spherical collision shape sized to that model; the guard lives in the mixin and keys on the ball set id, so
 * Earth, Namek and the other sets keep DMZ's original nub.
 *
 * <p>WHAT the model measures. Read straight off the geo cubes: the sphere spans 46 px (2.875 blocks) in X and Z
 * centred on the block cell, and 45 px (2.8125 blocks) tall from the cell floor. In cell-relative VoxelShape coords
 * that is x/z in [-0.9375, 1.9375] and y in [0, 2.8125]. We model it as an ellipsoid with horizontal semi-axis
 * 1.4375, vertical semi-axis 1.40625, centred at (0.5, 1.40625, 0.5), sliced into thin horizontal bands so the
 * collision reads round rather than a blocky 2.9-cube that would stop a player in the empty corners.
 *
 * <p>THE ONE HONEST LIMIT. Vanilla only honours block collision that overhangs the owning cell by at most one block:
 * {@code BlockCollisions} expands the scan box by exactly one cell in each direction and only checks that outer ring
 * for blocks whose collision shape extends past their own cell. Our sphere overhangs 0.9375 horizontally, inside
 * that one-block margin, so every SIDE of the ball is solid to the true surface. Vertically, though, the sphere
 * rises 1.8 blocks above the cell, past the one-block reach, so a resting entity can only be held cleanly up to about
 * y=2.0 (feet under ~2.08); a shape taller than that makes an entity on top sink-and-bounce instead of standing. We
 * therefore cap the collision at {@link #COLLISION_TOP} for a clean stand and accept that the top ~0.8 block of the
 * visible sphere is not solid. That residual cannot be removed with a block-collision shape alone, whatever its
 * detail, because it is a property of how far vanilla reaches out from a block cell.
 */
public final class SuperDragonBallCollision
{
    private SuperDragonBallCollision()
    {
    }

    /** The ball set id our Super set registers under (see {@code SuDragonBallDefinitions.registerSuper}). */
    public static final String SUPER_SET_ID = "super";

    // ellipsoid fitted to the dball_super4x model, in cell-relative coords (the cell is 0..1, its centre 0.5).
    private static final double H_SEMI = 1.4375D;
    private static final double V_SEMI = 1.40625D;
    private static final double Y_CENTER = 1.40625D;

    // cap the solid height at the point a resting entity can still be held cleanly by the one-block overhang reach.
    private static final double COLLISION_TOP = 2.0D;

    // thickness of each horizontal slice; a small value keeps the sphere smooth without an excessive box count.
    private static final double BAND_HEIGHT = 0.2D;

    private static final VoxelShape SUPER_SHAPE = buildShape();

    /** True when the given ball set id is our Super set. Null-safe (a null id is simply not Super). */
    public static boolean isSuper(String ballSetId)
    {
        return SUPER_SET_ID.equals(ballSetId);
    }

    /** The near-spherical collision/outline shape for a Super dragon ball. */
    public static VoxelShape shape()
    {
        return SUPER_SHAPE;
    }

    /**
     * Distance along {@code direction} from {@code from} to the near surface of the ball whose block cell is
     * {@code pos}, or a negative number when the ray does not reach it. Unlike {@link #shape()} this is the FULL
     * ellipsoid, top included, because it answers "did the player aim at the visible ball", not "may an entity stand
     * here"; the {@link #COLLISION_TOP} cap exists only for the standing case.
     *
     * @param from      ray origin, normally the player's eye
     * @param direction ray direction, which MUST be unit length so the return value is a real distance
     * @param maxDistance the furthest hit worth reporting; anything beyond is reported as a miss
     */
    public static double raycast(Vec3 from, Vec3 direction, BlockPos pos, double maxDistance)
    {
        // work in a space where the ellipsoid is the unit sphere: divide each axis by its semi-axis.
        double ox = (from.x - (pos.getX() + 0.5D)) / H_SEMI;
        double oy = (from.y - (pos.getY() + Y_CENTER)) / V_SEMI;
        double oz = (from.z - (pos.getZ() + 0.5D)) / H_SEMI;
        double dx = direction.x / H_SEMI;
        double dy = direction.y / V_SEMI;
        double dz = direction.z / H_SEMI;

        double a = dx * dx + dy * dy + dz * dz;
        if (a <= 0.0D)
        {
            return -1.0D;
        }
        double b = 2.0D * (ox * dx + oy * dy + oz * dz);
        double c = ox * ox + oy * oy + oz * oz - 1.0D;
        double discriminant = b * b - 4.0D * a * c;
        if (discriminant < 0.0D)
        {
            return -1.0D;
        }

        // the smaller root is the near surface. A negative one means the origin is already inside or past the ball,
        // which we treat as a miss: an eye inside the sphere has no sensible outside face to click.
        double t = (-b - Math.sqrt(discriminant)) / (2.0D * a);
        if (t < 0.0D || t > maxDistance)
        {
            return -1.0D;
        }
        return t;
    }

    /**
     * The face of the ball a hit point sits on, taken from the ellipsoid's surface normal at that point (the axis the
     * normal leans towards most). Used only to give a synthetic {@code BlockHitResult} a sensible direction.
     */
    public static Direction faceAt(BlockPos pos, Vec3 hit)
    {
        // gradient of (x/H)^2 + (y/V)^2 + (z/H)^2, i.e. the outward normal, with the constant factor dropped.
        double nx = (hit.x - (pos.getX() + 0.5D)) / (H_SEMI * H_SEMI);
        double ny = (hit.y - (pos.getY() + Y_CENTER)) / (V_SEMI * V_SEMI);
        double nz = (hit.z - (pos.getZ() + 0.5D)) / (H_SEMI * H_SEMI);
        return Direction.getNearest(nx, ny, nz);
    }

    // stack thin horizontal boxes from the cell floor up to COLLISION_TOP, each box as wide as the ellipsoid is at
    // that band's mid-height, so the union approximates the sphere. The widest band overhangs 0.934 past the cell
    // edge, inside vanilla's one-block reach, so it is honoured for entities in the neighbouring cells.
    private static VoxelShape buildShape()
    {
        VoxelShape shape = Shapes.empty();
        for (double y0 = 0.0D; y0 < COLLISION_TOP - 1.0E-9D; y0 += BAND_HEIGHT)
        {
            double y1 = Math.min(y0 + BAND_HEIGHT, COLLISION_TOP);
            double ym = (y0 + y1) / 2.0D;
            double t = (ym - Y_CENTER) / V_SEMI;
            double hw = H_SEMI * Math.sqrt(Math.max(0.0D, 1.0D - t * t));
            shape = Shapes.or(shape, Shapes.box(0.5D - hw, y0, 0.5D - hw, 0.5D + hw, y1, 0.5D + hw));
        }
        return shape;
    }
}
