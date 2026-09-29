package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import com.dragonminez.common.spacepod.SpacePodDestinationDefinition;
import com.dragonminez.common.spacepod.SpacePodDestinationRegistry;

import net.shurui.shuruisutilities.util.DynamicLevels;

/**
 * Decides which dimensions are planet BODIES in the space dimension, and their derived positions/tints.
 *
 * <p>Source of truth is DMZ's own space-pod destination list, read server-side through the OPEN static
 * {@link SpacePodDestinationRegistry#getServerDestinations()} (populated by DMZ's SimpleJsonResourceReloadListener
 * over {@code data/<ns>/spacepod/*.json}). dragonminez is a mandatory dependency of this addon, so the direct
 * reference needs no ModList guard. We do NOT maintain a parallel destination list: every candidate comes from
 * whatever destinations are loaded, so any datapack that adds one automatically gets a body (subject to the
 * exclusions below). If DMZ's list is somehow empty (registry not yet reloaded, e.g. queried before first world
 * load), we fall back to the sensible-default dimensions so Earth/Namek/Sacred Kai always exist as bodies.
 *
 * <p>A planet's stable KEY is its target dimension id. Two destinations that point at the same dimension collapse
 * to one body (a dimension is one place). Position and tint come from {@link PlanetPositions}, hashed off the key.
 *
 * <p>Exclusions:
 * <ul>
 *   <li>Hard, never overridable: the {@link SpaceTravelModule#FORBIDDEN_DIMENSIONS} set reused verbatim from
 *       phase 1a (nether, end, dragonminez:otherworld). A forbidden launch source is never a body.</li>
 *   <li>Configurable default-exclude: {@code dragonminez:time_chamber} (a room, not a planet) and any destination
 *       whose target dimension is not actually loaded on this server. That drops {@code beerus} and {@code cereal}
 *       on installs without those dimensions (they are also unlock NEVER in DMZ), without us hardcoding their ids.</li>
 * </ul>
 */
public final class PlanetRegistry
{
    private PlanetRegistry()
    {
    }

    // The room dimension excluded by default (configurable). Not a planet body: it is an interior.
    public static final String TIME_CHAMBER = "dragonminez:time_chamber";

    // Fallback body set used only when DMZ reports no loaded destinations. These three are the sensible defaults
    // the feature guarantees as bodies (Earth, Namek, Sacred Kai). Never includes anything forbidden.
    private static final List<String> DEFAULT_BODIES = Collections.unmodifiableList(Arrays.asList(
            "minecraft:overworld",
            "dragonminez:namek",
            "dragonminez:sacredkaiplanet"));

    // config-baked toggles, set by PlanetSpawnModule.bakeConfig. Defaults match the module's config defaults so a
    // query before bake still behaves sanely.
    static volatile boolean excludeTimeChamber = true;
    static volatile boolean requireLoadedDimension = true;

    // Cached set of body dimension ids, used only by the hot isBody() launch-eligibility path (queried per player
    // per tick). bodies() itself rebuilds a full Planet list each call: cheap enough at once-per-second scenery
    // spawns, but far too much to run for every player every tick just to answer "is this dimension a body?". So we
    // memoise the key set the first time isBody() is asked and reuse it until invalidate() clears it. Invalidation
    // is driven by datapack reload and config bake (see SpaceTravelModule / PlanetSpawnModule), the only two things
    // that can change the answer, so the cache can never go stale silently. volatile: written on the reload thread,
    // read on the server thread.
    private static volatile Set<String> bodyKeyCache = null;

    /**
     * A single planet body: its stable key (target dimension id), its dimension resource key, and its derived
     * position, tint and visual radius. Position/tint/radius are re-derived from the key, never stored.
     */
    public static final class Planet
    {
        public final String key;
        public final ResourceKey<Level> dimension;

        Planet(String key)
        {
            this.key = key;
            this.dimension = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(key));
        }

