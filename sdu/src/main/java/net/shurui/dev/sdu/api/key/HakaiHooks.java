package net.shurui.dev.sdu.api.key;

import com.dragonminez.common.stats.StatsData;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE admin hakai ({@code shuruis_hakai}: freeze, erase and a permanent ban; logic in the
 * Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps the technique definition, its sound and the DMZ cast mixin
 * ({@code TechniqueDispatcherMixin}); this hook is how the key runs the sequence, and only when the key is installed.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false, {@code /rg npc hakai} is not
 * offered, the technique cannot be granted, and a cast by someone who already holds it does nothing.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class HakaiHooks {

    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "hakai";

    private HakaiHooks() {
    }

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl {
        /** Whether the admin hakai is live (the key installed it). Keyless: false. */
        default boolean available() {
            return false;
        }

        /**
         * Run the hakai for an operator's cast. Returns true when the sequence started (DMZ then applies the cooldown
         * and ki cost and spawns nothing). Keyless: false, nothing happens.
         */
        default boolean cast(ServerPlayer caster, Level level, StatsData casterStats) {
            return false;
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

    /** Whether the admin hakai is live on this server. */
    public static boolean available() {
        return impl.available();
    }
}
