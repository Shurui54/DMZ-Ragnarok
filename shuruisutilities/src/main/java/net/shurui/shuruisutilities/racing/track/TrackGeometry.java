package net.shurui.shuruisutilities.racing.track;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.phys.Vec3;

/**
 * The geometry a {@link TrackDef} implies: a centripetal Catmull-Rom spline through the node graph, sampled every
 * {@link #SAMPLE_STEP} blocks into a road ribbon (pos, arc length, tangent, normal, width, left / right edge), the
 * gate list (checkpoint-node gates, auto gates every {@link TrackDef#autoGateSpacing}, and the start gate), the
 * checkpoint order, normalised lap progress, and a point-to-track projection. Pure common code (no client imports):
 * the client preview, the key builder and the key race engine all derive geometry the same way.
 *
 * <p><b>Progress normalisation.</b> Main-route samples get {@code p = s / lapLength}. An alternate route (branch)
 * runs between two junctions whose progress is already known; its samples interpolate {@code p} between the entry and
 * exit junction by fractional arc length, so a shortcut that rejoins later still ranks a racer correctly. Branches
 * are resolved topologically, so a branch off a branch resolves once its parent has (bounded by the branch count, no
 * infinite loop on a malformed graph).
 *
 * <p>Robust by construction: it never throws on a malformed track. With no valid main cycle it splines the main chain
 * as an OPEN path so an operator still gets a preview, and {@link TrackValidator} is what reports the track unraceable.
 */
public final class TrackGeometry
{
    /** Centreline sample spacing, blocks. */
    public static final double SAMPLE_STEP = 0.5;

    /** Vertical half-tolerance a gate crossing accepts, blocks (the plan's +-4). */
    public static final double GATE_VERTICAL_TOLERANCE = 4.0;

    /** Catmull-Rom knot exponent: 0.5 = centripetal (no cusps or self-intersections between control points). */
    private static final double CR_ALPHA = 0.5;

    /** Dense pre-sampling step before arc-length resampling, blocks. Finer than SAMPLE_STEP for smooth arc length. */
    private static final double DENSE_STEP = 0.125;

    /** One point of the sampled centreline / ribbon. */
    public static final class Sample
    {
        public final Vec3 pos;
        /** Arc length from the start of this sample's route. */
        public final double s;
        public final Vec3 tangent;
        /** Horizontal unit normal (tangent rotated +90 about Y). */
        public final Vec3 normal;
        public final double width;
        public final Vec3 leftEdge;
        public final Vec3 rightEdge;
        /** Normalised lap progress in [0, 1). */
        public double p;
        /** 0 = main route, > 0 = branch index. */
        public final int routeId;

        Sample(Vec3 pos, double s, Vec3 tangent, Vec3 normal, double width, int routeId)
        {
            this.pos = pos;
            this.s = s;
            this.tangent = tangent;
            this.normal = normal;
            this.width = width;
            this.leftEdge = pos.add(normal.scale(width / 2.0));
            this.rightEdge = pos.subtract(normal.scale(width / 2.0));
            this.routeId = routeId;
        }
    }

    /** A gate: the segment left-edge -> right-edge a racer crosses, with its progress and checkpoint order. */
    public static final class Gate
    {
        public final Vec3 left;
        public final Vec3 right;
        /** The centre of the gate line (the sample position), for placement and forward-facing rescue. */
        public final Vec3 centre;
        /** Forward direction of travel through this gate (the sample tangent), for the forward-crossing test. */
        public final Vec3 tangent;
        public final double y;
        public final double p;
        public final int routeId;
        public final boolean checkpointNode;
        public final boolean start;
        /** Order along the checkpoint sequence (assigned by {@link #gates()}). */
        public int order;

        Gate(Sample s, boolean checkpointNode, boolean start)
        {
            this.left = s.leftEdge;
            this.right = s.rightEdge;
            this.centre = s.pos;
            this.tangent = s.tangent;
            this.y = s.pos.y;
            this.p = s.p;
            this.routeId = s.routeId;
            this.checkpointNode = checkpointNode;
            this.start = start;
        }
    }

