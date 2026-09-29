package net.shurui.shuruisutilities.client.dball;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the SINGLE convex hull of a ball's box table (one row per cube, {@code {x0,y0,z0, x1,y1,z1}} from
 * {@link DragonBallCubeGeo} or {@code SpaceBodyRenderer.SUPER_GEO}) and hands back its outward-wound triangles. This is
 * the shape the CUBE-style rim and the space super rim draw from: every ball geo is a faceted approximation of a sphere,
 * so the convex hull of its corner points closely follows the ball's real silhouette and gives exactly ONE outline,
 * shaped like the ball, with no per-cube internal edges and no oversized circumscribing box.
 *
 * <h3>Why a convex hull and not the earlier forms</h3>
 * The per-cube inverted-box hull enlarged every cube of the model, so every panel that poked past its neighbours drew its
 * own outline and the ball read as a mess of internal edges. The single union AABB was one axis-aligned box around a
 * roughly round body, so its empty corners ballooned far past the ball (worst on the 4x super body). A convex hull is the
 * tightest single closed surface that contains all the corners, so its silhouette is the ball's silhouette: one clean
 * edge. It is convex, so an enlarged reverse-wound copy has no interior folds, which is exactly what the depth-tested rim
 * pass relies on (same guarantee the accepted sphere-hull rim has).
 *
 * <h3>Robustness (why the points are perturbed)</h3>
 * The inputs are axis-aligned boxes, so their corners are massively coplanar (four corners per box face share a plane),
 * and a plain incremental hull drops a coplanar corner as "not strictly visible" from the face it lies on, leaving a hole
 * in the mesh. To avoid that we run the hull on points nudged into GENERAL POSITION by a tiny deterministic per-point
 * offset (about 1e-5, far below one screen pixel at any ball size), which removes every exact coplanarity so the
 * incremental step is well defined and the mesh is closed, then emit each chosen vertex at its ORIGINAL coordinate so
 * truly-flat faces come back out flat. A real hull VERTEX is strictly extreme in some direction with a macroscopic margin
 * (box corners are far apart), so a 1e-5 nudge can never demote one to interior; at worst a strictly-interior corner is
 * promoted to a 1e-5 bump, which is invisible.
 *
 * <p>Computed once per table and cached by identity (the tables are static singletons), so this runs a handful of times
 * for the life of the client and never per frame.
 */
public final class DragonBallHull
{
    private DragonBallHull()
    {
    }

    // one hull per box table, keyed by identity because every table is a distinct static singleton. Each value is the
    // outward-wound triangle list {ax,ay,az, bx,by,bz, cx,cy,cz}; the rim drawers enlarge and reverse-wind these per draw.
    private static final Map<float[][], float[][]> CACHE = new IdentityHashMap<>();

    // general-position nudge magnitude, in the box table's own units. Large enough to break exact coplanarity of the box
    // corners, small enough to be well under a screen pixel at every ball size (cerulean half-extent is about 0.12, super
    // 23/16, so 1e-5 is at least four orders of magnitude below any feature).
    private static final double JITTER = 1.0E-5;
    // visibility epsilon for the incremental step. Well below JITTER, so a corner made visible by the nudge is never
    // rejected, and well above double round-off on coordinates near 1.
    private static final double EPS = 1.0E-9;

    /**
     * The convex hull of {@code boxes} as outward-wound triangles {@code {ax,ay,az, bx,by,bz, cx,cy,cz}}. Cached per
     * table. Never null; a table with fewer than four non-coplanar corners (never the case for a ball geo) returns empty.
     */
    public static synchronized float[][] hull(float[][] boxes)
    {
        float[][] cached = CACHE.get(boxes);
        if (cached != null)
        {
            return cached;
        }
        float[][] built = build(boxes);
        CACHE.put(boxes, built);
        return built;
    }

