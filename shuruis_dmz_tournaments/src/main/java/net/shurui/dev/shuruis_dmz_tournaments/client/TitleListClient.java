package net.shurui.dev.shuruis_dmz_tournaments.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/**
 * Client side cache of the title ids the server recognises, pushed by {@link
 * net.shurui.dev.shuruis_dmz_tournaments.network.TitleListSyncPacket}. The authoritative list is the server's
 * {@code TitleManager.definitions()} (config rows plus built-in roles); the client's own common config can be stale,
 * so the editor dropdowns read this instead when a sync has arrived. See {@link GameRegistries#titleIds()}.
 *
 * <p><b>Fail safe.</b> Until the packet has arrived {@link #received()} is false, so single player, an older server,
 * or any moment before the first sync leaves {@code GameRegistries.titleIds()} falling back to the local config rather
 * than reporting an empty list.
 *
 * <p>State is a pair of volatile references to immutable snapshots, written only on the client network thread's
 * enqueued work and read on the render thread, so the volatile publish is enough to keep each read consistent.
 */
public final class TitleListClient {
    private TitleListClient() {}

    private static final Logger LOG = LogUtils.getLogger();

    // Immutable snapshots, replaced wholesale on each sync. Null before any sync (distinguishes "not received" from
    // "received an empty list", which the fallback in GameRegistries.titleIds() depends on).
    private static volatile List<String> ids = null;
    private static volatile Map<String, String> displays = null;

    /** Replace the cached title lists with the server's authoritative set for this world. */
    public static void apply(List<String> newIds, List<String> newDisplays) {
        List<String> idSnapshot = newIds == null ? List.of() : List.copyOf(newIds);
        Map<String, String> displaySnapshot = new LinkedHashMap<>();
        if (newIds != null && newDisplays != null) {
            for (int i = 0; i < newIds.size() && i < newDisplays.size(); i++) {
                displaySnapshot.put(newIds.get(i), newDisplays.get(i));
            }
        }
        ids = idSnapshot;
        displays = Map.copyOf(displaySnapshot);
        // Proof of what the client actually received, so a stale or empty list is observable rather than silent: this
        // exact gap cost a debugging round when an admin's dropdown could not offer god_of_destruction or angel.
        LOG.info("[tournaments] client received title id sync: {}", idSnapshot);
    }

    /** True once at least one sync has arrived. When false, callers must fall back to the local config. */
    public static boolean received() {
        return ids != null;
    }

    /** The synced ids, or an empty list if none received yet. Callers should gate on {@link #received()} first. */
    public static List<String> ids() {
        List<String> snapshot = ids;
        return snapshot == null ? List.of() : new ArrayList<>(snapshot);
    }

    /** The display name for a synced id, or the id itself if unknown. */
    public static String displayName(String id) {
        Map<String, String> snapshot = displays;
        if (snapshot == null) return id;
        return snapshot.getOrDefault(id, id);
    }

    /** Drop the cache (e.g. on disconnect) so the next session starts falling back to the local config again. */
    public static void clear() {
        ids = null;
        displays = null;
    }
}