    /** The nearest point on the track to a query point. */
    public static final class Projection
    {
        public final Sample sample;
        public final double distance;

        Projection(Sample sample, double distance)
        {
            this.sample = sample;
            this.distance = distance;
        }

        /** Signed-ish "how far past the road edge", used by rescue: distance minus half the local width. */
        public double distancePastEdge()
        {
            return distance - sample.width / 2.0;
        }
    }

    private final TrackDef def;
    private final Map<Integer, TrackNode> nodeById = new HashMap<>();

    private final List<Sample> main = new ArrayList<>();
    private final List<List<Sample>> branches = new ArrayList<>();
    private final List<Sample> all = new ArrayList<>();
    private final List<Gate> gates = new ArrayList<>();
    private Gate startGate;
    private double lapLength;
    private boolean mainClosed;

    private TrackGeometry(TrackDef def)
    {
        this.def = def;
        for (TrackNode n : def.nodes)
            nodeById.put(n.id, n);
    }

    /** Build the geometry for a track. Never throws; a malformed track simply yields fewer samples / gates. */
    public static TrackGeometry of(TrackDef def)
    {
        TrackGeometry g = new TrackGeometry(def);
        g.build();
        return g;
    }

    public List<Sample> mainSamples()
    {
        return main;
    }

    public List<List<Sample>> branchSamples()
    {
        return branches;
    }

    /** Every sample across every route, main first. */
    public List<Sample> allSamples()
    {
        return all;
    }

    public List<Gate> gates()
    {
        return gates;
    }

    public Gate startGate()
    {
        return startGate;
    }

    /** Main-cycle arc length, blocks. For an open main chain this is the chain length. */
    public double lapLength()
    {
        return lapLength;
    }

    public boolean mainClosed()
    {
        return mainClosed;
    }

    // --- build ---

    private void build()
    {
        List<Integer> mainOrder = mainOrder();
        if (mainOrder.size() >= 2)
        {
            mainClosed = isMainClosed(mainOrder);
            List<Sample> mainRoute = splineRoute(mainOrder, mainClosed, 0, null, null);
            double len = mainRoute.isEmpty() ? 0.0 : mainRoute.get(mainRoute.size() - 1).s + SAMPLE_STEP;
            // lapLength is the arc that maps to a full lap; use the closed-loop wrap length when closed.
            lapLength = mainClosed ? closedLength(mainOrder) : len;
            if (lapLength <= 0)
                lapLength = Math.max(len, 1.0);
            for (Sample s : mainRoute)
                s.p = clamp01(s.s / lapLength);
            main.addAll(mainRoute);
        }
        all.addAll(main);

        resolveBranches(mainOrder);
        buildGates();
    }

    // The main cycle, from startNode following the main-route successor. Falls back to the first node if startNode
    // is unset. Stops on a repeat (closed loop) or a dead end (open chain).
    private List<Integer> mainOrder()
    {
        List<Integer> order = new ArrayList<>();
        if (def.nodes.isEmpty())
            return order;
        int startId = def.startNode >= 0 && nodeById.containsKey(def.startNode)
                ? def.startNode
                : def.nodes.get(0).id;
        Set<Integer> seen = new HashSet<>();
        int cur = startId;
        while (cur >= 0 && seen.add(cur))
        {
            order.add(cur);
            cur = mainSuccessor(cur);
        }
        return order;
    }

    private boolean isMainClosed(List<Integer> order)
    {
        if (order.size() < 3)
            return false;
        int last = order.get(order.size() - 1);
        return mainSuccessor(last) == order.get(0);
    }

    // The main-route successor of a node: its first successor that is itself a main node. -1 if none.
    private int mainSuccessor(int nodeId)
    {
        TrackNode n = nodeById.get(nodeId);
        if (n == null)
            return -1;
        for (int t : n.next)
        {
            TrackNode tn = nodeById.get(t);
            if (tn != null && tn.main)
                return t;
        }
        return -1;
    }

    private int mainPredecessor(int nodeId)
    {
        for (TrackNode n : def.nodes)
            if (n.main && mainSuccessor(n.id) == nodeId)
                return n.id;
        return -1;
    }

