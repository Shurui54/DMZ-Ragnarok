package net.shurui.shuruisutilities.zorb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Server-wide Z orb settings, stored once in {@code <SUdir>/zorbs.json} (see {@link ZOrbGlobalStore}) and carried
 * across shards under {@code zorbs:globals}. As of Z5 this is the ONE config for the WORLD spawner: the open-world
 * dimensions orbs spawn in, the per-world cap and lifetime, chain length and shape, the ground/air split, the item
 * tail chance and pool, and the EXACT reward level scale. It is edited in one in-game menu ({@code ZOrbGlobalsScreen}
 * tabs Spawn / Chains / Rewards / Items). The older per-region {@link ZOrbConfig} is unchanged and legacy.
 *
 * <p>Plain fields so Gson writes the file. NEW fields default to the plan's values, so an older {@code zorbs.json}
 * that lacks them loads with the defaults (forward compatible). Keyless servers never read or write this.
 */
public class ZOrbGlobals
{
    // --- legacy per-chain / anti-farm globals (unchanged) ---
    public int maxChainsPerPlayer = 1;
    public int maxChainsServer = 40;
    public int afkSec = 120;
    public double chainSeparation = 8.0;
    public double despawnNoPlayerRadius = 64.0;
    public int despawnNoPlayerSec = 10;
    public int maxChainsPerHour = 20;
    public int maxTpPerDay = 0;
    public long maxZeniPerDay = 0;
    public int maxItemOrbsPerDay = 10;
    public String chimeSound = "minecraft:block.amethyst_block.chime";
    public String completionSound = "minecraft:ui.toast.challenge_complete";
    public List<String> dimensionBlacklist = new ArrayList<>();

    // --- Z5 WORLD SPAWNER ---
    /** Master switch for the world pool (private feature; on by default when the key is present). */
    public boolean worldSpawnerEnabled = true;
    /** Open-world dimensions trails may spawn in. Default-deny: only these ids, editable in game. */
    public List<String> openWorldDimensions = new ArrayList<>(Arrays.asList(
            "minecraft:overworld", "dragonminez:namek", "dmz_ragnarok:namekow", "dmz_ragnarok:kaiow"));
    /** Most trails alive in one dimension at once (the network-wide cap, read from the synced pool). */
    public int maxTrailsPerDimension = 40;
    /** Absolute ceiling across all dimensions, a safety cap on the whole pool. */
    public int maxTrailsServer = 200;
    /** Seconds an uncollected trail lives before it relocates to a fresh spot (keeps the pool topped up, moving). */
    public int trailLifetimeSec = 300;
    /** Seconds a collected (tombstoned) trail is kept so the claim propagates before it is GC'd. */
    public int tombstoneGraceSec = 30;
    /** Minimum spacing in blocks between two trail anchors in one dimension. */
    public double trailMinSeparation = 40.0;
    /** Closest a new trail seeds to a player; the far edge derives from the server view distance. */
    public int seedRingMin = 24;
    /** Maintenance cadence in ticks, and how many placement attempts per cycle (spread the cost). */
    public int maintCycleTicks = 20;
    public int maintPlacementsPerCycle = 2;

    // --- chain shape / placement ---
    public int chainMin = 5;
    public int chainMax = 15;
    public double spacing = 2.5;
    /** Stored by name; see {@link ZOrbShape}. */
    public String shape = ZOrbShape.TRAIL.name();
    public double headingDriftDeg = 25.0;
    /** Fraction of chains that are AIR chains (float above ground) rather than ground-hugging. */
    public double airChainFraction = 0.35;
    /** Air chains sit this many blocks above the dragon-ball ground Y (random in range). */
    public int airHeightMin = 10;
    public int airHeightMax = 50;
    /** Ground orbs sit this far above the dragon-ball ground cell (small, so they read like a scattered ball). */
    public double groundOrbHover = 0.5;
    /** The clearance cube side an orb needs entirely in air (covers the 25%-bigger last orb). */
    public double orbClearance = 1.0;
    /** How many blocks a blocked orb may be lifted before the column is given up. */
    public int maxClearanceLift = 4;
    /** Chain kind weighting (TP vs zeni); the last orb may instead be an item tail. */
    public int kindWeightTp = 60;
    public int kindWeightZeni = 40;
    /** Only the next orb in order is collectible. */
    public boolean strictOrder = true;
    /** Seconds after the last pickup a claimed chain waits before the remaining orbs pop paying nothing. */
    public int progressTimeoutSec = 20;

