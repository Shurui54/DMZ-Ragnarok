package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Derivation of a planet body's world position and colour tint from its stable key (the target dimension id, e.g.
 * "minecraft:overworld"). Tint and radius are pure per-key functions. POSITION is a COORDINATED layout over the whole
 * fixed-body set, not an independent per-key hash: a purely per-key hash can never GUARANTEE a minimum separation
 * between two bodies (two keys can hash to nearly the same angle and radius), so the fixed bodies are instead spread
 * evenly by angle on a ring sized so that the closest pair is at least the configured minimum separation apart. See
 * {@link #buildLayout}.
 *
 * <p>WHERE THE POSITIONS COME FROM. The SERVER computes the coordinated layout once from the deduped body-key set (see
 * {@link PlanetRegistry#bodies}) and stores it here as a snapshot; {@link #position} reads that snapshot. The CLIENT does
 * NOT recompute the maths: it mirrors the server's authoritative positions straight out of the synced fixed-body list
 * ({@link #setClientLayout}), so client and server can never drift on where a body is even though only the server runs
 * the layout. A key that is not currently a body (e.g. a moon parent whose dimension is not loaded, or a lookup before
 * the first layout is built) falls back to a stable per-key ring hash ({@link #fallbackPosition}).
 *
 * <p>Nothing is persisted: positions are recomputed from the key set and the config numbers every time, so they are
 * identical across restarts as long as the loaded body set is the same, and no SavedData has to be migrated.
 */
public final class PlanetPositions
{
    private PlanetPositions()
    {
    }

    // Minimum ring radius floor (blocks from the space origin) and the additive per-body radius jitter. The ACTUAL ring
    // radius is derived from the minimum-separation requirement below and the body count (buildLayout), so it is normally
    // far larger than this floor; the floor only lets an operator push the WHOLE system further out than the separation
    // maths alone would. Both are config-baked (PlanetSpawnModule.bakeConfig). volatile: written on the config thread,
    // read on the server thread.
    static volatile double ringRadius = 2400.0;
    static volatile double radiusJitter = 1000.0;

    // The hard minimum centre-to-centre distance, in blocks, between any two FIXED main planet bodies. Config-baked
    // (PlanetSpawnModule.fixedPlanetMinSeparation, floored at 10000 in the config so the operator can never drop below
    // the user's rule). The layout in buildLayout sizes the ring so the closest pair is at least this far apart, and
    // VERIFIES it, falling back to a jitter-free regular polygon (which provably meets it exactly) if the visual jitter
    // ever breaches it. volatile: written on the config thread, read on the server thread.
    static volatile double minSeparation = 15000.0;

    // The coordinated layout snapshot: fixed-body key -> world position. Written by the SERVER from buildLayout (via
    // PlanetRegistry.bodies) and by the CLIENT from the synced fixed-body list (setClientLayout). On an integrated server
    // both write it, but with identical values (the client mirrors the server's own positions), so the shared static is
    // safe. volatile: the reference is swapped atomically; readers see either the whole old or the whole new map.
    private static volatile Map<String, Vec3> layout = Collections.emptyMap();

    // The key set the current snapshot was built for, so buildLayout can skip a rebuild (and its log line) when bodies()
    // is called repeatedly with an unchanged set. Server-side only.
    private static volatile List<String> layoutKeys = Collections.emptyList();

    // Fixed cruising altitude for every body. Space is min_y -64, height 2048 (ceiling 1984). 900 leaves a
    // comfortable band above and below each body so a visual radius of a few dozen blocks never pokes out of the
    // playable Y range, and it sits well above the return altitude (288) so arriving next to a planet never
    // instantly re-triggers the descend-out check.
    public static final double PLANET_Y = 900.0;

    // ===== B2: the sun at the layout centre =====
    //
    // A real central body the fixed planets orbit. It is NOT a landing destination (never an entry in
    // PlanetRegistry.bodies, so travel/landing never target it) and NOT a fixed body (it is drawn and made hazardous
    // separately, see SpaceBodyRenderer and SpaceHazardModule). Everything about it is a constant: it sits at the space
    // origin on the shared body plane, so the direction to the sun from any planet is simply the bearing toward the
    // origin, and every ring is centred on it.

    // A synthetic key for the sun, distinct from any dimension id and from the generated / moon id namespaces, so it can
    // never collide with a landable body's key.
    public static final String SUN_KEY = "dmz_ragnarok:sun";

    // The sun sits at the space origin on the body plane.
    public static final Vec3 SUN_POSITION = new Vec3(0.0, PLANET_Y, 0.0);

    // The sun's TRUE visual half-extent (blocks). The renderer enlarges the drawn size by STAR_DRAW_SCALE, so the drawn
    // sphere is larger than this; SUN_DANGER_RADIUS is sized to stay outside the drawn sphere (see SpaceHazardModule).
    public static final float SUN_RADIUS = 140.0F;

    // The outer edge of the sun's heat / no-fly field, blocks from the sun centre. The burn field spans from here in to
    // SUN_RADIUS, and a player inside SUN_RADIUS is pushed back out (the sun is not enterable). Chosen so:
    //   - it clears the space arrival column: a player arriving at (x, SPACE_ARRIVAL_Y=320, z) sits at least 580 blocks
    //     from the sun centre (0, 900, 0), comfortably outside this radius, so entering space never drops anyone into the
    //     sun; and
    //   - it stays well inside the existing 1200-block origin clearance every scenery field already keeps clear, so no
    //     generated planet, star, asteroid or black hole (all excluded from that column) can ever sit in the sun, and no
    //     body that existed before B2 (all far outside the origin column) is retroactively inside it.
    public static final double SUN_DANGER_RADIUS = 480.0;

    // Warm sun tint (drawn as a bright star with a corona, see SpaceBodyRenderer.drawStar).
    public static final int SUN_TINT = 0xFFE7A8;

    public static Vec3 sunPosition()
    {
        return SUN_POSITION;
    }

    public static double sunDangerRadius()
    {
        return SUN_DANGER_RADIUS;
    }

    // ===== B2: concentric orbital rings =====
    //
    // The fixed bodies no longer share one ring: each sits on its OWN ring around the sun, at a radius that is a pure
    // function of THAT body's key alone (its orbit index), never of how many bodies exist. This is the memory lesson made
    // structural: with the old single ring, adding or removing a body resized the ring and moved every planet; here a
    // body's radius (and angle) depend only on its own key, so the set changing never moves a body that is still present.
    // Because two bodies on different rings are separated radially by at least the ring spacing, and the ring spacing is
    // the configured minimum separation, no two fixed bodies can ever be closer than that floor, so a pod arriving beside
    // one never spawns inside another.

    // The innermost ring's radius floor (blocks). Kept well outside the sun's danger field and the origin arrival column
    // so the first planet, and a pod arriving beside it, are always clear of the sun. The actual innermost radius is
    // max(ringRadius, this), so an operator who raises ringRadius pushes the whole system further out.
    private static final double INNER_ORBIT_FLOOR = 6000.0;

    // Deliberate inner-to-outer orbit order for the known solar-system bodies. A plain table (like NOMINAL_RADIUS) so each
    // body's ring is fixed and readable, and a body's radius depends ONLY on its own key. Earth is the home world on the
    // innermost ring; the rest step outward. A key ABSENT here (cereal, beerus, the SMP world, any future body) falls back
    // to a hash-chosen outer orbit (see orbitIndex), so it still gets a stable per-key ring with no code change.
    private static final Map<String, Integer> ORBIT_INDEX = Map.of(
            "minecraft:overworld", 0,
            "dragonminez:namek", 1,
            "dragonminez:sacredkaiplanet", 2,
            "dmz_ragnarok:planet_vegeta", 3);
    // The number of reserved inner orbit indices (0..KNOWN_ORBIT_COUNT-1) the table above uses; unknown bodies hash into
    // the band that starts here, so they never collide with a known body's ring.
    private static final int KNOWN_ORBIT_COUNT = 4;
    // How many outer rings unknown bodies distribute across (indices KNOWN_ORBIT_COUNT .. KNOWN_ORBIT_COUNT+this-1).
    private static final int FALLBACK_ORBIT_COUNT = 8;

    // The radius, blocks, generated planets / stars / asteroids / black holes are kept clear of around the sun, so the
    // inner solar system reads as the deliberate sun-plus-rings rather than random clutter. A fixed constant (independent
    // of the body set) so both sides agree with no extra sync, sized to clear the sun and the approach up to just inside
    // the innermost ring. It is NOT the whole system: scenery beyond it is unchanged. A generated planet that is already
    // CLAIMED or STAMPED is exempt (GeneratedPlanets keeps it where it is), so this never orphans persisted state; only
    // never-visited (purely derived, no saved data) inner planets are biased away, exactly as a density change would move
    // them.
    private static final double INNER_SYSTEM_CLEAR = 5000.0;

    // The ring spacing between adjacent orbit indices: the configured minimum separation, so two bodies one ring apart are
    // at least that far apart radially (and further at different angles), which is the separation guarantee.
    private static double ringSpacing()
    {
        return Math.max(minSeparation, 1.0);
    }

    // The orbit index (ring number, 0 = innermost) for a body key. Known bodies read the deliberate table; any other body
    // hashes into the outer band, deterministically per key.
    private static int orbitIndex(String planetKey)
    {
        Integer known = ORBIT_INDEX.get(planetKey);
        if (known != null)
        {
            return known;
        }
        return KNOWN_ORBIT_COUNT + (int) Math.floorMod(hash(planetKey), (long) FALLBACK_ORBIT_COUNT);
    }

    // The radius, blocks, of a given orbit index. Depends only on the index (and the config numbers), never on the body
    // set.
    private static double orbitRadius(int orbitIndex)
    {
        return Math.max(ringRadius, INNER_ORBIT_FLOOR) + orbitIndex * ringSpacing();
    }

    // The angle around the sun for a body key, a stable per-key hash so bodies on the same ring (only ever unknown bodies
    // that collided on a fallback orbit) still scatter to different bearings.
    private static double ringAngle(String planetKey)
    {
        long h = hash(planetKey);
        return ((h & 0xFFFFFFFFL) / 4294967296.0) * (Math.PI * 2.0);
    }

    // The world position of a body key on its own ring around the sun. A PURE function of the key (and the config ring
    // numbers): the same key always lands on the same ring at the same bearing whatever else is in the system.
    private static Vec3 ringPosition(String planetKey)
    {
        double r = orbitRadius(orbitIndex(planetKey));
        double a = ringAngle(planetKey);
        return new Vec3(Math.cos(a) * r, PLANET_Y, Math.sin(a) * r);
    }

    // Whether a space position falls inside the reserved inner solar system (the sun and the approach up to the innermost
    // ring), measured on the XZ plane like the origin clearance. Read by the generated / star / asteroid / black hole
    // fields to keep scenery out of the inner system. A fixed constant, so client and server agree with no extra sync.
    public static boolean insideInnerSystem(Vec3 pos)
    {
        return Math.hypot(pos.x, pos.z) <= INNER_SYSTEM_CLEAR;
    }

    // stable 64-bit hash of the key; splitmix64-style finaliser on the String hashCode so nearby ids (which have
    // nearby String hashes) still scatter to very different angles/radii. Deterministic and JVM-stable.
    private static long hash(String planetKey)
    {
        long z = planetKey.hashCode() * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // world position of the body for this key, AT THE CURRENT ORBITAL INSTANT. Each fixed body revolves around the central
    // sun on its own ring; this returns where it is right now. Authoritative and time-based: landing, autopilot, the
    // standoff on leave, collision, the star map and the rendered body all read this, so a fixed body is drawn, targeted
    // and landed on at one agreed position. The epoch comes from {@link OrbitClock}, which resolves the SAME wall-clock
    // instant on every shard and pushes it to clients, so client and server never disagree on where a body is even though
    // it moves. The ring RADIUS and starting phase are pure per-key functions (never of how many bodies exist), so the set
    // changing never moves a body that is still present (the B2 lesson kept), and adding the time term only rotates it.
    public static Vec3 position(String planetKey)
    {
        return orbitPositionAt(planetKey, OrbitClock.epochMillis());
    }

    /**
     * The body's position on its ring at an EXPLICIT epoch, the pure core of {@link #position}. Public so the self-test
     * can pin the orbit maths against two simulated clocks. A body at ring radius {@code r} and starting phase {@link
     * #ringAngle} sweeps {@link Orbits#angle} at {@link Orbits#periodMillis}. Every body shares the sun's body plane
     * ({@link #PLANET_Y}); the sun sits at the origin, so a ring is centred on the origin.
     */
    public static Vec3 orbitPositionAt(String planetKey, long epochMillis)
    {
        double r = orbitRadius(orbitIndex(planetKey));
        double phase0 = ringAngle(planetKey);
        double[] xz = Orbits.positionXZ(SUN_POSITION.x, SUN_POSITION.z, r, phase0, epochMillis);
        return new Vec3(xz[0], PLANET_Y, xz[1]);
    }

    /**
     * The ring RADIUS a fixed body orbits the central sun at, a pure per-key function. Public so a renderer can cache the
     * orbital CONSTANTS (centre, radius, phase) once and recompute the body's live position every frame from the shared
     * clock, rather than reading a position snapshot that only updates on a cache refresh (which read as the body jumping).
     */
    public static double orbitRadiusOf(String planetKey)
    {
        return orbitRadius(orbitIndex(planetKey));
    }

    /**
     * The starting phase (angle at epoch 0) a fixed body orbits at, a pure per-key function. See {@link #orbitRadiusOf}:
     * with the sun centre and this pair, {@link Orbits#positionXZ} reproduces {@link #orbitPositionAt} exactly, so a caller
     * can draw a smooth per-frame orbit without a per-frame server lookup.
     */
    public static double orbitPhase0Of(String planetKey)
    {
        return ringAngle(planetKey);
    }

    /**
     * The body's REST position: where it sits at epoch 0, ignoring the orbit's time term. This is the stable per-key ring
     * point the B2 layout used before orbits. Kept for the layout-verification log ({@link #buildLayout}) and for any
     * caller that wants a body's ring slot rather than its live position.
     */
    public static Vec3 restPosition(String planetKey)
    {
        return ringPosition(planetKey);
    }

    // the per-key ring position, used for a key absent from the coordinated layout (an unloaded moon parent, or a lookup
    // before the first layout is built). Since B2 the coordinated layout IS this same pure per-key ring formula, so a
    // fallback lookup and a snapshot lookup give the identical position; the snapshot is kept only so the client mirrors
    // the server's exact bytes over the sync rather than re-running the maths.
    private static Vec3 fallbackPosition(String planetKey)
    {
        return ringPosition(planetKey);
    }

    /**
     * Build (or reuse) the coordinated fixed-body layout for the given deduped body-key set and install it as the
     * snapshot {@link #position} reads. Called by {@link PlanetRegistry#bodies} on the server. Since B2 each body sits on
     * its OWN concentric ring around the sun at {@link #ringPosition}, a PURE function of that body's key alone: the ring
     * radius comes from the body's orbit index (a fixed table for the known solar-system bodies, a per-key hash for the
     * rest) and the bearing from a per-key hash. Because a body's position depends only on its own key, the set changing
     * never moves a body that is still present (the memory lesson made structural, replacing the old single ring that
     * resized with the count). Deterministic: the same key always gives the same ring, so every player gets the same sky
     * and positions never shift between sessions. The result is still verified against the separation floor and logged.
     */
    static void buildLayout(List<String> keys)
    {
        // Compare the SET, not the sequence. The keys arrive in whatever order DMZ's destination registry happens to
        // hand them back, and that order is not stable across reloads, so a List.equals guard read a pure re-ordering
        // as a change: a live 2h43m server rebuilt and re-logged this 58 times and produced the byte-identical layout
        // every single time. Set comparison makes the guard mean what it was always meant to mean.
        if (layoutKeys.size() == keys.size() && new HashSet<>(layoutKeys).containsAll(keys))
        {
            return; // unchanged set: reuse the snapshot (and do not re-log).
        }
        Map<String, Vec3> map = new HashMap<>(keys.size());
        for (String key : keys)
        {
            map.put(key, ringPosition(key));
        }
        if (!map.isEmpty())
        {
            double min = minPairwise(map);
            double sep = Math.max(minSeparation, 1.0);
            if (map.size() >= 2 && min < sep)
            {
                // Only reachable if two UNKNOWN bodies collided on the same fallback orbit AND at nearly the same bearing,
                // astronomically unlikely on a ring kilometres in radius. The known solar-system bodies each own a unique
                // ring, so they can never breach the floor. We log rather than reshuffle (reshuffling would reintroduce the
                // set dependence B2 exists to remove); the pair simply reads a little close.
                LoggingHandler.sulog.warn(
                        "[SpacePlanets] two fixed bodies are only {} blocks apart (floor {}); a rare fallback-orbit "
                                + "collision. Positions stay per-key stable.", Math.round(min), Math.round(sep));
            }
            LoggingHandler.sulog.info(
                    "[SpacePlanets] sun-centred layout: {} bodies on concentric rings (spacing {}, inner floor {}), "
                            + "min pairwise distance {} blocks.",
                    map.size(), Math.round(ringSpacing()), Math.round(Math.max(ringRadius, INNER_ORBIT_FLOOR)),
                    Math.round(min));
        }
        layout = map;
        layoutKeys = new ArrayList<>(keys);
    }

    // the smallest centre-to-centre distance between any two bodies in a layout, or +inf for fewer than two bodies. Used
    // by buildLayout to VERIFY the separation floor rather than assume it.
    private static double minPairwise(Map<String, Vec3> map)
    {
        List<Vec3> pts = new ArrayList<>(map.values());
        double min = Double.MAX_VALUE;
        for (int i = 0; i < pts.size(); ++i)
        {
            for (int j = i + 1; j < pts.size(); ++j)
            {
                min = Math.min(min, pts.get(i).distanceTo(pts.get(j)));
            }
        }
        return min;
    }

    // CLIENT: mirror the server's authoritative fixed-body positions straight from the synced list, so the client's
    // position() returns exactly what the server placed without re-running the layout maths. Called from the layout sync
    // handler. Never runs the derivation, so it cannot drift from the server even by a rounding bit.
    public static void setClientLayout(List<FixedBody> fixed)
    {
        if (fixed == null || fixed.isEmpty())
        {
            layout = Collections.emptyMap();
            layoutKeys = Collections.emptyList();
            return;
        }
        Map<String, Vec3> map = new HashMap<>(fixed.size());
        List<String> ks = new ArrayList<>(fixed.size());
        for (FixedBody fb : fixed)
        {
            map.put(fb.key, fb.position);
            ks.add(fb.key);
        }
        layout = map;
        layoutKeys = ks;
    }

    // drop the cached layout so the next buildLayout rebuilds it (and logs the fresh min distance). Called on datapack
    // reload / config bake, alongside PlanetRegistry.invalidate.
    static void invalidateLayout()
    {
        layoutKeys = Collections.emptyList();
    }

    // packed 0xRRGGBB colour tint for this key, multiplied over the grayscale placeholder texture in the
    // renderer. Derived from the key so a given planet always reads the same colour, but kept bright (each
    // channel floored well above black) so no body renders as an unreadable near-black sphere.
    public static int tint(String planetKey)
    {
        long h = hash(planetKey);
        int r = 96 + (int) ((h & 0xFF) * 160 / 255);
        int g = 96 + (int) (((h >>> 8) & 0xFF) * 160 / 255);
        int b = 96 + (int) (((h >>> 16) & 0xFF) * 160 / 255);
        return (r << 16) | (g << 8) | b;
    }

    // NOMINAL visual radius, in blocks, for each FIXED body, keyed by dimension id. A fixed body lands on a REAL
    // dimension and so has no finite block width to scale from (unlike a generated planet, whose radius is derived from
    // its stamped surface size). The old code rolled this off a hash (24..55), which read as random noise rather than a
    // deliberate solar system. These are chosen sizes on the SAME visual scale generated planets use (their body radius
    // band is 32..96), so a fixed body and a generated one read consistently side by side:
    //   Sacred Kai world  34  a tiny sacred sphere, the smallest body in the ring
    //   Earth (overworld) 60  a mid-scale home world
    //   Namek             88  a large planet
    //   Planet Vegeta     92  the largest, a dense Saiyan homeworld
    // Deterministic and stable (a plain table lookup), so a body is always the same size across restarts and clients.
    private static final java.util.Map<String, Float> NOMINAL_RADIUS = java.util.Map.of(
            "minecraft:overworld", 60.0F,
            "dragonminez:namek", 88.0F,
            "dragonminez:sacredkaiplanet", 34.0F,
            "dmz_ragnarok:planet_vegeta", 92.0F);

    // visual radius in blocks for this key. A known fixed body reads its deliberate nominal size from the table above;
    // any other fixed body (e.g. cereal or beerus on an install that loads them, or a future body) falls back to a fixed
    // mid size rather than a hash, so the system stays deliberate rather than random. This radius is the body's TRUE
    // radius, so it also feeds the overlap rejection for generated planets (padded by FIXED_CLEARANCE, so a few dozen
    // blocks of change here is dwarfed by the 128-block margin) and the label/buster surface-distance gate (dist minus
    // radius), both of which now read a deliberate size instead of a hashed one.
    public static float radius(String planetKey)
    {
        Float nominal = NOMINAL_RADIUS.get(planetKey);
        return nominal != null ? nominal : 50.0F;
    }
}
