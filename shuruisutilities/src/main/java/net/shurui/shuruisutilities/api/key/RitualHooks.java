package net.shurui.shuruisutilities.api.key;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE wish rituals: the Super Saiyan 5 fusion with Shenron and the namekian dragon ball
 * recreation at the idol (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps the wish counters
 * ({@code WishRitualStore}), the wish rows, the idol block and items, both idol packets (70 and 71) and the idol
 * screen; this hook is how the key decides and performs a ritual, and only when the key is installed.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false, the SSJ5 row is never offered
 * and refused if asked for, the eleventh Namek wish spends nothing, and the idol opens no prompt and confirms nothing.
 * Wish counters still run in core, so a keyed server reads the same numbers.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class RitualHooks
{
    /** The {@link KeyFeatures} id this hook marks on install, and the id the client gates its UI on. */
    public static final String FEATURE_ID = "rituals";

    private RitualHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the rituals are live (the key installed them). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** Whether the SSJ5 wish should be offered to (and may be granted to) this player. Keyless: never. */
        default boolean canWishForSsj5(ServerPlayer player)
        {
            return false;
        }

        /**
         * Grant-time gate for the SSJ5 wish: true when it must be REFUSED (dragon unspent), after telling the player
         * why. Keyless: always refused.
         */
        default boolean ssj5WishBlocked(ServerPlayer player)
        {
            return true;
        }

        /**
         * A counted wish on an Earth or Namek set pushed the player past the ritual threshold ({@code newCount} is
         * the new total). Keyless: no-op.
         */
        default void onWishCounted(ServerPlayer player, String set, int newCount)
        {
        }

        /** The player used a Shenron / Porunga idol: open the recreation prompt. Keyless: no-op. */
        default void openIdolPrompt(ServerPlayer player)
        {
        }

        /** The player confirmed the recreation prompt. Returns whether it happened. Keyless: false. */
        default boolean confirmRecreation(ServerPlayer player)
        {
            return false;
        }
    }

    /** The keyless default until the key installs its own. Never null. */
    private static volatile Impl impl = new Impl() {};

    /** Install the key's implementation and mark the feature. Called once from {@code RagnarokKeyMod}. */
    public static void install(Impl i)
    {
        if (i == null)
            return;
        impl = i;
        KeyFeatures.mark(FEATURE_ID);
    }

    /** The live implementation (never null: the keyless default until the key installs). */
    public static Impl get()
    {
        return impl;
    }

    /** Whether the rituals are live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
