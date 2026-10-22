package net.shurui.shuruisutilities.compat.client;

import java.util.Arrays;

import net.shurui.shuruisutilities.util.PolygonXZ;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The freeform NPC region draw mode on Xaero's World Map, like a polygonal lasso: each left click adds a vertex, the
 * outline follows the cursor, and clicking the first vertex (or Enter) closes the shape and opens the same
 * create-or-add popup the right-drag box uses. Right click or Backspace removes the last vertex, Escape leaves the
 * mode. A point whose new edge would cross the outline is refused as it is clicked, the closing edge is checked the
 * same way, and the vertex count is capped at {@link PolygonXZ#MAX_VERTICES}. The server validates everything again.
 *
 * <p>State only, no Xaero types: {@link NpcRegionMapInput} feeds it block coordinates and draws it. The points
 * survive the map being closed and reopened (so an accidental close loses nothing) and are dropped when the mode is
 * left, the shape is sent, or the player is no longer in the dimension the drawing started in.
 */
public final class NpcRegionMapDraw
{
    private static boolean active;
    private static String dim = "";
    private static int[] pts = new int[16];
    private static int n;

    private NpcRegionMapDraw() {}

    public static boolean active()
    {
        return active;
    }

    /** Vertices so far. */
    public static int count()
    {
        return n;
    }

    /** A copy of the vertices so far, interleaved x, z. */
    public static int[] points()
    {
        return Arrays.copyOf(pts, 2 * n);
    }

    public static String dim()
    {
        return dim;
    }

    /** Enter draw mode for the player's dimension, keeping nothing from an earlier drawing. */
    public static void start(String dimension)
    {
        active = true;
        dim = dimension == null ? "" : dimension;
        n = 0;
        say("gui.dmz_ragnarok.core.npcdraw.started", 0xFFFF55);
    }

    public static void stop()
    {
        active = false;
        n = 0;
    }

    /** Leave the mode with a message (Escape). */
    public static void cancel()
    {
        if (!active)
            return;
        stop();
        say("gui.dmz_ragnarok.core.npcdraw.cancelled", 0xAAAAAA);
    }

    /** A click while the map shows another dimension than the one being drawn in: refused with a message. */
    public static void wrongDimension()
    {
        say("gui.dmz_ragnarok.core.npcdraw.wrong_dim", 0xFF5555, dim);
    }

    /** Add a vertex at a block column. False (with a message) when it is refused. */
    public static boolean add(int x, int z)
    {
        if (!active)
            return false;
        if (n >= PolygonXZ.MAX_VERTICES)
        {
            say("gui.dmz_ragnarok.core.npcdraw.too_many", 0xFF5555, PolygonXZ.MAX_VERTICES);
            return false;
        }
        if (n > 0 && pts[2 * n - 2] == x && pts[2 * n - 1] == z)
            return false; // the same block again: a double click, not a new point
        if (PolygonXZ.newEdgeCrosses(points(), x, z, false))
        {
            say("gui.dmz_ragnarok.core.npcdraw.crosses", 0xFF5555);
            return false;
        }
        if (2 * n + 2 > pts.length)
            pts = Arrays.copyOf(pts, pts.length * 2);
        pts[2 * n] = x;
        pts[2 * n + 1] = z;
        n++;
        return true;
    }

    /** Remove the last vertex. False when there is none. */
    public static boolean undo()
    {
        if (!active || n == 0)
            return false;
        n--;
        return true;
    }

    /**
     * Close the shape: needs {@link PolygonXZ#MIN_VERTICES} points and a valid ring. On success opens the
     * create-or-add popup with the outline and leaves the mode; otherwise says why and keeps drawing.
     */
    public static boolean close()
    {
        if (!active)
            return false;
        if (n < PolygonXZ.MIN_VERTICES)
        {
            say("gui.dmz_ragnarok.core.npcdraw.need_three", 0xFF5555, PolygonXZ.MIN_VERTICES);
            return false;
        }
        int[] ring = points();
        if (PolygonXZ.newEdgeCrosses(ring, 0, 0, true) || PolygonXZ.validate(ring) != null)
        {
            String why = PolygonXZ.validate(ring);
            if (why == null)
                say("gui.dmz_ragnarok.core.npcdraw.closing_crosses", 0xFF5555);
            else
                say("gui.dmz_ragnarok.core.npcdraw.invalid", 0xFF5555, why);
            return false;
        }
        String forDim = dim;
        stop();
        Minecraft.getInstance().setScreen(
                new net.shurui.shuruisutilities.client.gui.editor.NpcRegionAddSelectionScreen(forDim, ring));
        return true;
    }

    // the action bar is hidden behind a full-screen map, so the last message is drawn by the map overlay instead
    private static Component message;
    private static long messageUntil;

    /** The latest status message while it is fresh (about four seconds), else null. */
    public static Component message()
    {
        return message != null && System.currentTimeMillis() < messageUntil ? message : null;
    }

    private static void say(String key, int color, Object... args)
    {
        message = Component.translatable(key, args).withStyle(s -> s.withColor(color));
        messageUntil = System.currentTimeMillis() + 4000L;
    }
}
