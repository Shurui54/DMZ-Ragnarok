/**
 * Core-side hook holders for the sdu tree's PRIVATE features (the ones whose logic lives in the Ragnarok Key mod,
 * {@code dmz_ragnarok_key}).
 *
 * <p><b>The XHooks pattern.</b> A private feature keeps its registries, packet codecs (fixed ids), DTOs and client
 * screens in core; only its BEHAVIOUR moves to the key mod. Each feature gets one holder class in this package,
 * shaped like this:
 *
 * <pre>{@code
 * public final class OcarinaHook {
 *     public interface Impl {
 *         // methods the feature exposes; the interface's DEFAULTS are the keyless behaviour (usually no-ops
 *         // or an inert answer), so core works correctly with no key installed.
 *         default void onPlayed(ServerPlayer player) { }
 *     }
 *     private static final Impl KEYLESS = new Impl() { };
 *     private static volatile Impl impl = KEYLESS;              // volatile: written once by the key, read anywhere
 *     public static void install(Impl real) {                  // the key mod calls this from its constructor
 *         impl = real;
 *         KeyFeatures.mark("ocarina");                          // record that the feature is really installed
 *     }
 *     public static Impl get() { return impl; }                // read LAZILY at the point of use, never cached
 * }
 * }</pre>
 *
 * <p>Rules that keep this safe:
 * <ul>
 *   <li>The keyless default must be a correct, inert behaviour, so core alone (no key) never misbehaves.</li>
 *   <li>{@code install()} marks {@link net.shurui.dev.sdu.api.KeyFeatures}, so presence is "the hook was really
 *       installed", not "a jar with the id is present".</li>
 *   <li>The impl field is {@code volatile} and read lazily: mod construction is parallel, and the key mod may
 *       construct after a core reader, so never snapshot the hook (or a {@code present()} answer) at construction.</li>
 *   <li>The key mod NEVER registers a registry object; it only installs hooks here.</li>
 * </ul>
 *
 * <p><b>Client UI gates on {@code ClientGate.feature("<id>")}, never on this package.</b> A private feature's
 * screens, buttons and HUD live in core, so a keyless client still has the classes; whether to SHOW them is decided
 * by {@link net.shurui.dev.sdu.api.ClientGate#feature(String)}, which the server fills from
 * {@link net.shurui.dev.sdu.api.KeyFeatures#ids()} over {@code KeyFeatureSyncPacket} on login. Client code must NOT
 * read {@code KeyFeatures} directly: the key mod is server-only, so on a physical client it always answers empty
 * ({@code tools/check-client-key-reads.py} fails the build on such a read).
 *
 * <p>Empty in K0 (scaffolding): the holders arrive with their features in later batches. The sibling package
 * {@code net.shurui.shuruisutilities.api.key} holds the same pattern for the shuruisutilities tree's private features.
 */
package net.shurui.dev.sdu.api.key;
