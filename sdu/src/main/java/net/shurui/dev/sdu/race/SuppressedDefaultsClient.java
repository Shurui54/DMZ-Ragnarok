package net.shurui.dev.sdu.race;

import net.shurui.dev.sdu.DmzNpc;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Client-side mirror of the server's {@link SuppressedDefaultsConfig} suppressed race/class id sets,
 * pushed by {@link net.shurui.dev.sdu.network.SuppressedDefaultsSyncPacket} on login and after any
 * suppress/unsuppress change. This is DISTINCT from DMZ's synced loaded-races list on purpose: a
 * suppressed default race/class is stripped from DMZ's {@code SERVER_SYNCED_*} maps, so the client
 * editor can no longer see it via {@code getLoadedRaces()}. Without this cache an admin could suppress
 * a default but never restore it, because it would vanish from the list. The race/class editors union
 * these ids into their lists so suppressed defaults stay visible (greyed, "click to restore").
 *
 * <p>Mirrors {@link net.shurui.dev.sdu.form.FormQuestGateConfig#applySynced} in lifecycle. All access
 * is on the client thread; plain sets are enough, guarded by this class's monitor for safety.
 */
public final class SuppressedDefaultsClient {

    private static final Set<String> RACES = new LinkedHashSet<>();
    private static final Set<String> CLASSES = new LinkedHashSet<>();

    private SuppressedDefaultsClient() {
    }

    /** Replace the client's suppressed race/class id sets from a server sync. */
    public static synchronized void applySynced(Set<String> races, Set<String> classes) {
        RACES.clear();
        CLASSES.clear();
        if (races != null) {
            for (String r : races) {
                String k = key(r);
                if (!k.isEmpty()) {
                    RACES.add(k);
                }
            }
        }
        if (classes != null) {
            for (String c : classes) {
                String k = key(c);
                if (!k.isEmpty()) {
                    CLASSES.add(k);
                }
            }
        }
        DmzNpc.LOGGER.info("[{}] Applied synced suppressed defaults: {} race(s), {} class(es).",
                DmzNpc.MODID, RACES.size(), CLASSES.size());
    }

    /** Immutable snapshot of suppressed race ids known to this client (lowercase). */
    public static synchronized Set<String> races() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(RACES));
    }

    /** Immutable snapshot of suppressed class ids known to this client (lowercase). */
    public static synchronized Set<String> classes() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(CLASSES));
    }

    public static synchronized boolean isRaceSuppressed(String id) {
        return RACES.contains(key(id));
    }

    public static synchronized boolean isClassSuppressed(String id) {
        return CLASSES.contains(key(id));
    }

    private static String key(String id) {
        return id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
