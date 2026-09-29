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

        public StampParams(int deepDepth, int seaLevelOffset, double basinFrequency, double vegetationDensity,
                double villageFrequency, double hutFrequency, boolean waterEnabled, boolean vegetationEnabled,
                boolean structuresEnabled)
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
                s.stampedParams.put(planet, new StampParams(
                        p.getInt("deepDepth"), p.getInt("seaLevelOffset"), p.getDouble("basinFrequency"),
                        p.getDouble("vegetationDensity"), p.getDouble("villageFrequency"), p.getDouble("hutFrequency"),
                        p.getBoolean("waterEnabled"), p.getBoolean("vegetationEnabled"),
                        p.getBoolean("structuresEnabled")));
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
