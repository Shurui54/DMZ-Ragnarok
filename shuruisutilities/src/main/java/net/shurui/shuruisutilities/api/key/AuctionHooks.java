package net.shurui.shuruisutilities.api.key;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE auction house (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps the
 * auction block and its item (registry), {@code PacketOpenAuction} (59) and the client screen, the listing and claim
 * DTOs and {@code AuctionStore} (SavedData {@code shuruisutilities_auction}). {@code ShardAuction}, the module, the
 * server front ({@code AuctionServer}), {@code /auctionhouse} and the hub row live in the key; the hub row answers the
 * screen's {@code PacketEditorAction}s through the hub row registry, so no packet handler is needed here.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false and an open request (the
 * auction block, a CustomNPCs auctioneer) is ignored. The stored listings and claims are never read or written
 * keyless, so a keyed server reads them again unchanged.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class AuctionHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "auction";

    private AuctionHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the auction house is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** Open the auction house for this player (it re-checks eligibility itself). Keyless: ignored. */
        default void open(ServerPlayer player)
        {
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

    /** Whether the auction house is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
