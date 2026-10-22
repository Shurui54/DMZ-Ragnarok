package net.shurui.shuruisutilities.util;

/**
 * Pure geometry for a freeform top-down outline: an ordered ring of block columns stored INTERLEAVED as
 * {@code [x0, z0, x1, z1, ...]}. Used by NPC region selections drawn point by point on Xaero's World Map, on the
 * server (containment, spawning, tethering) and on the client (map fill, outline, hover), so both sides answer the
 * same question the same way.
 *
 * <p>Each vertex is the CENTRE of its block column ({@code x + 0.5, z + 0.5}), which is what the admin clicked. A block
 * column belongs to the shape when its own centre is inside the ring (even-odd rule), and an entity belongs when its
 * exact position is. Callers test the bounding box first; {@link #contains} is only the ray cast.
 *
 * <p>No Minecraft types, never throws on a malformed ring (a ring that fails {@link #validate} contains nothing).
 */
public final class PolygonXZ
{
    /** Most vertices one outline may have: keeps the per-point test and the sync packet small. */
    public static final int MAX_VERTICES = 128;
    /** Fewest vertices that enclose anything. */
    public static final int MIN_VERTICES = 3;
    /** Widest span (blocks) on either axis. Bigger than any sane spawn zone, small enough to stop a typo. */
    public static final int MAX_SPAN = 16384;

    private PolygonXZ() {}

    /** Number of vertices in an interleaved ring (0 for null or odd-length input). */
    public static int count(int[] pts)
    {
        return pts == null || (pts.length & 1) != 0 ? 0 : pts.length / 2;
    }

    /** {@code {minX, minZ, maxX, maxZ}} over the vertices (block coordinates), or null for an empty ring. */
    public static int[] bounds(int[] pts)
    {
        int n = count(pts);
        if (n == 0)
            return null;
        int minX = pts[0], minZ = pts[1], maxX = pts[0], maxZ = pts[1];
        for (int i = 1; i < n; i++)
        {
            int x = pts[2 * i], z = pts[2 * i + 1];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        return new int[] {minX, minZ, maxX, maxZ};
    }

    /**
     * Even-odd ray cast against the ring with vertices at block centres. {@code (x, z)} is a world position (an
     * entity's, or a column centre). Points exactly on an edge may fall either way, which is fine at block scale.
     */
    public static boolean contains(int[] pts, double x, double z)
    {
        int n = count(pts);
        if (n < MIN_VERTICES)
            return false;
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++)
        {
            double xi = pts[2 * i] + 0.5, zi = pts[2 * i + 1] + 0.5;
            double xj = pts[2 * j] + 0.5, zj = pts[2 * j + 1] + 0.5;
            if ((zi > z) != (zj > z))
            {
                double cross = (xj - xi) * (z - zi) / (zj - zi) + xi;
                if (x < cross)
                    inside = !inside;
            }
        }
        return inside;
    }

    /** Whether the block column {@code (bx, bz)} belongs to the shape (its centre is inside). */
    public static boolean containsColumn(int[] pts, int bx, int bz)
    {
        return contains(pts, bx + 0.5, bz + 0.5);
    }

    /** Twice the signed shoelace area (vertex units). Zero for a degenerate ring. */
    public static long area2(int[] pts)
    {
        int n = count(pts);
        long s = 0;
        for (int i = 0, j = n - 1; i < n; j = i++)
            s += (long) pts[2 * j] * pts[2 * i + 1] - (long) pts[2 * i] * pts[2 * j + 1];
        return s;
    }

    /** Enclosed area in blocks (absolute, rounded down, at least 1 for a valid ring). */
    public static long area(int[] pts)
    {
        return Math.max(1L, Math.abs(area2(pts)) / 2L);
    }

