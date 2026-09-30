package net.shurui.shuruisutilities.space;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Overworld-attached saved data for generated planets, mirroring {@link
 * net.shurui.shuruisutilities.corrupted.ShadowDragonStorage}: stored under a fixed name in the overworld data storage.
 *
 * <p>The one authoritative home for a generated planet's claim, keyed by the planet's STABLE ID (the {@code sugen:<hash>}
 * from {@link GeneratedPlanets}), never by an entity. Planet bodies evaporate when players leave and are re-derived from
 * the hash, so no ownership may live on them: delete every planet entity and not one claim is lost.
 *
 * <p>Two maps: {@code claims} (planet id -&gt; owning guild id) and {@code surfaceGenerated} (planet ids whose surface
 * has been stamped, so a stamp runs once per planet). One-per-guild is enforced by scanning {@code claims} (see {@link
 * #claimedByGuild}); no reverse index, the map is tiny.
 */
public final class GeneratedPlanetClaims extends SavedData
{
    private static final String NAME = "shuruisutilities_generated_planets";

    // planet id -> owning guild id
    private final Map<String, String> claims = new HashMap<>();
    // planet id -> owning PLAYER uuid (string), the PUBLIC personal-conquest claim (see PlanetConquest). A SEPARATE map
    // from the guild claims above so the existing guild-claim schema and saves are never touched: a save from before this
    // feature simply has no "personalClaims" list and loads with an empty map. A planet is never in both maps at once
    // (each claim path refuses when the other already owns it), and neither one-per-guild rule nor the guild sync ever
    // scans this map. This is the durable result of a conquest and rides the exact same overworld-SavedData persistence
    // (and, therefore, cross-shard) path the guild claims do, per the workspace "all new persistent data syncs" rule.
    private final Map<String, String> personalClaims = new HashMap<>();
    // planet id -> the uuid (string) of the conquest DEFENDER BOSS currently standing on that planet, so a re-visit never
    // spawns a second boss and a boss death is validated against the record. Cleared on defeat, claim or destruction. Also
    // additive: an older save has no "conquestBoss" list.
    private final Map<String, String> conquestBoss = new HashMap<>();
    // planet id -> the OWNER-AVATAR snapshot (the owning player's look and combat stats, captured at claim time and
    // refreshed while the owner is online on the planet, see PlanetOwnerAvatar). Additive: an older save has no
    // "avatarSnapshots" list. Dropped with the personal claim on destruction.
    private final Map<String, AvatarSnapshot> avatarSnapshots = new HashMap<>();
    // planet id -> the SHARED DB-CLOCK epoch millis at which the owner avatar was last DEFEATED, opening the timed
    // explodable/respawn windows (0/absent = never defeated, so the avatar guards the planet). Stamped and compared with
    // OrbitClock.serverEpochMillis() (System.currentTimeMillis() + the DB-clock offset), NOT the host wall clock: shard
    // wall clocks run many hours apart, so a bare-wall-clock window would read as long expired or far in the future on
    // another shard. Additive: an older save has no "avatarDefeated" list. Rides the same SavedData path (and therefore
    // the same cross-shard sync) as the personal claims.
    private final Map<String, Long> avatarDefeatedAt = new HashMap<>();
    // planet ids whose surface has been stamped already
    private final Set<String> surfaceGenerated = new HashSet<>();
    // planet id -> the surface size (blocks per side) and theme the planet was actually STAMPED at. Persisted so a later
    // build changing the derived size/theme (a pure function of the shared MIN/MAX_SURFACE constants and the theme
    // weights) cannot desync an already-stamped planet: its terrain stays put, so size/theme are read back from here.
    // Recorded when a stamp BEGINS so a mid-stamp read is authoritative; removed only when the surface flag is cleared.
    // A planet stamped by an older build has no entry and falls back to the derived value, which backfillStampedGeometry
    // locks in on server start.
    private final Map<String, Integer> stampedSize = new HashMap<>();
    private final Map<String, String> stampedTheme = new HashMap<>();
    // planet id -> the rest of the stamp snapshot (deep-column depth, sea-level offset, basin/vegetation/village/hut
    // frequencies, the three enable flags). Only size/theme above are read during play; this fuller snapshot lets a
    // planet-destroy salvage recompute (NaturalSurfaceOracle) reproduce the exact terrain even if the operator reloaded
    // the config between stamp and destroy. A planet from a build predating this store has no entry; the oracle then
    // falls back to the live config (best effort). Recorded and dropped alongside size/theme.
    private final Map<String, StampParams> stampedParams = new HashMap<>();
    // Currently-destroyed cells, keyed by CELL, not planet id: when the debris timer bumps a cell's generation a new
    // planet id is derived for that cell, and the cell key is the one identifier fixed across the bump. An entry is the
    // active "this slot is rubble now" record, removed once the timer bumps to the next generation. Carries the
    // destroyed planet id (so lookup by id is direct) and the destruction game time (so the timer knows when to bump).
    private final Map<String, DestroyedCell> destroyed = new HashMap<>();

    // Per-cell generation counter, keyed by CELL, PERMANENT (never removed). 0 (no entry) is the original planet; each
    // destruction eventually bumps it so a wholly different planet is derived in that slot. Kept separate from {@link
    // #destroyed} because it must OUTLIVE the destroyed record: after debris clears the slot is no longer "destroyed"
    // but must stay on its bumped generation forever, or the old planet reappears.
    private final Map<String, Integer> generations = new HashMap<>();

    // A monotonically-rising counter bumped whenever the SET of derivable planet ids could change: a destruction, a
    // generation bump (debris cleared, new planet formed) or an admin restore. NOT persisted: it exists only to let the
    // admin search cache (GeneratedPlanetIndex) know when to rebuild, and the cache starts stale on every boot anyway, so
    // a fresh 0 after a restart forces exactly the one rebuild it needs. Claims changing does NOT bump it, because a claim
    // only changes a planet's state (read live), never which ids exist.
    private long searchEpoch = 0L;

    /**
     * One currently-destroyed cell: the destroyed planet id (at the cell's generation at the moment of destruction), the
     * overworld game time the destruction happened (so the debris timer knows when to bump), and the generation the cell
     * was on when destroyed.
     */
    public static final class DestroyedCell
    {
        public final String destroyedPlanetId;
        public final long destroyedAtGameTime;
        public final int generation;

        DestroyedCell(String destroyedPlanetId, long destroyedAtGameTime, int generation)
        {
            this.destroyedPlanetId = destroyedPlanetId;
            this.destroyedAtGameTime = destroyedAtGameTime;
            this.generation = generation;
        }
    }

    /**
     * The persisted rest-of-snapshot for a stamped surface: everything beyond size/theme the deterministic terrain,
     * water, vegetation and structure placement is a pure function of, snapshotted from the live config when the stamp
     * began so a later salvage reproduces the exact ground. Immutable.
     */
    public static final class StampParams
    {
        public final int deepDepth;
        public final int seaLevelOffset;
        public final double basinFrequency;
        public final double vegetationDensity;
        public final double villageFrequency;
        public final double hutFrequency;
        public final boolean waterEnabled;
        public final boolean vegetationEnabled;
        public final boolean structuresEnabled;
        // The terrain generator version this planet was stamped by (SurfaceStamp.GEN_VERSION_*). 1 (or a legacy value of
        // 0 from a build predating the field) is the original disc-and-shell terrain; 2 is the tileable square terrain
        // with scattered basin pools; 3 is the tileable square with a real per-theme sea level and a periodic wrap margin.
        // A planet keeps its version forever, so already-stamped cells are never re-shaped: only cells stamped after a
        // given batch carry its version and get its terrain.
        public final int generatorVersion;

        public StampParams(int deepDepth, int seaLevelOffset, double basinFrequency, double vegetationDensity,
                double villageFrequency, double hutFrequency, boolean waterEnabled, boolean vegetationEnabled,
                boolean structuresEnabled, int generatorVersion)
        {
            this.deepDepth = deepDepth;
            this.seaLevelOffset = seaLevelOffset;
            this.basinFrequency = basinFrequency;
            this.vegetationDensity = vegetationDensity;
            this.villageFrequency = villageFrequency;
            this.hutFrequency = hutFrequency;
            this.waterEnabled = waterEnabled;
            this.vegetationEnabled = vegetationEnabled;
            this.structuresEnabled = structuresEnabled;
            this.generatorVersion = generatorVersion;
        }
    }

    /**
     * The owner-avatar snapshot: the owning player's DragonMineZ look (race body appearance) and combat stats, captured
     * when the planet is conquered and refreshed while the owner is online on it, so the avatar defender renders as the
     * owner and fights at the owner's strength even while the owner is offline or on another shard. Immutable. Colours are
     * packed 0xRRGGBB. Stats are the RAW derived combat numbers (not yet scaled by the config multiplier, which is applied
     * at spawn), so a config change takes effect without re-snapshotting.
     */
    public static final class AvatarSnapshot
    {
        public final String ownerName;
        public final String race;
        public final int bodyType;
        public final int bodyColor1;
        public final int bodyColor2;
        public final int bodyColor3;
        public final int hairColor;
        public final double health;
        public final double melee;
        public final double defense;
        public final float ki;
        public final double battlePower;

        public AvatarSnapshot(String ownerName, String race, int bodyType, int bodyColor1, int bodyColor2,
                int bodyColor3, int hairColor, double health, double melee, double defense, float ki, double battlePower)
        {
            this.ownerName = ownerName == null ? "" : ownerName;
            this.race = race == null ? "" : race;
            this.bodyType = bodyType;
            this.bodyColor1 = bodyColor1;
            this.bodyColor2 = bodyColor2;
            this.bodyColor3 = bodyColor3;
            this.hairColor = hairColor;
            this.health = health;
            this.melee = melee;
            this.defense = defense;
            this.ki = ki;
            this.battlePower = battlePower;
        }
    }

    public static GeneratedPlanetClaims get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(GeneratedPlanetClaims::load, GeneratedPlanetClaims::new, NAME);
    }

    private static GeneratedPlanetClaims load(CompoundTag tag)
    {
        GeneratedPlanetClaims s = new GeneratedPlanetClaims();
        ListTag claimList = tag.getList("claims", Tag.TAG_COMPOUND);
        for (int i = 0; i < claimList.size(); i++)
        {
            CompoundTag c = claimList.getCompound(i);
            s.claims.put(c.getString("planet"), c.getString("guild"));
        }
        // additive: an older save has no "personalClaims"/"conquestBoss" list, so these loops simply run zero times.
        ListTag personalList = tag.getList("personalClaims", Tag.TAG_COMPOUND);
        for (int i = 0; i < personalList.size(); i++)
        {
            CompoundTag c = personalList.getCompound(i);
            s.personalClaims.put(c.getString("planet"), c.getString("owner"));
        }
        ListTag bossList = tag.getList("conquestBoss", Tag.TAG_COMPOUND);
        for (int i = 0; i < bossList.size(); i++)
        {
            CompoundTag c = bossList.getCompound(i);
            s.conquestBoss.put(c.getString("planet"), c.getString("boss"));
        }
        // additive: an older save has no "avatarSnapshots"/"avatarDefeated" list, so these loops run zero times.
        ListTag avatarList = tag.getList("avatarSnapshots", Tag.TAG_COMPOUND);
        for (int i = 0; i < avatarList.size(); i++)
        {
            CompoundTag c = avatarList.getCompound(i);
            s.avatarSnapshots.put(c.getString("planet"), new AvatarSnapshot(
                    c.getString("ownerName"), c.getString("race"), c.getInt("bodyType"),
                    c.getInt("body1"), c.getInt("body2"), c.getInt("body3"), c.getInt("hair"),
                    c.getDouble("health"), c.getDouble("melee"), c.getDouble("defense"),
                    c.getFloat("ki"), c.getDouble("battlePower")));
        }
        ListTag avatarDefeatedList = tag.getList("avatarDefeated", Tag.TAG_COMPOUND);
        for (int i = 0; i < avatarDefeatedList.size(); i++)
        {
            CompoundTag c = avatarDefeatedList.getCompound(i);
            s.avatarDefeatedAt.put(c.getString("planet"), c.getLong("at"));
        }
        ListTag genList = tag.getList("surfaceGenerated", Tag.TAG_STRING);
        for (int i = 0; i < genList.size(); i++)
        {
            s.surfaceGenerated.add(genList.getString(i));
        }
        ListTag geometryList = tag.getList("stampedGeometry", Tag.TAG_COMPOUND);
        for (int i = 0; i < geometryList.size(); i++)
        {
            CompoundTag c = geometryList.getCompound(i);
            String planet = c.getString("planet");
            s.stampedSize.put(planet, c.getInt("size"));
            if (c.contains("theme", Tag.TAG_STRING))
            {
                s.stampedTheme.put(planet, c.getString("theme"));
            }
            // the fuller snapshot rides in the same per-planet compound; a planet stamped before this field existed has
            // no "params" tag and the oracle falls back to the live config for it.
            if (c.contains("params", Tag.TAG_COMPOUND))
            {
                CompoundTag p = c.getCompound("params");
                // generatorVersion is absent (getInt returns 0) for a planet stamped before batch B4; 0 is read back as
                // the legacy generator by SurfaceStamp, so an existing cell keeps its original terrain.
                s.stampedParams.put(planet, new StampParams(
                        p.getInt("deepDepth"), p.getInt("seaLevelOffset"), p.getDouble("basinFrequency"),
                        p.getDouble("vegetationDensity"), p.getDouble("villageFrequency"), p.getDouble("hutFrequency"),
                        p.getBoolean("waterEnabled"), p.getBoolean("vegetationEnabled"),
                        p.getBoolean("structuresEnabled"), p.getInt("generatorVersion")));
            }
        }
        ListTag destroyedList = tag.getList("destroyed", Tag.TAG_COMPOUND);
        for (int i = 0; i < destroyedList.size(); i++)
        {
            CompoundTag c = destroyedList.getCompound(i);
            s.destroyed.put(c.getString("cell"), new DestroyedCell(
                    c.getString("planet"), c.getLong("at"), c.getInt("generation")));
        }
        ListTag genCounters = tag.getList("generations", Tag.TAG_COMPOUND);
        for (int i = 0; i < genCounters.size(); i++)
        {
            CompoundTag c = genCounters.getCompound(i);
            s.generations.put(c.getString("cell"), c.getInt("generation"));
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        ListTag claimList = new ListTag();
        for (Map.Entry<String, String> e : claims.entrySet())
        {
            CompoundTag c = new CompoundTag();
            c.putString("planet", e.getKey());
            c.putString("guild", e.getValue());
            claimList.add(c);
        }
        tag.put("claims", claimList);

        ListTag personalList = new ListTag();
        for (Map.Entry<String, String> e : personalClaims.entrySet())
        {
            CompoundTag c = new CompoundTag();
            c.putString("planet", e.getKey());
            c.putString("owner", e.getValue());
            personalList.add(c);
        }
        tag.put("personalClaims", personalList);

        ListTag bossList = new ListTag();
        for (Map.Entry<String, String> e : conquestBoss.entrySet())
        {
            CompoundTag c = new CompoundTag();
            c.putString("planet", e.getKey());
            c.putString("boss", e.getValue());
            bossList.add(c);
        }
        tag.put("conquestBoss", bossList);

        ListTag avatarList = new ListTag();
        for (Map.Entry<String, AvatarSnapshot> e : avatarSnapshots.entrySet())
        {
            AvatarSnapshot a = e.getValue();
            CompoundTag c = new CompoundTag();
            c.putString("planet", e.getKey());
            c.putString("ownerName", a.ownerName);
            c.putString("race", a.race);
            c.putInt("bodyType", a.bodyType);
            c.putInt("body1", a.bodyColor1);
            c.putInt("body2", a.bodyColor2);
            c.putInt("body3", a.bodyColor3);
            c.putInt("hair", a.hairColor);
            c.putDouble("health", a.health);
            c.putDouble("melee", a.melee);
            c.putDouble("defense", a.defense);
            c.putFloat("ki", a.ki);
            c.putDouble("battlePower", a.battlePower);
            avatarList.add(c);
        }
        tag.put("avatarSnapshots", avatarList);

        ListTag avatarDefeatedList = new ListTag();
        for (Map.Entry<String, Long> e : avatarDefeatedAt.entrySet())
        {
            CompoundTag c = new CompoundTag();
            c.putString("planet", e.getKey());
            c.putLong("at", e.getValue());
            avatarDefeatedList.add(c);
        }
        tag.put("avatarDefeated", avatarDefeatedList);

        ListTag genList = new ListTag();
        for (String id : surfaceGenerated)
        {
            genList.add(StringTag.valueOf(id));
        }
        tag.put("surfaceGenerated", genList);

        ListTag geometryList = new ListTag();
        for (Map.Entry<String, Integer> e : stampedSize.entrySet())
        {
            CompoundTag c = new CompoundTag();
            c.putString("planet", e.getKey());
            c.putInt("size", e.getValue());
            String theme = stampedTheme.get(e.getKey());
            if (theme != null)
            {
                c.putString("theme", theme);
            }
            StampParams params = stampedParams.get(e.getKey());
            if (params != null)
            {
                CompoundTag p = new CompoundTag();
                p.putInt("deepDepth", params.deepDepth);
                p.putInt("seaLevelOffset", params.seaLevelOffset);
                p.putDouble("basinFrequency", params.basinFrequency);
                p.putDouble("vegetationDensity", params.vegetationDensity);
                p.putDouble("villageFrequency", params.villageFrequency);
                p.putDouble("hutFrequency", params.hutFrequency);
                p.putBoolean("waterEnabled", params.waterEnabled);
                p.putBoolean("vegetationEnabled", params.vegetationEnabled);
                p.putBoolean("structuresEnabled", params.structuresEnabled);
                p.putInt("generatorVersion", params.generatorVersion);
                c.put("params", p);
            }
            geometryList.add(c);
        }
        tag.put("stampedGeometry", geometryList);

        ListTag destroyedList = new ListTag();
        for (Map.Entry<String, DestroyedCell> e : destroyed.entrySet())
        {
            CompoundTag c = new CompoundTag();
            c.putString("cell", e.getKey());
            c.putString("planet", e.getValue().destroyedPlanetId);
            c.putLong("at", e.getValue().destroyedAtGameTime);
            c.putInt("generation", e.getValue().generation);
            destroyedList.add(c);
        }
        tag.put("destroyed", destroyedList);

        ListTag genCounters = new ListTag();
        for (Map.Entry<String, Integer> e : generations.entrySet())
        {
            CompoundTag c = new CompoundTag();
            c.putString("cell", e.getKey());
            c.putInt("generation", e.getValue());
            genCounters.add(c);
        }
        tag.put("generations", genCounters);
        return tag;
    }

    /** The guild id that owns the given planet id, or null if unclaimed. */
    public String owner(String planetId)
    {
        return claims.get(planetId);
    }

    public boolean isClaimed(String planetId)
    {
        return claims.containsKey(planetId);
    }

    /**
     * Read-only view of the claim map (planet id -&gt; owning guild id), for the layout sync's owner-nameplate list.
     * Never mutate through this.
     */
    public java.util.Map<String, String> claims()
    {
        return java.util.Collections.unmodifiableMap(claims);
    }

    /** The planet id claimed by the given guild id, or null if that guild owns none. One planet per guild. */
    public String claimedByGuild(String guildId)
    {
        for (Map.Entry<String, String> e : claims.entrySet())
        {
            if (e.getValue().equals(guildId))
            {
                return e.getKey();
            }
        }
        return null;
    }

    /** Record ownership. The caller has already enforced the one-per-guild and not-already-claimed rules. */
    public void setClaim(String planetId, String guildId)
    {
        claims.put(planetId, guildId);
        setDirty();
    }

    /** Drop a claim by planet id. No-op if unclaimed. */
    public void unclaim(String planetId)
    {
        if (claims.remove(planetId) != null)
        {
            setDirty();
        }
    }

    /** Drop every claim held by a guild id (used when a guild disbands). No-op if it owned none. */
    public void unclaimGuild(String guildId)
    {
        if (claims.values().removeIf(guildId::equals))
        {
            setDirty();
        }
    }

    // ---- PUBLIC personal-conquest claims (PlanetConquest) -------------------------------------------------------------

    /** The owning player uuid (string) of a personally-conquered planet, or null if none. */
    public String personalOwner(String planetId)
    {
        return personalClaims.get(planetId);
    }

    /** Whether this planet is held by a PERSONAL conquest claim (as opposed to a guild claim, or unclaimed). */
    public boolean isPersonallyClaimed(String planetId)
    {
        return personalClaims.containsKey(planetId);
    }

    /**
     * Whether this planet is owned by ANYONE, guild or personal. The single "is this planet taken" test the garrison and
     * both claim paths use, so a planet owned by either mechanism blocks the other and grows no new wild garrison.
     */
    public boolean isOwned(String planetId)
    {
        return claims.containsKey(planetId) || personalClaims.containsKey(planetId);
    }

    /** Read-only view of the personal-claim map (planet id -&gt; owner uuid string), for the layout sync's nameplates. */
    public java.util.Map<String, String> personalClaims()
    {
        return java.util.Collections.unmodifiableMap(personalClaims);
    }

    /** Record a personal conquest claim. The caller has already enforced not-already-owned. */
    public void setPersonalClaim(String planetId, java.util.UUID owner)
    {
        personalClaims.put(planetId, owner.toString());
        setDirty();
    }

    /** Drop a personal claim by planet id, and with it the owner-avatar snapshot and defeat window. No-op if unclaimed. */
    public void unclaimPersonal(String planetId)
    {
        boolean changed = personalClaims.remove(planetId) != null;
        changed |= avatarSnapshots.remove(planetId) != null;
        changed |= avatarDefeatedAt.remove(planetId) != null;
        if (changed)
        {
            setDirty();
        }
    }

    // ---- OWNER AVATAR defender (PlanetOwnerAvatar) --------------------------------------------------------------------

    /** The owner-avatar snapshot for a personally-claimed planet, or null if none has been captured yet. */
    public AvatarSnapshot avatarSnapshot(String planetId)
    {
        return avatarSnapshots.get(planetId);
    }

    /** Record (or refresh) the owner-avatar snapshot for a planet. */
    public void setAvatarSnapshot(String planetId, AvatarSnapshot snapshot)
    {
        if (snapshot == null)
        {
            if (avatarSnapshots.remove(planetId) != null)
            {
                setDirty();
            }
            return;
        }
        avatarSnapshots.put(planetId, snapshot);
        setDirty();
    }

    /** Epoch millis the avatar was last defeated on this planet, or 0 if never (so the avatar currently guards it). */
    public long avatarDefeatedAt(String planetId)
    {
        return avatarDefeatedAt.getOrDefault(planetId, 0L);
    }

    /** Record the wall-clock epoch millis at which the avatar was defeated, opening the explodable/respawn windows. */
    public void setAvatarDefeatedAt(String planetId, long epochMillis)
    {
        avatarDefeatedAt.put(planetId, epochMillis);
        setDirty();
    }

    /** The uuid (string) of the conquest boss currently on this planet, or null if none is pending. */
    public String conquestBoss(String planetId)
    {
        return conquestBoss.get(planetId);
    }

    /** Record the conquest boss spawned for a planet (so a revisit does not spawn a second). */
    public void setConquestBoss(String planetId, java.util.UUID bossId)
    {
        conquestBoss.put(planetId, bossId.toString());
        setDirty();
    }

    /** Drop the conquest-boss record for a planet (on defeat, claim or destruction). No-op if absent. */
    public void clearConquestBoss(String planetId)
    {
        if (conquestBoss.remove(planetId) != null)
        {
            setDirty();
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    public boolean isSurfaceGenerated(String planetId)
    {
        return surfaceGenerated.contains(planetId);
    }

    public void markSurfaceGenerated(String planetId)
    {
        if (surfaceGenerated.add(planetId))
        {
            setDirty();
        }
    }

    /**
     * Read-only view of every stamped planet id: the exhaustive set of planets a player can be STANDING on, since a
     * surface exists only where it was stamped. The space module uses it to resolve which planet a player is on from
     * POSITION when no per-player {@link SurfaceTravelData} exists (a /tp, a login/respawn onto the surface): each id's
     * cell is a pure function of the id ({@link SurfaceDimension#cellX}/{@link SurfaceDimension#cellZ}), so a cell match
     * needs no inversion of the one-way id-&gt;cell fold. Never mutate through this.
     */
    public java.util.Set<String> surfaceGeneratedIds()
    {
        return java.util.Collections.unmodifiableSet(surfaceGenerated);
    }

    /**
     * Clear the surface-generated flag for a planet id, so a future planet in that slot re-stamps fresh terrain. Also
     * drops the stamped size/theme so a new planet derived in the same cell does not inherit the old one's geometry.
     */
    public void clearSurfaceGenerated(String planetId)
    {
        boolean changed = surfaceGenerated.remove(planetId);
        changed |= stampedSize.remove(planetId) != null;
        changed |= stampedTheme.remove(planetId) != null;
        changed |= stampedParams.remove(planetId) != null;
        if (changed)
        {
            setDirty();
        }
    }

    /**
     * Read-only view of the stamped-size map (planet id -&gt; size in blocks per side). The layout sync pushes these so a
     * client draws every already-stamped planet at the size the server built it, not a newly-derived one. Never mutate
     * through this.
     */
    public java.util.Map<String, Integer> stampedSizes()
    {
        return java.util.Collections.unmodifiableMap(stampedSize);
    }

    /**
     * Record the size (blocks per side) and theme a planet was stamped at. Called when a stamp BEGINS, so a read during
     * the batched stamp is already authoritative and a resumed stamp reuses the same size/theme. Idempotent.
     */
    public void recordStampedGeometry(String planetId, int size, String themeName, StampParams params)
    {
        Integer prevSize = stampedSize.put(planetId, size);
        String prevTheme = stampedTheme.put(planetId, themeName);
        StampParams prevParams = stampedParams.put(planetId, params);
        if (prevSize == null || prevSize != size || !themeName.equals(prevTheme) || prevParams != params)
        {
            setDirty();
        }
    }

    /**
     * The persisted stamp snapshot for a planet, or null if it has none (stamped by a build predating this store, or
     * never stamped). The salvage oracle falls back to the live config on null.
     */
    public static StampParams stampedParamsForId(MinecraftServer server, String planetId)
    {
        if (server != null && planetId != null)
        {
            return get(server).stampedParams.get(planetId);
        }
        return null;
    }

    /**
     * Whether the planet's stamped surface was built by the TILEABLE (version 2) terrain generator, the only terrain
     * whose opposite edges match block for block and can therefore be crossed with a seamless edge wrap. A planet with
     * no stamp snapshot (never stamped, or stamped by a build predating the snapshot store) or one stamped by the legacy
     * disc generator returns false, so it keeps the invisible rim wall. Client-safe: a null server returns false.
     */
    public static boolean isTileableSurface(MinecraftServer server, String planetId)
    {
        StampParams params = stampedParamsForId(server, planetId);
        // version 2 (square + basins) and version 3 (square + global sea + wrap margin) are BOTH tileable: their periodic
        // height field matches block for block at the opposite edge, so both wrap. Only the legacy disc (version 1) or an
        // unstamped/pre-snapshot planet keep the wall.
        return params != null && params.generatorVersion >= SurfaceStamp.GEN_VERSION_TILEABLE;
    }

    /**
     * Whether the planet was stamped by the version-3 SEAS generator, which stamps a periodic MARGIN beyond every edge (so
     * the opposite side is already rendered across the wrap seam) and PROTECTS that margin from building. Used by the
     * margin build/break guard. A null server, an unstamped planet or an older generator returns false.
     */
    public static boolean isSeaSurface(MinecraftServer server, String planetId)
    {
        StampParams params = stampedParamsForId(server, planetId);
        return params != null && params.generatorVersion >= SurfaceStamp.GEN_VERSION_SEAS;
    }

    /**
     * The surface size (blocks per side) a planet is actually built at: the stored stamped size for a stamped planet, or
     * the derived {@link GeneratedPlanets#surfaceSizeForId} for a fresh one. The single accessor every surface-geometry
     * consumer (horizontal boundary, garrison scatter, space-body radius, leave standoff) reads, so all stay in lockstep
     * with the ground even after the derived formula's constants change. A null server (client-side) has no store and
     * falls back to the derived value.
     */
    public static int stampedSizeForId(MinecraftServer server, String planetId)
    {
        if (planetId != null)
        {
            if (server != null)
            {
                Integer stored = get(server).stampedSize.get(planetId);
                if (stored != null)
                {
                    return stored;
                }
            }
            else
            {
                // client-side (no server): consult the synced stamped-size snapshot before the derived fallback, so an
                // already-stamped planet draws at the size the server built it. Critical after the surface range changed:
                // otherwise the client would derive the NEW-range size for an OLD planet and draw a body radius the
                // server's landing/collision geometry disagrees with. An id absent from the snapshot returns 0 and falls
                // through to the derived value, which both sides compute identically.
                int synced = SpaceLayout.clientStampedSize(planetId);
                if (synced > 0)
                {
                    return synced;
                }
            }
        }
        return GeneratedPlanets.surfaceSizeForId(planetId);
    }

    /**
     * The theme a planet was stamped with: the stored theme, or the derived {@link SurfaceStamp#surfaceThemeFor} for a
     * fresh one (or an unparseable stored value). Same lockstep rationale as {@link #stampedSizeForId}: persisting the
     * theme guards a later theme-weight change from re-theming an already-stamped planet's ground.
     */
    public static SurfaceStamp.Theme stampedThemeForId(MinecraftServer server, String planetId)
    {
        if (server != null && planetId != null)
        {
            String stored = get(server).stampedTheme.get(planetId);
            if (stored != null)
            {
                try
                {
                    return SurfaceStamp.Theme.valueOf(stored);
                }
                catch (IllegalArgumentException ignored)
                {
                    // theme renamed/removed in a later build: fall through to the derived theme.
                }
            }
        }
        return SurfaceStamp.surfaceThemeFor(planetId);
    }

    /**
     * Lock in the derived size and theme for every already-stamped planet that has no stored geometry yet, so a later
     * build that changes the derived formula cannot retroactively resize or re-theme it. Safe on every server start:
     * touches only planets missing an entry over the tiny surfaceGenerated set. Must run BEFORE any build that alters
     * the constants, which is why it is wired at server start.
     */
    public void backfillStampedGeometry()
    {
        int backfilledSize = 0;
        boolean changed = false;
        for (String planetId : surfaceGenerated)
        {
            if (!stampedSize.containsKey(planetId))
            {
                // A stamped planet with no stored size predates this store, and so predates the 100..500 range change:
                // its terrain on disk was built under the OLD 20..200 range. Lock in the LEGACY size, NOT the current
                // derived one, which would resolve far larger for the same id hash and leave the boundary, garrison
                // scatter, salvage, confine goals and body radius oversized while the real terrain ends at the old rim.
                // See GeneratedPlanets.legacySurfaceSizeForId.
                stampedSize.put(planetId, GeneratedPlanets.legacySurfaceSizeForId(planetId));
                backfilledSize++;
                changed = true;
            }
            if (!stampedTheme.containsKey(planetId))
            {
                // The theme derivation's salt and weights did not change with the size range, so the current derivation
                // is still correct for an old planet and no legacy theme tier is needed.
                stampedTheme.put(planetId, SurfaceStamp.surfaceThemeFor(planetId).name());
                changed = true;
            }
        }
        if (changed)
        {
            setDirty();
        }
        if (backfilledSize > 0)
        {
            LoggingHandler.sulog.info(
                    "[GeneratedPlanets] Backfilled stamped size for {} pre-existing planet(s) using the legacy {}..{} surface range.",
                    backfilledSize, GeneratedPlanets.LEGACY_MIN_SURFACE, GeneratedPlanets.LEGACY_MAX_SURFACE);
        }
    }

    /**
     * Whether the planet with this exact id is destroyed right now. A cheap scan over the tiny active-debris map. A slot
     * that has since bumped to a new generation is NOT destroyed (its old id left the map, its new id was never in it),
     * which is the "debris cleared, new planet formed" behaviour.
     */
    public boolean isDestroyed(String planetId)
    {
        for (DestroyedCell cell : destroyed.values())
        {
            if (cell.destroyedPlanetId.equals(planetId))
            {
                return true;
            }
        }
        return false;
    }

    /** The running generation for a cell key. 0 (no entry) is the original planet. Pure read, no mutation. */
    public int generationFor(String cellKey)
    {
        return generations.getOrDefault(cellKey, 0);
    }

    /**
     * Mark a cell destroyed at the given game time. Caller has already run the destructible check. Records the active
     * debris entry at the cell's current generation. Idempotent: re-marking just refreshes the record.
     */
    public void markDestroyed(String cellKey, String planetId, long gameTime)
    {
        destroyed.put(cellKey, new DestroyedCell(planetId, gameTime, generationFor(cellKey)));
        searchEpoch++;
        setDirty();
    }

    /**
     * Clear a cell's destroyed record and bump its generation by one, so the next derivation yields a wholly different
     * planet. The "debris cleared, a new planet forms" transition. No-op if the cell is not currently destroyed.
     */
    public void bumpGeneration(String cellKey)
    {
        if (destroyed.remove(cellKey) != null)
        {
            generations.put(cellKey, generationFor(cellKey) + 1);
            searchEpoch++;
            setDirty();
        }
    }

    /**
     * Restore a destroyed cell WITHOUT bumping its generation, so the same planet reappears (admin restore command).
     * No-op if not currently destroyed. Returns the restored planet id, or null.
     */
    public String restore(String cellKey)
    {
        DestroyedCell removed = destroyed.remove(cellKey);
        if (removed != null)
        {
            searchEpoch++;
            setDirty();
            return removed.destroyedPlanetId;
        }
        return null;
    }

    /**
     * The current search epoch, bumped on every destruction, generation bump and restore. Read by {@link
     * GeneratedPlanetIndex} to decide when its enumerated snapshot is stale. Not persisted (see the field's note).
     */
    public long searchEpoch()
    {
        return searchEpoch;
    }

    /** Read-only snapshot of every destroyed planet id, for the layout sync's "these planets are gone" list. */
    public java.util.Set<String> destroyedPlanetIds()
    {
        java.util.Set<String> out = new HashSet<>();
        for (DestroyedCell cell : destroyed.values())
        {
            out.add(cell.destroyedPlanetId);
        }
        return out;
    }

    /**
     * Read-only snapshot of the per-cell generation counters, for the layout sync. Only ever-bumped cells appear
     * (generation 0 is the default absence). Never mutate through this.
     */
    public java.util.Map<String, Integer> generationsView()
    {
        return java.util.Collections.unmodifiableMap(generations);
    }

    /** Every currently-destroyed cell, for the debris-timer sweep. Returns a copy so the timer can bump while iterating. */
    public java.util.Map<String, DestroyedCell> destroyedCells()
    {
        return new HashMap<>(destroyed);
    }
}
