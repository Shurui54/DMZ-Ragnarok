package net.shurui.dev.sdu.api;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The registry of PRIVATE feature ids the Ragnarok Key has installed this run.
 *
 * <p>The Ragnarok Key ({@code dmz_ragnarok_key}) houses the suite's private feature logic. Each moved feature keeps
 * its registries, packet codecs, DTOs and client screens in CORE, reached through an inert-default hook (the
 * {@code XHooks} pattern in {@code net.shurui.dev.sdu.api.key} / {@code net.shurui.shuruisutilities.api.key}). When
 * the key mod installs a hook it also {@link #mark marks} the feature here, so a gate can ask "did the key actually
 * install feature X" without classloading the key mod or naming it.
 *
 * <p><b>Presence is installation, not the jar.</b> This is deliberately NOT {@code ModList.isLoaded("dmz_ragnarok_key")}:
 * a fake jar could carry that id but install nothing, so a feature must unlock only once its real hook has run. Read
 * this LAZILY (mod construction is parallel, and the key mod may construct after a reader), never cached at
 * construction time.
 *
 * <p>Thread-safe: the key mod marks features from its constructor (a mod-loading worker thread) while other code may
 * read on the server thread. The backing set is copy-on-write, so reads never block and never see a torn state.
 */
public final class KeyFeatures {

    private static final Set<String> INSTALLED = new CopyOnWriteArraySet<>();

    /**
     * Notified once per NEWLY installed feature id. Set by core (sdu) so it can re-sync online clients if the key
     * installs a feature after login; kept as a plain functional interface here so this class imports nothing (sdu
     * must not import SU, and this must not import networking or client types). Reads are lazy elsewhere; here the
     * listener is written once and read on the marking thread.
     */
    public interface Listener {
        void onFeatureInstalled(String featureId);
    }

    private static volatile Listener listener;

    private KeyFeatures() {
    }

    /** Register the (single) listener that reacts to a feature being installed. Core sets this at setup. */
    public static void setListener(Listener l) {
        listener = l;
    }

    /**
     * Records that the private feature with this id is installed. Called by the Ragnarok Key mod as it installs the
     * feature's core hook. Idempotent; a null or blank id is ignored. Notifies the listener ONLY when the id is new,
     * so a re-install (or a duplicate mark) does not trigger a redundant re-sync.
     */
    public static void mark(String featureId) {
        if (featureId != null && !featureId.isBlank() && INSTALLED.add(featureId)) {
            Listener l = listener;
            if (l != null) {
                try {
                    l.onFeatureInstalled(featureId);
                } catch (Throwable ignored) {
                    // A listener failure must never stop a feature from being marked installed.
                }
            }
        }
    }

    /** Whether the private feature with this id has been installed by the key this run. */
    public static boolean installed(String featureId) {
        return featureId != null && INSTALLED.contains(featureId);
    }

    /** Whether the key installed ANY private feature this run (a cheap "is the key present and working" answer). */
    public static boolean any() {
        return !INSTALLED.isEmpty();
    }

    /** An unmodifiable snapshot of the installed feature ids, for logging or diagnostics. */
    public static Set<String> ids() {
        return Collections.unmodifiableSet(INSTALLED);
    }
}
