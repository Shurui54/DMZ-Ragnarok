package net.shurui.shuruisutilities.prestige;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import net.shurui.shuruisutilities.core.SUConfig;

/**
 * Runtime-editable prestige settings (max prestige, TP bonus per level) so admins can tune them from the
 * in-game admin GUI without touching config files. Persisted with the overworld; seeded from the SUConfig
 * defaults the first time it is created.
 */
public class PrestigeSettings extends SavedData
{
    private static final String NAME = "shuruisutilities_prestige";

    private int maxPrestige;
    private int tpBonusPerLevel;
    /** Bonus to the DMZ per-stat max cap, in percent, granted per prestige level (additive). */
    private int capBonusPerLevel;
    /** Default entity type used when spawning a new prestige NPC (the "model"). */
    private String model = "minecraft:villager";
    /**
     * Server-wide prestige gate per race: lowercase DMZ race id -&gt; minimum prestige level required to pick
     * that race. Absent or &lt;= 0 means the race is unlocked. Authored from the admin GUI's Race tab, enforced
     * server-side and pushed to clients so the DMZ race-select screen can grey/lock the entry.
     */
    private final Map<String, Integer> raceRequiredPrestige = new HashMap<>();
    /**
     * Races only an operator may pick: lowercase DMZ race id.
     *
     * <p>A SEPARATE axis from the prestige map above, not a very high required level, because the two answer
     * different questions. A prestige gate is something a player can work towards and the client draws a padlock
     * saying how far off they are; this one is never reachable by playing and exists for a race that is staff
     * only. Enforced server-side in both the preview and the commit gate, and folded into the lock set pushed to
     * clients so the carousel greys it for everybody who is not an operator.
     */
    private final Set<String> opOnlyRaces = new HashSet<>();
    /**
     * Server-wide prestige gate per DMZ quest: lowercase quest id -&gt; minimum prestige level required to start
     * that quest. Absent or &lt;= 0 means the quest is unlocked. Quest ids are {@code "<sagaId>:<questNumericId>"}
     * for saga quests or a bare side-quest id, matching what DMZ's {@code QuestService.startQuest} receives.
     * Enforced server-side by {@code MixinDmzQuestPrestigeGate}; there is no client lock display in this batch.
     */
    private final Map<String, Integer> questRequiredPrestige = new HashMap<>();
    /**
     * Server-wide FLOOR on prestige: every character of every player who logs in is raised to at least this level.
     *
     * <p>This is how "put everyone who has ever played on prestige 1" is done, and it is deliberately a floor rather
     * than a one-shot pass over {@code playerdata/}: prestige lives in each player's persisted NBT, so a bulk edit
     * would mean opening and rewriting every {@code <uuid>.dat} on disk - for players who are offline, on a save the
     * server is holding open, with no second chance if one file is wrong. A floor reaches exactly the same people
     * the first time each of them logs in, costs nothing, and is idempotent: run it twice and nothing happens twice.
     */
    private int minimumPrestige;
    /**
     * Epoch millis at which the current floor was set, and the line between "has played" and "has never played".
     *
     * <p>The floor keeps applying forever, so without a boundary it reaches players who join years later, which is
     * the opposite of what it is for: it exists to put the EXISTING population on prestige 1 for the 1.0 reset.
     * A player qualifies when their {@code PlayerInfo.firstLogin} predates this stamp, which is exactly "was already
     * a player when the admin ran the command", since a brand new player's SU record is created on the login that
     * would otherwise raise them. Re-set by every {@code /prestige forceall <level>}, cleared to 0 by {@code 0}.
     *
     * <p>Travels in {@link #loadInto} with the rest of the block, so every shard uses the ONE stamp taken on the
     * shard the command was run on rather than each stamping its own.
     */
    private long minimumPrestigeSetAt;
    /**
     * prestige level -&gt; kit name, handed out ONCE when a character crosses that level (the same
     * newly-crossed-level accounting the character-slot reward uses, so a second character reaching an
     * already-rewarded level gets nothing).
     */
    private final Map<Integer, String> levelKits = new HashMap<>();
    /**
     * prestige level -&gt; colour for the player's name. A level with no entry inherits the highest configured
     * level at or below it, so setting 1 alone colours everybody from prestige 1 up.
     */
    private final Map<Integer, String> levelColours = new HashMap<>();

