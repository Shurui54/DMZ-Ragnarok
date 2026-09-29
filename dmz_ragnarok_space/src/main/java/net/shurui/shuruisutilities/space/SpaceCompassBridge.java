package net.shurui.shuruisutilities.space;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/**
 * Optional-dependency bridge to sdu's HUD compass for the space course. sdu classes are named only inside the inner
 * {@code Sdu} holder, never classloaded unless sdu is present, so this loads safely with sdu absent (no pip; the course
 * and proximity landing still work).
 *
 * <p>The pip is PER PLAYER, but sdu's {@link net.shurui.dev.sdu.waypoint.GlobalMarkers} is a shown-to-everyone
 * registry, so each player gets a stable marker id derived from their UUID. That makes it read as that player's own
 * course and lets a new selection replace it in place. TRANSIENT (memory only), and it only draws while the viewer is
 * in the pip's dimension, so the course set from the pod on the ground appears once in space.
 *
 * <p>{@link ModList#isLoaded} proves sdu is PRESENT, not that this build carries the GlobalMarkers API. A missing class
 * surfaces as a LinkageError (an Error, not an Exception), so every sdu call is wrapped in {@code catch (Throwable)}.
 */
final class SpaceCompassBridge
{
    // one stable pip per player: su_space_course_<uuid>, so a new selection replaces in place.
    private static final String MARKER_ID_PREFIX = "su_space_course_";
    private static final int MARKER_COLOR = 0xFF66E0FF; // pale cyan
    private static final String MARKER_ICON = "minecraft:compass";
    // the chat message already names the planet, so the pip stays generic.
    private static final String MARKER_NAME = "Course";

    private SpaceCompassBridge()
    {
    }

    private static String markerId(ServerPlayer player)
    {
        return MARKER_ID_PREFIX + player.getUUID();
    }

    // sdu is now part of this same container, so the course pip is always set/cleared.
    static void setCourse(ServerPlayer player, String dim, double x, double y, double z)
    {
        try
        {
            Sdu.set(markerId(player), dim, x, y, z);
        }
        catch (Throwable ignored)
        {
        }
    }

    static void clearCourse(ServerPlayer player)
    {
        try
        {
            Sdu.remove(markerId(player));
        }
        catch (Throwable ignored)
        {
        }
    }

    // the only class that names sdu types; never classloaded unless sdu is present.
    private static final class Sdu
    {
        static void set(String id, String dim, double x, double y, double z)
        {
            net.shurui.dev.sdu.waypoint.GlobalMarkers.set(id, dim, x, y, z, MARKER_NAME, MARKER_COLOR, MARKER_ICON);
        }

        static void remove(String id)
        {
            net.shurui.dev.sdu.waypoint.GlobalMarkers.remove(id);
        }
    }
}
