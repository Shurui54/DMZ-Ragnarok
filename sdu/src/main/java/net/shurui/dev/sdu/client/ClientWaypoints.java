package net.shurui.dev.sdu.client;

import net.shurui.dev.sdu.waypoint.Waypoint;

import java.util.List;

/**
 * Client-only cache of the waypoints the server last synced for this player. Written by
 * {@code WaypointSyncPacket} and read by {@link net.shurui.dev.sdu.client.hud.CompassOverlay} each frame.
 */
public final class ClientWaypoints {

    private static volatile List<Waypoint> waypoints = List.of();

    private ClientWaypoints() {
    }

    public static void accept(List<Waypoint> list) {
        waypoints = list == null ? List.of() : List.copyOf(list);
    }

    public static List<Waypoint> all() {
        return waypoints;
    }

    public static void clear() {
        waypoints = List.of();
    }
}
