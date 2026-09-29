package net.shurui.dev.sdu.waypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side registry of <b>global</b> compass markers: points shown to every online player, unlike the
 * per-player manual/quest waypoints. Other mods register under a stable id (e.g. the Server Utilities
 * airdrop uses {@code su_airdrop}) and remove it when the target is gone; the once-a-second
 * {@code WaypointTracker} sync folds these into every player's waypoint set.
 *
 * <p>Markers here are transient, so the owning feature must re-register on server start if its target
 * still exists.
 */
public final class GlobalMarkers {

    private static final Map<String, Waypoint> MARKERS = new ConcurrentHashMap<>();

    private GlobalMarkers() {
    }

    /**
     * Register (or replace) the global marker {@code id}. {@code icon} is an optional item id (e.g.
     * {@code minecraft:chest}) drawn on the compass bar instead of the coloured triangle.
     */
    public static void set(String id, String dim, double x, double y, double z,
                           String name, int color, String icon) {
        set(id, dim, x, y, z, name, color, icon, WaypointMark.NONE);
    }

    /**
     * As above, wearing one of the commissioned pins. A pin adds a beacon beam and a floating in-world icon
     * (client {@code WaypointMarkerRenderer}); {@code icon} still wins on the compass bar when both are given.
     */
    public static void set(String id, String dim, double x, double y, double z,
                           String name, int color, String icon, WaypointMark mark) {
        if (id == null || id.isBlank() || dim == null || dim.isBlank()) {
            return;
        }
        // beacon follows whether a pin was given: an airdrop (AIRDROP pin) keeps its beam, a bar-only global
        // marker (no pin, e.g. the space course and the shadow-dragon marker) stays on the compass bar.
        MARKERS.put(id, new Waypoint(dim, x, y, z, name, color, false, icon, false, mark, mark.hasPin()));
    }

    public static void remove(String id) {
        if (id != null) {
            MARKERS.remove(id);
        }
    }

    public static List<Waypoint> all() {
        return new ArrayList<>(MARKERS.values());
    }
}