    // --- item tail ---
    /** Chance the LAST orb is an item orb (when the pool is non-empty). */
    public int worldItemChancePct = 25;
    /** The world item pool, edited in the same menu (item, count range, weight). */
    public List<ZOrbItemEntry> worldItemPool = new ArrayList<>();

    // --- EXACT reward level scale ---
    /** The upper anchor level. Rewards interpolate linearly from level 1 to here, clamped outside. */
    public int scaleMaxLevel = 100000;
    public int tpAtLevel1 = 5;
    public int tpAtMaxLevel = 20000;
    public long zeniAtLevel1 = 5;
    public long zeniAtMaxLevel = 50000;
    /** Multiplier applied to the LAST orb's payout (part of the configured reward). */
    public double completionMultiplier = 3.0;

    public ZOrbGlobals() {}

    public ZOrbGlobals copy()
    {
        ZOrbGlobals g = new ZOrbGlobals();
        g.maxChainsPerPlayer = maxChainsPerPlayer;
        g.maxChainsServer = maxChainsServer;
        g.afkSec = afkSec;
        g.chainSeparation = chainSeparation;
        g.despawnNoPlayerRadius = despawnNoPlayerRadius;
        g.despawnNoPlayerSec = despawnNoPlayerSec;
        g.maxChainsPerHour = maxChainsPerHour;
        g.maxTpPerDay = maxTpPerDay;
        g.maxZeniPerDay = maxZeniPerDay;
        g.maxItemOrbsPerDay = maxItemOrbsPerDay;
        g.chimeSound = chimeSound;
        g.completionSound = completionSound;
        g.dimensionBlacklist = dimensionBlacklist == null ? new ArrayList<>() : new ArrayList<>(dimensionBlacklist);
        g.worldSpawnerEnabled = worldSpawnerEnabled;
        g.openWorldDimensions = openWorldDimensions == null ? new ArrayList<>() : new ArrayList<>(openWorldDimensions);
        g.maxTrailsPerDimension = maxTrailsPerDimension;
        g.maxTrailsServer = maxTrailsServer;
        g.trailLifetimeSec = trailLifetimeSec;
        g.tombstoneGraceSec = tombstoneGraceSec;
        g.trailMinSeparation = trailMinSeparation;
        g.seedRingMin = seedRingMin;
        g.maintCycleTicks = maintCycleTicks;
        g.maintPlacementsPerCycle = maintPlacementsPerCycle;
        g.chainMin = chainMin;
        g.chainMax = chainMax;
        g.spacing = spacing;
        g.shape = shape;
        g.headingDriftDeg = headingDriftDeg;
        g.airChainFraction = airChainFraction;
        g.airHeightMin = airHeightMin;
        g.airHeightMax = airHeightMax;
        g.groundOrbHover = groundOrbHover;
        g.orbClearance = orbClearance;
        g.maxClearanceLift = maxClearanceLift;
        g.kindWeightTp = kindWeightTp;
        g.kindWeightZeni = kindWeightZeni;
        g.strictOrder = strictOrder;
        g.progressTimeoutSec = progressTimeoutSec;
        g.worldItemChancePct = worldItemChancePct;
        g.worldItemPool = new ArrayList<>();
        if (worldItemPool != null)
            for (ZOrbItemEntry e : worldItemPool)
                if (e != null)
                    g.worldItemPool.add(e.copy());
        g.scaleMaxLevel = scaleMaxLevel;
        g.tpAtLevel1 = tpAtLevel1;
        g.tpAtMaxLevel = tpAtMaxLevel;
        g.zeniAtLevel1 = zeniAtLevel1;
        g.zeniAtMaxLevel = zeniAtMaxLevel;
        g.completionMultiplier = completionMultiplier;
        return g;
    }

