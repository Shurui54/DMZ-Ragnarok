package net.shurui.dev.sdu.api.key;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.dev.sdu.buff.TokenBuffStore;

/**
 * Core-side hook for the PRIVATE token buffs (TP gems and stat gems; logic in the Ragnarok Key,
 * {@code dmz_ragnarok_key}). Core keeps the gem items, the buff store, the pip effects, the sync packet and the
 * stat-cost mixins; this hook is how the key applies a gem, scales TP gain and discounts a stat purchase, and only
 * when the key is installed.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false, a gem is refused and kept,
 * a stat purchase pays DMZ's full price, and nothing is resynced. Stored buffs stay in the player's persistent data
 * untouched (the respawn carry-over is core, {@code TokenBuffCarryOver}), so a keyed server picks them up again.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class TokenBuffHooks {

    /** The {@link KeyFeatures} id this hook marks on install, and the id the client gates its UI on. */
    public static final String FEATURE_ID = "tokenbuffs";

    private TokenBuffHooks() {
    }

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl {
        /** Whether token buffs are live (the key installed them). Keyless: false. */
        default boolean available() {
            return false;
        }

        /**
         * Apply a gem the player right-clicked: {@code percent} of {@code category} for {@code durationMillis}.
         * Returns true when the buff was applied (the caller then reports success), false when it was refused and
         * the gem kept. The impl consumes the stack itself. Keyless: false.
         */
        default boolean useGem(ServerPlayer player, ItemStack stack, TokenBuffStore.Category category,
                               double percent, long durationMillis) {
            return false;
        }

        /** The stat-purchase discount (a fraction) to apply for this buyer. Keyless: 0, DMZ's price stands. */
        default double statDiscount(ServerPlayer buyer) {
            return 0.0;
        }

        /** Tell the player's client what the server counts after a stat purchase. Keyless: no-op. */
        default void resync(ServerPlayer player) {
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

    /** Whether token buffs are live on this server. */
    public static boolean available() {
        return impl.available();
    }
}