    // Sum of chord-ish arc between successive control points, walking the closed loop back to the start.
    private double closedLength(List<Integer> order)
    {
        List<Integer> loop = new ArrayList<>(order);
        loop.add(order.get(0));
        List<Sample> full = splineRoute(loop, false, 0, mainPredecessorVec(order.get(0)), mainSuccessorVec(order.get(order.size() - 1)));
        return full.isEmpty() ? 0.0 : full.get(full.size() - 1).s + SAMPLE_STEP;
    }

    private Vec3 mainPredecessorVec(int nodeId)
    {
        int p = mainPredecessor(nodeId);
        return p >= 0 ? nodeById.get(p).pos() : null;
    }

    private Vec3 mainSuccessorVec(int nodeId)
    {
        int s = mainSuccessor(nodeId);
        return s >= 0 ? nodeById.get(s).pos() : null;
    }

    // --- branches (alternate routes) ---

    private void resolveBranches(List<Integer> mainOrder)
    {
        // Progress known at each node that a resolved route passes through, keyed by node id.
        Map<Integer, Double> nodeP = new HashMap<>();
        for (int id : mainOrder)
        {
            TrackNode n = nodeById.get(id);
            if (n != null)
                nodeP.put(id, nodeProgressOnMain(id));
        }

        List<List<Integer>> pending = new ArrayList<>(findBranchChains(mainOrder));
        int guard = pending.size() + 4;
        int routeId = 1;
        while (!pending.isEmpty() && guard-- > 0)
        {
            boolean progressed = false;
            for (int i = 0; i < pending.size(); i++)
            {
                List<Integer> chain = pending.get(i);
                int entry = chain.get(0);
                int exit = chain.get(chain.size() - 1);
                if (!nodeP.containsKey(entry) || !nodeP.containsKey(exit))
                    continue;

                double pEntry = nodeP.get(entry);
                double pExit = nodeP.get(exit);
                double span = pExit - pEntry;
                if (span <= 0)
                    span += 1.0; // the branch crosses the start / finish line

                List<Sample> route = splineRoute(chain, false, routeId,
                        nodeById.containsKey(mainPredecessor(entry)) ? nodeById.get(mainPredecessor(entry)).pos() : null,
                        nodeById.containsKey(mainSuccessor(exit)) ? nodeById.get(mainSuccessor(exit)).pos() : null);
                double branchLen = route.isEmpty() ? 1.0 : route.get(route.size() - 1).s + SAMPLE_STEP;
                for (Sample s : route)
                    s.p = wrap01(pEntry + span * (branchLen > 0 ? s.s / branchLen : 0.0));

                // Record progress at each interior node of this branch so a nested branch can resolve off it.
                recordInteriorNodeProgress(chain, route, nodeP);

                branches.add(route);
                all.addAll(route);
                routeId++;
                pending.remove(i);
                i--;
                progressed = true;
            }
            if (!progressed)
                break; // remaining chains reference an unresolved endpoint (malformed); leave them out
        }
    }

    private double nodeProgressOnMain(int nodeId)
    {
        Vec3 target = nodeById.get(nodeId).pos();
        Sample best = null;
        double bestD2 = Double.MAX_VALUE;
        for (Sample s : main)
        {
            double d2 = s.pos.distanceToSqr(target);
            if (d2 < bestD2)
            {
                bestD2 = d2;
                best = s;
            }
        }
        return best != null ? best.p : 0.0;
    }

    // Map each interior control node of a branch to the p of the nearest sample on that branch route.
    private void recordInteriorNodeProgress(List<Integer> chain, List<Sample> route, Map<Integer, Double> nodeP)
    {
        for (int idx = 1; idx < chain.size() - 1; idx++)
        {
            int id = chain.get(idx);
            Vec3 target = nodeById.get(id).pos();
            Sample best = null;
            double bestD2 = Double.MAX_VALUE;
            for (Sample s : route)
            {
                double d2 = s.pos.distanceToSqr(target);
                if (d2 < bestD2)
                {
                    bestD2 = d2;
                    best = s;
                }
            }
            if (best != null)
                nodeP.putIfAbsent(id, best.p);
        }
    }

