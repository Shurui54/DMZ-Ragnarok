package net.shurui.dev.sdu.waypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import net.minecraft.server.level.ServerPlayer;

/**
 * Per-player marker sources other parts of the suite can plug into the waypoint sync.
 *
 * <p>{@link GlobalMarkers} covers markers everyone sees. This is the other half: a marker that depends on WHO
 * is being synced (task progress, whether they signed up for the open raid). Those live in the admin suite,
 * the raid boss mod and the tournament mod, and sdu deliberately imports nothing from any of them, so they
 * hand a function in here instead.
 *
 * <p>Called once a second per player from {@code WaypointTracker.sync}, on the server thread. Keep them cheap
 * and side-effect free. A provider that throws is dropped for that pass and never breaks the rest of a
 * player's markers.
 *
 * <p>Registration is at mod init and never undone, so the copy-on-write list needs no locking on reads.
 */
public final class WaypointProviders {

    private static final List<Function<ServerPlayer, List<Waypoint>>> PROVIDERS = new CopyOnWriteArrayList<>();

    private WaypointProviders() {
    }

    /** Add a source of per-player markers. Call once, at mod init. */
    public static void register(Function<ServerPlayer, List<Waypoint>> provider) {
        if (provider != null) {
            PROVIDERS.add(provider);
        }
    }

    /** Everything every provider offers for this player. Never throws. */
    public static List<Waypoint> collect(ServerPlayer player) {
        if (PROVIDERS.isEmpty() || player == null) {
            return List.of();
        }
        List<Waypoint> out = new ArrayList<>();
        for (Function<ServerPlayer, List<Waypoint>> p : PROVIDERS) {
            try {
                List<Waypoint> got = p.apply(player);
                if (got != null && !got.isEmpty()) {
                    out.addAll(got);
                }
            } catch (Throwable ignored) {
                // One bad provider must not cost a player every other marker they hold.
            }
        }
        return out;
    }
}
