package net.shurui.shuruisutilities.api.key;

import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE Zeni economy (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps
 * {@code economy.EconomyManager} as a facade with its public statics unchanged (every caller, and the reflective
 * lookup in the dungeons module, keeps working), plus {@code PacketZeniSync}, the client zeni pill and
 * {@code api.economy}. The ledger, the module, the commands, the CustomNPCs shop and fast-travel fee, the
 * {@code su:cfg_economy} state sync and the hub rows live in the key.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false, every balance reads 0,
 * nothing can be spent, credits are dropped, and nothing is loaded or written. The files under
 * {@code ShuruisUtilities/economy/} are never touched keyless, so a keyed server reads them again unchanged.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class EconomyHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "economy";

    private EconomyHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the economy is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** The player's balance. Keyless: 0. */
        default long balance(UUID player)
        {
            return 0L;
        }

        /** Whether the player holds at least {@code amount}. Keyless: false. */
        default boolean has(UUID player, long amount)
        {
            return false;
        }

        /** Atomic spend if affordable. Keyless: false, nothing debited. */
        default boolean trySpend(UUID player, long amount)
        {
            return false;
        }

        /** An earned credit (counts toward an {@code earn_zeni} task); returns the new balance. Keyless: 0, no-op. */
        default long earn(UUID player, long amount)
        {
            return 0L;
        }

        /** A plain wallet move (credit, or debit with a negative delta); returns the new balance. Keyless: 0, no-op. */
        default long add(UUID player, long delta)
        {
            return 0L;
        }

        /** Set the balance. Keyless: no-op. */
        default void set(UUID player, long amount)
        {
        }

        /** Every known balance (the network's when sharded). Keyless: empty. */
        default Map<UUID, Long> allBalances()
        {
            return Map.of();
        }

        /** The balances in this server's own file, ignoring any shared wallet. Keyless: empty. */
        default Map<UUID, Long> allBalancesLocal()
        {
            return Map.of();
        }

        /** The currency's display name. Keyless: "Zeni". */
        default String currencyName()
        {
            return "Zeni";
        }

        /** The balance an unseen player starts with. Keyless: 0. */
        default long startingBalance()
        {
            return 0L;
        }

        /** An amount with the currency name, e.g. "1,000 Zeni". Keyless: {@code "%,d Zeni"}. */
        default String format(long amount)
        {
            return String.format("%,d %s", amount, "Zeni");
        }

        /** The currency config snapshot for the shard state sync. Keyless: an empty tag. */
        default CompoundTag saveState()
        {
            return new CompoundTag();
        }

        /** Adopt a sibling server's currency config. Keyless: no-op. */
        default void mergeState(CompoundTag tag)
        {
        }

        /** Load balances and config from disk. Keyless: no-op (nothing is read or created). */
        default void load()
        {
        }

        /** Write the balances file. Keyless: no-op. */
        default void save()
        {
        }

        /** Write the currency config file. Keyless: no-op. */
        default void saveConfig()
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

    /** Whether the economy is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
