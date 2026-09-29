package net.shurui.dev.sdu.api;

/**
 * The client's answer to "does the server we are connected to hold the Ragnarok Key, and is this module on there".
 *
 * <p>Client code must NEVER read {@link RagnarokKey}, {@code KeyGate} or {@code PublicContent}:
 * those evaluate the SERVER's key state, and on a physical client the key mod is not present, so they answer wrong
 * (a keyed server's private UI would vanish). The server states its key status on login and this holds it, so a
 * screen can hide a private feature exactly when the server would refuse it. {@code tools/check-client-key-reads.py}
 * fails the build on any client read of the server-side gates.
 *
 * <p>Default is LOCKED (false): until the login packet arrives, or against a server too old to send one, private UI
 * stays hidden rather than flashing in and then disappearing. This is the deliberate direction; a hidden private
 * button is a smaller fault than a leaked one.
 *
 * <p>Plain by design: it must not import any client-only class, because it can be classloaded on a dedicated
 * server by accident (a shared handler touching it), and a client-only import would crash there.
 */
public final class ClientGate {

    /** Set only by the login key sync (KeySyncPacket / PacketRgKeySync), cleared on logout. */
    private static volatile boolean key = false;

    /**
     * The PRIVATE feature ids the connected server's Ragnarok Key installed, as of the last
     * {@code KeyFeatureSyncPacket}. Empty until that packet arrives (and against a server too old to send it), so
     * a private feature's UI stays hidden by default. This is a finer axis than {@link #key()}: {@code key()} says
     * the Ragnarok Key installed its hooks, this says which private features it installed.
     */
    private static volatile java.util.Set<String> features = java.util.Collections.emptySet();

    private ClientGate() {}

    /** Whether the connected server reported holding the Ragnarok Key. */
    public static boolean key() {
        return key;
    }

    /** Whether the last key-feature sync listed this private feature id (the correct client-side gate for its UI). */
    public static boolean feature(String id) {
        return id != null && features.contains(id);
    }

    /** An unmodifiable snapshot of the private feature ids the server reported installed. */
    public static java.util.Set<String> features() {
        return features;
    }

    /** Called by the key-feature sync packet on the client thread. */
    public static void setFeatures(java.util.Collection<String> ids) {
        features = (ids == null || ids.isEmpty())
                ? java.util.Collections.emptySet()
                : java.util.Collections.unmodifiableSet(new java.util.HashSet<>(ids));
    }

    /**
     * The operator per-feature switchboard was removed in batch M: a module's presence is decided by installing
     * its jar and a private feature by the Ragnarok Key, so there is no server-sent "off" set any more. Every
     * feature is on, so this is always true. Kept so the old call sites read the same.
     */
    public static boolean on(String k) {
        return true;
    }

    /** A private feature is visible exactly when the server holds the key. (The per-feature switch is gone.) */
    public static boolean priv(String k) {
        return key();
    }

    /** Called by the login sync packet on the client thread. */
    public static void set(boolean value) {
        key = value;
    }

    /** Forget the last server's key state on disconnect, so it never carries into the next server or the menu. */
    public static void reset() {
        key = false;
        features = java.util.Collections.emptySet();
    }
}
