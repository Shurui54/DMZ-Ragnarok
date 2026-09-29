package net.shurui.shuruisutilities.space;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Overworld-attached saved data for the Black Star ("apophis") dragon-ball feature. Mirrors the suite's SavedData idiom
 * ({@link GeneratedPlanetClaims}, {@link net.shurui.shuruisutilities.guilds.raid.GuildRaidLockouts}): persisted with the
 * overworld data storage under a fixed name so all of it survives restarts.
 *
 * <p>Two independent pieces of state live here:
 * <ul>
 *   <li>The current SCATTER assignment: which of the seven Black Star stars sits on which generated planet. Keyed by
 *       star (1..7) to a {@link Assignment} carrying the planet id and its SPACE cell key. This is what the planet-aware
 *       radar guidance reads to point a player at the body holding an outstanding ball, and what re-scatter overwrites.</li>
 *   <li>The armed DESTRUCTION TIMER: after a successful apophis summon we record the planet the wish fired on, the space
 *       cell key needed to re-derive that planet a week later with nobody nearby, an absolute wall-clock deadline
 *       ({@link System#currentTimeMillis()} + one real week, mirroring GuildRaidLockouts, so the timer advances even
 *       while the server is offline), a warning-stage cursor so each approaching-deadline broadcast fires once, and the
 *       set of stars re-COLLECTED since the summon. When all seven are re-collected the timer disarms (the planet is
 *       spared); if the deadline passes first the planet is destroyed.</li>
 * </ul>
 */
public final class ApophisPlanetData extends SavedData
{
    private static final String NAME = "shuruisutilities_apophis_planets";

    /** One star's placement: the generated planet holding it and that planet's SPACE cell key (for re-derivation). */
    public static final class Assignment
    {
        public final String planetId;
        public final String cellKey;

        public Assignment(String planetId, String cellKey)
        {
            this.planetId = planetId;
            this.cellKey = cellKey;
        }
    }

    // whether the one-time initial seeding of the seven balls has been done. Set true once the initial scatter succeeds.
    private boolean scatterInitialized;

    // star (1..7) -> its current placement. Overwritten wholesale on each (re)scatter.
    private final Map<Integer, Assignment> assignments = new HashMap<>();

    private boolean armed;
    private String summonPlanetId = "";
    private String summonCellKey = "";
    private long deadlineMillis;
    private int warnStage;
    private final Set<Integer> foundStars = new LinkedHashSet<>();

    public static ApophisPlanetData get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(ApophisPlanetData::load, ApophisPlanetData::new, NAME);
    }

    private static ApophisPlanetData load(CompoundTag tag)
    {
        ApophisPlanetData s = new ApophisPlanetData();
        s.scatterInitialized = tag.getBoolean("scatterInitialized");
        ListTag list = tag.getList("assignments", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag e = list.getCompound(i);
            s.assignments.put(e.getInt("star"), new Assignment(e.getString("planet"), e.getString("cell")));
        }
        s.armed = tag.getBoolean("armed");
        s.summonPlanetId = tag.getString("summonPlanet");
        s.summonCellKey = tag.getString("summonCell");
        s.deadlineMillis = tag.getLong("deadline");
        s.warnStage = tag.getInt("warnStage");
        int[] found = tag.getIntArray("found");
        for (int star : found)
        {
            s.foundStars.add(star);
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        tag.putBoolean("scatterInitialized", scatterInitialized);
        ListTag list = new ListTag();
        for (Map.Entry<Integer, Assignment> e : assignments.entrySet())
        {
            CompoundTag c = new CompoundTag();
            c.putInt("star", e.getKey());
            c.putString("planet", e.getValue().planetId);
            c.putString("cell", e.getValue().cellKey);
            list.add(c);
        }
        tag.put("assignments", list);
        tag.putBoolean("armed", armed);
        tag.putString("summonPlanet", summonPlanetId);
        tag.putString("summonCell", summonCellKey);
        tag.putLong("deadline", deadlineMillis);
        tag.putInt("warnStage", warnStage);
        int[] found = new int[foundStars.size()];
        int i = 0;
        for (int star : foundStars)
        {
            found[i++] = star;
        }
        tag.putIntArray("found", found);
        return tag;
    }

    public boolean isScatterInitialized()
    {
        return scatterInitialized;
    }

    public void setScatterInitialized(boolean value)
    {
        if (scatterInitialized != value)
        {
            scatterInitialized = value;
            setDirty();
        }
    }

    /** Replace the whole star -> planet assignment with a fresh one. Called on every (re)scatter. */
    public void setAssignments(Map<Integer, Assignment> next)
    {
        assignments.clear();
        assignments.putAll(next);
        setDirty();
    }

    /** A read-only view of the current star -> planet assignment. Used by the radar guidance. */
    public Map<Integer, Assignment> assignments()
    {
        return java.util.Collections.unmodifiableMap(assignments);
    }

    public boolean isArmed()
    {
        return armed;
    }

    public String summonPlanetId()
    {
        return summonPlanetId;
    }

    public String summonCellKey()
    {
        return summonCellKey;
    }

    public long deadlineMillis()
    {
        return deadlineMillis;
    }

    public int warnStage()
    {
        return warnStage;
    }

    public void setWarnStage(int stage)
    {
        if (warnStage != stage)
        {
            warnStage = stage;
            setDirty();
        }
    }

    /** True once every one of the seven stars has been re-collected since the timer was armed. */
    public boolean allFound()
    {
        return foundStars.size() >= 7;
    }

    public java.util.Set<Integer> foundStars()
    {
        return java.util.Collections.unmodifiableSet(foundStars);
    }

    /** Mark a star re-collected. No-op (and no dirty) if it was already marked or no timer is armed. */
    public void markFound(int star)
    {
        if (armed && foundStars.add(star))
        {
            setDirty();
        }
    }

    /**
     * Arm the destruction timer on a fresh summon: record the planet, its space cell key, the absolute deadline, and
     * reset the warning cursor and the re-found set. Any previously armed timer is replaced (a second summon before the
     * first lapsed simply re-arms on the new planet).
     */
    public void arm(String planetId, String cellKey, long deadline)
    {
        armed = true;
        summonPlanetId = planetId;
        summonCellKey = cellKey;
        deadlineMillis = deadline;
        warnStage = 0;
        foundStars.clear();
        setDirty();
    }

    /** Disarm the timer (planet spared, or destroyed). Idempotent. */
    public void disarm()
    {
        if (armed)
        {
            armed = false;
            summonPlanetId = "";
            summonCellKey = "";
            deadlineMillis = 0L;
            warnStage = 0;
            foundStars.clear();
            setDirty();
        }
    }
}
