package net.shurui.shuruisutilities.api.key;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Presence of the PRIVATE small admin modules (S11), whose logic lives in the Ragnarok Key ({@code dmz_ragnarok_key}):
 * the {@code MultiworldV2} admin surface ({@code /mw}, {@code /mwtp}), {@code BanItem} (the ban list, its
 * enforcement, {@code /banitem} and the {@code su:banned_items} shard state, same {@code banned_items.json}),
 * {@code ScheduledRestart} (the timed restart and its countdown, same {@code ScheduledRestart.toml}) and
 * {@code Perftools} ({@code /perfstats}, {@code /chunkloaderlist} and the memory watchdog, same
 * {@code Perftools.toml}). Core calls none of them, so the hook only answers whether they are installed; the world
 * border and airdrops have their own hooks ({@link WorldBorderHooks}, {@link AirdropHooks}).
 *
 * <p>The {@link Impl} DEFAULT is the keyless behaviour: {@link #available()} is false. Keyless none of those modules
 * exists, and their files are neither read nor written. The multiworld ENGINE is not part of this: it stays in core
 * ({@code multiworld.v2.MultiworldEngine}) and runs keyless.
 */
public final class AdminHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "admin";

    private AdminHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the admin modules are installed (the key installed them). Keyless: false. */
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

    /** Whether the admin modules are installed on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
