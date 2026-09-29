package net.shurui.dev.sdu.api.key;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the last few PRIVATE answers that core code still gives at scattered sites (SF-a, the remaining
 * gates audit before the HMAC retires). Each site used to read the Ragnarok Key directly ({@code KeyGate} or
 * {@code RagnarokKey}); once SF makes key presence "a mod marked {@code ragnarok_key}", any jar with that id would
 * have switched them on. Asking this hook instead means the behaviour exists only when the real Ragnarok Key
 * ({@code dmz_ragnarok_key}) installs it.
 *
 * <p>The {@link Impl} DEFAULTS are today's keyless answers, so a server without the key behaves exactly as before:
 * <ul>
 *   <li>{@link Impl#contentUnlocked()}: false, SU's own gated content stays locked ({@code ContentGate}).</li>
 *   <li>{@link Impl#starterSkills()}: false, no class starts with Ki Control ({@code StarterSkills}).</li>
 *   <li>{@link Impl#serverOnlyPodDestinations()}: false, the server-only space-pod destinations are stripped
 *       ({@code MixinDmzSpacePodDestinations}).</li>
 *   <li>{@link Impl#rgModelsUnlocked()}: false, a key-locked rgnpc model degrades to the default
 *       ({@code RgNpcEntity.applyModelChoice}).</li>
 *   <li>{@link Impl#patreonCommand()}: false, {@code /patreon} is not registered ({@code ModulePatreon}).</li>
 *   <li>{@link Impl#formDodgeDisabled()}: false, the form dodge stays on ({@code FormCombatHandler}).</li>
 * </ul>
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class CoreGateHooks {

    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "coregates";

    private CoreGateHooks() {
    }

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl {
        /** Whether SU's own gated blocks, items and entities function here. Keyless: false (locked). */
        default boolean contentUnlocked() {
            return false;
        }

        /** Whether a new Spiritualist or Cleric starts with Ki Control. Keyless: false. */
        default boolean starterSkills() {
            return false;
        }

        /**
         * Whether the key allows the server-only space-pod destinations (the SMP world). The caller still requires a
         * dedicated server on top of this. Keyless: false (the destinations are stripped).
         */
        default boolean serverOnlyPodDestinations() {
            return false;
        }

        /** Whether a key-locked rgnpc model may be applied at runtime. Keyless: false (it degrades to the default). */
        default boolean rgModelsUnlocked() {
            return false;
        }

        /** Whether {@code /patreon} is registered. Keyless: false. */
        default boolean patreonCommand() {
            return false;
        }

        /** Whether the stacked form dodge is switched off. Keyless: false (the dodge stays on). */
        default boolean formDodgeDisabled() {
            return false;
        }
    }

    /** The keyless default until the key installs its own. Never null. */
    private static volatile Impl impl = new Impl() {
    };

    /** Set once the key installs its implementation (a jar that only marks the feature id never sets it). */
    private static volatile boolean installed;

    /** Install the key's implementation and mark the feature. Called once from {@code RagnarokKeyMod}. */
    public static void install(Impl i) {
        if (i == null) {
            return;
        }
        impl = i;
        installed = true;
        KeyFeatures.mark(FEATURE_ID);
    }

    /** The live implementation (never null: the keyless default until the key installs). */
    public static Impl get() {
        return impl;
    }

    /** Whether the Ragnarok Key installed this hook. Keyless, or a jar installing nothing: false. */
    public static boolean available() {
        return installed;
    }
}
