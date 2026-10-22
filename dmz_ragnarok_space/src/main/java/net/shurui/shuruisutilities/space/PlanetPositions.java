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

    // The main system's reference plane: the sun's height, which every fixed ring is tilted about. Space is now the full
    // vanilla column (SpaceDimension.COLUMN_MIN_Y -2032 to COLUMN_TOP_Y 2032), so the sun sits at its centre, Y 0, and the
    // tilted rings swing planets up to MAIN_MAX_LIFT above and below it with the same room either way. Was 900 when the
    // column was -64..1984. Nothing persisted depends on it: fixed bodies are re-derived every tick, and the super bodies
    // keep their stored plane heights, which still sit inside SuperPlanetPositions' band.
    public static final double PLANET_Y = 0.0;

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
    //   - it clears the space arrival column: a player arriving at (x, SPACE_ARRIVAL_Y = PLANET_Y - 580, z) sits at least
    //     580 blocks from the sun centre, comfortably outside this radius, so entering space never drops anyone into the
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
    // The fixed bodies no longer share one ring: each sits on its OWN ring around the sun (or, for a designed pair, shares
    // its anchor's ring half a turn away, see ORBIT_PARTNER), at a radius that is a pure function of THAT body's key alone
    // (its orbit index), never of how many bodies exist. This is the memory lesson made
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
    // innermost ring; the rest step outward. A key ABSENT here (cereal, any future body) falls back to a hash-chosen outer
    // orbit (see orbitIndex), so it still gets a stable per-key ring with no code change.
    //
    // Beerus planet is listed at index 5 because that is exactly the ring its key used to hash to, so pinning it here did
    // not move it; it is pinned so its ring can be SHARED (see ORBIT_PARTNER) and kept out of the hash fallback band.
    private static final Map<String, Integer> ORBIT_INDEX = Map.of(
            "minecraft:overworld", 0,
            "dragonminez:namek", 1,
            "dragonminez:sacredkaiplanet", 2,
            "dmz_ragnarok:planet_vegeta", 3,
            "dmz_ragnarok:beerus_planet", 5);
    // The number of reserved inner orbit indices (0..KNOWN_ORBIT_COUNT-1) the table above uses; unknown bodies hash into
    // the band that starts here, so they never collide with a known body's ring.
    private static final int KNOWN_ORBIT_COUNT = 4;
    // How many outer rings unknown bodies distribute across (indices KNOWN_ORBIT_COUNT .. KNOWN_ORBIT_COUNT+this-1).
    private static final int FALLBACK_ORBIT_COUNT = 8;
    // A ring inside the fallback band that a table body owns (beerus planet and its partner). An unknown key that hashes
    // onto it is moved one ring past the whole band instead, so nothing else ever shares the pair's ring. Only a key that
    // would have collided moves; every other unknown key keeps the ring it always had.
    private static final int RESERVED_FALLBACK_INDEX = 5;
    private static final int RESERVED_FALLBACK_BUMP = KNOWN_ORBIT_COUNT + FALLBACK_ORBIT_COUNT;

    // SHARED ORBITS (owner, 2026-10-21: "I want namek ow to be opposite to namek on the same orbit, same with smp and
    // earth, and kaiow and beerus planet, so they are not so far apart"). Each PARTNER key rides its ANCHOR's ring at the
    // anchor's phase plus half a turn. Same ring radius means the same orbital period (Orbits.periodMillis is a function of
    // the radius alone), so the two stay exactly opposite each other for ever, on every shard and client, with nothing
    // stored or synced beyond what the anchor already uses. Partner -> anchor. Before this the partners hashed onto outer
    // fallback rings (SMP ring 4, Namek OW and Kai OW both ring 10).
    //
    // Owner, 2026-10-21 (DMZ 2.2): "demon realm should be on the same ring as beerus planet, a quarter rotation from
    // kaiow". DMZ 2.2's Demon Realm (dragonminez:demon_realm, a pod destination) rides Beerus's ring a quarter turn
    // ahead of Beerus, so Beerus, Demon Realm and Kai OW sit at 0, 90 and 180 degrees on ring 5. Before this it hashed
    // onto its own fallback ring. The phase offset of each partner is in ORBIT_PHASE (half a turn when absent).
    private static final Map<String, String> ORBIT_PARTNER = Map.of(
            "dmz_ragnarok:smp", "minecraft:overworld",
            "dmz_ragnarok:namekow", "dragonminez:namek",
            "dmz_ragnarok:kaiow", "dmz_ragnarok:beerus_planet",
            "dragonminez:demon_realm", "dmz_ragnarok:beerus_planet");

    // A partner's phase ahead of its anchor, radians. Absent: half a turn (opposite).
    private static final Map<String, Double> ORBIT_PHASE = Map.of(
            "dragonminez:demon_realm", Math.PI / 2.0);

    private static double partnerPhase(String partnerKey)
    {
        Double phase = ORBIT_PHASE.get(partnerKey);
        return phase != null ? phase : Math.PI;
    }

    /** The anchor a partner body shares its orbit with (half a turn ahead of it), or null for a body with no partner. */
    public static String orbitAnchorOf(String planetKey)
    {
        return ORBIT_PARTNER.get(planetKey);
    }

    // true if the two keys are a designed shared-orbit pair (either order). The layout check below accepts their
    // diameter-apart separation by design instead of warning about it.
    private static boolean arePartners(String a, String b)
    {
        return b.equals(ORBIT_PARTNER.get(a)) || a.equals(ORBIT_PARTNER.get(b));
    }

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
        String anchor = ORBIT_PARTNER.get(planetKey);
        if (anchor != null)
        {
            planetKey = anchor;   // a partner rides its anchor's ring.
        }
        Integer known = ORBIT_INDEX.get(planetKey);
        if (known != null)
        {
            return known;
        }
        int index = KNOWN_ORBIT_COUNT + (int) Math.floorMod(hash(planetKey), (long) FALLBACK_ORBIT_COUNT);
        return index == RESERVED_FALLBACK_INDEX ? RESERVED_FALLBACK_BUMP : index;
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
        String anchor = ORBIT_PARTNER.get(planetKey);
        if (anchor != null)
        {
            // ahead of the anchor on the same ring by a fixed phase (half a turn unless ORBIT_PHASE says otherwise);
            // one ring means one period, so the spacing holds for ever.
            return ringAngle(anchor) + partnerPhase(planetKey);
        }
        long h = hash(planetKey);
        return ((h & 0xFFFFFFFFL) / 4294967296.0) * (Math.PI * 2.0);
    }

    // ===== 3D: each fixed ring is tilted =====
    //
    // Owner, 2026-10-22: "I want planets to be in real 3 dimensional space". Each ring has its OWN orbital plane, a pure
    // function of the ring index (never of the body set), so every body on a ring shares it: the shared-orbit pairs stay
    // exactly opposite (half a turn on one tilted circle is the antipode through the sun) and Demon Realm stays a quarter
    // turn from Beerus. The tilt is sized by the vertical swing it gives, not by an angle, because the rings are kilometres
    // apart: a ring swings its bodies up to MAIN_MAX_LIFT * (0.55..1) above and below the sun plane, capped so no ring is
    // tilted past MAIN_MAX_INCLINATION. The innermost ring (6000) gets about 11 degrees; outer rings get a fraction of a
    // degree each but the same ~1 km swing. Worst case a body sits PLANET_Y +- (1200 + 92 radius + its moon's orbit,
    // ~330) = +-1622, inside SpaceDimension.BODY_FLOOR_Y..BODY_CEILING_Y (-1776..1968).
    static final double MAIN_MAX_LIFT = 1200.0;
    private static final double MAIN_MAX_INCLINATION = Math.toRadians(12.0);

    // the inclination (radians) of fixed ring {@code index}: its vertical swing over its radius.
    private static double ringInclination(int index)
    {
        double r = orbitRadius(index);
        double fraction = 0.55 + 0.45 * ringUnit(index, 0);
        double lift = Math.min(MAIN_MAX_LIFT, r * Math.sin(MAIN_MAX_INCLINATION)) * fraction;
        return Math.asin(Math.min(1.0, lift / Math.max(1.0, r)));
    }

    // the line of nodes (radians) of fixed ring {@code index}.
    private static double ringNode(int index)
    {
        return ringUnit(index, 24) * (Math.PI * 2.0);
    }

    // a 0..1 value from a per-ring hash window, so a ring's tilt is stable and identical on both sides.
    private static double ringUnit(int index, int shift)
    {
        long z = (index + 0x5EEDL) * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= (z >>> 31);
        return ((z >>> shift) & 0xFFFFFFL) / 16777216.0;
    }

    /** The tilt (radians) of the ring a fixed body orbits on, for renderers that recompute its live position. */
    public static double orbitInclinationOf(String planetKey)
    {
        return ringInclination(orbitIndex(planetKey));
    }

    /** The line of nodes (radians) of the ring a fixed body orbits on. */
    public static double orbitNodeOf(String planetKey)
    {
        return ringNode(orbitIndex(planetKey));
    }

    // The world position of a body key on its own ring around the sun at epoch 0. A PURE function of the key (and the
    // config ring numbers): the same key always lands on the same ring at the same bearing whatever else is in the system.
    private static Vec3 ringPosition(String planetKey)
    {
        return orbitPositionAt(planetKey, 0L);
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
     * #ringAngle} sweeps {@link Orbits#angle} at {@link Orbits#periodMillis} on its ring's tilted plane through the sun
     * ({@link #orbitInclinationOf} / {@link #orbitNodeOf}); the sun sits at the origin, so a ring is centred on it.
     */
    public static Vec3 orbitPositionAt(String planetKey, long epochMillis)
    {
        int index = orbitIndex(planetKey);
        double r = orbitRadius(index);
        double phase0 = ringAngle(planetKey);
        double[] xyz = Orbits.inclinedPosition(SUN_POSITION.x, SUN_POSITION.y, SUN_POSITION.z, r, phase0, epochMillis,
                ringInclination(index), ringNode(index));
        return new Vec3(xyz[0], xyz[1], xyz[2]);
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
     * The radius, blocks from the sun, of fixed orbit ring {@code index} (0 = innermost) under the current config ring
     * numbers. Public so the super dragon ball bodies can keep their own orbits in the gaps BETWEEN the fixed rings.
     */
    public static double ringRadiusOfIndex(int index)
    {
        return orbitRadius(index);
    }

    /** The largest nominal half-extent any fixed body has (Planet Vegeta), for clearance maths against the rings. */
    public static final float MAX_FIXED_RADIUS = 92.0F;

    /**
     * The starting phase (angle at epoch 0) a fixed body orbits at, a pure per-key function. See {@link #orbitRadiusOf}:
     * with the sun centre, this, {@link #orbitInclinationOf} and {@link #orbitNodeOf}, {@link Orbits#inclinedPosition}
     * reproduces {@link #orbitPositionAt} exactly, so a caller
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
            logPartners(map);
            if (map.size() >= 2 && min < sep)
            {
                // Only reachable if two UNKNOWN bodies collided on the same fallback orbit AND at nearly the same bearing,
                // astronomically unlikely on a ring kilometres in radius. The known solar-system bodies each own a ring
                // (a shared-orbit pair shares one, half a turn apart, and is left out of minPairwise), so they can never
                // breach the floor. We log rather than reshuffle (reshuffling would reintroduce the
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
    // A designed shared-orbit pair is left out: the two sit a ring DIAMETER apart (2 x the ring radius, at least 12000 on
    // the innermost ring) on purpose, and they can never close in because they share one period. logPartners reports them.
    private static double minPairwise(Map<String, Vec3> map)
    {
        List<Map.Entry<String, Vec3>> pts = new ArrayList<>(map.entrySet());
        double min = Double.MAX_VALUE;
        for (int i = 0; i < pts.size(); ++i)
        {
            for (int j = i + 1; j < pts.size(); ++j)
            {
                if (arePartners(pts.get(i).getKey(), pts.get(j).getKey()))
                {
                    continue;
                }
                min = Math.min(min, pts.get(i).getValue().distanceTo(pts.get(j).getValue()));
            }
        }
        return min;
    }

    // one line per shared-orbit pair present in the layout: the ring they share and how far apart they sit (always the
    // ring diameter). A partner whose anchor is not loaded on this install just orbits alone on the anchor's ring.
    private static void logPartners(Map<String, Vec3> map)
    {
        for (Map.Entry<String, String> e : ORBIT_PARTNER.entrySet())
        {
            Vec3 partner = map.get(e.getKey());
            Vec3 anchor = map.get(e.getValue());
            if (partner == null)
            {
                continue;
            }
            double phase = partnerPhase(e.getKey());
            String where = Math.abs(phase - Math.PI) < 1e-9 ? "opposite"
                    : Math.round(Math.toDegrees(phase)) + " degrees ahead of";
            LoggingHandler.sulog.info("[SpacePlanets] shared orbit: {} {} {} on ring {} (radius {}), {} blocks apart{}.",
                    e.getKey(), where, e.getValue(), orbitIndex(e.getKey()), Math.round(orbitRadiusOf(e.getKey())),
                    anchor == null ? "-" : String.valueOf(Math.round(partner.distanceTo(anchor))),
                    anchor == null ? " (anchor not loaded here)" : "");
        }
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
