package net.shurui.shuruisutilities.api.key;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE shrines (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps
 * {@code ShrinePlayerData} (the {@code su_shrine} buff timers in the player's persisted NBT, unchanged) and the
 * client editor screens. The module, {@code ShrineManager} (still {@code shrines.json}, and the right-click on a bound
 * block, which it handles on the Forge bus itself), {@code ConfigShrine} (still {@code Shrine.toml}), {@code /shrine}
 * and the hub rows live in the key, so core has nothing to call into beyond the presence answer.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false. Keyless no shrine is loaded,
 * a bound block is an ordinary block, and the shrine files and buff NBT are never touched.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class ShrineHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "shrine";

    private ShrineHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether shrines are live (the key installed them). Keyless: false. */
        default boolean available()
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

    /** Whether shrines are live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