    // Every alternate-route chain: start at a node that has a non-main successor, walk the non-main nodes, end at
    // the main / junction node it rejoins. The chain includes its bracketing junction nodes (entry and exit).
    private List<List<Integer>> findBranchChains(List<Integer> mainOrder)
    {
        List<List<Integer>> chains = new ArrayList<>();
        Set<Integer> mainSet = new HashSet<>(mainOrder);
        Set<Integer> visitedBranchStart = new HashSet<>();

        for (TrackNode n : def.nodes)
        {
            // A branch STARTS only where a route forks off: a main node, or any fork (out-degree > 1). A plain
            // non-main node with a single non-main successor is a CONTINUATION of a branch, not a new one.
            boolean anchor = n.main || n.next.size() > 1;
            if (!anchor)
                continue;
            for (int succ : n.next)
            {
                TrackNode sn = nodeById.get(succ);
                if (sn == null || sn.main)
                    continue; // main-to-main edges are the main route, handled elsewhere
                if (!visitedBranchStart.add(succ))
                    continue;

                List<Integer> chain = new ArrayList<>();
                chain.add(n.id);
                int cur = succ;
                Set<Integer> guard = new HashSet<>();
                while (cur >= 0 && guard.add(cur))
                {
                    chain.add(cur);
                    TrackNode cn = nodeById.get(cur);
                    if (cn == null)
                        break;
                    // Follow to the next node; stop once we reach a main node (the merge).
                    int nxt = firstSuccessor(cn);
                    if (nxt < 0)
                        break;
                    TrackNode nn = nodeById.get(nxt);
                    chain.add(nxt);
                    if (nn != null && nn.main)
                        break;
                    cur = nxt;
                }
                // A well-formed branch has its last node a main node (it rejoined). Keep it either way; the
                // resolver drops any whose endpoint never resolves.
                if (chain.size() >= 2)
                    chains.add(dedupeConsecutive(chain));
            }
        }
        return chains;
    }

    private int firstSuccessor(TrackNode n)
    {
        return n.next.isEmpty() ? -1 : n.next.get(0);
    }

    private static List<Integer> dedupeConsecutive(List<Integer> in)
    {
        List<Integer> out = new ArrayList<>();
        for (int v : in)
            if (out.isEmpty() || out.get(out.size() - 1) != v)
                out.add(v);
        return out;
    }

    // --- spline ---

