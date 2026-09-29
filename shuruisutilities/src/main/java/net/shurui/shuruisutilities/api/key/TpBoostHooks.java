package net.shurui.shuruisutilities.api.key;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE global TP boost (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps the
 * sdu {@code GlobalTpBoostEffect} (the visible pip, a registry entry) and the stats-screen TP multiplier push in
 * {@code PrestigeManager}, which reads the live window through {@link Impl#globalFactor()}. The module,
 * {@code TpBoostState} (still {@code tpboost.json}, the {@code tpboost.global} shard state and the TP-gain multiply,
 * which it registers on the Forge bus itself, shrine TP buffs included) and {@code /tpboost} live in the key.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false and the global factor is 1.0
 * (no window). Keyless {@code tpboost.json} is never read or written.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class TpBoostHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "tpboost";

    private TpBoostHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the TP boost is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** The live global {@code /tpboost} window as a multiplicative TP factor (1.0 = none). Keyless: 1.0. */
        default double globalFactor()
        {
            return 1.0;
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

    /** Whether the TP boost is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
