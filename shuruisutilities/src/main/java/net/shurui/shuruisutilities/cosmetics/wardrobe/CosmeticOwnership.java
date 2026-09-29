package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * One row of the ownership ledger: this player owns this cosmetic, as this instance, at this quality.
 *
 * <h2>Why an instance id and not just (player, cosmetic)</h2>
 * {@link #instanceId} is minted once, at the moment a cosmetic first comes into existence for somebody, and
 * never changes while it stays theirs. It is what makes every future grant, redemption and transfer IDEMPOTENT:
 * a row is keyed on the instance, so replaying a grant, a webhook retry, a shard merge arriving twice or a
 * duplicated token being redeemed twice all collapse onto the same row instead of creating a second one. This
 * codebase has had a fusion dupe; a set keyed only on (player, cosmetic) would have no way to tell a replay from
 * a second legitimate copy.
 *
 * <h2>The row is where two copies of one hat differ</h2>
 * {@link #quality}, {@link #effectId}, {@link #bound} and {@link #counts} are facts about THIS COPY, not about
 * the cosmetic. A Super Bat Hat and a Normal Bat Hat are the same {@link CosmeticDef} at two qualities, and the
 * definition only says which qualities are ELIGIBLE ({@link CosmeticDef#allowedQualities}). That split is why the
 * catalogue can be retuned without disturbing anybody's copy, and why a counter has somewhere to live at all: a
 * definition is shared by everybody and could not hold one person's number.
 *
 * <h2>Revocation is a tombstone, never a delete</h2>
 * A revoked row stays, with {@link #revoked} set and a fresh {@link #stamp}. A deleted row would be resurrected
 * by the next merge from a shard that still held it, which is the same reasoning {@code ShardConfigFiles} gives
 * for the tombstone discipline on its user lists and {@code TaskPool} gives for deleted tasks.
 *
 * <h2>Adding a field here is free, and the counts are the exception</h2>
 * Every field is keyed by NAME in NBT, so a row written before a field existed reads it as its default and needs
 * no migration. {@link #counts} is the one field that does not merge like the rest: see
 * {@link #mergeCountsFrom} and {@link CosmeticCounter}.
 *
 * <p>Plain-data object with explicit NBT, matching the shape the rest of the suite uses.
 */
public class CosmeticOwnership
{
    /** Primary key. Minted once, never reused. See the class note. */
    public UUID instanceId;

    /** Who owns it. CHANGES on a trade; the instance id does not. */
    public UUID player;

    /** Which {@link CosmeticDef#id} this is a copy of. */
    public String catalogId = "";

    /** When it was first granted, in epoch millis. Display only; ordering uses {@link #stamp}. */
    public long grantedAt;

    /**
     * Free text recording where it came from: {@code "admin"}, {@code "crate"}, {@code "shop"}, a task id.
     *
     * <p>Not a permission and never read by a gate. It exists so a support question ("where did this come
     * from") has an answer in the data rather than in a log file that has rotated away.
     */
    public String source = "";

    /** Revoked rather than deleted. See the class note. */
    public boolean revoked;

    /**
     * Last change to THIS row, in epoch millis, for the per-row merge.
     *
     * <p>Per row rather than per player, which is what makes the merge a grow-only set with tombstones: two
     * shards each granting a different cosmetic to the same player keep both, because the two grants are never
     * in the same comparison. A per-player last-write-wins merge would lose one of them.
     *
     * <p>Governs the SCALAR fields only. {@link #counts} is merged outside it, on purpose.
     */
    public long stamp;

    /**
     * How good THIS COPY is. Normal, Super or Magic. Default NORMAL.
     *
     * <p>Set once when the copy is minted and not edited afterwards by anything ordinary. The definition's
     * {@link CosmeticDef#allowedQualities} says what a roll was ALLOWED to produce; this says what it produced.
     */
    public CosmeticQuality quality = CosmeticQuality.NORMAL;

    /**
     * Which authored effect this copy rolled, blank unless {@link #quality} is MAGIC.
     *
     * <p>An id rather than an inline effect, so an admin retuning "Burning Flames" retunes it for everybody who
     * has it, exactly as retuning a definition does. Nothing rolls one yet and no effect record exists yet.
     */
    public String effectId = "";

    /**
     * Which pool this copy's effect was rolled FROM. HISTORY, and never consulted to decide anything.
     *
     * <p>Not the same field as {@link CosmeticDef#defaultEffectPoolId}, and the distinction matters enough to
     * say twice: the definition's pool is a DEFAULT for a roll that has not happened yet and an admin may change
     * it whenever they like, while this is a record of a roll that DID happen and must never change again. It is
     * what makes a "Series 1 Burning Flames" provably from series 1 even after the pool has been edited or
     * deleted, which is the one thing TF2's case series give a player that a mutable pool cannot.
     *
     * <p>Free to add now, a migration across every live ownership row later. That is the whole reason it is here
     * before anything rolls.
     */
    public String effectPoolId = "";

    /**
     * Whether THIS COPY may never move, whatever the catalogue says.
     *
     * <p>A SECOND default-deny on top of {@link CosmeticDef#tradeable}, not a replacement for it. The definition
     * answers "may this kind of cosmetic be traded at all"; this answers "may this particular copy". A shop
     * purchase sets it true even when the cosmetic type is tradeable, which is what stops the shop being an
     * infinite supply into the trade economy while a crate-won copy of the same hat stays tradeable.
     *
     * <p>{@link CosmeticTransferGate} is the only thing allowed to read it, together with {@code tradeable}.
     */
    public boolean bound;

    /**
     * Whether THIS COPY is currently held as a token item rather than in the wardrobe. Its authority, not the item's.
     *
     * <p>A tradeable copy has exactly two forms and is never in both at once: an ownership row you may wear, or a
     * {@code CosmeticTokenItem} you may trade, auction, mail or drop. Minting the token flips this true and the copy
     * leaves every wardrobe query while the SINGLE row stays live and owner-stamped; redeeming the token flips it
     * false and moves {@link #player} to the redeemer. Keeping it one row (rather than revoking here and granting a
     * fresh instance on redeem) is what makes a cross-shard double-redeem of two copies of one token CONVERGE on a
     * single owner through {@link CosmeticLedgerData#mergeInto} instead of leaving two live grants, which a
     * fresh-instance redeem could not avoid without a database compare-and-set.
     *
     * <p>Read only by {@link CosmeticTransferGate} (an outstanding token may move; a spent or forged one may not),
     * by the mint and redeem mutators on {@link CosmeticLedgerData}, and by the wardrobe queries that must not offer
     * or equip a copy that is presently an item. Nothing prices, rolls or grants on it.
     */
    public boolean escrowed;

    /**
     * Add-ons applied to this copy, in the order applied. Consumable parts that install an extra counter.
     *
     * <p>Order is kept because it is the order an owner applied them in, which is the order they expect to see
     * them listed. A set-add keyed by id, so a replayed apply finds the id already present and does nothing.
     * Nothing applies one yet.
     */
    public final List<String> addonIds = new ArrayList<>();

    /**
     * Counter values on this copy, keyed by {@link CosmeticTracker#id}.
     *
     * <p>DOES NOT MERGE LIKE THE REST OF THIS ROW. See {@link #mergeCountsFrom} and the long argument in
     * {@link CosmeticCounter}: a stamp merge can roll a counter backwards on clock skew, so counters merge by
     * generation then by value and never consult a clock.
     *
     * <p>Nothing increments one yet. The map exists so the counting milestone has somewhere to write and so the
     * merge rule is settled before there is any data to migrate.
     */
    public final Map<String, CosmeticCounter> counts = new LinkedHashMap<>();

    public CosmeticOwnership()
    {
    }

    public CosmeticOwnership(UUID instanceId, UUID player, String catalogId, String source, long now)
    {
        this.instanceId = instanceId;
        this.player = player;
        this.catalogId = catalogId == null ? "" : catalogId;
        this.source = source == null ? "" : source;
        this.grantedAt = now;
        this.stamp = now;
    }

    public CosmeticOwnership copy()
    {
        CosmeticOwnership c = new CosmeticOwnership(instanceId, player, catalogId, source, grantedAt);
        c.revoked = revoked;
        c.stamp = stamp;
        c.quality = quality == null ? CosmeticQuality.NORMAL : quality;
        c.effectId = effectId;
        c.effectPoolId = effectPoolId;
        c.bound = bound;
        c.escrowed = escrowed;
        c.addonIds.addAll(addonIds);
        for (Map.Entry<String, CosmeticCounter> e : counts.entrySet())
            if (e.getKey() != null && e.getValue() != null)
                c.counts.put(e.getKey(), e.getValue().copy());
        return c;
    }

    /** A row is usable only if it is intact and not revoked. */
    public boolean live()
    {
        return !revoked && instanceId != null && player != null && catalogId != null && !catalogId.isBlank();
    }

    /** The value of one counter, or 0 when this copy has never counted it. Never null. */
    public long count(String trackerId)
    {
        CosmeticCounter c = trackerId == null ? null : counts.get(trackerId);
        return c == null ? 0L : c.n;
    }

    /**
     * Merge another copy of THIS ROW's counters into ours, by the grow-only rule. Returns whether anything moved.
     *
     * <p>Called from both sides of {@link CosmeticLedgerData}'s stamp comparison, deliberately: a row that LOSES
     * the stamp comparison can still be carrying the higher count, and that count has to survive. See
     * {@link CosmeticCounter}'s class note for why this cannot be folded into the stamp merge.
     */
    public boolean mergeCountsFrom(CosmeticOwnership other)
    {
        if (other == null || other.counts.isEmpty())
            return false;
        boolean changed = false;
        for (Map.Entry<String, CosmeticCounter> e : other.counts.entrySet())
        {
            String trackerId = e.getKey();
            if (trackerId == null || trackerId.isBlank() || e.getValue() == null)
                continue;
            CosmeticCounter local = counts.get(trackerId);
            CosmeticCounter merged = CosmeticCounter.merge(local, e.getValue());
            if (local == null || !local.sameAs(merged))
            {
                counts.put(trackerId, merged);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Serialise to NBT. Deterministic: the add-on list keeps its order and the counters are written in sorted
     * tracker-id order, so two converged servers produce byte-identical NBT and the shard sync's content hash
     * only moves on a real change rather than on the iteration order of a map.
     *
     * <p>Blank and default-valued optional fields are omitted rather than written empty, for the same reason: a
     * plain Normal row written by two servers must not differ by a field one of them wrote as "".
     */
    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        if (instanceId != null)
            t.putUUID("instance", instanceId);
        if (player != null)
            t.putUUID("player", player);
        t.putString("catalogId", catalogId == null ? "" : catalogId);
        t.putLong("grantedAt", grantedAt);
        t.putString("source", source == null ? "" : source);
        t.putBoolean("revoked", revoked);
        t.putLong("stamp", stamp);
        t.putString("quality", (quality == null ? CosmeticQuality.NORMAL : quality).key);
        if (effectId != null && !effectId.isBlank())
            t.putString("effectId", effectId);
        if (effectPoolId != null && !effectPoolId.isBlank())
            t.putString("effectPoolId", effectPoolId);
        if (bound)
            t.putBoolean("bound", true);
        if (escrowed)
            t.putBoolean("escrowed", true);
        if (!addonIds.isEmpty())
        {
            ListTag addons = new ListTag();
            for (String a : addonIds)
                if (a != null && !a.isBlank())
                    addons.add(StringTag.valueOf(a));
            t.put("addons", addons);
        }
        if (!counts.isEmpty())
        {
            List<String> ordered = new ArrayList<>(counts.keySet());
            java.util.Collections.sort(ordered);
            ListTag list = new ListTag();
            for (String trackerId : ordered)
            {
                CosmeticCounter c = counts.get(trackerId);
                if (trackerId == null || trackerId.isBlank() || c == null)
                    continue;
                CompoundTag e = c.toNbt();
                e.putString("id", trackerId);
                list.add(e);
            }
            if (!list.isEmpty())
                t.put("counts", list);
        }
        return t;
    }

    /** Null when the tag carries no instance or player id, which is the only shape that cannot be repaired. */
    public static CosmeticOwnership fromNbt(CompoundTag t)
    {
        if (t == null || !t.hasUUID("instance") || !t.hasUUID("player"))
            return null;
        CosmeticOwnership c = new CosmeticOwnership();
        c.instanceId = t.getUUID("instance");
        c.player = t.getUUID("player");
        c.catalogId = t.getString("catalogId");
        c.grantedAt = t.getLong("grantedAt");
        c.source = t.getString("source");
        c.revoked = t.getBoolean("revoked");
        c.stamp = t.getLong("stamp");
        // Every field below is read by NAME with a default, so a row written before this milestone loads as a
        // plain untradeable Normal copy with no counters rather than failing.
        c.quality = CosmeticQuality.byKey(t.getString("quality"));
        c.effectId = t.getString("effectId");
        c.effectPoolId = t.getString("effectPoolId");
        c.bound = t.getBoolean("bound");
        c.escrowed = t.getBoolean("escrowed");
        ListTag addons = t.getList("addons", Tag.TAG_STRING);
        for (int i = 0; i < addons.size(); i++)
        {
            String a = addons.getString(i);
            if (a != null && !a.isBlank() && !c.addonIds.contains(a))
                c.addonIds.add(a);
        }
        ListTag list = t.getList("counts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag e = list.getCompound(i);
            String trackerId = e.getString("id");
            if (trackerId != null && !trackerId.isBlank())
                c.counts.put(trackerId, CosmeticCounter.fromNbt(e));
        }
        return c;
    }
}