    // Sample a route of control-point node ids into ribbon samples every SAMPLE_STEP. When closed, the route wraps
    // (last connects to first). before/after are optional extra control points supplying end tangents for an open route.
    private List<Sample> splineRoute(List<Integer> order, boolean closed, int routeId, Vec3 before, Vec3 after)
    {
        List<Vec3> pts = new ArrayList<>();
        List<Double> widths = new ArrayList<>();
        for (int id : order)
        {
            TrackNode n = nodeById.get(id);
            if (n == null)
                continue;
            pts.add(n.pos());
            widths.add(n.widthOr(def.defaultWidth));
        }
        if (pts.size() < 2)
        {
            List<Sample> single = new ArrayList<>();
            if (pts.size() == 1)
                single.add(new Sample(pts.get(0), 0, new Vec3(0, 0, 1), new Vec3(1, 0, 0), widths.get(0), routeId));
            return single;
        }

        // Dense polyline with per-point width, plus arc length.
        List<Vec3> dense = new ArrayList<>();
        List<Double> denseW = new ArrayList<>();
        int segCount = closed ? pts.size() : pts.size() - 1;
        for (int seg = 0; seg < segCount; seg++)
        {
            Vec3 p1 = pts.get(seg);
            Vec3 p2 = pts.get((seg + 1) % pts.size());
            Vec3 p0 = controlBefore(pts, seg, closed, before);
            Vec3 p3 = controlAfter(pts, seg, closed, after);
            double w1 = widths.get(seg);
            double w2 = widths.get((seg + 1) % pts.size());

            double chord = p1.distanceTo(p2);
            int steps = Math.max(2, (int) Math.ceil(chord / DENSE_STEP));
            // Include the segment start once (skip on later segments to avoid duplicate join points).
            int startK = (seg == 0) ? 0 : 1;
            for (int k = startK; k <= steps; k++)
            {
                double t = (double) k / steps;
                dense.add(catmullRom(p0, p1, p2, p3, t));
                denseW.add(w1 + (w2 - w1) * t);
            }
        }

        // Cumulative arc length over the dense polyline.
        double[] arc = new double[dense.size()];
        for (int i = 1; i < dense.size(); i++)
            arc[i] = arc[i - 1] + dense.get(i - 1).distanceTo(dense.get(i));
        double total = arc[arc.length - 1];

        // Resample every SAMPLE_STEP by walking the dense polyline.
        List<Sample> out = new ArrayList<>();
        int di = 0;
        for (double s = 0; s <= total + 1e-6; s += SAMPLE_STEP)
        {
            while (di < arc.length - 1 && arc[di + 1] < s)
                di++;
            double segLen = (di < arc.length - 1) ? (arc[di + 1] - arc[di]) : 1.0;
            double f = segLen > 1e-9 ? (s - arc[di]) / segLen : 0.0;
            f = Math.max(0.0, Math.min(1.0, f));
            Vec3 a = dense.get(di);
            Vec3 b = dense.get(Math.min(di + 1, dense.size() - 1));
            Vec3 pos = a.add(b.subtract(a).scale(f));
            double w = denseW.get(di) + (denseW.get(Math.min(di + 1, denseW.size() - 1)) - denseW.get(di)) * f;
            Vec3 tangent = tangentAt(dense, di);
            Vec3 normal = horizontalNormal(tangent);
            out.add(new Sample(pos, s, tangent, normal, w, routeId));
        }
        return out;
    }

    private Vec3 controlBefore(List<Vec3> pts, int seg, boolean closed, Vec3 before)
    {
        if (closed)
            return pts.get((seg - 1 + pts.size()) % pts.size());
        if (seg - 1 >= 0)
            return pts.get(seg - 1);
        if (before != null)
            return before;
        // Mirror the first segment to get a natural end tangent.
        return pts.get(0).scale(2).subtract(pts.get(1));
    }

    private Vec3 controlAfter(List<Vec3> pts, int seg, boolean closed, Vec3 after)
    {
        if (closed)
            return pts.get((seg + 2) % pts.size());
        if (seg + 2 < pts.size())
            return pts.get(seg + 2);
        if (after != null)
            return after;
        int last = pts.size() - 1;
        return pts.get(last).scale(2).subtract(pts.get(last - 1));
    }

    private Vec3 tangentAt(List<Vec3> dense, int i)
    {
        Vec3 a = dense.get(Math.max(0, i - 1));
        Vec3 b = dense.get(Math.min(dense.size() - 1, i + 1));
        Vec3 d = b.subtract(a);
        if (d.lengthSqr() < 1e-9)
            d = new Vec3(0, 0, 1);
        return d.normalize();
    }

    private static Vec3 horizontalNormal(Vec3 tangent)
    {
        Vec3 flat = new Vec3(tangent.x, 0, tangent.z);
        if (flat.lengthSqr() < 1e-9)
            return new Vec3(1, 0, 0);
        flat = flat.normalize();
        // Rotate +90 about Y: (x,0,z) -> (-z,0,x).
        return new Vec3(-flat.z, 0, flat.x);
    }

