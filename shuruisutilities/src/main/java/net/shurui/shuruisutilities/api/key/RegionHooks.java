package net.shurui.shuruisutilities.api.key;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE region editing and enforcement (logic in the Ragnarok Key, {@code dmz_ragnarok_key}):
 * the {@code Regions} module, {@code /serverclaim}, the region editor hub rows, the event-mapped flag enforcement and
 * the residency effects (greeting, entry, heal, game mode...). Core keeps the region store and its queries
 * ({@code regions.RegionManager}, {@code regions.RegionEventHandler}), loads {@code regions.json} at every server start
 * and hooks DragonMineZ ki griefing ({@code regions.RegionEngine}), because public terrain regen and the world-flag
 * mixins read the flags on every server exactly as they did keyless before the move.
 *
 * <p>The {@link Impl} DEFAULT is the keyless behaviour: {@link Impl#available()} is false. Nothing in core needs more
 * from the key: every flag answer comes from the core store, keyed or not.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class RegionHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "regions";

    private RegionHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the module is live (the key installed it). Keyless: false. */
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

    /** Whether the module is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
