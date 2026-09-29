package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.HashMap;
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
 * Who owns which cosmetics. The ledger.
 *
 * <h2>DO NOT RENAME THIS CLASS, AND DO NOT RENAME {@link #NAME}</h2>
 * The SavedData file on disk is named by {@link #NAME}, which is a stable id we control. Renaming it orphans
 * every purchase on every live server with no error anywhere. The class name matters too, because a future
 * contributor reaching for {@code DataManager} instead would land on {@code DataManager.getTypePath}, which names
 * a folder after the Java simple class name. Neither name is free to change. This holds real-money purchases, so
 * the cost of getting it wrong is not a lost preference, it is a refund.
 *
 * <h2>The merge is a grow-only set with tombstones, not last write wins</h2>
 * Rows are keyed by {@link CosmeticOwnership#instanceId} and merged per ROW by {@link CosmeticOwnership#stamp}.
 * That matters: {@code ShardPayload}'s own JavaDoc says the ledgers (zeni, ranks, permissions, guilds) are
 * deliberately not carried back and forth because a whole-store swap makes the last server to quit the winner,
 * and a last-write-wins merge on a SET of owned items either loses a grant or resurrects a revoked one. Keyed per
 * row, neither can happen: two shards granting different cosmetics to the same player keep both (the two rows are
 * never in the same comparison), a replayed grant collapses onto the same instance id, and a revocation carries
 * because it is a newer stamp on the row rather than its absence.
 *
 * <p>That property is what lets the SAME merge function be fed from two transports safely: the periodic
 * {@code ShardStateSync} pass and the per-player snapshot {@code ShardPayload} carries with a hopping player.
 * Both call {@link #mergeInto}, both are idempotent and commutative, so they converge on the same rows instead of
 * fighting. See {@code ShardPayload}'s cosmetics key.
 *
 * <h2>COUNTERS ARE MERGED OUTSIDE THE STAMP COMPARISON. THIS IS NOT AN OVERSIGHT.</h2>
 * The stamp merge above is correct for a SET with tombstones and WRONG for a counter, because a stale republish
 * from a shard whose clock runs fast carries a newer stamp and an older count and would roll the counter
 * backwards. {@link #mergeInto} therefore does two different things to one row: the scalar fields take the newer
 * stamp exactly as they always have, and {@link CosmeticOwnership#counts} merges by higher generation then
 * higher value, on BOTH sides of the stamp comparison, because a row that loses on stamp can still be carrying
 * the higher count. The full argument, and the failure it rules out, is in {@link CosmeticCounter}. Do not
 * "simplify" the two back into one.
 *
 * <p>A row whose {@link CosmeticOwnership#catalogId} names a definition that no longer exists is KEPT. An admin
 * tidying the catalogue must not be able to destroy somebody's purchase, and a definition recreated under the
 * same id should find everybody still owning it. Such a row simply does not appear in the wardrobe.
 */
public final class CosmeticLedgerData extends SavedData
{
    // NOTE: never rename (SavedData file name is a stable id). See the class note.
    private static final String NAME = "shuruisutilities_cosmetic_ledger";

    /**
     * Cross-shard state sync change signal: a monotonic counter bumped on every mutation. NEVER reset (unlike
     * SavedData's own dirty flag, which the autosave clears), so ShardStateSync can skip rebuilding this store's
     * NBT while it has not moved and can never miss a change. See ShardStateSync.register.
     */
    private long shardDirtyVersion;

    @Override
    public void setDirty()
    {
        shardDirtyVersion++;
        super.setDirty();
    }

    public long shardDirtyVersion()
    {
        return shardDirtyVersion;
    }

    /** Every row ever minted here or merged in, live and revoked alike, keyed by instance id. */
    private final Map<UUID, CosmeticOwnership> rows = new LinkedHashMap<>();

    public static CosmeticLedgerData get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(CosmeticLedgerData::load, CosmeticLedgerData::new, NAME);
    }

    private static CosmeticLedgerData load(CompoundTag tag)
    {
        CosmeticLedgerData d = new CosmeticLedgerData();
        ListTag list = tag.getList("rows", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CosmeticOwnership row = CosmeticOwnership.fromNbt(list.getCompound(i));
            if (row != null && row.instanceId != null)
                d.rows.put(row.instanceId, row);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        tag.put("rows", rowsTag());
        return tag;
    }

    /**
     * Every row as NBT, in instance-id order.
     *
     * <p>Sorted so two shards holding the same ledger produce byte-identical NBT whatever order the rows were
     * inserted in. Without it the state sync's content hash would differ between converged servers and they would
     * republish the same state forever, which is the trap {@code TaskPool.saveState} records.
     */
    private ListTag rowsTag()
    {
        List<UUID> ordered = new ArrayList<>(rows.keySet());
        ordered.sort(java.util.Comparator.comparing(UUID::toString));
        ListTag list = new ListTag();
        for (UUID id : ordered)
            list.add(rows.get(id).toNbt());
        return list;
    }

    /** The whole ledger as one tag, for the shard state sync. */
    public CompoundTag saveState()
    {
        CompoundTag t = new CompoundTag();
        t.put("rows", rowsTag());
        return t;
    }

    /**
     * Only ONE player's rows, for the snapshot that travels with a hopping player.
     *
     * <p>Revoked rows are included on purpose: a revocation that did not travel would be undone the moment the
     * player's old shard published its copy of the live row.
     */
    public CompoundTag saveStateFor(UUID player)
    {
        CompoundTag t = new CompoundTag();
        ListTag list = new ListTag();
        if (player != null)
        {
            List<UUID> ordered = new ArrayList<>();
            for (Map.Entry<UUID, CosmeticOwnership> e : rows.entrySet())
                if (player.equals(e.getValue().player))
                    ordered.add(e.getKey());
            ordered.sort(java.util.Comparator.comparing(UUID::toString));
            for (UUID id : ordered)
                list.add(rows.get(id).toNbt());
        }
        t.put("rows", list);
        return t;
    }

    /**
     * Adopt a sibling server's rows. Per ROW, newer stamp wins, and a row we have never seen is always added.
     *
     * <p>Idempotent and commutative, which is what lets the periodic state sync and the per-player vault
     * snapshot both call this without a second sync path fighting the first. Replaying the same tag twice changes
     * nothing on the second pass.
     */
    public void mergeInto(CompoundTag tag)
    {
        if (tag == null)
            return;
        boolean changed = false;
        ListTag list = tag.getList("rows", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CosmeticOwnership incoming = CosmeticOwnership.fromNbt(list.getCompound(i));
            if (incoming == null || incoming.instanceId == null)
                continue;
            CosmeticOwnership local = rows.get(incoming.instanceId);
            // A row we have never seen is ADOPTED whatever its stamp. A grant must never be lost by arriving from
            // a server whose clock happens to read earlier than ours; only a row we already hold is a comparison.
            if (local == null)
            {
                rows.put(incoming.instanceId, incoming);
                changed = true;
                continue;
            }
            if (incoming.stamp > local.stamp)
            {
                // The incoming row wins on the SCALARS. Its counters are folded together with ours FIRST, before
                // it replaces us, or the swap would throw away any counter we held that the sender had not seen.
                // That is the case the class note warns about: newer does not mean higher.
                incoming.mergeCountsFrom(local);
                rows.put(incoming.instanceId, incoming);
                changed = true;
            }
            // The stamp comparison decided the scalars and nothing else. A row that LOST it can still carry a
            // higher count, so the counters are merged either way, by generation then by value, never by clock.
            else if (local.mergeCountsFrom(incoming))
            {
                changed = true;
            }
        }
        if (changed)
            setDirty();
    }

    /** The row for this instance, or null. A COPY: never mutate a ledger row in place from outside. */
    public CosmeticOwnership row(UUID instanceId)
    {
        CosmeticOwnership r = instanceId == null ? null : rows.get(instanceId);
        return r == null ? null : r.copy();
    }

    // ---------------------------------------------------------------- the token escrow, minting and redemption

    /**
     * Mint this copy into a token: mark the SINGLE live row escrowed, so it leaves the wardrobe but stays the
     * authority a {@code CosmeticTokenItem} points back at. Returns true when it flipped.
     *
     * <p>Refuses, changing nothing, unless the row is live, owned by {@code expectedOwner}, not already a token and
     * not bound. Not bound is checked here as well as at the definition, because a shop-bought copy is bound per
     * copy whatever the type says, and a bound copy must never become a tradeable item. The stamp is bumped so the
     * flip carries in the per-row merge like any other change.
     */
    public boolean escrow(UUID instanceId, UUID expectedOwner)
    {
        CosmeticOwnership r = instanceId == null ? null : rows.get(instanceId);
        if (r == null || !r.live() || r.escrowed || r.bound)
            return false;
        if (expectedOwner != null && !expectedOwner.equals(r.player))
            return false;
        r.escrowed = true;
        r.stamp = System.currentTimeMillis();
        setDirty();
        return true;
    }

    /**
     * Redeem a token back into the wardrobe: move the escrowed row to {@code newOwner} and clear the escrow, all on
     * the ONE row the token names. Returns the instance on success, or null when there was nothing to redeem.
     *
     * <p>The single-row transfer is the whole no-dupe story. A second redemption of a copy of the same token finds
     * the row no longer escrowed and returns null, so the item is spent without granting anything. Two redemptions
     * racing on two shards each move the same instance to a different owner and clear the escrow; the per-row stamp
     * merge in {@link #mergeInto} then keeps exactly one of those owners, so the network converges on a single live
     * copy rather than two. There is never a fresh instance for the merge to fail to reconcile.
     *
     * <p>Idempotent for the SAME redeemer: a row already redeemed to {@code newOwner} (a replayed use, a doubled
     * packet) returns the instance again and changes nothing, so the caller may consume the item once and not fear
     * a second grant.
     */
    public UUID redeem(UUID instanceId, UUID newOwner)
    {
        CosmeticOwnership r = instanceId == null ? null : rows.get(instanceId);
        if (r == null || r.revoked || newOwner == null)
            return null;
        if (!r.escrowed)
            // Already redeemed. Idempotent only when it is the same owner claiming it again; anyone else is a spent
            // copy and gets nothing.
            return newOwner.equals(r.player) ? r.instanceId : null;
        r.player = newOwner;
        r.escrowed = false;
        r.source = "redeem";
        r.stamp = System.currentTimeMillis();
        setDirty();
        return r.instanceId;
    }

    /** Whether the row this instance names is a live outstanding token (the gate's authority). */
    public boolean isEscrowed(UUID instanceId)
    {
        CosmeticOwnership r = instanceId == null ? null : rows.get(instanceId);
        return r != null && r.live() && r.escrowed;
    }

    /**
     * Record a grant. Returns the row as stored.
     *
     * <p>Idempotent on {@code instanceId}: granting the same instance twice keeps the first row untouched and
     * returns it, which is what makes a replayed webhook, a retried command and a doubled packet all safe. That
     * is the {@code INSERT IGNORE} discipline {@code ShardPatreonGrants} uses, for the same reason: the failure
     * being ruled out is silently dropping or doubling somebody's paid reward.
     */
    public CosmeticOwnership grant(UUID instanceId, UUID player, String catalogId, String source)
    {
        return grant(instanceId, player, catalogId, source, CosmeticQuality.NORMAL, "", "", false);
    }

    /**
     * Record a grant of a particular QUALITY. Returns the row as stored.
     *
     * <p>Same idempotence: granting the same instance twice keeps the first row and returns it, so a replayed
     * crate roll cannot upgrade a copy by arriving a second time. The quality, the rolled effect, the pool it
     * came from and the bound flag are all decided once, by whatever minted the instance, and are not editable
     * afterwards by an ordinary path.
     *
     * @param quality      what this copy is, never what the definition allows
     * @param effectId     the rolled effect, blank unless MAGIC
     * @param effectPoolId which pool it was rolled from, HISTORY, blank when nothing rolled it
     * @param bound        true for a shop purchase, whatever the definition's tradeable flag says
     */
    public CosmeticOwnership grant(UUID instanceId, UUID player, String catalogId, String source,
            CosmeticQuality quality, String effectId, String effectPoolId, boolean bound)
    {
        if (instanceId == null || player == null || catalogId == null || catalogId.isBlank())
            return null;
        CosmeticOwnership existing = rows.get(instanceId);
        if (existing != null)
            return existing.copy();
        CosmeticOwnership row = new CosmeticOwnership(instanceId, player, catalogId, source,
                System.currentTimeMillis());
        row.quality = quality == null ? CosmeticQuality.NORMAL : quality;
        row.effectId = effectId == null ? "" : effectId.trim();
        row.effectPoolId = effectPoolId == null ? "" : effectPoolId.trim();
        row.bound = bound;
        rows.put(instanceId, row);
        setDirty();
        return row.copy();
    }

    /**
     * ONE live instance of this cosmetic held by this player, preferring the BEST copy they own.
     *
     * <p>Best means the highest {@link CosmeticQuality#rank}, tie-broken by the earliest grant and then by
     * instance id. Both tie-breaks exist so the answer is DETERMINISTIC: two shards asked the same question
     * about the same player must pick the same copy, or hopping would silently swap which hat somebody is
     * wearing. Preferring the highest quality is the answer a player expects when nothing has asked them: if you
     * own a Magic copy and a plain one, putting the hat on means putting the Magic one on.
     *
     * <p>The wardrobe screen now carries a per-copy picker, so this is the DEFAULT rather than the whole of the
     * choice: it answers "put the hat on" when nobody named a copy, and a named copy goes through
     * {@code WardrobeManager.equip(player, slot, catalogId, instanceId)} instead. It is stated here rather than
     * being an accident of map iteration order because the two paths have to agree about which copy is "the"
     * copy, on every shard.
     */
    public UUID bestInstance(UUID player, String catalogId)
    {
        if (player == null || catalogId == null)
            return null;
        CosmeticOwnership best = null;
        for (CosmeticOwnership row : rows.values())
        {
            if (!row.live() || row.escrowed || !player.equals(row.player) || !catalogId.equals(row.catalogId))
                continue;
            if (best == null || betterThan(row, best))
                best = row;
        }
        return best == null ? null : best.instanceId;
    }

    private static boolean betterThan(CosmeticOwnership candidate, CosmeticOwnership incumbent)
    {
        int a = candidate.quality == null ? 0 : candidate.quality.rank;
        int b = incumbent.quality == null ? 0 : incumbent.quality.rank;
        if (a != b)
            return a > b;
        if (candidate.grantedAt != incumbent.grantedAt)
            return candidate.grantedAt < incumbent.grantedAt;
        return candidate.instanceId.toString().compareTo(incumbent.instanceId.toString()) < 0;
    }

    /**
     * Revoke one instance. Returns true if a live row was revoked.
     *
     * <p>A tombstone, never a delete. See the class note.
     */
    public boolean revoke(UUID instanceId)
    {
        CosmeticOwnership row = instanceId == null ? null : rows.get(instanceId);
        if (row == null || row.revoked)
            return false;
        row.revoked = true;
        row.stamp = System.currentTimeMillis();
        setDirty();
        return true;
    }

    /**
     * Lift the tombstone on one revoked instance. Returns true if a revoked row was made live again.
     *
     * <p>For an ENTITLEMENT row only (a Patreon cosmetic whose supporter pledged again, see {@code PatreonWardrobe}),
     * whose instance id is deterministic and so cannot be granted a second time. A fresh stamp carries the restore
     * through {@link #mergeInto} exactly as a revocation is carried, so it is a deliberate newer state, never a
     * resurrection by absence.
     */
    public boolean restore(UUID instanceId)
    {
        CosmeticOwnership row = instanceId == null ? null : rows.get(instanceId);
        if (row == null || !row.revoked)
            return false;
        row.revoked = false;
        row.stamp = System.currentTimeMillis();
        setDirty();
        return true;
    }

    /**
     * Revoke every live row this player holds of one cosmetic. Returns how many were revoked.
     *
     * <p>Every row rather than one, because an admin asking to take a cosmetic away means all of it: leaving a
     * second copy behind would look like the command had failed.
     */
    public int revokeAll(UUID player, String catalogId)
    {
        if (player == null || catalogId == null)
            return 0;
        int n = 0;
        long now = System.currentTimeMillis();
        for (CosmeticOwnership row : rows.values())
        {
            if (row.revoked || !player.equals(row.player) || !catalogId.equals(row.catalogId))
                continue;
            row.revoked = true;
            row.stamp = now;
            n++;
        }
        if (n > 0)
            setDirty();
        return n;
    }

    /** Whether this player holds at least one live row of this cosmetic. The ownership question. */
    public boolean owns(UUID player, String catalogId)
    {
        if (player == null || catalogId == null)
            return false;
        for (CosmeticOwnership row : rows.values())
            if (row.live() && !row.escrowed && player.equals(row.player) && catalogId.equals(row.catalogId))
                return true;
        return false;
    }

    /** One live instance id of this cosmetic held by this player, or null. */
    public UUID anyInstance(UUID player, String catalogId)
    {
        if (player == null || catalogId == null)
            return null;
        for (CosmeticOwnership row : rows.values())
            if (row.live() && !row.escrowed && player.equals(row.player) && catalogId.equals(row.catalogId))
                return row.instanceId;
        return null;
    }

    /**
     * The distinct catalogue ids this player owns, in grant order.
     *
     * <p>Distinct because the wardrobe is a list of what you may wear, and two copies of the same hat are one
     * entry there. The rows themselves stay separate: duplicates matter to trading, not to dressing.
     */
    public Set<String> ownedIds(UUID player)
    {
        Set<String> out = new LinkedHashSet<>();
        if (player == null)
            return out;
        List<CosmeticOwnership> mine = new ArrayList<>();
        for (CosmeticOwnership row : rows.values())
            if (row.live() && !row.escrowed && player.equals(row.player))
                mine.add(row);
        mine.sort(java.util.Comparator.comparingLong(r -> r.grantedAt));
        for (CosmeticOwnership row : mine)
            out.add(row.catalogId);
        return out;
    }

    /** The distinct catalogue ids of every live row, any player, granted from this source. */
    public Set<String> liveCatalogIdsFromSource(String source)
    {
        Set<String> out = new LinkedHashSet<>();
        if (source == null)
            return out;
        for (CosmeticOwnership row : rows.values())
            if (row.live() && source.equals(row.source))
                out.add(row.catalogId);
        return out;
    }

    /** Every live row this player holds, as copies. */
    public List<CosmeticOwnership> rowsOf(UUID player)
    {
        List<CosmeticOwnership> out = new ArrayList<>();
        if (player == null)
            return out;
        for (CosmeticOwnership row : rows.values())
            if (row.live() && !row.escrowed && player.equals(row.player))
                out.add(row.copy());
        out.sort(java.util.Comparator.comparingLong(r -> r.grantedAt));
        return out;
    }

    /** How many rows are held in total, live and revoked, for a diagnostic line. */
    public Map<String, Integer> counts()
    {
        Map<String, Integer> out = new HashMap<>();
        int live = 0;
        for (CosmeticOwnership row : rows.values())
            if (row.live())
                live++;
        out.put("live", live);
        out.put("total", rows.size());
        return out;
    }
}
