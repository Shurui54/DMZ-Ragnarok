package net.shurui.shuruisutilities.cosmetics.wardrobe;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * One counter on one instance: how many, and which generation of the count that is.
 *
 * <h2>READ THIS BEFORE SIMPLIFYING THE MERGE. A COUNTER IS NOT A SET.</h2>
 * Everything else in the cosmetic ledger merges newest-stamp-wins, per row, and that is CORRECT there: an
 * ownership ledger is a grow-only set with tombstones, where the newest fact about a row is the true one. See
 * {@link CosmeticLedgerData}'s class note.
 *
 * <p>A counter is not that. Stamp-merging a counter can roll it BACKWARDS, and here is exactly how:
 *
 * <ol>
 *   <li>A player is on shard A. Their Super hat reaches 412 kills. The row is stamped with A's clock.</li>
 *   <li>They hop to shard B, whose clock runs a few seconds fast. The count is carried and adopted at 412.</li>
 *   <li>Shard A republishes its own copy of that row through the periodic state sync. A's copy still says 412,
 *       which is fine, but a stale copy from BEFORE the last flush, say 389, is equally possible: the flush
 *       cadence and the sync cadence are different clocks and nothing lines them up.</li>
 *   <li>If B's clock skew makes that republished stamp look NEWER, a stamp merge takes the whole row, and the
 *       player's count silently drops from 412 to 389.</li>
 * </ol>
 *
 * <p>Nobody would ever be able to reproduce that on demand, and the only symptom is a number going down, which
 * reads to a player as the server eating their progress. There is also no safe direction to guess in: the whole
 * point of a Strange counter is that it only ever goes up.
 *
 * <h2>The rule: generation first, then value. Never a clock.</h2>
 * <pre>
 *   if      (incoming.generation &gt; local.generation) take incoming
 *   else if (incoming.generation == local.generation) take max(incoming.n, local.n)
 *   else                                             keep local
 * </pre>
 *
 * <p>This is the standard grow-only counter merge, with a generation added so that a DELIBERATE reset is still
 * expressible. It is:
 *
 * <ul>
 *   <li><b>Idempotent.</b> Merging the same value twice changes nothing the second time, so the periodic state
 *       sync and the per-player vault snapshot can both feed it without a second sync path.</li>
 *   <li><b>Commutative and associative.</b> The order in which three shards' copies arrive cannot change the
 *       answer, which is what lets a hop, a republish and a reconnect interleave freely.</li>
 *   <li><b>Monotonic under a single writer.</b> {@code PlayerVault} guarantees exactly one server holds a player
 *       at a time, so exactly one process is incrementing, and max() of a set of values one writer produced is
 *       always the latest of them.</li>
 *   <li><b>Immune to clock skew, because it never reads a clock.</b> That is the property being bought here.</li>
 * </ul>
 *
 * <h2>generation moves only on a deliberate reset</h2>
 * An ordinary increment NEVER touches it. An admin resetting a counter bumps it, which is what makes the reset
 * survive a merge against a sibling shard still holding the old high value: a higher generation wins outright,
 * whatever the numbers say. If generation were also bumped on increments, max() would stop applying and the whole
 * merge would collapse back into last-write-wins.
 *
 * <p>Nothing increments a counter yet. The record exists now so the counting milestone has somewhere to write and
 * so the merge rule is in the data model before any data exists to migrate.
 */
public final class CosmeticCounter
{
    /** The count. Monotonic within one generation. */
    public long n;

    /**
     * Which generation of this count we are on. Bumped ONLY on a deliberate reset. See the class note.
     *
     * <p>An int rather than a long because a reset is an admin action, and a server that has reset one counter
     * two billion times has a different problem.
     */
    public int generation;

    public CosmeticCounter()
    {
    }

    public CosmeticCounter(long n, int generation)
    {
        this.n = n;
        this.generation = generation;
    }

    public CosmeticCounter copy()
    {
        return new CosmeticCounter(n, generation);
    }

    /** Add to the count. Never touches {@link #generation}: see the class note on why that matters. */
    public void add(long amount)
    {
        if (amount <= 0L)
            return;
        // Saturating rather than wrapping. A counter that wrapped to a negative would look like a rollback, which
        // is the exact failure this whole class exists to rule out.
        long next = n + amount;
        n = next < n ? Long.MAX_VALUE : next;
    }

    /** A deliberate reset: back to zero, in a NEW generation, so the reset beats a sibling's old high value. */
    public void reset()
    {
        n = 0L;
        generation++;
    }

    /**
     * Merge two counters. Higher generation wins; at equal generation the higher count wins. See the class note.
     *
     * <p>Static and null tolerant so a caller does not have to branch on "have I seen this tracker before". Never
     * mutates either argument: the result is a fresh value, which is what keeps this safe to call from a merge
     * walking a store somebody else owns.
     */
    public static CosmeticCounter merge(CosmeticCounter local, CosmeticCounter incoming)
    {
        if (local == null)
            return incoming == null ? new CosmeticCounter() : incoming.copy();
        if (incoming == null)
            return local.copy();
        if (incoming.generation > local.generation)
            return incoming.copy();
        if (incoming.generation < local.generation)
            return local.copy();
        return new CosmeticCounter(Math.max(local.n, incoming.n), local.generation);
    }

    /** Whether two counters hold the same value, so a merge can tell whether it actually changed anything. */
    public boolean sameAs(CosmeticCounter other)
    {
        return other != null && other.n == n && other.generation == generation;
    }

    /** Deterministic field order, so the shard sync's content hash only moves on a real change. */
    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        t.putLong("n", n);
        t.putInt("gen", generation);
        return t;
    }

    public static CosmeticCounter fromNbt(CompoundTag t)
    {
        if (t == null)
            return new CosmeticCounter();
        return new CosmeticCounter(Math.max(0L, t.getLong("n")), Math.max(0, t.getInt("gen")));
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarLong(n);
        buf.writeVarInt(generation);
    }

    public static CosmeticCounter decode(FriendlyByteBuf buf)
    {
        return new CosmeticCounter(buf.readVarLong(), buf.readVarInt());
    }
}
