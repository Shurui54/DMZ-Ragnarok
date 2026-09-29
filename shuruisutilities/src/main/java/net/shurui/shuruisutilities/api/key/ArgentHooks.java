package net.shurui.shuruisutilities.api.key;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE cosmetic shard currency ("argent" in code; logic in the Ragnarok Key,
 * {@code dmz_ragnarok_key}). Core keeps the balances ({@code ArgentLedgerData}, SavedData name unchanged), the
 * {@code ArgentEntry} and {@code ArgentResult} records and the HUD packet ({@code PacketShardSync}). The shared
 * database table ({@code ShardArgent}, since Sh2), the "CosmeticShards" module, {@code /shards}, the currency front
 * door, the audit trail, the grant journal and the balance push live in the key, and only the key's cosmetic shop
 * spends through them, so core has nothing to call here beyond the presence answer.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false. Keyless, no balance is read
 * or written, no journal is replayed and no HUD balance is sent, exactly as before the move (the module was torn down
 * keyless and {@code ArgentCurrency.active()} was false).
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class ArgentHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "shards";

    private ArgentHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the shard currency is live (the key installed it). Keyless: false. */
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

    /** Whether the shard currency is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