    public static PrestigeSettings get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(PrestigeSettings::load, PrestigeSettings::create, NAME);
    }

    private static PrestigeSettings create()
    {
        PrestigeSettings s = new PrestigeSettings();
        s.maxPrestige = Math.max(0, SUConfig.prestigeMax);
        s.tpBonusPerLevel = Math.max(0, SUConfig.prestigeTpBonusPerLevel);
        s.capBonusPerLevel = Math.max(0, SUConfig.prestigeCapBonusPerLevel);
        return s;
    }

    public static PrestigeSettings load(CompoundTag tag)
    {
        PrestigeSettings s = new PrestigeSettings();
        s.maxPrestige = tag.getInt("max");
        s.tpBonusPerLevel = tag.getInt("tpPerLevel");
        // Absent on worlds saved before the cap-bonus feature existed: seed from the config default so the
        // stat-cap boost is on out of the box rather than silently disabled (0).
        s.capBonusPerLevel = tag.contains("capPerLevel") ? tag.getInt("capPerLevel")
                : Math.max(0, SUConfig.prestigeCapBonusPerLevel);
        if (tag.contains("model") && !tag.getString("model").isBlank())
            s.model = tag.getString("model");
        if (tag.contains("raceReq"))
        {
            CompoundTag req = tag.getCompound("raceReq");
            for (String race : req.getAllKeys())
            {
                int lvl = req.getInt(race);
                if (lvl > 0)
                    s.raceRequiredPrestige.put(race.toLowerCase(Locale.ROOT), lvl);
            }
        }
        if (tag.contains("questReq"))
        {
            CompoundTag req = tag.getCompound("questReq");
            for (String quest : req.getAllKeys())
            {
                int lvl = req.getInt(quest);
                if (lvl > 0)
                    s.questRequiredPrestige.put(quest.toLowerCase(Locale.ROOT), lvl);
            }
        }
        s.minimumPrestige = Math.max(0, tag.getInt("minPrestige"));
        s.minimumPrestigeSetAt = tag.getLong("minPrestigeAt");
        readLevelMap(tag, "levelKits", s.levelKits);
        readLevelMap(tag, "levelColours", s.levelColours);
        return s;
    }

    /** Both level maps are stored the same way: a compound keyed by the level written as a decimal string. */
    private static void readLevelMap(CompoundTag tag, String key, Map<Integer, String> into)
    {
        if (!tag.contains(key))
            return;
        CompoundTag stored = tag.getCompound(key);
        for (String levelKey : stored.getAllKeys())
        {
            int level;
            try
            {
                level = Integer.parseInt(levelKey);
            }
            catch (NumberFormatException e)
            {
                continue;
            }
            String value = stored.getString(levelKey);
            if (level > 0 && !value.isBlank())
                into.put(level, value);
        }
    }

    private static CompoundTag writeLevelMap(Map<Integer, String> from)
    {
        CompoundTag out = new CompoundTag();
        for (Map.Entry<Integer, String> e : from.entrySet())
            if (e.getKey() != null && e.getKey() > 0 && e.getValue() != null && !e.getValue().isBlank())
                out.putString(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    /**
     * Cross-server state sync write path: take the settings a sibling server holds and copy them into THIS live
     * instance rather than swapping the object, since the enforcers and the login handler hold a reference to it.
     * Every field is replaced wholesale (the maps too), so a removed race gate or level kit travels the same way an
     * added one does. These are deliberate admin edits, so last-write-wins on the whole settings block is the right
     * trade. Mirrors {@link #load(CompoundTag)} exactly, but into the running object.
     */
    public void loadInto(CompoundTag tag)
    {
        maxPrestige = tag.getInt("max");
        tpBonusPerLevel = tag.getInt("tpPerLevel");
        capBonusPerLevel = tag.contains("capPerLevel") ? tag.getInt("capPerLevel")
                : Math.max(0, SUConfig.prestigeCapBonusPerLevel);
        if (tag.contains("model") && !tag.getString("model").isBlank())
            model = tag.getString("model");
        raceRequiredPrestige.clear();
        if (tag.contains("raceReq"))
        {
            CompoundTag req = tag.getCompound("raceReq");
            for (String race : req.getAllKeys())
            {
                int lvl = req.getInt(race);
                if (lvl > 0)
                    raceRequiredPrestige.put(race.toLowerCase(Locale.ROOT), lvl);
            }
        }
        opOnlyRaces.clear();
        if (tag.contains("raceOpOnly"))
        {
            net.minecraft.nbt.ListTag list = tag.getList("raceOpOnly", net.minecraft.nbt.Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++)
            {
                String race = list.getString(i);
                if (race != null && !race.isBlank())
                    opOnlyRaces.add(race.toLowerCase(Locale.ROOT));
            }
        }
        questRequiredPrestige.clear();
        if (tag.contains("questReq"))
        {
            CompoundTag req = tag.getCompound("questReq");
            for (String quest : req.getAllKeys())
            {
                int lvl = req.getInt(quest);
                if (lvl > 0)
                    questRequiredPrestige.put(quest.toLowerCase(Locale.ROOT), lvl);
            }
        }
        minimumPrestige = Math.max(0, tag.getInt("minPrestige"));
        minimumPrestigeSetAt = tag.getLong("minPrestigeAt");
        levelKits.clear();
        readLevelMap(tag, "levelKits", levelKits);
        levelColours.clear();
        readLevelMap(tag, "levelColours", levelColours);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        tag.putInt("max", maxPrestige);
        tag.putInt("tpPerLevel", tpBonusPerLevel);
        tag.putInt("capPerLevel", capBonusPerLevel);
        tag.putString("model", model);
        CompoundTag req = new CompoundTag();
        for (Map.Entry<String, Integer> e : raceRequiredPrestige.entrySet())
            if (e.getValue() != null && e.getValue() > 0)
                req.putInt(e.getKey(), e.getValue());
        tag.put("raceReq", req);
        net.minecraft.nbt.ListTag opOnly = new net.minecraft.nbt.ListTag();
        for (String race : opOnlyRaces)
            opOnly.add(net.minecraft.nbt.StringTag.valueOf(race));
        tag.put("raceOpOnly", opOnly);
        CompoundTag questReq = new CompoundTag();
        for (Map.Entry<String, Integer> e : questRequiredPrestige.entrySet())
            if (e.getValue() != null && e.getValue() > 0)
                questReq.putInt(e.getKey(), e.getValue());
        tag.put("questReq", questReq);
        tag.putInt("minPrestige", minimumPrestige);
        tag.putLong("minPrestigeAt", minimumPrestigeSetAt);
        tag.put("levelKits", writeLevelMap(levelKits));
        tag.put("levelColours", writeLevelMap(levelColours));
        return tag;
    }

    /** The server-wide prestige floor (0 = none). */
    public int getMinimumPrestige()
    {
        return minimumPrestige;
    }

    /**
     * Epoch millis at which the floor was set. 0 when no floor is set, and also on a world whose floor was set by a
     * build older than the stamp: see {@code PrestigeManager.floorAppliesTo} for what is used in that case.
     */
    public long getMinimumPrestigeSetAt()
    {
        return minimumPrestigeSetAt;
    }

    /**
     * Set (or, with 0, clear) the floor, stamping the moment it was set.
     *
     * <p>The stamp is taken here rather than read back from the settings later because it has to be the moment the
     * ADMIN drew the line, not the moment some login happened to consult it. Clearing the floor clears the stamp
     * too, so a later floor never inherits an old boundary and silently skips everyone who joined in between.
     */
    public void setMinimumPrestige(int level)
    {
        this.minimumPrestige = Math.max(0, level);
        this.minimumPrestigeSetAt = this.minimumPrestige > 0 ? System.currentTimeMillis() : 0L;
        setDirty();
    }

    /** The kit handed out on first reaching {@code level}, or null. */
    public String getLevelKit(int level)
    {
        return levelKits.get(level);
    }

    public Map<Integer, String> getLevelKits()
    {
        return new HashMap<>(levelKits);
    }

    /**
     * Whether this kit is SOME level's prestige reward, whichever level that is.
     *
     * <p>The reverse lookup {@code CommandKit} needs to close a prestige kit to players who have not earned it.
     * Here rather than there, and scanning the live map rather than a copy, because it is asked once per kit for
     * every {@code /kit} listing and every tab completion.
     */
    public boolean isLevelKit(String kitName)
    {
        if (kitName == null || kitName.isBlank())
            return false;
        for (String configured : levelKits.values())
            if (kitName.equals(configured))
                return true;
        return false;
    }

    public void setLevelKit(int level, String kit)
    {
        if (level <= 0)
            return;
        if (kit == null || kit.isBlank())
            levelKits.remove(level);
        else
            levelKits.put(level, kit);
        setDirty();
    }

    /**
     * The name colour for a player AT {@code level}: the entry for that exact level, or failing that the highest
     * configured level below it. Inheriting downward is what makes one entry enough - set prestige 1 to gold and
     * every prestige is gold until some higher level says otherwise.
     */
    public String getColourFor(int level)
    {
        String best = null;
        int bestLevel = -1;
        for (Map.Entry<Integer, String> e : levelColours.entrySet())
            if (e.getKey() != null && e.getKey() <= level && e.getKey() > bestLevel)
            {
                bestLevel = e.getKey();
                best = e.getValue();
            }
        return best;
    }

    public Map<Integer, String> getLevelColours()
    {
        return new HashMap<>(levelColours);
    }

    public void setLevelColour(int level, String colour)
    {
        if (level <= 0)
            return;
        if (colour == null || colour.isBlank())
            levelColours.remove(level);
        else
            levelColours.put(level, colour);
        setDirty();
    }

    public int getMaxPrestige()
    {
        return maxPrestige;
    }

    public int getTpBonusPerLevel()
    {
        return tpBonusPerLevel;
    }

    public int getCapBonusPerLevel()
    {
        return capBonusPerLevel;
    }

    public String getModel()
    {
        return model == null || model.isBlank() ? "minecraft:villager" : model;
    }

    /** A copy of the race-&gt;required-prestige gate map (lowercase race id -&gt; required level, only levels &gt; 0). */
    /** The races only an operator may pick. A copy: callers must go through {@link #setOpOnlyRaces} to change it. */
    public Set<String> getOpOnlyRaces()
    {
        return new HashSet<>(opOnlyRaces);
    }

    /** Whether this race is operator only. Blank or unknown is never locked. */
    public boolean isOpOnly(String race)
    {
        return race != null && !race.isBlank() && opOnlyRaces.contains(race.toLowerCase(Locale.ROOT));
    }

    /** Replace the operator-only set. Ids are lower cased so a mixed-case id from a GUI still matches DMZ's. */
    public void setOpOnlyRaces(java.util.Collection<String> races)
    {
        opOnlyRaces.clear();
        if (races != null)
            for (String race : races)
                if (race != null && !race.isBlank())
                    opOnlyRaces.add(race.toLowerCase(Locale.ROOT));
        setDirty();
    }

    public Map<String, Integer> getRaceRequiredPrestige()
    {
        return new HashMap<>(raceRequiredPrestige);
    }

    /** The required prestige level for {@code race} (0 = unlocked). */
    public int getRaceRequired(String race)
    {
        if (race == null)
            return 0;
        Integer v = raceRequiredPrestige.get(race.toLowerCase(Locale.ROOT));
        return v == null ? 0 : Math.max(0, v);
    }

    /** Replace the whole race-gate map (levels &lt;= 0 drop out = unlocked), marking dirty. */
    public void setRaceRequiredPrestige(Map<String, Integer> map)
    {
        raceRequiredPrestige.clear();
        if (map != null)
            for (Map.Entry<String, Integer> e : map.entrySet())
            {
                if (e.getKey() == null || e.getValue() == null)
                    continue;
                int lvl = Math.max(0, e.getValue());
                if (lvl > 0)
                    raceRequiredPrestige.put(e.getKey().toLowerCase(Locale.ROOT), lvl);
            }
        setDirty();
    }

    /** A copy of the quest-&gt;required-prestige gate map (lowercase quest id -&gt; required level, only levels &gt; 0). */
    public Map<String, Integer> getQuestRequiredPrestige()
    {
        return new HashMap<>(questRequiredPrestige);
    }

    /** The required prestige level for {@code questId} (0 = unlocked). */
    public int getQuestRequired(String questId)
    {
        if (questId == null)
            return 0;
        Integer v = questRequiredPrestige.get(questId.toLowerCase(Locale.ROOT));
        return v == null ? 0 : Math.max(0, v);
    }

    /** Replace the whole quest-gate map (levels &lt;= 0 drop out = unlocked), marking dirty. */
    public void setQuestRequiredPrestige(Map<String, Integer> map)
    {
        questRequiredPrestige.clear();
        if (map != null)
            for (Map.Entry<String, Integer> e : map.entrySet())
            {
                if (e.getKey() == null || e.getValue() == null)
                    continue;
                int lvl = Math.max(0, e.getValue());
                if (lvl > 0)
                    questRequiredPrestige.put(e.getKey().toLowerCase(Locale.ROOT), lvl);
            }
        setDirty();
    }

    public void set(int maxPrestige, int tpBonusPerLevel, int capBonusPerLevel, String model)
    {
        this.maxPrestige = Math.max(0, maxPrestige);
        this.tpBonusPerLevel = Math.max(0, tpBonusPerLevel);
        this.capBonusPerLevel = Math.max(0, capBonusPerLevel);
        if (model != null && !model.isBlank())
            this.model = model;
        setDirty();
    }
}