    private static float[][] build(float[][] boxes)
    {
        List<double[]> orig = corners(boxes);
        if (orig.size() < 4)
        {
            return new float[0][];
        }
        // the nudged copy used only for the hull combinatorics; vertices are emitted from orig.
        List<double[]> jit = new ArrayList<>(orig.size());
        for (int i = 0; i < orig.size(); ++i)
        {
            double[] p = orig.get(i);
            jit.add(new double[] {
                    p[0] + jitter(i, 0),
                    p[1] + jitter(i, 1),
                    p[2] + jitter(i, 2)
            });
        }
        List<int[]> faces = incremental(jit);
        List<float[]> out = new ArrayList<>(faces.size());
        for (int[] f : faces)
        {
            double[] a = orig.get(f[0]);
            double[] b = orig.get(f[1]);
            double[] c = orig.get(f[2]);
            out.add(new float[] {
                    (float) a[0], (float) a[1], (float) a[2],
                    (float) b[0], (float) b[1], (float) b[2],
                    (float) c[0], (float) c[1], (float) c[2]
            });
        }
        return out.toArray(new float[0][]);
    }

    // the eight corners of every box, deduped so coincident corners across neighbouring boxes are one point.
    private static List<double[]> corners(float[][] boxes)
    {
        List<double[]> pts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (float[] box : boxes)
        {
            for (int cx = 0; cx < 2; ++cx)
            {
                for (int cy = 0; cy < 2; ++cy)
                {
                    for (int cz = 0; cz < 2; ++cz)
                    {
                        double x = box[cx == 0 ? 0 : 3];
                        double y = box[cy == 0 ? 1 : 4];
                        double z = box[cz == 0 ? 2 : 5];
                        String key = Math.round(x * 1.0E5) + "," + Math.round(y * 1.0E5) + "," + Math.round(z * 1.0E5);
                        if (seen.add(key))
                        {
                            pts.add(new double[] {x, y, z});
                        }
                    }
                }
            }
        }
        return pts;
    }

