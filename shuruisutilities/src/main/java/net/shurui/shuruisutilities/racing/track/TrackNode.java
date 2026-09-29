package net.shurui.shuruisutilities.racing.track;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.phys.Vec3;

/**
 * One control point of a race track's node graph. The wand appends these along the route (R2); the geometry
 * ({@link TrackGeometry}) threads a centripetal Catmull-Rom spline through them, so a node is a control point, not
 * a literal path vertex. Pure common data (no client imports): the client preview, the key's track store and the
 * key's race engine all read the same object.
 *
 * <p>Identity is the integer {@link #id}, stable across edits (a removed node's id is never reused within a track,
 * so {@link #next} references never dangle onto a different node). Positions are doubles so a wand MOVE keeps
 * sub-block precision; the console commands pass integer-valued doubles.
 */
public final class TrackNode
{
    /** Stable node id within its track. Referenced by {@link #next}, {@link TrackDef#startNode} and gates. */
    public int id;

    /** World position of the control point. */
    public double x;
    public double y;
    public double z;

    /** Road half-to-half width at this node, in blocks; {@code <= 0} means "use the track's default width". */
    public double width;

    /** Whether a checkpoint gate sits at this node (the lap / wrong-way graph is built from these). */
    public boolean checkpoint;

    /** Whether a wall is built on the left / right edge through this node (R2 builder; geometry ignores them). */
    public boolean wallL;
    public boolean wallR;

    /** Whether this node lies on the MAIN lap cycle. Non-main nodes form alternate-route (branch) chains. */
    public boolean main = true;

    /** Successor node ids (directed edges). A main node's main-route successor is the successor that is also main. */
    public final List<Integer> next = new ArrayList<>();

    public TrackNode() {}

    public TrackNode(int id, double x, double y, double z, double width)
    {
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.width = width;
    }

    /** The control point as a vector. */
    public Vec3 pos()
    {
        return new Vec3(x, y, z);
    }

    /** This node's effective width: its own if set, else the supplied track default. */
    public double widthOr(double def)
    {
        return width > 0 ? width : def;
    }

    /** Add a successor edge to {@code targetId} if not already present. */
    public void linkTo(int targetId)
    {
        if (!next.contains(targetId))
            next.add(targetId);
    }
}
