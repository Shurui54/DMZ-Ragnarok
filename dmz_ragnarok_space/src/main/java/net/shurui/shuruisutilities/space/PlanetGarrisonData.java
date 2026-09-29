package net.shurui.shuruisutilities.space;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Overworld-attached saved data for a wild planet's visible GARRISON (see {@link PlanetGarrison}). Follows the exact
 * idiom of {@link GeneratedPlanetClaims} (and, in the guild package, GuildRaidSpoils / GuildRaidLockouts): persisted
 * under a fixed name on the overworld data storage, keyed by the planet's STABLE id, never by an entity.
 *
 * <p>This is the ONE authoritative home for "does this planet still have living defenders", and it is deliberately
 * independent of whether any defender entity is loaded RIGHT NOW. Defenders are surface Mobs with no chunk tickets, so
 * they evaporate from memory whenever nobody is on the planet; if "are any defender entities loaded" were the signal, a
 * planet would read as cleared the moment its chunks unloaded and a guild could claim it without a fight. So a garrison
 * is tracked by IDENTITY: the full roster of spawned defender UUIDs is persisted here, and a defender only counts as
 * beaten once its DEATH is observed ({@link #markDefeated}). A chunk-unload never touches the defeated set, so an
 * unloaded-but-alive defender still blocks the claim.
 *
 * <p>Per planet we store: which {@link PlanetGarrisonRoster.Family} garrisons it, the roster of defender UUIDs, the set
 * already defeated, the clash TOUGHNESS the defenders set for the world, and a RECOMMENDED battle power figure for
 * display. Presence of a record means the planet has been POPULATED (its defenders were rolled once), so a later visit
 * never re-rolls; an empty roster is a legitimate populated state (defenders disabled at spawn time, or every spawn
 * failed), and it reads as immediately claimable, which is the whole "a broken defender system never makes a planet
 * permanently unclaimable" guarantee.
 */
public final class PlanetGarrisonData extends SavedData
{
    private static final String NAME = "shuruisutilities_planet_garrison";

    /** One planet's garrison record. Mutable in place; the map holds it and {@link #setDirty()} persists changes. */
    private static final class Record
    {
        String family;
        final Set<UUID> roster = new HashSet<>();
        final Set<UUID> defeated = new HashSet<>();
        double toughness;
        double recommendedBattlePower;
    }

    // planet id -> its garrison record. Tiny: one entry per planet a player has ever landed on and populated.
    private final Map<String, Record> records = new HashMap<>();

    // planet id -> the packed chunk keys of the NEUTRAL settlements already populated on it (see PlanetGarrison's
    // per-settlement neutral path). This is a SEPARATE store from the hostile records above and never touches the
    // roster/defeat/claim machinery: a neutral saiyan is not a defender, so it is tracked purely so each settlement
    // spawns its crowd exactly once and killed inhabitants never respawn. Keyed by StructureStart chunk (ChunkPos.toLong),
    // which is stable per settlement. Grows only as players actually visit settlements, so it stays small.
    private final Map<String, Set<Long>> neutralSettlements = new HashMap<>();

    public static PlanetGarrisonData get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(PlanetGarrisonData::load, PlanetGarrisonData::new, NAME);
    }

    private static PlanetGarrisonData load(CompoundTag tag)
    {
        PlanetGarrisonData s = new PlanetGarrisonData();
        ListTag list = tag.getList("garrisons", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag c = list.getCompound(i);
            Record r = new Record();
            r.family = c.getString("family");
            r.toughness = c.getDouble("toughness");
            r.recommendedBattlePower = c.getDouble("recommendedBp");
            readUuids(c.getList("roster", Tag.TAG_STRING), r.roster);
            readUuids(c.getList("defeated", Tag.TAG_STRING), r.defeated);
            s.records.put(c.getString("planet"), r);
        }
        ListTag settlementList = tag.getList("neutralSettlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < settlementList.size(); i++)
        {
            CompoundTag c = settlementList.getCompound(i);
            Set<Long> keys = new HashSet<>();
            for (long key : c.getLongArray("chunks"))
            {
                keys.add(key);
            }
            s.neutralSettlements.put(c.getString("planet"), keys);
        }
        return s;
    }

    private static void readUuids(ListTag list, Set<UUID> into)
    {
        for (int i = 0; i < list.size(); i++)
        {
            try
            {
                into.add(UUID.fromString(list.getString(i)));
            }
            catch (IllegalArgumentException ignored)
            {
                // a malformed uuid string (hand-edited save): drop it rather than fail the whole load.
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        ListTag list = new ListTag();
        for (Map.Entry<String, Record> e : records.entrySet())
        {
            Record r = e.getValue();
            CompoundTag c = new CompoundTag();
            c.putString("planet", e.getKey());
            c.putString("family", r.family == null ? "" : r.family);
            c.putDouble("toughness", r.toughness);
            c.putDouble("recommendedBp", r.recommendedBattlePower);
            c.put("roster", writeUuids(r.roster));
            c.put("defeated", writeUuids(r.defeated));
            list.add(c);
        }
        tag.put("garrisons", list);

        ListTag settlementList = new ListTag();
        for (Map.Entry<String, Set<Long>> e : neutralSettlements.entrySet())
        {
            CompoundTag c = new CompoundTag();
            c.putString("planet", e.getKey());
            long[] keys = new long[e.getValue().size()];
            int i = 0;
            for (long key : e.getValue())
            {
                keys[i++] = key;
            }
            c.putLongArray("chunks", keys);
            settlementList.add(c);
        }
        tag.put("neutralSettlements", settlementList);
        return tag;
    }

    private static ListTag writeUuids(Set<UUID> uuids)
    {
        ListTag list = new ListTag();
        for (UUID id : uuids)
        {
            list.add(StringTag.valueOf(id.toString()));
        }
        return list;
    }

    /** Whether this planet's garrison has already been rolled (so a revisit never re-populates it). */
    public boolean isPopulated(String planetId)
    {
        return records.containsKey(planetId);
    }

    /**
     * Record a freshly-populated garrison: the chosen family, the roster of UUIDs that actually spawned alive (may be
     * empty), the clash toughness the defenders set, and the recommended battle power. Overwrites any prior record for
     * the id (a re-population after a destroy that cleared the old one).
     */
    public void markPopulated(String planetId, String family, Set<UUID> roster, double toughness,
                              double recommendedBattlePower)
    {
        Record r = new Record();
        r.family = family;
        r.roster.addAll(roster);
        r.toughness = toughness;
        r.recommendedBattlePower = recommendedBattlePower;
        records.put(planetId, r);
        setDirty();
    }

    /**
     * Whether this planet's neutral settlement at the given packed chunk key has already had its saiyan crowd spawned,
     * so a revisit never re-populates it and killed inhabitants never respawn. Independent of the hostile roster above.
     */
    public boolean isSettlementPopulated(String planetId, long chunkKey)
    {
        Set<Long> keys = neutralSettlements.get(planetId);
        return keys != null && keys.contains(chunkKey);
    }

    /** Record a neutral settlement as populated. Idempotent; does NOT touch any roster/defeat/claim state. */
    public void markSettlementPopulated(String planetId, long chunkKey)
    {
        if (neutralSettlements.computeIfAbsent(planetId, k -> new HashSet<>()).add(chunkKey))
        {
            setDirty();
        }
    }

    /** How many of this planet's defenders are not yet defeated. 0 for an unpopulated planet or a fully-cleared one. */
    public int remaining(String planetId)
    {
        Record r = records.get(planetId);
        if (r == null)
        {
            return 0;
        }
        int count = 0;
        for (UUID id : r.roster)
        {
            if (!r.defeated.contains(id))
            {
                count++;
            }
        }
        return count;
    }

    /**
     * Mark one defender defeated by UUID (called when its death is observed). Only counts if the UUID is part of this
     * planet's roster, so a stray marker can never over-count. No-op if already defeated or unknown.
     */
    public void markDefeated(String planetId, UUID defenderId)
    {
        Record r = records.get(planetId);
        if (r == null || !r.roster.contains(defenderId))
        {
            return;
        }
        if (r.defeated.add(defenderId))
        {
            setDirty();
        }
    }

    /**
     * Force the whole roster to defeated. Used only by the claim gate's live-scan reconciliation: if the planet is
     * populated with a non-zero remaining count yet a full scan of the loaded surface finds NO living defender for it,
     * the garrison is genuinely gone (a defender vanished without a death event) and this clears the phantom so the
     * planet is claimable rather than stuck. Never called on a planet with a live defender still standing.
     */
    public void markAllDefeated(String planetId)
    {
        Record r = records.get(planetId);
        if (r == null)
        {
            return;
        }
        if (r.defeated.addAll(r.roster))
        {
            setDirty();
        }
    }

    /** The clash toughness the defenders set for this planet, or 0 if unpopulated. Read by {@link PlanetToughness}. */
    public double toughness(String planetId)
    {
        Record r = records.get(planetId);
        return r == null ? 0.0 : r.toughness;
    }

    /** The recommended battle power figure for this planet (strongest defender), or 0 if unpopulated. For display. */
    public double recommendedBattlePower(String planetId)
    {
        Record r = records.get(planetId);
        return r == null ? 0.0 : r.recommendedBattlePower;
    }

    /**
     * The stored garrison {@link PlanetGarrisonRoster.Family} name for this planet, or "" if the planet is unpopulated
     * or was populated with no defenders (an empty roster stores an empty family). Read-only display helper for the
     * planet-info GUI; the roster/defeat counts come from {@link #remaining}/{@link #roster}.
     */
    public String family(String planetId)
    {
        Record r = records.get(planetId);
        return r == null || r.family == null ? "" : r.family;
    }

    /** A read-only copy of this planet's full defender roster (all UUIDs, defeated or not), or an empty set. */
    public Set<UUID> roster(String planetId)
    {
        Record r = records.get(planetId);
        return r == null ? Set.of() : new HashSet<>(r.roster);
    }

    /** Drop a planet's garrison record entirely (on claim, or on destruction). No-op if absent. */
    public void clear(String planetId)
    {
        if (records.remove(planetId) != null)
        {
            setDirty();
        }
    }
}