    public void sanitize()
    {
        if (maxChainsPerPlayer < 1) maxChainsPerPlayer = 1;
        if (maxChainsServer < 1) maxChainsServer = 1;
        if (afkSec < 0) afkSec = 0;
        if (!(chainSeparation >= 0)) chainSeparation = 0;
        if (!(despawnNoPlayerRadius >= 0)) despawnNoPlayerRadius = 0;
        if (despawnNoPlayerSec < 1) despawnNoPlayerSec = 1;
        if (maxChainsPerHour < 0) maxChainsPerHour = 0;
        if (maxTpPerDay < 0) maxTpPerDay = 0;
        if (maxZeniPerDay < 0) maxZeniPerDay = 0;
        if (maxItemOrbsPerDay < 0) maxItemOrbsPerDay = 0;
        if (chimeSound == null) chimeSound = "";
        if (completionSound == null) completionSound = "";
        if (dimensionBlacklist == null) dimensionBlacklist = new ArrayList<>();
        dimensionBlacklist.removeIf(s -> s == null || s.isBlank());

        if (openWorldDimensions == null) openWorldDimensions = new ArrayList<>();
        openWorldDimensions.removeIf(s -> s == null || s.isBlank());
        if (maxTrailsPerDimension < 0) maxTrailsPerDimension = 0;
        if (maxTrailsServer < 1) maxTrailsServer = 1;
        if (trailLifetimeSec < 5) trailLifetimeSec = 5;
        if (tombstoneGraceSec < 5) tombstoneGraceSec = 5;
        if (!(trailMinSeparation >= 0)) trailMinSeparation = 0;
        if (seedRingMin < 1) seedRingMin = 1;
        if (maintCycleTicks < 1) maintCycleTicks = 1;
        if (maintPlacementsPerCycle < 1) maintPlacementsPerCycle = 1;
        if (chainMin < 1) chainMin = 1;
        if (chainMax < chainMin) chainMax = chainMin;
        if (!(spacing >= 0.5)) spacing = 2.5;
        shape = ZOrbShape.byName(shape).name();
        if (headingDriftDeg < 0) headingDriftDeg = 0;
        if (headingDriftDeg > 180) headingDriftDeg = 180;
        if (airChainFraction < 0) airChainFraction = 0;
        if (airChainFraction > 1) airChainFraction = 1;
        if (airHeightMin < 0) airHeightMin = 0;
        if (airHeightMax < airHeightMin) airHeightMax = airHeightMin;
        if (!(groundOrbHover >= 0)) groundOrbHover = 0;
        if (!(orbClearance >= 0.2)) orbClearance = 0.2;
        if (maxClearanceLift < 0) maxClearanceLift = 0;
        if (kindWeightTp < 0) kindWeightTp = 0;
        if (kindWeightZeni < 0) kindWeightZeni = 0;
        if (kindWeightTp == 0 && kindWeightZeni == 0) kindWeightTp = 1;
        if (progressTimeoutSec < 1) progressTimeoutSec = 1;
        if (worldItemChancePct < 0) worldItemChancePct = 0;
        if (worldItemChancePct > 100) worldItemChancePct = 100;
        if (worldItemPool == null) worldItemPool = new ArrayList<>();
        worldItemPool.removeIf(e -> e == null);
        for (ZOrbItemEntry e : worldItemPool) e.sanitize();
        if (scaleMaxLevel < 2) scaleMaxLevel = 2;
        if (tpAtLevel1 < 0) tpAtLevel1 = 0;
        if (tpAtMaxLevel < 0) tpAtMaxLevel = 0;
        if (zeniAtLevel1 < 0) zeniAtLevel1 = 0;
        if (zeniAtMaxLevel < 0) zeniAtMaxLevel = 0;
        if (!(completionMultiplier >= 1.0)) completionMultiplier = 1.0;
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(maxChainsPerPlayer);
        buf.writeVarInt(maxChainsServer);
        buf.writeVarInt(afkSec);
        buf.writeDouble(chainSeparation);
        buf.writeDouble(despawnNoPlayerRadius);
        buf.writeVarInt(despawnNoPlayerSec);
        buf.writeVarInt(maxChainsPerHour);
        buf.writeVarInt(maxTpPerDay);
        buf.writeVarLong(maxZeniPerDay);
        buf.writeVarInt(maxItemOrbsPerDay);
        buf.writeUtf(chimeSound == null ? "" : chimeSound);
        buf.writeUtf(completionSound == null ? "" : completionSound);
        writeList(buf, dimensionBlacklist);

        buf.writeBoolean(worldSpawnerEnabled);
        writeList(buf, openWorldDimensions);
        buf.writeVarInt(maxTrailsPerDimension);
        buf.writeVarInt(maxTrailsServer);
        buf.writeVarInt(trailLifetimeSec);
        buf.writeVarInt(tombstoneGraceSec);
        buf.writeDouble(trailMinSeparation);
        buf.writeVarInt(seedRingMin);
        buf.writeVarInt(maintCycleTicks);
        buf.writeVarInt(maintPlacementsPerCycle);
        buf.writeVarInt(chainMin);
        buf.writeVarInt(chainMax);
        buf.writeDouble(spacing);
        buf.writeUtf(shape == null ? ZOrbShape.TRAIL.name() : shape);
        buf.writeDouble(headingDriftDeg);
        buf.writeDouble(airChainFraction);
        buf.writeVarInt(airHeightMin);
        buf.writeVarInt(airHeightMax);
        buf.writeDouble(groundOrbHover);
        buf.writeDouble(orbClearance);
        buf.writeVarInt(maxClearanceLift);
        buf.writeVarInt(kindWeightTp);
        buf.writeVarInt(kindWeightZeni);
        buf.writeBoolean(strictOrder);
        buf.writeVarInt(progressTimeoutSec);
        buf.writeVarInt(worldItemChancePct);
        buf.writeVarInt(worldItemPool == null ? 0 : worldItemPool.size());
        if (worldItemPool != null)
            for (ZOrbItemEntry e : worldItemPool)
                e.encode(buf);
        buf.writeVarInt(scaleMaxLevel);
        buf.writeVarInt(tpAtLevel1);
        buf.writeVarInt(tpAtMaxLevel);
        buf.writeVarLong(zeniAtLevel1);
        buf.writeVarLong(zeniAtMaxLevel);
        buf.writeDouble(completionMultiplier);
    }

