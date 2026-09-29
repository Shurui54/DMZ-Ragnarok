package net.shurui.shuruisutilities.economy;

import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

import net.shurui.shuruisutilities.api.key.EconomyHooks;

/**
 * The Zeni economy FACADE. The economy is private: its ledger (balances in economy/balances.json, config in
 * economy.json), module, commands and shard sync live in the Ragnarok Key, reached through {@link EconomyHooks}.
 *
 * <p>This class keeps its name and every public static so the many callers (auction, trade, guilds, tasks, NPC
 * regions, the shard tables, the key's own features) compile unchanged, and so the dungeons module's reflective
 * lookup of {@code net.shurui.shuruisutilities.economy.EconomyManager#earn(UUID, long)} keeps resolving. It holds no
 * logic: without the key every call answers with the keyless defaults documented on {@link EconomyHooks.Impl}
 * (balance 0, nothing spendable, credits dropped, nothing read or written).
 */
public final class EconomyManager
{
    private EconomyManager() {}

    public static String currencyName()
    {
        return EconomyHooks.get().currencyName();
    }

    public static long startingBalance()
    {
        return EconomyHooks.get().startingBalance();
    }

    /** Persist just the currency config (currencyName + startingBalance) to economy.json. */
    public static void saveConfig()
    {
        EconomyHooks.get().saveConfig();
    }

    /** Snapshot the currency config for {@code ShardStateSync}. */
    public static CompoundTag saveState()
    {
        return EconomyHooks.get().saveState();
    }

    /** Adopt a sibling server's currency config and persist it here. */
    public static void mergeState(CompoundTag t)
    {
        EconomyHooks.get().mergeState(t);
    }

    // e.g. "1,000 Zeni"
    public static String format(long amount)
    {
        return EconomyHooks.get().format(amount);
    }

    public static long getBalance(UUID player)
    {
        return EconomyHooks.get().balance(player);
    }

    public static void setBalance(UUID player, long amount)
    {
        EconomyHooks.get().set(player, amount);
    }

    /** Atomic credit (or debit with a negative delta); returns the new balance. */
    public static long add(UUID player, long delta)
    {
        return EconomyHooks.get().add(player, delta);
    }

    /**
     * A credit the player EARNED (a shop sale, a kill reward, a crate, a wish, a task payout): same wallet arithmetic
     * as {@link #add}, and it also counts toward an accepted {@code earn_zeni} task. Refunds, {@code /pay}, trade
     * stakes, auction claims and guild bank withdrawals keep using {@link #add}.
     */
    public static long earn(UUID player, long amount)
    {
        return EconomyHooks.get().earn(player, amount);
    }

    /** Atomic spend if affordable: true and debited, or false and untouched. */
    public static boolean trySpend(UUID player, long amount)
    {
        return EconomyHooks.get().trySpend(player, amount);
    }

    public static boolean has(UUID player, long amount)
    {
        return EconomyHooks.get().has(player, amount);
    }

    /** Unmodifiable snapshot of all known balances, for /baltop. */
    public static Map<UUID, Long> allBalances()
    {
        return EconomyHooks.get().allBalances();
    }

    /** The balances held in THIS server's own json file, ignoring any shared wallet (the one-time shard carry over). */
    public static Map<UUID, Long> allBalancesLocal()
    {
        return EconomyHooks.get().allBalancesLocal();
    }

    public static void load()
    {
        EconomyHooks.get().load();
    }

    public static void save()
    {
        EconomyHooks.get().save();
    }
}
