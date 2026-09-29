package net.shurui.shuruisutilities.cosmetics.argent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The shard balances and the applied transaction keys, for a server that is NOT on a network.
 *
 * <h2>DO NOT RENAME THIS CLASS, AND DO NOT RENAME {@link #NAME}</h2>
 * The SavedData file on disk is named by {@link #NAME}, a stable id we control. Renaming it orphans every
 * purchase on the server with no error anywhere. The class name matters too, because a future contributor
 * reaching for {@code DataManager} instead would land on {@code DataManager.getTypePath}, which names a folder
 * after the Java simple class name. Neither name is free to change. This holds real-money purchases, so getting
 * it wrong costs a refund, not a preference.
 *
 * <h2>This is the FALLBACK, and it is deliberately never merged</h2>
 * When the shard layer is configured, the balance lives in one row in one database ({@code ShardArgent}) and
 * this class is not consulted at all. It exists so singleplayer, LAN and a single dedicated server still have a
 * working currency without a MariaDB.
 *
 * <p>It is NOT carried in {@code ShardPayload} and NOT published to {@code ShardStateSync}, and that is the
 * point rather than an omission. {@code CosmeticLedgerData} can travel because ownership is a set of per-row
 * stamped tombstones whose merge is idempotent and commutative. A balance is one number: any merge rule for two
 * numbers either loses a grant or invents one, and clock skew decides which. So there is no merge. On a network
 * this store is unused and has nothing to carry; off a network there is nowhere to carry it to.
 *
 * <h2>The applied set is as important as the balance</h2>
 * {@link #appliedTxns} is what refuses a replayed store delivery. It is kept for ever and never pruned: a key
 * dropped after a year is a key that can be replayed after a year. A few dozen bytes per purchase is nothing
 * next to that.
 *
 * <p>Every method here runs on the server thread, which is what makes the claim-then-credit sequence atomic
 * without a lock.
 */
public final class ArgentLedgerData extends SavedData
{
    // NOTE: never rename (SavedData file name is a stable id). See the class note.
    private static final String NAME = "shuruisutilities_shard_currency";

    /**
     * The largest balance either store holds (a data-integrity clamp, not a game-balance cap). The shared table's
     * {@code ShardArgent.MAX_BALANCE} is this same value, so the local and network ledgers always clamp alike.
     */
    public static final long MAX_BALANCE = 1_000_000_000_000L;

    /** Player to balance. Absent means zero; a zero balance is not stored. */
    private final Map<UUID, Long> balances = new LinkedHashMap<>();

    /** Every idempotency key ever applied here. Never pruned. See the class note. */
    private final Set<String> appliedTxns = new LinkedHashSet<>();

    public static ArgentLedgerData get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(ArgentLedgerData::load, ArgentLedgerData::new, NAME);
    }

    private static ArgentLedgerData load(CompoundTag tag)
    {
        ArgentLedgerData d = new ArgentLedgerData();
        ListTag list = tag.getList("balances", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag row = list.getCompound(i);
            if (!row.hasUUID("player"))
                continue;
            long value = row.getLong("balance");
            if (value > 0L)
                d.balances.put(row.getUUID("player"), Math.min(MAX_BALANCE, value));
        }
        ListTag txns = tag.getList("applied", Tag.TAG_STRING);
        for (int i = 0; i < txns.size(); i++)
        {
            String txn = txns.getString(i);
            if (txn != null && !txn.isBlank())
                d.appliedTxns.add(txn);
        }
        return d;
    }

    /**
     * Written in sorted order so the same ledger always produces byte-identical NBT.
     *
     * <p>Nothing hashes this store today, because it is never published anywhere. It is sorted anyway so that if
     * somebody later diffs two backups, or compares a copied world, the difference they see is a real one.
     */
    @Override
    public CompoundTag save(CompoundTag tag)
    {
        List<UUID> ordered = new ArrayList<>(balances.keySet());
        ordered.sort(java.util.Comparator.comparing(UUID::toString));
        ListTag list = new ListTag();
        for (UUID id : ordered)
        {
            Long value = balances.get(id);
            if (value == null || value <= 0L)
                continue;
            CompoundTag row = new CompoundTag();
            row.putUUID("player", id);
            row.putLong("balance", value);
            list.add(row);
        }
        tag.put("balances", list);

        List<String> keys = new ArrayList<>(appliedTxns);
        Collections.sort(keys);
        ListTag txns = new ListTag();
        for (String key : keys)
            txns.add(net.minecraft.nbt.StringTag.valueOf(key));
        tag.put("applied", txns);
        return tag;
    }

    public long balance(UUID player)
    {
        if (player == null)
            return 0L;
        Long value = balances.get(player);
        return value == null ? 0L : value;
    }

    public boolean applied(String txn)
    {
        return txn != null && appliedTxns.contains(txn);
    }

    /**
     * Apply a keyed change exactly once. The local twin of {@code ShardArgent.applyKeyed}.
     *
     * <p>The key is checked and recorded in the same server-thread call as the balance move, so there is no
     * window in which one exists without the other. A replay finds the key present and returns
     * {@link ArgentResult.Status#DUPLICATE} having changed nothing. There is no partial-failure case to reason
     * about here: either the whole method ran or the process died, and a process that died wrote neither, since
     * SavedData is only serialised on a save.
     */
    public ArgentResult applyKeyed(String txn, UUID player, long delta)
    {
        if (txn == null || txn.isBlank() || player == null || delta == 0L)
            return ArgentResult.refused();
        if (appliedTxns.contains(txn))
            return ArgentResult.duplicate(balance(player));
        appliedTxns.add(txn);
        long before = balance(player);
        long after = clamp(before + delta);
        put(player, after);
        setDirty();
        return ArgentResult.applied(after - before, after, Math.abs(delta) - Math.abs(after - before));
    }

    /** Take the amount only if it is there. Nothing is taken when it is not. */
    public ArgentResult trySpend(UUID player, long amount)
    {
        if (player == null || amount <= 0L)
            return ArgentResult.refused();
        long before = balance(player);
        if (before < amount)
            return ArgentResult.insufficient(before);
        long after = before - amount;
        put(player, after);
        setDirty();
        return ArgentResult.applied(-amount, after, 0L);
    }

    /** An unconditional clamped credit, for putting back what a failed purchase took. */
    public ArgentResult refund(UUID player, long amount)
    {
        if (player == null || amount <= 0L)
            return ArgentResult.refused();
        long before = balance(player);
        long after = clamp(before + amount);
        put(player, after);
        setDirty();
        return ArgentResult.applied(after - before, after, 0L);
    }

    private void put(UUID player, long value)
    {
        if (value <= 0L)
            balances.remove(player);
        else
            balances.put(player, value);
    }

    private static long clamp(long value)
    {
        if (value < 0L)
            return 0L;
        return Math.min(MAX_BALANCE, value);
    }

    /** Every non-zero balance, for the one-time carry into a shared table and for an operator overview. */
    public Map<UUID, Long> allBalances()
    {
        return Collections.unmodifiableMap(new LinkedHashMap<>(balances));
    }

    /** Every applied key, for the one-time carry into a shared table. See the class note on why it must travel. */
    public Set<String> allAppliedTxns()
    {
        return Collections.unmodifiableSet(new LinkedHashSet<>(appliedTxns));
    }
}
