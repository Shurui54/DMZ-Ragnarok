package net.shurui.shuruisutilities.racing.track;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a {@link TrackDef}'s node graph against the rules a raceable track must obey, from the racing plan section 2:
 * <ul>
 *   <li>exactly one start, on the main cycle;</li>
 *   <li>the main route is a closed cycle of at least three nodes;</li>
 *   <li>every node is reachable from the start AND can reach the start (reachable both ways), so no orphan or
 *       one-way dead end;</li>
 *   <li>every alternate route (branch) rejoins the graph at a main node;</li>
 *   <li>every fork (a node with more than one successor) marks exactly one main-route successor.</li>
 * </ul>
 *
 * <p>Pure common code. The key's {@code /race track validate} runs it and prints the messages; the R2 builder refuses
 * to build a track that does not pass. It never throws.
 */
public final class TrackValidator
{
    private TrackValidator() {}

    /** The outcome: valid when {@link #errors} is empty. {@link #warnings} never blocks a build. */
    public static final class Result
    {
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        public boolean ok()
        {
            return errors.isEmpty();
        }
    }

    public static Result validate(TrackDef def)
    {
        Result r = new Result();
        if (def.nodes.isEmpty())
        {
            r.errors.add("Track has no nodes.");
            return r;
        }

        Map<Integer, TrackNode> byId = new HashMap<>();
        for (TrackNode n : def.nodes)
            byId.put(n.id, n);

        // Start node present and on the main cycle.
        TrackNode start = byId.get(def.startNode);
        if (start == null)
            r.errors.add("Start node " + def.startNode + " does not exist.");
        else if (!start.main)
            r.errors.add("Start node " + def.startNode + " is not on the main route.");

        // Edges point at real nodes.
        for (TrackNode n : def.nodes)
            for (int t : n.next)
                if (!byId.containsKey(t))
                    r.errors.add("Node " + n.id + " links to missing node " + t + ".");

        // Main cycle: follow main successors from the start, back to the start, at least three nodes.
        List<Integer> mainOrder = mainCycle(def, byId);
        if (mainOrder.size() < 3)
            r.errors.add("The main route is not a closed cycle of at least three nodes (found " + mainOrder.size()
                    + " main node(s) before it broke).");
        else if (mainSuccessor(byId, mainOrder.get(mainOrder.size() - 1)) != def.startNode)
            r.errors.add("The main route does not loop back to the start (it ends at node "
                    + mainOrder.get(mainOrder.size() - 1) + "). In Node mode, right-click the start node to close the lap.");

        // Every main node should be part of that single cycle.
        for (TrackNode n : def.nodes)
            if (n.main && !mainOrder.contains(n.id))
                r.errors.add("Main node " + n.id + " is not on the single main cycle (a second loop or a dead end).");

        // Forks mark exactly one main successor.
        for (TrackNode n : def.nodes)
        {
            if (n.next.size() <= 1)
                continue;
            long mainSucc = n.next.stream().filter(t -> byId.get(t) != null && byId.get(t).main).count();
            if (n.main && mainSucc != 1)
                r.errors.add("Fork at node " + n.id + " must mark exactly one main-route successor (found "
                        + mainSucc + ").");
        }

        // Branches rejoin at a main node.
        for (List<Integer> chain : branchChains(def, byId))
        {
            int last = chain.get(chain.size() - 1);
            TrackNode lastNode = byId.get(last);
            if (lastNode == null || !lastNode.main)
                r.errors.add("Branch starting at node " + chain.get(0) + " never rejoins the main route.");
        }

        // Reachability both ways from the start.
        if (start != null)
        {
            Set<Integer> forward = reach(byId, def.startNode, false);
            Set<Integer> backward = reach(byId, def.startNode, true);
            for (TrackNode n : def.nodes)
            {
                if (!forward.contains(n.id))
                    r.errors.add("Node " + n.id + " is not reachable from the start.");
                else if (!backward.contains(n.id))
                    r.errors.add("Node " + n.id + " cannot reach the start (one-way dead end).");
            }
        }

        if (def.laps < 1)
            r.warnings.add("Laps is " + def.laps + "; a race needs at least one lap.");
        if (def.defaultWidth <= 0)
            r.warnings.add("Default width is " + def.defaultWidth + "; nodes without their own width will be zero-wide.");

        return r;
    }

    private static List<Integer> mainCycle(TrackDef def, Map<Integer, TrackNode> byId)
    {
        List<Integer> order = new ArrayList<>();
        TrackNode start = byId.get(def.startNode);
        if (start == null || !start.main)
            return order;
        Set<Integer> seen = new HashSet<>();
        int cur = def.startNode;
        while (cur >= 0 && seen.add(cur))
        {
            order.add(cur);
            cur = mainSuccessor(byId, cur);
        }
        // A closed cycle wraps back to the start; if it does not, report what we walked (caller flags it).
        if (!order.isEmpty() && mainSuccessor(byId, order.get(order.size() - 1)) != def.startNode)
            return order.size() >= 3 ? order : order; // still return; the size / wrap checks above catch it
        return order;
    }

    private static int mainSuccessor(Map<Integer, TrackNode> byId, int nodeId)
    {
        TrackNode n = byId.get(nodeId);
        if (n == null)
            return -1;
        for (int t : n.next)
        {
            TrackNode tn = byId.get(t);
            if (tn != null && tn.main)
                return t;
        }
        return -1;
    }

    private static List<List<Integer>> branchChains(TrackDef def, Map<Integer, TrackNode> byId)
    {
        List<List<Integer>> chains = new ArrayList<>();
        Set<Integer> started = new HashSet<>();
        for (TrackNode n : def.nodes)
        {
            // Same anchor rule as TrackGeometry: a branch starts at a main node or a fork, never at a continuation.
            if (!(n.main || n.next.size() > 1))
                continue;
            for (int succ : n.next)
            {
                TrackNode sn = byId.get(succ);
                if (sn == null || sn.main || !started.add(succ))
                    continue;
                List<Integer> chain = new ArrayList<>();
                chain.add(n.id);
                int cur = succ;
                Set<Integer> guard = new HashSet<>();
                while (cur >= 0 && guard.add(cur))
                {
                    chain.add(cur);
                    TrackNode cn = byId.get(cur);
                    int nxt = (cn == null || cn.next.isEmpty()) ? -1 : cn.next.get(0);
                    if (nxt < 0)
                        break;
                    TrackNode nn = byId.get(nxt);
                    if (nn != null && nn.main)
                    {
                        chain.add(nxt);
                        break;
                    }
                    cur = nxt;
                }
                chains.add(chain);
            }
        }
        return chains;
    }

    // Nodes reachable from start; reverse=true walks edges backward (who links to whom).
    private static Set<Integer> reach(Map<Integer, TrackNode> byId, int start, boolean reverse)
    {
        Map<Integer, List<Integer>> adj = new HashMap<>();
        for (TrackNode n : byId.values())
            adj.computeIfAbsent(n.id, k -> new ArrayList<>());
        for (TrackNode n : byId.values())
            for (int t : n.next)
            {
                if (!byId.containsKey(t))
                    continue;
                if (reverse)
                    adj.computeIfAbsent(t, k -> new ArrayList<>()).add(n.id);
                else
                    adj.get(n.id).add(t);
            }

        Set<Integer> seen = new HashSet<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty())
        {
            int cur = stack.pop();
            if (!seen.add(cur))
                continue;
            for (int nb : adj.getOrDefault(cur, List.of()))
                if (!seen.contains(nb))
                    stack.push(nb);
        }
        return seen;
    }
}
