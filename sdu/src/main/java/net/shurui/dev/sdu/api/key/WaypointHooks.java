package net.shurui.dev.sdu.api.key;

import java.util.List;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.dev.sdu.waypoint.Waypoint;

/**
 * Core-side hook for the PRIVATE waypoint compass and beacon markers (OWNER-SPECS 4: "Waypoint compass and
 * beacon/icon logic are private; the quest/objective location stays public"; logic in the Ragnarok Key,
 * {@code dmz_ragnarok_key}). {@code WaypointTracker.sync} always sends the player's current quest marker, which is
 * PUBLIC (the quest tracker HUD reads it on every server); everything else it sends comes from this hook: the manual
 * pins an admin set with {@code /rg npc waypoint} ({@code WaypointStore}), the global markers
 * ({@code GlobalMarkers}) and the per-player provider markers ({@code WaypointProviders}). Core keeps the store, the
 * marker registries, the sync packet and the client renderers; the compass and beacon renderers themselves stay
 * hidden on the client unless the server reported the key.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: no manual pins and no markers, so only the quest marker is
 * synced.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class WaypointHooks {

    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "waypoints";

    private WaypointHooks() {
    }

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl {
        /** Whether the private waypoints are live (the key installed them). Keyless: false. */
        default boolean available() {
            return false;
        }

        /** The manual pins set for this player with {@code /rg npc waypoint}, synced first. Keyless: none. */
        default List<Waypoint> manualPins(ServerPlayer player) {
            return List.of();
        }

        /** The global and provider markers, synced after the quest marker. Keyless: none. */
        default List<Waypoint> privateMarkers(ServerPlayer player) {
            return List.of();
        }
    }

    /** The keyless default until the key installs its own. Never null. */
    private static volatile Impl impl = new Impl() {
    };

    /** Install the key's implementation and mark the feature. Called once from {@code RagnarokKeyMod}. */
    public static void install(Impl i) {
        if (i == null) {
            return;
        }
        impl = i;
        KeyFeatures.mark(FEATURE_ID);
    }

    /** The live implementation (never null: the keyless default until the key installs). */
    public static Impl get() {
        return impl;
    }

    /** Whether the private waypoints are live on this server. */
    public static boolean available() {
        return impl.available();
    }
}
