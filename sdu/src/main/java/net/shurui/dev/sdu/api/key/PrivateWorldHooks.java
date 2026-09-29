package net.shurui.dev.sdu.api.key;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for travel to the PRIVATE imported worlds, {@code namekow} and {@code kaiow} (these
 * are not public; the allow decision lives in the Ragnarok Key, {@code dmz_ragnarok_key}). The space pod's arrival gate
 * ({@code TravelToPlanetC2SMixin}) stays in core, since the key carries no mixins, and asks {@link Impl#allowTravel}
 * for those two destinations only.
 *
 * <p>The {@link Impl} DEFAULT is the keyless behaviour: travel there is refused, exactly as it was keyless before
 * (the key was required outright, singleplayer included).
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class PrivateWorldHooks {

    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "privateworlds";

    private PrivateWorldHooks() {
    }

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl {
        /**
         * Whether this player may travel to the given private destination id ({@code namekow} or {@code kaiow}).
         * Keyless: false.
         */
        default boolean allowTravel(ServerPlayer player, String destinationId) {
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