    public static ZOrbGlobals decode(FriendlyByteBuf buf)
    {
        ZOrbGlobals g = new ZOrbGlobals();
        g.maxChainsPerPlayer = buf.readVarInt();
        g.maxChainsServer = buf.readVarInt();
        g.afkSec = buf.readVarInt();
        g.chainSeparation = buf.readDouble();
        g.despawnNoPlayerRadius = buf.readDouble();
        g.despawnNoPlayerSec = buf.readVarInt();
        g.maxChainsPerHour = buf.readVarInt();
        g.maxTpPerDay = buf.readVarInt();
        g.maxZeniPerDay = buf.readVarLong();
        g.maxItemOrbsPerDay = buf.readVarInt();
        g.chimeSound = buf.readUtf();
        g.completionSound = buf.readUtf();
        g.dimensionBlacklist = readList(buf);

        g.worldSpawnerEnabled = buf.readBoolean();
        g.openWorldDimensions = readList(buf);
        g.maxTrailsPerDimension = buf.readVarInt();
        g.maxTrailsServer = buf.readVarInt();
        g.trailLifetimeSec = buf.readVarInt();
        g.tombstoneGraceSec = buf.readVarInt();
        g.trailMinSeparation = buf.readDouble();
        g.seedRingMin = buf.readVarInt();
        g.maintCycleTicks = buf.readVarInt();
        g.maintPlacementsPerCycle = buf.readVarInt();
        g.chainMin = buf.readVarInt();
        g.chainMax = buf.readVarInt();
        g.spacing = buf.readDouble();
        g.shape = buf.readUtf();
        g.headingDriftDeg = buf.readDouble();
        g.airChainFraction = buf.readDouble();
        g.airHeightMin = buf.readVarInt();
        g.airHeightMax = buf.readVarInt();
        g.groundOrbHover = buf.readDouble();
        g.orbClearance = buf.readDouble();
        g.maxClearanceLift = buf.readVarInt();
        g.kindWeightTp = buf.readVarInt();
        g.kindWeightZeni = buf.readVarInt();
        g.strictOrder = buf.readBoolean();
        g.progressTimeoutSec = buf.readVarInt();
        g.worldItemChancePct = buf.readVarInt();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            g.worldItemPool.add(ZOrbItemEntry.decode(buf));
        g.scaleMaxLevel = buf.readVarInt();
        g.tpAtLevel1 = buf.readVarInt();
        g.tpAtMaxLevel = buf.readVarInt();
        g.zeniAtLevel1 = buf.readVarLong();
        g.zeniAtMaxLevel = buf.readVarLong();
        g.completionMultiplier = buf.readDouble();
        return g;
    }

    private static void writeList(FriendlyByteBuf buf, List<String> list)
    {
        List<String> l = list == null ? new ArrayList<>() : list;
        buf.writeVarInt(l.size());
        for (String s : l)
            buf.writeUtf(s == null ? "" : s);
    }

    private static List<String> readList(FriendlyByteBuf buf)
    {
        int n = buf.readVarInt();
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++)
            l.add(buf.readUtf());
        return l;
    }

    public ZOrbShape shape()
    {
        return ZOrbShape.byName(shape);
    }
}