    // a tiny deterministic per-point, per-axis nudge so the corner cloud is in general position (no exact coplanarity).
    // Deterministic so the hull is identical on every client and stable across runs.
    private static double jitter(int index, int axis)
    {
        long h = index * 0x9E3779B97F4A7C15L ^ ((long) axis * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        // map to about (-1..1) then scale to JITTER.
        double u = ((h >>> 11) / (double) (1L << 53)) * 2.0 - 1.0;
        return u * JITTER;
    }

    // incremental convex hull over points known to be in general position. Returns faces as vertex-index triples wound CCW
    // as seen from OUTSIDE. All face orientation is fixed against a fixed interior point (the initial tetra centroid, which
    // stays strictly inside as the hull only grows), so no directed-edge bookkeeping is needed.
    private static List<int[]> incremental(List<double[]> pts)
    {
        int n = pts.size();
        int[] tetra = initialTetra(pts);
        if (tetra == null)
        {
            return new ArrayList<>();
        }
        double[] ipt = new double[3];
        for (int t : tetra)
        {
            ipt[0] += pts.get(t)[0] / 4.0;
            ipt[1] += pts.get(t)[1] / 4.0;
            ipt[2] += pts.get(t)[2] / 4.0;
        }
        List<int[]> faces = new ArrayList<>();
        addFace(faces, pts, ipt, tetra[0], tetra[1], tetra[2]);
        addFace(faces, pts, ipt, tetra[0], tetra[1], tetra[3]);
        addFace(faces, pts, ipt, tetra[0], tetra[2], tetra[3]);
        addFace(faces, pts, ipt, tetra[1], tetra[2], tetra[3]);

        Set<Integer> inTetra = new HashSet<>();
        for (int t : tetra)
        {
            inTetra.add(t);
        }

        for (int p = 0; p < n; ++p)
        {
            if (inTetra.contains(p))
            {
                continue;
            }
            double[] pp = pts.get(p);
            List<Integer> visible = new ArrayList<>();
            for (int k = 0; k < faces.size(); ++k)
            {
                if (faceVisible(pts, faces.get(k), pp))
                {
                    visible.add(k);
                }
            }
            if (visible.isEmpty())
            {
                continue;
            }
            // horizon edges: an undirected edge shared by exactly ONE visible face (its other face is not visible). Every
            // edge of a closed manifold borders exactly two faces, so count==1 marks the boundary of the visible cap.
            Map<Long, Integer> count = new HashMap<>();
            Map<Long, int[]> edge = new HashMap<>();
            for (int vi : visible)
            {
                int[] f = faces.get(vi);
                accEdge(count, edge, f[0], f[1]);
                accEdge(count, edge, f[1], f[2]);
                accEdge(count, edge, f[2], f[0]);
            }
            Set<Integer> vis = new HashSet<>(visible);
            List<int[]> kept = new ArrayList<>();
            for (int k = 0; k < faces.size(); ++k)
            {
                if (!vis.contains(k))
                {
                    kept.add(faces.get(k));
                }
            }
            for (Map.Entry<Long, Integer> e : count.entrySet())
            {
                if (e.getValue() == 1)
                {
                    int[] uv = edge.get(e.getKey());
                    addFace(kept, pts, ipt, uv[0], uv[1], p);
                }
            }
            faces = kept;
        }
        return faces;
    }

    private static void accEdge(Map<Long, Integer> count, Map<Long, int[]> edge, int a, int b)
    {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        long key = ((long) lo << 32) | (hi & 0xFFFFFFFFL);
        count.merge(key, 1, Integer::sum);
        edge.putIfAbsent(key, new int[] {lo, hi});
    }

    // create a face from three vertices, oriented so its normal points AWAY from the fixed interior point (outward).
    private static void addFace(List<int[]> faces, List<double[]> pts, double[] ipt, int a, int b, int c)
    {
        double[] pa = pts.get(a);
        double[] pb = pts.get(b);
        double[] pc = pts.get(c);
        double[] nrm = cross(sub(pb, pa), sub(pc, pa));
        double d = nrm[0] * (pa[0] - ipt[0]) + nrm[1] * (pa[1] - ipt[1]) + nrm[2] * (pa[2] - ipt[2]);
        if (d < 0.0)
        {
            faces.add(new int[] {a, c, b});
        }
        else
        {
            faces.add(new int[] {a, b, c});
        }
    }

    // a point is visible from a face when it lies strictly outside the face's outward plane.
    private static boolean faceVisible(List<double[]> pts, int[] f, double[] p)
    {
        double[] pa = pts.get(f[0]);
        double[] pb = pts.get(f[1]);
        double[] pc = pts.get(f[2]);
        double[] nrm = cross(sub(pb, pa), sub(pc, pa));
        double d = nrm[0] * (p[0] - pa[0]) + nrm[1] * (p[1] - pa[1]) + nrm[2] * (p[2] - pa[2]);
        return d > EPS;
    }

    // pick four points that span a non-degenerate tetrahedron: an axis extreme, the point farthest from it, the point
    // farthest from that line, then the point farthest from that plane. Null only if the cloud is flat (never for a ball).
    private static int[] initialTetra(List<double[]> pts)
    {
        int n = pts.size();
        int i0 = 0;
        for (int i = 1; i < n; ++i)
        {
            if (pts.get(i)[0] < pts.get(i0)[0])
            {
                i0 = i;
            }
        }
        int i1 = -1;
        double best = -1.0;
        for (int i = 0; i < n; ++i)
        {
            double d = distSq(pts.get(i), pts.get(i0));
            if (d > best)
            {
                best = d;
                i1 = i;
            }
        }
        if (best <= EPS)
        {
            return null;
        }
        int i2 = -1;
        best = -1.0;
        double[] line = sub(pts.get(i1), pts.get(i0));
        for (int i = 0; i < n; ++i)
        {
            double[] cr = cross(line, sub(pts.get(i), pts.get(i0)));
            double d = cr[0] * cr[0] + cr[1] * cr[1] + cr[2] * cr[2];
            if (d > best)
            {
                best = d;
                i2 = i;
            }
        }
        if (best <= EPS)
        {
            return null;
        }
        int i3 = -1;
        best = -1.0;
        double[] nrm = cross(sub(pts.get(i1), pts.get(i0)), sub(pts.get(i2), pts.get(i0)));
        for (int i = 0; i < n; ++i)
        {
            double d = Math.abs(nrm[0] * (pts.get(i)[0] - pts.get(i0)[0])
                    + nrm[1] * (pts.get(i)[1] - pts.get(i0)[1])
                    + nrm[2] * (pts.get(i)[2] - pts.get(i0)[2]));
            if (d > best)
            {
                best = d;
                i3 = i;
            }
        }
        if (best <= EPS)
        {
            return null;
        }
        return new int[] {i0, i1, i2, i3};
    }

    private static double[] sub(double[] a, double[] b)
    {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] cross(double[] a, double[] b)
    {
        return new double[] {
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private static double distSq(double[] a, double[] b)
    {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }
}
