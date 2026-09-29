package net.shurui.shuruisutilities.racing.track;

import java.util.ArrayList;
import java.util.List;

/**
 * A maximal chain of nodes between two junctions, the unit {@link TrackGeometry} splines. The main lap is one closed
 * section (or a chain of them, if the main route itself forks); each alternate route is an open section running from
 * a main junction (its {@link #entryNode}) to a main junction ({@link #exitNode}).
 *
 * <p>A JUNCTION is any node whose out-degree is not 1 or whose in-degree is not 1 (a fork, a merge, or the start of
 * a route). Splining a section on its own, with the junction's main neighbours supplying the end tangents, keeps the
 * curve continuous across the junction without letting a branch bend the main line.
 */
public final class TrackSection
{
    /** The node ids along the section, entry first, exit last. */
    public final List<Integer> nodeIds = new ArrayList<>();

    /** Whether this section is part of the main lap cycle. */
    public final boolean main;

    /** The junction this section leaves (its first node) and the junction it rejoins (its last node). */
    public final int entryNode;
    public final int exitNode;

    public TrackSection(List<Integer> nodeIds, boolean main)
    {
        this.nodeIds.addAll(nodeIds);
        this.main = main;
        this.entryNode = nodeIds.isEmpty() ? -1 : nodeIds.get(0);
        this.exitNode = nodeIds.isEmpty() ? -1 : nodeIds.get(nodeIds.size() - 1);
    }
}