    /**
     * Whether segment {@code (ax,az)-(bx,bz)} properly crosses or touches segment {@code (cx,cz)-(dx,dz)}. Exact in
     * long arithmetic, so a vertex lying on another edge counts as touching.
     */
    public static boolean segmentsIntersect(long ax, long az, long bx, long bz, long cx, long cz, long dx, long dz)
    {
        long d1 = orient(cx, cz, dx, dz, ax, az);
        long d2 = orient(cx, cz, dx, dz, bx, bz);
        long d3 = orient(ax, az, bx, bz, cx, cz);
        long d4 = orient(ax, az, bx, bz, dx, dz);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0)))
            return true;
        return (d1 == 0 && onSegment(cx, cz, dx, dz, ax, az))
                || (d2 == 0 && onSegment(cx, cz, dx, dz, bx, bz))
                || (d3 == 0 && onSegment(ax, az, bx, bz, cx, cz))
                || (d4 == 0 && onSegment(ax, az, bx, bz, dx, dz));
    }

    private static long orient(long ax, long az, long bx, long bz, long px, long pz)
    {
        return Long.signum((bx - ax) * (pz - az) - (bz - az) * (px - ax));
    }

    private static boolean onSegment(long ax, long az, long bx, long bz, long px, long pz)
    {
        return Math.min(ax, bx) <= px && px <= Math.max(ax, bx) && Math.min(az, bz) <= pz && pz <= Math.max(az, bz);
    }

    /**
     * Would the edge from the last vertex of the OPEN chain {@code pts} to {@code (x, z)} cross an earlier edge? Used
     * while drawing, so a crossing point is refused as it is clicked. {@code closing} tests the closing edge back to
     * the first vertex instead (then {@code x, z} are ignored).
     */
    public static boolean newEdgeCrosses(int[] pts, int x, int z, boolean closing)
    {
        int n = count(pts);
        if (n < 2)
            return false;
        long ax = pts[2 * (n - 1)], az = pts[2 * (n - 1) + 1];
        long bx = closing ? pts[0] : x, bz = closing ? pts[1] : z;
        // edges 0..n-2 of the chain; skip the one sharing the new edge's start, and when closing the one sharing its end
        for (int i = 0; i < n - 1; i++)
        {
            if (i == n - 2)
                continue; // adjacent to the new edge's start vertex
            if (closing && i == 0)
                continue; // adjacent to the first vertex, which the closing edge ends on
            if (segmentsIntersect(ax, az, bx, bz, pts[2 * i], pts[2 * i + 1], pts[2 * i + 2], pts[2 * i + 3]))
                return true;
        }
        return false;
    }

    /** True when any two non-adjacent edges of the closed ring touch or cross. */
    public static boolean selfIntersects(int[] pts)
    {
        int n = count(pts);
        if (n < 4)
            return false;
        for (int i = 0; i < n; i++)
        {
            int i2 = (i + 1) % n;
            for (int j = i + 1; j < n; j++)
            {
                int j2 = (j + 1) % n;
                if (j == i2 || j2 == i)
                    continue; // adjacent edges share a vertex
                if (segmentsIntersect(pts[2 * i], pts[2 * i + 1], pts[2 * i2], pts[2 * i2 + 1],
                        pts[2 * j], pts[2 * j + 1], pts[2 * j2], pts[2 * j2 + 1]))
                    return true;
            }
        }
        return false;
    }

    /**
     * Why this ring cannot be a region, or null when it can: vertex count in [{@link #MIN_VERTICES},
     * {@link #MAX_VERTICES}], no repeated consecutive vertex, a span within {@link #MAX_SPAN}, a non-zero area and no
     * self-intersection. The server runs this on every ring a client sends; the client runs it before sending.
     */
    public static String validate(int[] pts)
    {
        if (pts == null || (pts.length & 1) != 0)
            return "malformed outline";
        int n = pts.length / 2;
        if (n < MIN_VERTICES)
            return "needs at least " + MIN_VERTICES + " points";
        if (n > MAX_VERTICES)
            return "at most " + MAX_VERTICES + " points";
        for (int i = 0; i < n; i++)
        {
            int j = (i + 1) % n;
            if (pts[2 * i] == pts[2 * j] && pts[2 * i + 1] == pts[2 * j + 1])
                return "two points in a row are the same block";
        }
        int[] b = bounds(pts);
        if ((long) b[2] - b[0] > MAX_SPAN || (long) b[3] - b[1] > MAX_SPAN)
            return "wider than " + MAX_SPAN + " blocks";
        if (selfIntersects(pts))
            return "the outline crosses itself";
        if (area2(pts) == 0)
            return "the points are in a line, the shape has no area";
        return null;
    }

    /**
     * A point inside the shape that is as close as practical to {@code (x, z)}: the nearest point on the outline,
     * then nudged up to two blocks towards the inside. Used to walk a strayed mob back in. Falls back to
     * {@link #interiorPoint} when no nudge lands inside.
     */
    public static double[] nearestInside(int[] pts, double x, double z)
    {
        int n = count(pts);
        if (n < MIN_VERTICES)
            return new double[] {x, z};
        double bestD2 = Double.MAX_VALUE, bx = x, bz = z, nx = 0, nz = 0;
        for (int i = 0, j = n - 1; i < n; j = i++)
        {
            double ax = pts[2 * j] + 0.5, az = pts[2 * j + 1] + 0.5;
            double cx = pts[2 * i] + 0.5, cz = pts[2 * i + 1] + 0.5;
            double ex = cx - ax, ez = cz - az;
            double len2 = ex * ex + ez * ez;
            double t = len2 <= 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * ex + (z - az) * ez) / len2));
            double px = ax + ex * t, pz = az + ez * t;
            double d2 = (px - x) * (px - x) + (pz - z) * (pz - z);
            if (d2 < bestD2)
            {
                bestD2 = d2;
                bx = px;
                bz = pz;
                double len = Math.sqrt(len2);
                // the edge normal; which side is inside is decided by testing both below
                nx = len <= 0 ? 0 : -ez / len;
                nz = len <= 0 ? 0 : ex / len;
            }
        }
        for (double step = 1.0; step <= 2.0; step += 0.5)
        {
            if (contains(pts, bx + nx * step, bz + nz * step))
                return new double[] {bx + nx * step, bz + nz * step};
            if (contains(pts, bx - nx * step, bz - nz * step))
                return new double[] {bx - nx * step, bz - nz * step};
        }
        return interiorPoint(pts);
    }

    /**
     * Some point inside the shape, deterministic: the vertex centroid when it is inside (every convex shape), else
     * the midpoint of the widest inside run on the horizontal line through the bounding-box middle.
     */
    public static double[] interiorPoint(int[] pts)
    {
        int n = count(pts);
        if (n == 0)
            return new double[] {0, 0};
        double cx = 0, cz = 0;
        for (int i = 0; i < n; i++)
        {
            cx += pts[2 * i] + 0.5;
            cz += pts[2 * i + 1] + 0.5;
        }
        cx /= n;
        cz /= n;
        if (contains(pts, cx, cz))
            return new double[] {cx, cz};
        int[] b = bounds(pts);
        // scan a few horizontal lines; take the first inside column found from the left on each
        for (int k = 1; k <= 7; k++)
        {
            double z = b[1] + (b[3] - b[1] + 1) * k / 8.0;
            for (int x = b[0]; x <= b[2]; x++)
                if (contains(pts, x + 0.5, z))
                    return new double[] {x + 0.5, z};
        }
        return new double[] {pts[0] + 0.5, pts[1] + 0.5};
    }
}
