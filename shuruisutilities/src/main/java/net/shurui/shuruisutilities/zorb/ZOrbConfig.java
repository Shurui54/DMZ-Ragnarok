package net.shurui.shuruisutilities.zorb;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Per-region Z orb settings. Held as {@link net.shurui.shuruisutilities.npcregion.NpcRegion#zorbs}, so a region
 * that has never been configured leaves that field {@code null} and Gson omits it entirely (the region JSON stays
 * byte-identical to a pre-Z-orb save). Only when an admin opts in does this object exist.
 *
 * <p>Plain fields (primitives, enums, strings and a list of {@link ZOrbItemEntry}) so it rides both the region
 * JSON (Gson, and thus the shard NPC-region sync for free) and the editor packets with no adapter. All defaults
 * match the Z orb plan.
 */
public class ZOrbConfig
{
    /** Opt-in: a fresh config is OFF, so merely opening the editor never starts spawns. */
    public boolean enabled = false;

    // Chain shape and length.
    public int chainMin = 5;
    public int chainMax = 12;
    /** Stored by name; see {@link ZOrbShape}. */
    public String shape = ZOrbShape.TRAIL.name();
    /** Blocks between successive orbs. */
    public double spacing = 2.5;
    /** Maximum heading change, in degrees, applied each step for the TRAIL / ARC shapes. */
    public double headingDriftDeg = 25.0;

    // Spawn scheduling (a region's own timer, burning only while an eligible anchor is in range).
    public int intervalMinSec = 60;
    public int intervalMaxSec = 180;
    /** Most live chains this ONE region may hold at once. */
    public int maxChains = 2;
    /** Stored by name; see {@link ZOrbTimeOfDay}. */
    public String timeOfDay = ZOrbTimeOfDay.ANY.name();
    /** Chain start distance from the anchoring player, clamped to the region's placement radius. */
    public int minDist = 10;
    public int maxDist = 32;

    // Kind weighting and the last-orb rules.
    public int kindWeightTp = 60;
    public int kindWeightZeni = 40;
    /** Chance the LAST orb is an item orb (when the pool is non-empty), else it pays the chain kind x completion. */
    public int itemChancePct = 35;
    /** Multiplier applied to the LAST orb's payout. */
    public double completionMultiplier = 3.0;

    // Reward ranges (BEFORE any multiplier). TP is int, zeni is long.
    public int tpMin = 5;
    public int tpMax = 20;
    public long zeniMin = 2;
    public long zeniMax = 8;
    /** When true, TP is additionally scaled by the region's existing level falloff (20% floor). */
    public boolean applyRegionTpFalloff = true;
    /** When true, TP is shared with the claimant's party (off by owner decision). */
    public boolean shareTpWithParty = false;

    // Pickup / claim behaviour.
    /** Only the next orb in order is collectible; others are dimmed until it is taken. */
    public boolean strictOrder = true;
    /** Seconds after each pickup during which only the claimant may continue the chain. */
    public int claimLockSec = 15;

    // Despawn timers.
    /** Seconds an unclaimed chain lives before it pops paying nothing. */
    public int lifetimeSec = 60;
    /** Seconds after the last pickup a claimed chain waits before the remaining orbs pop paying nothing. */
    public int progressTimeoutSec = 20;

    /** The weighted item pool. Empty means the last orb always pays the chain kind x completion. */
    public List<ZOrbItemEntry> itemPool = new ArrayList<>();

    public ZOrbConfig() {}

    /** Deep copy, for the editor's local edits before a save. */
    public ZOrbConfig copy()
    {
        ZOrbConfig c = new ZOrbConfig();
        c.enabled = enabled;
        c.chainMin = chainMin;
        c.chainMax = chainMax;
        c.shape = shape;
        c.spacing = spacing;
        c.headingDriftDeg = headingDriftDeg;
        c.intervalMinSec = intervalMinSec;
        c.intervalMaxSec = intervalMaxSec;
        c.maxChains = maxChains;
        c.timeOfDay = timeOfDay;
        c.minDist = minDist;
        c.maxDist = maxDist;
        c.kindWeightTp = kindWeightTp;
        c.kindWeightZeni = kindWeightZeni;
        c.itemChancePct = itemChancePct;
        c.completionMultiplier = completionMultiplier;
        c.tpMin = tpMin;
        c.tpMax = tpMax;
        c.zeniMin = zeniMin;
        c.zeniMax = zeniMax;
        c.applyRegionTpFalloff = applyRegionTpFalloff;
        c.shareTpWithParty = shareTpWithParty;
        c.strictOrder = strictOrder;
        c.claimLockSec = claimLockSec;
        c.lifetimeSec = lifetimeSec;
        c.progressTimeoutSec = progressTimeoutSec;
        c.itemPool = new ArrayList<>();
        if (itemPool != null)
            for (ZOrbItemEntry e : itemPool)
                if (e != null)
                    c.itemPool.add(e.copy());
        return c;
    }

    /** Normalise every field to a sane, self-consistent value. Idempotent; never throws. */
    public void sanitize()
    {
        if (chainMin < 1)
            chainMin = 1;
        if (chainMax < chainMin)
            chainMax = chainMin;
        shape = ZOrbShape.byName(shape).name();
        if (!(spacing >= 0.5))
            spacing = 2.5;
        if (headingDriftDeg < 0)
            headingDriftDeg = 0;
        if (headingDriftDeg > 180)
            headingDriftDeg = 180;
        if (intervalMinSec < 1)
            intervalMinSec = 1;
        if (intervalMaxSec < intervalMinSec)
            intervalMaxSec = intervalMinSec;
        if (maxChains < 1)
            maxChains = 1;
        timeOfDay = ZOrbTimeOfDay.byName(timeOfDay).name();
        if (minDist < 1)
            minDist = 1;
        if (maxDist < minDist)
            maxDist = minDist;
        if (kindWeightTp < 0)
            kindWeightTp = 0;
        if (kindWeightZeni < 0)
            kindWeightZeni = 0;
        if (kindWeightTp == 0 && kindWeightZeni == 0)
            kindWeightTp = 1; // never leave a chain with no kind to pick
        if (itemChancePct < 0)
            itemChancePct = 0;
        if (itemChancePct > 100)
            itemChancePct = 100;
        if (!(completionMultiplier >= 1.0))
            completionMultiplier = 1.0;
        if (tpMin < 0)
            tpMin = 0;
        if (tpMax < tpMin)
            tpMax = tpMin;
        if (zeniMin < 0)
            zeniMin = 0;
        if (zeniMax < zeniMin)
            zeniMax = zeniMin;
        if (claimLockSec < 0)
            claimLockSec = 0;
        if (lifetimeSec < 1)
            lifetimeSec = 1;
        if (progressTimeoutSec < 1)
            progressTimeoutSec = 1;
        if (itemPool == null)
            itemPool = new ArrayList<>();
        itemPool.removeIf(e -> e == null);
        for (ZOrbItemEntry e : itemPool)
            e.sanitize();
    }

    /** The resolved shape enum (never null). */
    public ZOrbShape shape()
    {
        return ZOrbShape.byName(shape);
    }

    /** The resolved time-of-day enum (never null). */
    public ZOrbTimeOfDay timeOfDay()
    {
        return ZOrbTimeOfDay.byName(timeOfDay);
    }

    /** True when at least one pool entry names a registered item. */
    public boolean hasUsableItemPool()
    {
        if (itemPool == null)
            return false;
        for (ZOrbItemEntry e : itemPool)
            if (e != null && e.valid())
                return true;
        return false;
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(enabled);
        buf.writeVarInt(chainMin);
        buf.writeVarInt(chainMax);
        buf.writeUtf(shape == null ? ZOrbShape.TRAIL.name() : shape);
        buf.writeDouble(spacing);
        buf.writeDouble(headingDriftDeg);
        buf.writeVarInt(intervalMinSec);
        buf.writeVarInt(intervalMaxSec);
        buf.writeVarInt(maxChains);
        buf.writeUtf(timeOfDay == null ? ZOrbTimeOfDay.ANY.name() : timeOfDay);
        buf.writeVarInt(minDist);
        buf.writeVarInt(maxDist);
        buf.writeVarInt(kindWeightTp);
        buf.writeVarInt(kindWeightZeni);
        buf.writeVarInt(itemChancePct);
        buf.writeDouble(completionMultiplier);
        buf.writeVarInt(tpMin);
        buf.writeVarInt(tpMax);
        buf.writeVarLong(zeniMin);
        buf.writeVarLong(zeniMax);
        buf.writeBoolean(applyRegionTpFalloff);
        buf.writeBoolean(shareTpWithParty);
        buf.writeBoolean(strictOrder);
        buf.writeVarInt(claimLockSec);
        buf.writeVarInt(lifetimeSec);
        buf.writeVarInt(progressTimeoutSec);
        buf.writeVarInt(itemPool == null ? 0 : itemPool.size());
        if (itemPool != null)
            for (ZOrbItemEntry e : itemPool)
                e.encode(buf);
    }

    public static ZOrbConfig decode(FriendlyByteBuf buf)
    {
        ZOrbConfig c = new ZOrbConfig();
        c.enabled = buf.readBoolean();
        c.chainMin = buf.readVarInt();
        c.chainMax = buf.readVarInt();
        c.shape = buf.readUtf();
        c.spacing = buf.readDouble();
        c.headingDriftDeg = buf.readDouble();
        c.intervalMinSec = buf.readVarInt();
        c.intervalMaxSec = buf.readVarInt();
        c.maxChains = buf.readVarInt();
        c.timeOfDay = buf.readUtf();
        c.minDist = buf.readVarInt();
        c.maxDist = buf.readVarInt();
        c.kindWeightTp = buf.readVarInt();
        c.kindWeightZeni = buf.readVarInt();
        c.itemChancePct = buf.readVarInt();
        c.completionMultiplier = buf.readDouble();
        c.tpMin = buf.readVarInt();
        c.tpMax = buf.readVarInt();
        c.zeniMin = buf.readVarLong();
        c.zeniMax = buf.readVarLong();
        c.applyRegionTpFalloff = buf.readBoolean();
        c.shareTpWithParty = buf.readBoolean();
        c.strictOrder = buf.readBoolean();
        c.claimLockSec = buf.readVarInt();
        c.lifetimeSec = buf.readVarInt();
        c.progressTimeoutSec = buf.readVarInt();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            c.itemPool.add(ZOrbItemEntry.decode(buf));
        return c;
    }
}
