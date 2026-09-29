package net.shurui.shuruisutilities.api.key;

import java.util.UUID;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE player bounties (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps
 * the PvP rule in protection that a large bounty forces PvP on. The shard tables and their poller (Sh2), the module, {@code BountyManager} (still
 * {@code bounties.json}), {@code BountyAllies} (still {@code bounty_allies.json}), {@code /bounty} and the hub row live
 * in the key.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false, no board is loaded, nobody is
 * bounty-forced into PvP and remote bounty or ally changes are dropped. Keyless the bounty files are never read or
 * written, so a keyed server reads them again unchanged.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class BountyHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "bounty";

    /**
     * How long after sharing a party two players still count as allies (three real days). A fixed rule, shared by the
     * key's ally check and core's shard prune of the ally table, so both always agree.
     */
    public static final long PARTY_WINDOW_MILLIS = 3L * 24L * 60L * 60L * 1000L;

    private BountyHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether bounties are live (the key installed them). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** Whether the bounty board is loaded on this server (the Bounty module is running). Keyless: false. */
        default boolean boardLoaded()
        {
            return false;
        }

        /** Whether a bounty pool on this player forces their PvP on. Keyless: false. */
        default boolean pvpForced(UUID player)
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

    /** Whether bounties are live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
