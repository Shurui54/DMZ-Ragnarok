package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Orbit MATHS for the seven SUPER dragon-ball bodies in space: the id namespace, the visual half-extent, the reserved
 * radius BANDS their orbits live in, and the chooser {@link SuperPlanetData} calls to give a body a fresh random orbit
 * (on first start, on migrating an older save, and every time a body relocates). Nothing here is stored: each body's
 * orbit ELEMENTS (radius, start phase, period, plane height) are persisted in {@link SuperPlanetData} and synced to
 * clients in {@link PacketSpaceLayoutSync}; both sides derive the live position from them at the shared {@link
 * OrbitClock} instant, so nothing is pushed per tick.
 *
 * <h3>Orbits around the main sun (owner, 2026-10-21)</h3>
 * "super dragon balls should be orbiting the main sun, they do not do that, at random distances." They used to sit in a
 * 20000..45000 band around EARTH, frozen where they were seeded (their orbit was derived from that spot, so with
 * periods of a day or more they never visibly went anywhere). Each body now orbits the central sun on its OWN circular
 * orbit at a random radius inside its own band, with a random phase and the period of {@link Orbits#periodMillis} (the
 * same Kepler-like curve the fixed planets and system planets use).
 *
 * <h3>Bands: why two bodies can never collide and none touches a fixed planet</h3>
 * The bands sit in the gaps BETWEEN the fixed planet rings ({@link PlanetPositions#ringRadiusOfIndex}), two per gap for
 * the four innermost gaps, so there are {@link #bandCount()} (8) bands for seven bodies (one spare, so a relocating body
 * has a real choice). Each gap keeps {@link #ringKeepOut()} clear of both rings (the largest fixed body, its moon's
 * orbit and the super body itself plus a clearance), and the two bands of a gap are {@link #bandGap()} apart, which is
 * more than two super half-extents plus the clearance. At most one body owns a band, so any two bodies differ in orbit
 * radius by more than they could ever reach across: they can never touch whatever their phases. The band edges track
 * the configured ring numbers, so a ring config change is caught on load (a body whose radius no longer falls in a band
 * is given a new orbit, see {@link SuperPlanetData}).
 *
 * <h3>Generated systems, stars and black holes</h3>
 * Generated SYSTEMS no longer yield to super bodies at all (they used to yield to the seven "initial" spots, which were
 * anchored on Earth and so moved with Earth's orbit, making systems near them blink in and out). Instead the orbit
 * chooser picks the radius and plane height inside a body's band that keeps its whole orbit clear of every system's
 * planets, or, if no such radius exists in that band, the one that crosses the fewest systems (logged). Lone stars and
 * black holes keep yielding to a super body, now to its LIVE position ({@link #overlapsLive}), exactly the way they
 * already yield to the orbiting fixed planets: both sides derive the same live positions from the same synced elements.
 */
public final class SuperPlanetPositions
{
    private SuperPlanetPositions()
    {
    }

    // Stable id namespace for a super body. The suffix is the star number 1..7, so the id maps straight to the star and
    // therefore to the ball block it drops (dball<star>_super).
    public static final String ID_PREFIX = "susuper:";

    // the reference fixed body the super size is derived from: four times its half-extent.
    private static final String NAMEK_KEY = "dragonminez:namek";
    // Earth, used only for the admin status line's "distance from Earth" column.
    private static final String EARTH_KEY = "minecraft:overworld";

    public static final int COUNT = 7;

    // Vertical band a super body's orbital plane may sit at, around the main sun plane (PlanetPositions.PLANET_Y, 0). With
    // the 352 half-extent the body spans -1452..1452, inside SpaceDimension.BODY_FLOOR_Y..BODY_CEILING_Y (-1776..1968).
    // The upper edge is kept at 1100 on purpose: a save from the old -64..1984 column holds plane heights 700..1100,
    // and those stay valid here, so no super body moves on upgrade.
    static final double MIN_Y = PlanetPositions.PLANET_Y - 1100.0;
    static final double MAX_Y = PlanetPositions.PLANET_Y + 1100.0;

    // clearance in blocks added around a super body's half-extent when keeping it clear of anything else, matching the
    // generated field's FIXED_CLEARANCE approach.
    private static final double OVERLAP_CLEARANCE = 256.0;

    // the gaps between fixed rings the bands use (gap g lies between ring g and ring g+1), and bands per gap.
    private static final int GAPS_USED = 4;
    private static final int BANDS_PER_GAP = 2;

    // how many random (radius, plane height) candidates the chooser scores inside one band. Rare (first start, migration,
    // a relocation) and each candidate is a pass over the cached system list (a few hundred entries), so this is cheap.
    private static final int ORBIT_SAMPLES = 96;

    // the visual half-extent of every super body: four times Namek's half-extent, computed live so it tracks the fixed
    // radius formula. Both sides compute the identical value (a table lookup), so it needs no sync.
    public static float radius()
    {
        return 4.0F * PlanetPositions.radius(NAMEK_KEY);
    }

    // Earth's current world position, for the admin status line only.
    public static Vec3 earth()
    {
        return PlanetPositions.position(EARTH_KEY);
    }

    // the stable id for a star number.
    public static String keyFor(int star)
    {
        return ID_PREFIX + star;
    }

    // true if the given id is a super body id.
    public static boolean isSuper(String id)
    {
        return id != null && id.startsWith(ID_PREFIX);
    }

    // the star number (1..7) embedded in a super id, or 0 if the id is not a super id or is malformed.
    public static int starOf(String id)
    {
        if (!isSuper(id))
        {
            return 0;
        }
        try
        {
            int star = Integer.parseInt(id.substring(ID_PREFIX.length()));
            return (star >= 1 && star <= COUNT) ? star : 0;
        }
        catch (NumberFormatException ex)
        {
            return 0;
        }
    }

    // ===== orbit bands =====

    /** How many radius bands exist (two per used gap between fixed rings). Always more than {@link #COUNT}. */
    public static int bandCount()
    {
        return GAPS_USED * BANDS_PER_GAP;
    }

    // how far a band edge stays from a fixed ring: the largest fixed body, its moon's orbit and the moon itself, the
    // super half-extent and the clearance.
    static double ringKeepOut()
    {
        double fixedReach = PlanetPositions.MAX_FIXED_RADIUS * (1.0 + MoonBody.ORBIT_FACTOR + MoonBody.RADIUS_FACTOR);
        return fixedReach + radius() + OVERLAP_CLEARANCE;
    }

    // the radial gap between the two bands of one ring gap: more than two super half-extents plus the clearance, so a
    // body in one band can never reach a body in the next.
    static double bandGap()
    {
        return 2.0 * radius() + OVERLAP_CLEARANCE;
    }

    // the usable width of one band (both bands of a gap are equal).
    private static double bandWidth(int gap)
    {
        double inner = PlanetPositions.ringRadiusOfIndex(gap) + ringKeepOut();
        double outer = PlanetPositions.ringRadiusOfIndex(gap + 1) - ringKeepOut();
        return Math.max(0.0, (outer - inner - bandGap()) / BANDS_PER_GAP);
    }

    /** Inner edge of a band, blocks from the sun. */
    public static double bandLow(int band)
    {
        int gap = band / BANDS_PER_GAP;
        int half = band % BANDS_PER_GAP;
        double inner = PlanetPositions.ringRadiusOfIndex(gap) + ringKeepOut();
        return inner + half * (bandWidth(gap) + bandGap());
    }

    /** Outer edge of a band, blocks from the sun. */
    public static double bandHigh(int band)
    {
        return bandLow(band) + bandWidth(band / BANDS_PER_GAP);
    }

    /** The band an orbit radius falls in under the current ring config, or -1 if it falls in none. */
    public static int bandOf(double orbitRadius)
    {
        for (int band = 0; band < bandCount(); ++band)
        {
            if (orbitRadius >= bandLow(band) && orbitRadius <= bandHigh(band))
            {
                return band;
            }
        }
        return -1;
    }

    /** A random band not in {@code taken}, or -1 if every band is taken (impossible: 8 bands, 7 bodies). */
    public static int pickFreeBand(Set<Integer> taken, RandomSource random)
    {
        List<Integer> free = new ArrayList<>(bandCount());
        for (int band = 0; band < bandCount(); ++band)
        {
            if (!taken.contains(band))
            {
                free.add(band);
            }
        }
        return free.isEmpty() ? -1 : free.get(random.nextInt(free.size()));
    }

    /** A chosen orbit: band, radius from the sun, start phase, period, plane height, and how many systems it crosses. */
    public record Orbit(int band, double radius, double phase0, double period, double y, int systemsCrossed)
    {
        /** Where this orbit puts the body at an epoch. Same maths as {@link SuperPlanetData}'s live position. */
        public Vec3 positionAt(long epochMillis)
        {
            Vec3 sun = PlanetPositions.sunPosition();
            double[] xz = Orbits.positionXZ(sun.x, sun.z, radius, phase0, epochMillis);
            return new Vec3(xz[0], y, xz[1]);
        }
    }

    /**
     * A fresh random orbit inside {@code band}: a random phase, a radius and plane height sampled inside the band, and the
     * period from {@link Orbits#periodMillis}. Of the sampled radii it keeps the first whose whole orbit stays clear of
     * every generated system (radially past the system's outermost planet, or vertically above or below its planets),
     * otherwise the one crossing the fewest systems. Server only (it reads the system grid with the server's config).
     * It never consults the black-hole or star fields, which yield to super bodies (no recursion back into here).
     */
    public static Orbit chooseOrbit(MinecraftServer server, int band, RandomSource random)
    {
        double lo = bandLow(band);
        double hi = bandHigh(band);
        float superRadius = radius();
        // how close (radially / vertically) a super orbit may come to a system centre before it could touch the system's
        // outermost planet: the outer ring, the biggest planet, the super half-extent and the clearance. The vertical
        // reach adds the most a planet is lifted off the star's height by its system's orbital tilt.
        double radialReach = GeneratedSystems.MAX_OUTER_RING + GeneratedPlanets.maxBodyRadius() + superRadius
                + OVERLAP_CLEARANCE;
        // vertical reach is per system now (its planets swing up to sys.maxLift off the star's height).
        double verticalPad = GeneratedPlanets.maxBodyRadius() + superRadius + OVERLAP_CLEARANCE;
        // the systems whose radial extent touches this band at all; the rest can never conflict.
        List<GeneratedSystems.System> all = GeneratedSystems.allSystems(server);
        int n = 0;
        double[] dist = new double[all.size()];
        double[] height = new double[all.size()];
        double[] vReach = new double[all.size()];
        Vec3 sun = PlanetPositions.sunPosition();
        for (GeneratedSystems.System sys : all)
        {
            double d = Math.hypot(sys.starPos.x - sun.x, sys.starPos.z - sun.z);
            if (d + radialReach < lo || d - radialReach > hi)
            {
                continue;
            }
            dist[n] = d;
            height[n] = sys.starPos.y;
            vReach[n] = sys.maxLift + verticalPad;
            ++n;
        }
        double bestR = (lo + hi) * 0.5;
        double bestY = (MIN_Y + MAX_Y) * 0.5;
        int bestConflicts = Integer.MAX_VALUE;
        double bestClear = -Double.MAX_VALUE;
        for (int attempt = 0; attempt < ORBIT_SAMPLES; ++attempt)
        {
            double r = lo + random.nextDouble() * (hi - lo);
            double y = MIN_Y + random.nextDouble() * (MAX_Y - MIN_Y);
            int conflicts = 0;
            double minClear = Double.MAX_VALUE;
            for (int i = 0; i < n; ++i)
            {
                double clear = Math.max(Math.abs(dist[i] - r) - radialReach, Math.abs(height[i] - y) - vReach[i]);
                if (clear < 0.0)
                {
                    ++conflicts;
                }
                minClear = Math.min(minClear, clear);
            }
            if (conflicts < bestConflicts || (conflicts == bestConflicts && minClear > bestClear))
            {
                bestConflicts = conflicts;
                bestClear = minClear;
                bestR = r;
                bestY = y;
            }
            if (conflicts == 0)
            {
                break;
            }
        }
        double phase0 = random.nextDouble() * (Math.PI * 2.0);
        return new Orbit(band, bestR, phase0, Orbits.periodMillis(bestR), bestY, bestConflicts);
    }

    // ===== live positions, for the hazard fields that yield to a super body =====

    // SERVER memo: the seven live positions for the current tick, like SpaceLayout.fixedBodies. Only the server thread
    // fills it. CLIENT memo: rebuilt at most every CLIENT_LIVE_CACHE_MS from the synced elements (a body moves well under
    // a block in that window), so the per-cell hazard walks do not redo the trig per cell.
    private static long serverTick = Long.MIN_VALUE;
    private static double[] serverLive = new double[0];
    private static final long CLIENT_LIVE_CACHE_MS = 50L;
    private static long clientStamp = Long.MIN_VALUE;
    private static List<PacketSpaceLayoutSync.SuperBody> clientSource = null;
    private static double[] clientLive = new double[0];

    /** Drop the server memo (a body just got a new orbit, or a new world started in this JVM). */
    static void invalidateLive()
    {
        serverTick = Long.MIN_VALUE;
        serverLive = new double[0];
    }

    // the live positions as a flat {x0,y0,z0,x1,...} array. Server: from SuperPlanetData; client: from the synced list.
    private static double[] livePositions(MinecraftServer server)
    {
        if (server != null)
        {
            boolean cacheable = server.isSameThread();
            if (cacheable && server.getTickCount() == serverTick)
            {
                return serverLive;
            }
            double[] out = SuperPlanetData.livePositionsOrEmpty(server);
            if (cacheable)
            {
                serverLive = out;
                serverTick = server.getTickCount();
            }
            return out;
        }
        List<PacketSpaceLayoutSync.SuperBody> src = SpaceLayout.clientSuperBodies();
        long now = System.currentTimeMillis();
        if (src == clientSource && now - clientStamp < CLIENT_LIVE_CACHE_MS)
        {
            return clientLive;
        }
        long epoch = OrbitClock.epochMillis();
        double[] out = new double[src.size() * 3];
        int i = 0;
        for (PacketSpaceLayoutSync.SuperBody sb : src)
        {
            double[] xz = Orbits.positionXZ(sb.sunX(), sb.sunZ(), sb.radius(), sb.phase0(), epoch);
            out[i++] = xz[0];
            out[i++] = sb.y();
            out[i++] = xz[1];
        }
        clientLive = out;
        clientSource = src;
        clientStamp = now;
        return out;
    }

    /**
     * True if a body centred at {@code pos} reaching {@code reach} blocks would overlap any super body's LIVE expanded
     * bounding cube right now. Read by the lone-star and black-hole fields, which yield to a super body exactly the way
     * they yield to an orbiting fixed planet. Both sides derive the same live positions from the same orbit elements.
     */
    public static boolean overlapsLive(MinecraftServer server, Vec3 pos, double reach)
    {
        double[] live = livePositions(server);
        double half = radius() + OVERLAP_CLEARANCE + reach;
        for (int i = 0; i + 2 < live.length; i += 3)
        {
            if (Math.abs(pos.x - live[i]) <= half
                    && Math.abs(pos.y - live[i + 1]) <= half
                    && Math.abs(pos.z - live[i + 2]) <= half)
            {
                return true;
            }
        }
        return false;
    }
}