        public net.minecraft.world.phys.Vec3 position()
        {
            return PlanetPositions.position(key);
        }

        public int tint()
        {
            return PlanetPositions.tint(key);
        }

        public float radius()
        {
            return PlanetPositions.radius(key);
        }
    }

    // Ordered, deduped-by-key list of the planet bodies that should exist right now, given the loaded DMZ
    // destinations, the server's loaded dimensions and the config toggles. Order is insertion order (stable).
    /**
     * ONE-TICK MEMO, and it is not a micro-optimisation: it is the difference between space being playable and not.
     *
     * <p>This method is not called once per tick, it is called from inside CELL WALKS. A single player in space runs
     * {@code GeneratedPlanets.bodyContaining}, which derives 27 cells; every cell that clears the occupancy gate
     * rejects against the fixed bodies AND derives the neighbours its overlap contest is not able to prune, each of
     * which rejects too. Modelled against the real constants (sector 2048, density 0.5, and the contest's face
     * proximity pruning, which throws away most of the 26 neighbours because a body is rarely near a cell face) that
     * is a median of about 21 rebuilds for that ONE call, with the black hole field's own walk adding roughly another
     * 5 to 15. Call it a few dozen per player per tick.
     *
     * <p>Each of those re-queried DMZ's destination registry, re-parsed a ResourceLocation per destination, allocated
     * a ResourceKey per destination to ask the server whether that level exists, and built three fresh collections,
     * all to compute the same handful of planets. That is the work this removes; it is now one build per tick no
     * matter how many players are in space or how far they fly.
     *
     * <p>The answer only changes when the destination datapack reloads or a dimension appears, neither of which can
     * happen part way through a tick, so caching for the length of one tick is safe by construction rather than by
     * hoping. Keyed on the tick COUNT so it expires on its own with no invalidation hook to forget to call.
     *
     * <p>Only written from the server thread. An off-thread caller gets a freshly built list and never touches the
     * cache, so there is no publication race to reason about and no lock on the hot path.
     */
    private static long cachedTick = Long.MIN_VALUE;
    private static List<Planet> cachedBodies = Collections.emptyList();

    /**
     * Drop the memo. Called on server start, because tick counts restart at 0 with a new world while these statics
     * survive in the same JVM (opening a second singleplayer world), and a tick count that happened to match would
     * serve the previous world's bodies for a tick.
     */
    public static void invalidateCache()
    {
        cachedTick = Long.MIN_VALUE;
        cachedBodies = Collections.emptyList();
        SpaceLayout.invalidateFixedCache();
    }

    public static List<Planet> bodies(MinecraftServer server)
    {
        boolean cacheable = server != null && server.isSameThread();
        if (cacheable && server.getTickCount() == cachedTick)
        {
            return cachedBodies;
        }

        Set<String> forbidden = SpaceTravelModule.FORBIDDEN_DIMENSIONS;
        // dedupe by dimension key: many destinations may share a dimension, but a dimension is one body.
        Map<String, Planet> byKey = new LinkedHashMap<>();

        List<String> candidateKeys = candidateDimensionIds();
        for (String key : candidateKeys)
        {
            if (key == null || key.isEmpty())
            {
                continue;
            }
            // hard, never-overridable exclusion (reuses phase 1a's set).
            if (forbidden.contains(key))
            {
                continue;
            }
            // configurable default exclusion: the time chamber is a room.
            if (excludeTimeChamber && TIME_CHAMBER.equals(key))
            {
                continue;
            }
            // never a valid dimension id -> skip.
            ResourceLocation loc = ResourceLocation.tryParse(key);
            if (loc == null)
            {
                continue;
            }
            // configurable default exclusion: the target dimension must be INSTALLED on this server (a live level, or a
            // datapack level stem we can create one from on landing). Drops beerus/cereal on installs without them, no
            // hardcoded ids. It must NOT require an already-instantiated ServerLevel: a dimension added after the world
            // was created is present in the datapack yet has no level until something visits it, so a plain getLevel
            // check answered "not a planet" forever and the body silently vanished from space and could not be landed
            // on (bug 622). isInstalled treats a stem-backed dimension as a real place; DynamicLevels builds the level
            // when the player actually lands.
            if (requireLoadedDimension && server != null
                    && !DynamicLevels.isInstalled(server, ResourceKey.create(Registries.DIMENSION, loc)))
            {
                continue;
            }
            byKey.putIfAbsent(key, new Planet(key));
        }
        // install the coordinated even-ring layout for this exact body set BEFORE any Planet.position() is read, so the
        // snapshot PlanetPositions.position() serves matches the set we are returning. Cheap: a no-op when the set is
        // unchanged since the last call, a small build (well under a dozen bodies) when it changes.
        PlanetPositions.buildLayout(new ArrayList<>(byKey.keySet()));
        // Unmodifiable because it is now SHARED: a caller that mutated what it was handed would corrupt every other
        // caller for the rest of the tick. Nothing does today, and this makes sure a future one fails loudly at the
        // mutation rather than quietly somewhere else.
        List<Planet> out = Collections.unmodifiableList(new ArrayList<>(byKey.values()));
        if (cacheable)
        {
            cachedBodies = out;
            cachedTick = server.getTickCount();
        }
        return out;
    }

    // the target dimension ids of every loaded DMZ destination, in list order. Falls back to the default body set
    // when DMZ reports nothing (so Earth/Namek/Sacred Kai are always candidates).
    private static List<String> candidateDimensionIds()
    {
        List<String> ids = new ArrayList<>();
        try
        {
            List<SpacePodDestinationDefinition> dests = SpacePodDestinationRegistry.getServerDestinations();
            if (dests != null)
            {
                for (SpacePodDestinationDefinition def : dests)
                {
                    ids.add(def.dimension());
                }
            }
        }
        catch (Throwable ignored)
        {
            // extremely defensive: if DMZ's API shape ever shifts, fall through to defaults rather than crash.
        }
        if (ids.isEmpty())
        {
            ids.addAll(DEFAULT_BODIES);
        }
        return ids;
    }

    // find the body whose target dimension is the given launch dimension, or null if that dimension is not a body.
    // Used by space entry to place the arriving player next to the planet they took off from.
    public static Planet bodyForDimension(MinecraftServer server, ResourceKey<Level> dimension)
    {
        String key = dimension.location().toString();
        for (Planet p : bodies(server))
        {
            if (p.key.equals(key))
            {
                return p;
            }
        }
        return null;
    }

    // true if the given dimension is a planet body right now. Cheap, cache-backed membership test for the per-tick
    // launch-eligibility check: the first call after an invalidation builds the body list once and remembers its
    // keys, every later call is a plain set lookup. The cache is cleared on datapack reload and config bake, so it
    // reflects the current bodies() result without paying its cost on every tick.
    public static boolean isBody(MinecraftServer server, ResourceKey<Level> dimension)
    {
        Set<String> keys = bodyKeyCache;
        if (keys == null)
        {
            keys = new HashSet<>();
            for (Planet p : bodies(server))
            {
                keys.add(p.key);
            }
            bodyKeyCache = keys;
        }
        return keys.contains(dimension.location().toString());
    }

    // Drop the cached body-key set so the next isBody() rebuilds it. Called when the thing bodies() reads can
    // change: a datapack reload (DMZ's destination list) or a config bake (the exclusion toggles).
    public static void invalidate()
    {
        bodyKeyCache = null;
        // the ring radius / separation / jitter that shape the coordinated layout may also have changed, so force the
        // next bodies() call to rebuild the layout snapshot (and re-log its verified min distance) rather than reuse a
        // snapshot built against the old numbers.
        PlanetPositions.invalidateLayout();
    }
}
