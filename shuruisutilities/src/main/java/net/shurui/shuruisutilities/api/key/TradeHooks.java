package net.shurui.shuruisutilities.api.key;

import java.util.UUID;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE player trade (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps
 * {@code TradeEscrow} (SavedData {@code shuruisutilities_trade_escrow}), {@code PacketOpenTrade} (60) and the client
 * screen. The cross-server {@code ShardTrade}, the module, {@code TradeManager}, {@code TradeSession},
 * {@code TradeZeniEscrow} (still {@code ShuruisUtilities/trade/zeni_escrow.json}), {@code /trade} and the hub row
 * live in the key; the hub row answers the screen's {@code PacketEditorAction}s through the hub row registry.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false and nobody is ever in a
 * same-server trade. Keyless the escrow files are never read or written, so a keyed server reads them again unchanged.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class TradeHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "trade";

    private TradeHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether trading is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** Whether this player is a side of a same-server trade, request or session (read by ShardTrade). Keyless: false. */
        default boolean inLocalTrade(UUID player)
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

    /** Whether trading is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
