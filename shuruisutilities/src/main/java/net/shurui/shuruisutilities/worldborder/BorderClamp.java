package net.shurui.shuruisutilities.worldborder;

import net.minecraft.server.level.ServerLevel;

import net.shurui.shuruisutilities.api.key.WorldBorderHooks;
import net.shurui.shuruisutilities.commons.selections.AreaBase;
import net.shurui.shuruisutilities.commons.selections.Point;

/**
 * Pure arithmetic clamp of an X/Z into the effective play border of a level, used to keep every scattered dragon ball
 * inside the world border of its dimension.
 *
 * <p>The effective border is the INTERSECTION of two independent borders, whichever is tighter on each axis:
 * <ul>
 *   <li>the vanilla {@link net.minecraft.world.level.border.WorldBorder} of the level (always present, usually the
 *       near-infinite default, so it rarely binds), and</li>
 *   <li>the suite border for that dimension ({@link WorldBorderHooks.Impl#border}: the WorldBorder module's live
 *       border with the Ragnarok Key, the saved border record without it), but only when it is ENABLED. A disabled
 *       suite border contributes nothing.</li>
 * </ul>
 * A {@code margin} is then shaved off every edge so a ball never sits exactly on the line (16 is a good default for a
 * ball, which is a full block and whose collectors path right up to it).
 *
 * <h2>Why clamp instead of reject-and-reroll</h2>
 *
 * <p>Clamping the chosen coordinate into the inner box is O(1) and cannot loop. Rejecting an out-of-border pick and
 * rerolling could spin forever when the configured scatter radius dwarfs the border, and forcing a fresh heightmap read
 * per candidate would load chunks on the server thread (see the barrier-box watchdog hang). This method touches no
 * chunk and never blocks: it is safe to call from the scatter path on the server thread.
 *
 * <h2>Shapes</h2>
 *
 * <p>The suite border can be a BOX, a CYLINDER or an ELLIPSOID. A box clamps to its own edges. A round border is
 * reduced to the largest axis-aligned box that fits INSIDE it (each semi-axis divided by sqrt(2)), so a clamped point is
 * guaranteed inside the round shape too, with no trigonometry and no iteration. This is deliberately conservative: it
 * can leave the very corners of a round border unused, which for ball scatter is harmless.
 */
public final class BorderClamp
{
    private BorderClamp() {}

    private static final double INV_SQRT2 = 0.7071067811865476;

    /**
     * Clamp {@code (x, z)} into the effective border of {@code level}, shaving {@code margin} blocks off every edge.
     * Returns {@code {clampedX, clampedZ}}. Never loads a chunk, never loops, never throws: any failure degrades to
     * returning the input unchanged, because a ball placed at the original spot is a far smaller problem than a scatter
     * path that throws.
     */
    public static int[] clampInside(ServerLevel level, int x, int z, int margin)
    {
        try
        {
            double lowX = Double.NEGATIVE_INFINITY;
            double highX = Double.POSITIVE_INFINITY;
            double lowZ = Double.NEGATIVE_INFINITY;
            double highZ = Double.POSITIVE_INFINITY;

            // Vanilla border: always present. getMin/getMax already fold in centre and size.
            net.minecraft.world.level.border.WorldBorder vanilla = level.getWorldBorder();
            if (vanilla != null)
            {
                lowX = Math.max(lowX, vanilla.getMinX());
                highX = Math.min(highX, vanilla.getMaxX());
                lowZ = Math.max(lowZ, vanilla.getMinZ());
                highZ = Math.min(highZ, vanilla.getMaxZ());
            }

            // Suite border: only when enabled for this dimension.
            WorldBorder suite = WorldBorderHooks.get().border(level);
            if (suite != null && suite.isEnabled())
            {
                double[] box = suiteInnerBox(suite);
                if (box != null)
                {
                    lowX = Math.max(lowX, box[0]);
                    highX = Math.min(highX, box[1]);
                    lowZ = Math.max(lowZ, box[2]);
                    highZ = Math.min(highZ, box[3]);
                }
            }

            int cx = clampAxis(x, lowX, highX, margin);
            int cz = clampAxis(z, lowZ, highZ, margin);
            return new int[] { cx, cz };
        }
        catch (Throwable t)
        {
            return new int[] { x, z };
        }
    }

    /** Clamp one axis value into {@code [low + margin, high - margin]}, falling back to the box centre if the inset
     * band collapses (margin larger than the box) or an edge is unbounded on one side only. */
    private static int clampAxis(int value, double low, double high, int margin)
    {
        double lo = low + margin;
        double hi = high - margin;
        if (lo <= hi)
        {
            return (int) Math.round(Math.max(lo, Math.min(hi, value)));
        }
        // Inset band collapsed: aim for the untouched midpoint if both edges are finite, otherwise leave the value.
        if (low != Double.NEGATIVE_INFINITY && high != Double.POSITIVE_INFINITY)
        {
            return (int) Math.round((low + high) / 2.0);
        }
        return value;
    }

    /** The largest axis-aligned box strictly inside the suite border, as {@code {lowX, highX, lowZ, highZ}}. */
    private static double[] suiteInnerBox(WorldBorder suite)
    {
        switch (suite.getShape())
        {
        case BOX:
        {
            AreaBase area = suite.getArea();
            if (area == null)
            {
                return null;
            }
            Point lo = area.getLowPoint();
            Point hi = area.getHighPoint();
            return new double[] { lo.getX(), hi.getX(), lo.getZ(), hi.getZ() };
        }
        case CYLINDER:
        case ELLIPSOID:
        {
            Point c = suite.getCenter();
            Point size = suite.getSize(); // radius per axis
            double hx = Math.max(0.0, size.getX()) * INV_SQRT2;
            double hz = Math.max(0.0, size.getZ()) * INV_SQRT2;
            return new double[] { c.getX() - hx, c.getX() + hx, c.getZ() - hz, c.getZ() + hz };
        }
        default:
            return null;
        }
    }
}