    // Centripetal Catmull-Rom via the Barry-Goldman pyramid, robust to coincident control points.
    private static Vec3 catmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t)
    {
        double t0 = 0.0;
        double t1 = t0 + knot(p0, p1);
        double t2 = t1 + knot(p1, p2);
        double t3 = t2 + knot(p2, p3);
        double tt = t1 + (t2 - t1) * t;

        Vec3 a1 = lerpT(p0, p1, t0, t1, tt);
        Vec3 a2 = lerpT(p1, p2, t1, t2, tt);
        Vec3 a3 = lerpT(p2, p3, t2, t3, tt);
        Vec3 b1 = lerpT(a1, a2, t0, t2, tt);
        Vec3 b2 = lerpT(a2, a3, t1, t3, tt);
        return lerpT(b1, b2, t1, t2, tt);
    }

    private static double knot(Vec3 a, Vec3 b)
    {
        return Math.max(Math.pow(a.distanceTo(b), CR_ALPHA), 1e-4);
    }

    private static Vec3 lerpT(Vec3 a, Vec3 b, double ta, double tb, double t)
    {
        double denom = tb - ta;
        double f = Math.abs(denom) < 1e-9 ? 0.0 : (t - ta) / denom;
        return a.add(b.subtract(a).scale(f));
    }

    // --- gates ---

    private void buildGates()
    {
        if (main.isEmpty())
            return;

        // Start gate at the start node's nearest main sample.
        int startId = def.startNode >= 0 && nodeById.containsKey(def.startNode) ? def.startNode : def.nodes.get(0).id;
        Sample startSample = nearestSampleTo(main, nodeById.get(startId).pos());
        startGate = new Gate(startSample, false, true);
        gates.add(startGate);

        // Checkpoint-node gates on the main route.
        for (int id : mainOrder())
        {
            TrackNode n = nodeById.get(id);
            if (n == null || !n.checkpoint || id == startId)
                continue;
            gates.add(new Gate(nearestSampleTo(main, n.pos()), true, false));
        }

        // Auto gates every autoGateSpacing along the main arc.
        if (def.autoGateSpacing > 0 && lapLength > def.autoGateSpacing)
        {
            for (double target = def.autoGateSpacing; target < lapLength - 0.5; target += def.autoGateSpacing)
            {
                Sample s = sampleAtArc(main, target);
                if (s != null)
                    gates.add(new Gate(s, false, false));
            }
        }

        // Branch checkpoint-node gates.
        for (List<Sample> branch : branches)
            for (Sample s : sparseCheckpointsOnBranch(branch))
                gates.add(new Gate(s, true, false));

        // Order the checkpoint sequence by progress (start first, at p ~ 0).
        gates.sort((a, b) ->
        {
            if (a.start != b.start)
                return a.start ? -1 : 1;
            return Double.compare(a.p, b.p);
        });
        for (int i = 0; i < gates.size(); i++)
            gates.get(i).order = i;
    }

    // Branch samples nearest each checkpoint node on that branch route.
    private List<Sample> sparseCheckpointsOnBranch(List<Sample> branch)
    {
        List<Sample> out = new ArrayList<>();
        for (TrackNode n : def.nodes)
        {
            if (!n.checkpoint || n.main)
                continue;
            Sample s = nearestSampleTo(branch, n.pos());
            if (s != null && s.pos.distanceToSqr(n.pos()) < 4.0)
                out.add(s);
        }
        return out;
    }

    private Sample sampleAtArc(List<Sample> route, double targetS)
    {
        Sample best = null;
        double bestD = Double.MAX_VALUE;
        for (Sample s : route)
        {
            double d = Math.abs(s.s - targetS);
            if (d < bestD)
            {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    private static Sample nearestSampleTo(List<Sample> route, Vec3 target)
    {
        Sample best = null;
        double bestD2 = Double.MAX_VALUE;
        for (Sample s : route)
        {
            double d2 = s.pos.distanceToSqr(target);
            if (d2 < bestD2)
            {
                bestD2 = d2;
                best = s;
            }
        }
        return best;
    }

    // --- projection ---

    /** The nearest track sample to a world point, across the main route and every branch. Null only if empty. */
    public Projection project(Vec3 point)
    {
        Sample best = null;
        double bestD2 = Double.MAX_VALUE;
        for (Sample s : all)
        {
            double d2 = s.pos.distanceToSqr(point);
            if (d2 < bestD2)
            {
                bestD2 = d2;
                best = s;
            }
        }
        return best == null ? null : new Projection(best, Math.sqrt(bestD2));
    }

    private static double clamp01(double v)
    {
        return v < 0 ? 0 : (v >= 1 ? v - Math.floor(v) : v);
    }

    private static double wrap01(double v)
    {
        double r = v - Math.floor(v);
        return r < 0 ? r + 1 : r;
    }
}
