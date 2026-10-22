package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * The single authority for GENERATED STAR SYSTEMS in space, the sun-and-planets replacement for the old scattered
 * per-cell generated-planet field. Space is divided into a SPARSE, COARSE {@link #sectorSize}-block grid (much rarer
 * than the old planet field and far wider, so systems sit far apart); a {@link #density} fraction of cells hold ONE
 * system: a fixed system STAR plus 5 to 9 PLANETS on concentric rings, each planet ORBITING its star with the same
 * time-based model the fixed main planets use ({@link Orbits} + {@link OrbitClock}). The star never moves.
 *
 * <h3>Pure and cross-side identical</h3>
 * Everything here is a deterministic pure function of the cell coordinates and the two synced params ({@link #sectorSize}
 * / {@link #density}), plus the shared destroyed seam ({@link SpaceLayout#isDestroyed}) and the shared wall clock
 * ({@link OrbitClock}). It deliberately does NOT reject a system against the FIXED bodies (which orbit, so their
 * positions change every tick): that keeps the system layout a stable pure function so the client derives byte-identical
 * systems from the two synced numbers alone, exactly as {@link PlanetPositions} keeps the fixed layout per-key stable.
 * It DOES keep systems out of the reserved inner solar system, a pure check. (The super dragon ball bodies keep clear of
 * systems on their side, see {@link SuperPlanetPositions#chooseOrbit}.)
 *
 * <h3>How the rest of the code picks them up</h3>
 * The suns are surfaced through {@link StarPositions#starsNear} (which now delegates here), so the space renderer and the
 * star burn hazard draw and heat them with no change. The planets are surfaced through {@link GeneratedPlanets#generatedNear}
 * / {@link GeneratedPlanets#bodyContaining} (which now union in the system planets), so drawing, landing, claims, salvage,
 * the planet-info readout, the star map and the pod autopilot all treat a system planet exactly like an old generated
 * planet: it carries a {@code sugen:} id, and its {@code cellKey} is its OWN id (like a moon), so it takes no part in the
 * wreck / regeneration machinery (a destroyed system planet simply stops existing, no rubble, no respawn).
 */
public final class GeneratedSystems
{
    private GeneratedSystems()
    {
    }

    // Coarse system-grid params, synced by PacketSpaceLayoutSync so the client derives the identical systems. Defaults
    // make systems RARER than the old planet field (which ran density 0.5 over a 2048 sector). The sector was 24000, which
    // left space reading empty (the near draw distance is ~8000, so a system was rarely within it); it is now 20000, so
    // neighbouring system stars sit at least 0.6 sectors = 12000 blocks apart (was 14400), a modest "bit closer" that does
    // NOT meaningfully raise server generation load (a player still has at most one system within the near draw distance
    // at a time), while the client now draws EVERY system's sun as a distant beacon (SpaceBodyRenderer far-sun pass), so
    // the sky is populated whatever the spacing. volatile: written on the config/network thread, read on the server and
    // render threads.
    public static volatile int sectorSize = 20000;
    public static volatile double density = 0.5;

    // REAL 3D SYSTEMS (owner, 2026-10-22: "I want planets to be in real 3 dimensional space", after asking for "planets
    // straight above and below you"). The space column is now the vanilla maximum (SpaceDimension, -2032..2032), and a
    // system uses it in two ways:
    //
    //   1. STEEP ORBITAL PLANES. Each system has a base plane tilted MIN_SYSTEM_INCLINATION..MAX_SYSTEM_INCLINATION about
    //      a hashed line of nodes, and every planet gets its own plane near it (inclination +- PLANET_INCLINATION_JITTER,
    //      node +- PLANET_NODE_JITTER), so a system reads as a 3D cluster rather than one tilted disc. Each planet's tilt
    //      is capped so its vertical swing, ring * sin(inclination), never exceeds MAX_PLANET_LIFT.
    //   2. THE STAR SPREADS THROUGH THE WHOLE COLUMN. With L the largest swing of this system's planets (every planet
    //      slot, destroyed or not, so destroying a planet never moves the star), the star Y is uniform in
    //        [BODY_FLOOR_Y + L + maxBodyRadius, BODY_CEILING_Y - L - maxBodyRadius].
    //      L <= MAX_PLANET_LIFT 1600 and maxBodyRadius 96 keep that band non-empty (-80..272 at worst, the whole column
    //      for a gently tilted system).
    //
    // The reachability guarantee is unchanged in kind: every planet, swing and body radius included, stays inside
    // SpaceDimension.BODY_FLOOR_Y..BODY_CEILING_Y (-1776..1968), so it is above the descend-out return altitude (-1840)
    // and below the top of the column (2032), and a pod can always fly to it and land:
    //   lowest  = starY - L - 96 >= BODY_FLOOR_Y, highest = starY + L + 96 <= BODY_CEILING_Y.
    // The orbit is still a rotation of a flat circle, so a planet stays exactly its ring radius from its star in 3D and
    // two planets on adjacent rings stay at least RING_STEP apart whatever their planes.
    static final double MIN_SYSTEM_INCLINATION = Math.toRadians(18.0);
    static final double MAX_SYSTEM_INCLINATION = Math.toRadians(62.0);
    private static final double PLANET_INCLINATION_JITTER = Math.toRadians(14.0);
    private static final double PLANET_NODE_JITTER = Math.toRadians(35.0);
    static final double MAX_PLANET_LIFT = 1600.0;

    // Concentric ring layout around the star. INNER_RING is the first planet's orbit radius (blocks from the star), each
    // further planet steps out by RING_STEP. RING_STEP comfortably exceeds twice the largest planet body (96) so two
    // planets on adjacent rings never touch, and the outermost ring (INNER_RING + (MAX_PLANETS-1)*RING_STEP) stays well
    // inside a quarter of the sector so two neighbouring systems' planets never reach each other. The rings were 900 / 620
    // (outer 5860); they are TIGHTER now (500 / 360, outer 3380) so a system's planets visibly hug their own sun rather
    // than sitting a small system-width out, which read as "orbiting really far". INNER_RING 500 still clears the sun
    // (radius <= 82) plus a planet (<= 96), and RING_STEP 360 still exceeds twice the largest planet (192), so nothing
    // touches; outer 3380 stays inside a quarter of the 20000 sector (5000).
    private static final double INNER_RING = 500.0;
    private static final double RING_STEP = 360.0;
    static final int MIN_PLANETS = 5;
    static final int MAX_PLANETS = 9;
    // the farthest a planet's orbit reaches from its star, used to keep a whole system clear of the inner solar system.
    static final double MAX_OUTER_RING = INNER_RING + (MAX_PLANETS - 1) * RING_STEP;

    /** The first planet ring's radius around a system star (blocks). For client visuals that sit between the rings. */
    public static double innerRing()
    {
        return INNER_RING;
    }

    /** The spacing between a system's planet rings (blocks). */
    public static double ringStep()
    {
        return RING_STEP;
    }

    /** The farthest a system planet's orbit reaches from its star (blocks). */
    public static double maxOuterRing()
    {
        return MAX_OUTER_RING;
    }

    // The system star's visual half-extent band. Biased to the high end of the fixed-star range so a sun reads as the
    // biggest thing in its system without exceeding the star-radius bound the hazard and super-relocation scans assume.
    private static final float STAR_MIN_RADIUS = 62.0F;
    private static final float STAR_MAX_RADIUS = StarPositions.MAX_RADIUS;

    // Kept clear of the space origin/arrival column and of the reserved inner solar system, both measured on the XZ plane
    // like every other field. A system is rejected if any part of it (star centre out to the outermost ring) could enter
    // the inner system, so a system's planets never clutter the sun-and-fixed-rings core.
    private static final double ORIGIN_CLEARANCE = 1200.0;
    private static final double INNER_SYSTEM_CLEAR = 5000.0;

    // The bounded cube (half-extent, blocks) the whole-universe enumeration for the STAR MAP walks. Systems beyond this
    // are simply off the atlas; the live derivation (systemsNear) has no such bound and works at any coordinate.
    public static final double UNIVERSE_HALF_EXTENT = 200000.0;

    /** One planet of a system: its stable id, orbit radius around the star, start phase, its own orbital plane (tilt
     *  and line of nodes), visual size and tint. */
    public static final class SystemPlanet
    {
        public final String id;
        public final double ringRadius;
        public final double phase0;
        public final int surfaceSize;
        public final float radius;
        public final int tint;
        /** This planet's orbital-plane tilt from the flat body plane, radians. */
        public final double inclination;
        /** This planet's line of nodes, radians. */
        public final double nodeAngle;

        SystemPlanet(String id, double ringRadius, double phase0, int surfaceSize, float radius, int tint,
                     double inclination, double nodeAngle)
        {
            this.id = id;
            this.ringRadius = ringRadius;
            this.phase0 = phase0;
            this.surfaceSize = surfaceSize;
            this.radius = radius;
            this.tint = tint;
            this.inclination = inclination;
            this.nodeAngle = nodeAngle;
        }
    }

    /** One star system: its fixed star (key, position, radius, tint), its orbital-plane tilt, and its orbiting planets. */
    public static final class System
    {
        public final String starKey;
        public final Vec3 starPos;
        public final float starRadius;
        public final int starTint;
        /** The tilt of this system's BASE orbital plane, radians (MIN..MAX_SYSTEM_INCLINATION). Each planet's own plane
         *  sits near it ({@link SystemPlanet#inclination}); this one carries the system's dust belt. */
        public final double inclination;
        /** The azimuth of the base plane's line of nodes, radians (where it crosses the flat one). */
        public final double nodeAngle;
        /** The largest vertical swing any planet slot of this system gets from its tilt, blocks (at most MAX_PLANET_LIFT). */
        public final double maxLift;
        public final List<SystemPlanet> planets;

        System(String starKey, Vec3 starPos, float starRadius, int starTint, double inclination, double nodeAngle,
               double maxLift, List<SystemPlanet> planets)
        {
            this.starKey = starKey;
            this.starPos = starPos;
            this.starRadius = starRadius;
            this.starTint = starTint;
            this.inclination = inclination;
            this.nodeAngle = nodeAngle;
            this.maxLift = maxLift;
            this.planets = planets;
        }

        /** The world position of a planet of this system at the given epoch: it orbits the star on its own tilted
         *  plane, so it sits above or below the star while staying exactly its ring radius from it in 3D (see
         *  {@link Orbits#inclinedPosition}). */
        public Vec3 planetPositionAt(SystemPlanet p, long epochMillis)
        {
            double[] xyz = Orbits.inclinedPosition(starPos.x, starPos.y, starPos.z, p.ringRadius, p.phase0, epochMillis,
                    p.inclination, p.nodeAngle);
            return new Vec3(xyz[0], xyz[1], xyz[2]);
        }

        /** A planet as the {@link GeneratedPlanets.Generated} shape the rest of the pipeline consumes, at the given epoch. */
        public GeneratedPlanets.Generated toGenerated(SystemPlanet p, long epochMillis)
        {
            return GeneratedPlanets.forSystemPlanet(p.id, planetPositionAt(p, epochMillis), p.tint, p.radius, p.surfaceSize);
        }
    }

    // splitmix64-style hash of three cell coordinates, matching the other space fields so adjacent cells scatter.
    private static long hash(int cx, int cy, int cz)
    {
        long z = (cx * 0x9E3779B97F4A7C15L) ^ (cy * 0xC2B2AE3D27D4EB4FL) ^ (cz * 0x165667B19E3779F9L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // offset folded into mix() for a planet's PLANE, so its tilt is uncorrelated with its id, phase and colour (and the
    // ids, which existing claims are keyed by, stay exactly what mix(h, i) always gave).
    private static final int PLANE_SALT = 0x3D00;

    // fold a per-planet index into the system hash, so each planet gets an uncorrelated stable id / phase / colour.
    private static long mix(long systemHash, int index)
    {
        long z = systemHash ^ ((long) (index + 1) * 0xD6E8FEB86659FD93L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double unit(long h, int shift)
    {
        return ((h >>> shift) & 0xFFFFFFL) / 16777216.0;
    }

    /** The stable star key for a system cell, in the {@link StarPositions#ID_PREFIX} namespace so it reads as a star. */
    public static String starKeyFor(int cx, int cz)
    {
        long h = hash(cx, 0, cz);
        return StarPositions.ID_PREFIX + Long.toHexString(h & 0xFFFFFFFFFFFFL);
    }

    /** Whether an id names a system star (a {@code sustar:} key). */
    public static boolean isSystemStar(String id)
    {
        return id != null && id.startsWith(StarPositions.ID_PREFIX);
    }

    /**
     * The system in a grid cell, or null if the cell is empty (density roll failed, not the cy 0 layer, inside the origin
     * column or inner solar system, or its star has been destroyed). Pure but for the
     * destroyed seam. {@code includeDestroyed} true skips the destroyed suppression, for the cascade that needs a
     * destroyed star's planets to destroy them.
     */
    public static System systemAt(MinecraftServer server, int cx, int cy, int cz, boolean includeDestroyed)
    {
        if (cy != 0)
        {
            return null;   // systems live only in the cy 0 layer, so the whole grid is a plane.
        }
        long h = hash(cx, 0, cz);
        if (unit(h, 0) >= density)
        {
            return null;   // empty cell.
        }
        int sector = sectorSize;
        // star position at a central-40% hashed offset so two adjacent stars stay at least 0.6 sectors apart.
        double ox = (0.3 + unit(h, 24) * 0.4) * sector;
        double oz = (0.3 + unit(h, 8) * 0.4) * sector;
        double sx = (double) cx * sector + ox;
        double sz = (double) cz * sector + oz;

        // keep the whole system out of the origin column and the reserved inner solar system: reject if any orbit could
        // enter it. Both are pure XZ checks, so client and server agree with no extra sync.
        double hypot = Math.hypot(sx, sz);
        if (hypot <= ORIGIN_CLEARANCE + MAX_OUTER_RING || hypot <= INNER_SYSTEM_CLEAR + MAX_OUTER_RING)
        {
            return null;
        }
        // Systems do NOT yield to the super dragon ball bodies: those orbit the sun, and each body's orbit radius is chosen
        // to keep it clear of the systems instead (SuperPlanetPositions.chooseOrbit). Yielding here used to blank systems
        // near seven spots anchored on Earth, which moved with Earth's orbit, so those systems blinked in and out.

        String starKey = starKeyFor(cx, cz);
        // a destroyed star takes its whole system with it: suppressed at the source so every consumer inherits it.
        if (!includeDestroyed && SpaceLayout.isDestroyed(server, starKey))
        {
            return null;
        }

        float starRadius = STAR_MIN_RADIUS + (float) (unit(h, 16) * (STAR_MAX_RADIUS - STAR_MIN_RADIUS));
        int starTint = starTint(h);

        // the system's base orbital plane, a pure function of the cell hash so the client derives the identical orbits.
        double inclination = MIN_SYSTEM_INCLINATION + unit(h, 4) * (MAX_SYSTEM_INCLINATION - MIN_SYSTEM_INCLINATION);
        double nodeAngle = unit(h, 44) * (Math.PI * 2.0);

        int count = MIN_PLANETS + (int) (unit(h, 32) * (MAX_PLANETS - MIN_PLANETS + 1));
        if (count > MAX_PLANETS)
        {
            count = MAX_PLANETS;   // guard the 1.0 edge of unit().
        }
        // every planet SLOT's plane first (destroyed ones too), so the star height below never depends on what is
        // destroyed and the system never shifts when a planet goes.
        double[] incl = new double[count];
        double[] node = new double[count];
        double maxLift = 0.0;
        for (int i = 0; i < count; ++i)
        {
            long ph = mix(h, i + PLANE_SALT);
            double ring = INNER_RING + i * RING_STEP;
            double pi = inclination + (unit(ph, 0) * 2.0 - 1.0) * PLANET_INCLINATION_JITTER;
            // cap the swing: ring * sin(pi) <= MAX_PLANET_LIFT, so the star band below is never empty.
            pi = Math.min(Math.max(pi, 0.0), Math.asin(Math.min(1.0, MAX_PLANET_LIFT / ring)));
            incl[i] = pi;
            node[i] = nodeAngle + (unit(ph, 24) * 2.0 - 1.0) * PLANET_NODE_JITTER;
            maxLift = Math.max(maxLift, ring * Math.sin(pi));
        }

        // the star anywhere in the column that keeps every planet inside the body band (see the band maths above).
        // Uniform, so systems sit all the way above and below a player, not only near one height.
        double bodyReach = maxLift + GeneratedPlanets.maxBodyRadius();
        double lowY = SpaceDimension.BODY_FLOOR_Y + bodyReach;
        double highY = SpaceDimension.BODY_CEILING_Y - bodyReach;
        double sy = lowY + unit(h, 40) * (highY - lowY);
        Vec3 starPos = new Vec3(sx, sy, sz);

        List<SystemPlanet> planets = new ArrayList<>(count);
        for (int i = 0; i < count; ++i)
        {
            long ph = mix(h, i);
            String id = GeneratedPlanets.ID_PREFIX + Long.toHexString(ph & 0xFFFFFFFFFFFFL);
            // a destroyed planet is suppressed at the source, exactly like the old field, so the renderer / landing / star
            // map all inherit it. cascade callers pass includeDestroyed to keep the planet visible while they destroy it.
            if (!includeDestroyed && SpaceLayout.isDestroyed(server, id))
            {
                continue;
            }
            double ring = INNER_RING + i * RING_STEP;
            double phase0 = Orbits.phase0(ph);
            int surfaceSize = GeneratedPlanets.surfaceSizeForId(id);
            float radius = GeneratedPlanets.bodyRadiusForSurfaceSize(surfaceSize);
            planets.add(new SystemPlanet(id, ring, phase0, surfaceSize, radius, planetTint(ph), incl[i], node[i]));
        }
        return new System(starKey, starPos, starRadius, starTint, inclination, nodeAngle, maxLift, planets);
    }

    // a warm sun tint (yellow-white through amber), a pure function of the cell hash so it is stable and identical on
    // both sides. Kept bright and warm so a system sun reads as a star, never a dull sphere.
    private static int starTint(long h)
    {
        int r = 235 + (int) (unit(h, 16) * 20.0);   // 235..255
        int g = 180 + (int) (unit(h, 24) * 55.0);   // 180..235
        int b = 90 + (int) (unit(h, 8) * 70.0);     // 90..160
        return (r << 16) | (g << 8) | b;
    }

    // a planet tint hashed off the planet hash, each channel floored well above black, same shape as the old field.
    private static int planetTint(long h)
    {
        int r = 96 + (int) (unit(h, 16) * 150.0);
        int g = 96 + (int) (unit(h, 40) * 150.0);
        int b = 96 + (int) (unit(h, 8) * 150.0);
        return (r << 16) | (g << 8) | b;
    }

    // ===== range queries the rest of the code drives =====

    /** Every system whose star OR any of whose planets could fall within {@code range} of {@code around}. */
    public static List<System> systemsNear(MinecraftServer server, Vec3 around, double range)
    {
        List<System> out = new ArrayList<>();
        int sector = sectorSize;
        // a system's bodies reach at most MAX_OUTER_RING + a planet radius from the star, so widen the cell gather by that.
        double reach = range + MAX_OUTER_RING + GeneratedPlanets.maxBodyRadius();
        int minCx = Math.floorDiv((int) Math.floor(around.x - reach), sector);
        int maxCx = Math.floorDiv((int) Math.floor(around.x + reach), sector);
        int minCz = Math.floorDiv((int) Math.floor(around.z - reach), sector);
        int maxCz = Math.floorDiv((int) Math.floor(around.z + reach), sector);
        for (int cx = minCx; cx <= maxCx; ++cx)
        {
            for (int cz = minCz; cz <= maxCz; ++cz)
            {
                System s = systemAt(server, cx, 0, cz, false);
                if (s != null)
                {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** Every system STAR within {@code range} of {@code around}, as {@link StarPositions.Star}. */
    public static List<StarPositions.Star> starsNear(MinecraftServer server, Vec3 around, double range)
    {
        List<StarPositions.Star> out = new ArrayList<>();
        double rangeSq = range * range;
        for (System s : systemsNear(server, around, range))
        {
            if (s.starPos.distanceToSqr(around) <= rangeSq)
            {
                out.add(new StarPositions.Star(s.starKey, s.starPos, s.starRadius, s.starTint));
            }
        }
        return out;
    }

    /** Every system PLANET within {@code range} of {@code around}, at its live orbital position. */
    public static List<GeneratedPlanets.Generated> planetsNear(MinecraftServer server, Vec3 around, double range)
    {
        List<GeneratedPlanets.Generated> out = new ArrayList<>();
        long epoch = OrbitClock.epochMillis();
        double rangeSq = range * range;
        for (System s : systemsNear(server, around, range))
        {
            for (SystemPlanet p : s.planets)
            {
                Vec3 pos = s.planetPositionAt(p, epoch);
                if (pos.distanceToSqr(around) <= rangeSq)
                {
                    out.add(GeneratedPlanets.forSystemPlanet(p.id, pos, p.tint, p.radius, p.surfaceSize));
                }
            }
        }
        return out;
    }

    /** The system planet whose live body cube (half-extent + margin) contains {@code p}, or null. */
    public static GeneratedPlanets.Generated planetContaining(MinecraftServer server, Vec3 p, double margin)
    {
        long epoch = OrbitClock.epochMillis();
        // only systems whose star is within MAX_OUTER_RING + margin + maxBody of p can have a planet over p.
        double reach = MAX_OUTER_RING + margin + GeneratedPlanets.maxBodyRadius();
        for (System s : systemsNear(server, p, reach))
        {
            for (SystemPlanet sp : s.planets)
            {
                Vec3 pos = s.planetPositionAt(sp, epoch);
                double r = sp.radius + margin;
                if (Math.abs(p.x - pos.x) <= r && Math.abs(p.y - pos.y) <= r && Math.abs(p.z - pos.z) <= r)
                {
                    return GeneratedPlanets.forSystemPlanet(sp.id, pos, sp.tint, sp.radius, sp.surfaceSize);
                }
            }
        }
        return null;
    }

    /**
     * Resolve a LIVE system planet by its id to its {@link GeneratedPlanets.Generated} (current orbital position), by
     * scanning the bounded whole-universe enumeration. Used by the pod autopilot to aim at a generated-planet course
     * target and by the destruction cascade. Returns null if no system holds the id (it may be an old-scheme planet, a
     * moon, or destroyed). O(systems) over the bounded grid, a few hundred cells, so it is fine for a course arm and a
     * periodic re-resolve.
     */
    public static GeneratedPlanets.Generated findPlanetById(MinecraftServer server, String id)
    {
        if (id == null || !id.startsWith(GeneratedPlanets.ID_PREFIX))
        {
            return null;
        }
        long epoch = OrbitClock.epochMillis();
        for (System s : allSystems(server))
        {
            for (SystemPlanet p : s.planets)
            {
                if (p.id.equals(id))
                {
                    return GeneratedPlanets.forSystemPlanet(p.id, s.planetPositionAt(p, epoch), p.tint, p.radius,
                            p.surfaceSize);
                }
            }
        }
        return null;
    }

    /**
     * The system whose planet set contains {@code planetId}, or null. Scans the cached whole-universe enumeration, so it is
     * O(1) after the first build and safe on the client (server null reads the synced snapshots). Used by the surface sky to
     * find which sun and siblings a system planet's sky must show, and cached by the caller per planet so the scan runs only
     * when the player changes planet, never per frame.
     */
    public static System systemForPlanetId(MinecraftServer server, String planetId)
    {
        if (planetId == null || !planetId.startsWith(GeneratedPlanets.ID_PREFIX))
        {
            return null;
        }
        for (System s : allSystems(server))
        {
            for (SystemPlanet p : s.planets)
            {
                if (p.id.equals(planetId))
                {
                    return s;
                }
            }
        }
        return null;
    }

    /** The system that owns a given star key, resolved from the star's world position, INCLUDING a destroyed one (so the
     *  cascade can enumerate a just-destroyed star's planets). Returns null if the position maps to no system. */
    public static System systemForStar(MinecraftServer server, String starKey, Vec3 starPos)
    {
        int sector = sectorSize;
        int cx = Math.floorDiv((int) Math.floor(starPos.x), sector);
        int cz = Math.floorDiv((int) Math.floor(starPos.z), sector);
        System s = systemAt(server, cx, 0, cz, true);
        if (s != null && s.starKey.equals(starKey))
        {
            return s;
        }
        return null;
    }

    // ===== whole-universe enumeration for the star map (cached per layout version) =====

    private static volatile int cachedSector = Integer.MIN_VALUE;
    private static volatile double cachedDensity = Double.NaN;
    private static volatile List<System> cachedAll = null;

    /**
     * Every system in the bounded universe (the {@link #UNIVERSE_HALF_EXTENT} cube in the cy 0 plane), INCLUDING systems
     * whose star or planets are currently destroyed (the star map filters destroyed at draw through the synced destroyed
     * set, so the enumeration itself need not be rebuilt on a destruction). Enumerated ONCE per layout version (the
     * {@link #sectorSize} / {@link #density} pair) and cached, because the grid is a pure function of those two numbers;
     * a change to either rebuilds it. A few hundred cells, so the one-time walk is cheap and every later call is O(1).
     */
    public static List<System> allSystems(MinecraftServer server)
    {
        int sector = sectorSize;
        double dens = density;
        List<System> cached = cachedAll;
        if (cached != null && sector == cachedSector && dens == cachedDensity)
        {
            return cached;
        }
        List<System> out = new ArrayList<>();
        int span = (int) Math.floor(UNIVERSE_HALF_EXTENT / sector);
        for (int cx = -span; cx <= span; ++cx)
        {
            for (int cz = -span; cz <= span; ++cz)
            {
                System s = systemAt(server, cx, 0, cz, true);
                if (s != null)
                {
                    out.add(s);
                }
            }
        }
        cachedSector = sector;
        cachedDensity = dens;
        cachedAll = out;
        return out;
    }

    /** Drop the whole-universe cache; call when the layout params change (config bake / layout sync). */
    public static void invalidateAll()
    {
        cachedAll = null;
        cachedSector = Integer.MIN_VALUE;
        cachedDensity = Double.NaN;
    }
}
