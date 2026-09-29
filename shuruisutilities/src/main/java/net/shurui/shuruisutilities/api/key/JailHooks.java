package net.shurui.shuruisutilities.api.key;

import java.util.Map;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.jail.JailPoint;

/**
 * Core-side hook for the PRIVATE jail (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps the persisted
 * pieces: {@code jail.JailPoint} (the DataManager folder {@code JailPoint}), {@code jail.JailData} (the
 * {@code su_jail} player NBT) and {@code jail.JailHandoff} (the {@code su_jail_handoff} NBT the shard transfer code
 * reads). The {@code Jail} module (containment, release, the cross-shard placement) and {@code /jail},
 * {@code /unjail}, {@code /setjail} live in the key.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false and no jail is listed. Keyless
 * the {@code JailPoint} files are never read or written, so a keyed server reads them again unchanged.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class JailHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "jail";

    private JailHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the jail is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** Every jail by name. Keyless: empty. */
        default Map<String, JailPoint> jails()
        {
            return Map.of();
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

    /** Whether the jail is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
