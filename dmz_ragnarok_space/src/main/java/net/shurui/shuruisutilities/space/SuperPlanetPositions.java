package net.shurui.shuruisutilities.space;

import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Placement MATHS for the seven SUPER dragon-ball bodies in space: the id namespace, the visual half-extent, the
 * INITIAL layout (a pure function seeded into {@link SuperPlanetData} on first init) and the constraint-checking sampler
 * {@link SuperPlanetData} draws a fresh position from when a body RELOCATES. Nothing here is stored: the authoritative
 * current position of every body lives in {@link SuperPlanetData} and is synced to clients, because a body may move
 * (point 5 relocation) so its position can no longer be a pure function the way a fixed planet's is.
 *
 * <h3>Placement constraints (points 5 and 6)</h3>
 * Every body sits <b>{@value #MIN_FROM_EARTH}..{@value #MAX_FROM_EARTH}</b> blocks from Earth (the fixed body keyed
 * {@code minecraft:overworld}, whose position comes from {@link PlanetPositions#position} at runtime, never hardcoded)
 * and at least <b>{@value #MIN_SEPARATION}</b> blocks from every other super body. The initial layout spreads the seven
 * evenly by angle on a ring around Earth with a hashed per-body radius in a band that provably keeps any two of them
 * well past the separation minimum; the relocation sampler rejection-samples the same annulus, additionally rejecting a
 * candidate that would overlap a fixed body or a generated planet (which includes the Black Star target planets, since
 * those ARE generated planets), so a relocated body never lands on top of anything.
 *
 * <h3>Why generated planets still avoid the INITIAL ring</h3>
 * {@link #overlaps} is a pure function over the initial ring positions, consulted by {@link GeneratedPlanets} exactly as
 * before so a generated planet is never derived on top of a super body's starting spot. A relocated body is NOT added to
 * that check (it would need syncing into the pure derivation): instead the sampler guarantees the new spot is already
 * clear of every generated planet, and generated planets are a static pure field, so the two never collide afterwards.
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
    // Earth: the anchor every super body is placed relative to, resolved live so it tracks the fixed-ring config.
    private static final String EARTH_KEY = "minecraft:overworld";

    public static final int COUNT = 7;

    // distance band from Earth every super body must sit in (point 6).
    public static final double MIN_FROM_EARTH = 20000.0;
    public static final double MAX_FROM_EARTH = 45000.0;
    // minimum centre-to-centre spacing between any two super bodies (point 6).
    public static final double MIN_SEPARATION = 15000.0;

    // the INITIAL ring the seven bodies are seeded on, as a radius band around Earth. 24000..36000 sits inside the
    // 20000..45000 band with margin, and even angular spacing over this band keeps the closest possible pair (both at
    // 24000, adjacent) about 18,900 blocks apart, comfortably past MIN_SEPARATION. See the class note.
    private static final double INIT_RING_BASE = 24000.0;
    private static final double INIT_RING_SPREAD = 12000.0;
    // a small hashed angular wobble (up to +/- 5% of the even spacing) so the initial ring is not a perfect heptagon
    // while staying far past the separation minimum.
    private static final double INIT_ANGLE_WOBBLE = 0.05;

    // Vertical band a super body's centre may occupy. Kept well inside the space play band with room for the large
    // half-extent.
    private static final double MIN_Y = 700.0;
    private static final double MAX_Y = 1100.0;

    // clearance in blocks added around a super body's half-extent when rejecting an overlap (generated planet or fixed
    // body), matching the generated field's FIXED_CLEARANCE approach.
    private static final double OVERLAP_CLEARANCE = 256.0;

    // rejection-sampling budget for a relocation. Generous: with six ~15000 exclusion disks in a wide annulus there is
    // always free room (the initial seven fit), so a valid spot is normally found in a handful of tries; the high cap is
    // only a safety net.
    private static final int SAMPLE_ATTEMPTS = 4000;

    // splitmix64-style hash of the star number, deterministic and JVM-stable (integer arithmetic only).
    private static long hash(int star)
    {
        long z = (star * 0x9E3779B97F4A7C15L) ^ 0x5DB2A1C3F00DBEEFL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // the visual half-extent of every super body: four times Namek's half-extent, computed live so it tracks the fixed
    // radius formula. Both sides compute the identical value (a pure hash of the Namek key), so it needs no sync.
    public static float radius()
    {
        return 4.0F * PlanetPositions.radius(NAMEK_KEY);
    }

    // Earth's current world position, the anchor for every super body. Pure on both sides (PlanetPositions.position is a
    // hash over the synced ring config).
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

    /**
     * The INITIAL world position for a star, a pure function of the star and Earth's position. This is what seeds
     * {@link SuperPlanetData} on first init and what {@link #overlaps} keeps generated planets clear of. Evenly spaced by
     * angle around Earth on a hashed-radius ring inside the 20000..45000 band.
     */
    public static Vec3 initialPosition(int star)
    {
        Vec3 earth = earth();
        long h = hash(star);
        double baseAngle = (star - 1) * (Math.PI * 2.0 / COUNT);
        double wobble = (((h & 0xFFFFL) / 65536.0) - 0.5) * (Math.PI * 2.0 / COUNT) * (INIT_ANGLE_WOBBLE * 2.0);
        double angle = baseAngle + wobble;
        double radius = INIT_RING_BASE + ((h >>> 16) & 0xFFFFL) / 65536.0 * INIT_RING_SPREAD;
        double x = earth.x + Math.cos(angle) * radius;
        double z = earth.z + Math.sin(angle) * radius;
        double y = MIN_Y + ((h >>> 32) & 0xFFFFL) / 65536.0 * (MAX_Y - MIN_Y);
        return new Vec3(x, y, z);
    }

    /**
     * True if a body centred at {@code pos} with the given half-extent overlaps any super body's INITIAL ring position's
     * expanded bounding cube. Consulted by {@link GeneratedPlanets} so a generated planet is never derived on top of a
     * super body's starting spot. Pure function (initial positions only), so the client rejects identically.
     */
    public static boolean overlaps(Vec3 pos, float radius)
    {
        float superRadius = radius();
        for (int star = 1; star <= COUNT; ++star)
        {
            Vec3 c = initialPosition(star);
            double half = superRadius + OVERLAP_CLEARANCE + radius;
            if (Math.abs(pos.x - c.x) <= half
                    && Math.abs(pos.y - c.y) <= half
                    && Math.abs(pos.z - c.z) <= half)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Draw a fresh position for a body that is RELOCATING, satisfying every constraint: 20000..45000 from Earth, at
     * least {@value #MIN_SEPARATION} from each of {@code others} (the current positions of the six other bodies), and not
     * overlapping any fixed body or generated planet. Rejection-samples the annulus; on the (practically impossible)
     * event that no fully-clear spot is found it relaxes the generated/fixed checks and returns a spot that at least
     * keeps the separation, so this never returns null and never soft-locks a relocation.
     */
    public static Vec3 sampleRelocation(MinecraftServer server, List<Vec3> others, RandomSource random)
    {
        Vec3 earth = earth();
        float superRadius = radius();
        Vec3 separationOnly = null;
        for (int attempt = 0; attempt < SAMPLE_ATTEMPTS; ++attempt)
        {
            double angle = random.nextDouble() * (Math.PI * 2.0);
            double dist = MIN_FROM_EARTH + random.nextDouble() * (MAX_FROM_EARTH - MIN_FROM_EARTH);
            double x = earth.x + Math.cos(angle) * dist;
            double z = earth.z + Math.sin(angle) * dist;
            double y = MIN_Y + random.nextDouble() * (MAX_Y - MIN_Y);
            Vec3 p = new Vec3(x, y, z);
            if (!keepsSeparation(p, others))
            {
                continue;
            }
            if (separationOnly == null)
            {
                separationOnly = p; // remember the first separation-clear spot for the relaxed fallback.
            }
            if (overlapsFixed(server, p, superRadius) || overlapsGenerated(server, p, superRadius)
                    || overlapsHazard(server, p, superRadius))
            {
                continue;
            }
            return p;
        }
        if (separationOnly != null)
        {
            return separationOnly;
        }
        // nothing kept even the separation (all six others crowded the annulus, effectively impossible): fall back to a
        // ring point at the mid radius on the angle furthest from the others, so a relocation always yields SOMETHING.
        return furthestAngle(earth, others);
    }

    private static boolean keepsSeparation(Vec3 p, List<Vec3> others)
    {
        double minSq = MIN_SEPARATION * MIN_SEPARATION;
        for (Vec3 o : others)
        {
            if (o != null && p.distanceToSqr(o) < minSq)
            {
                return false;
            }
        }
        return true;
    }

    private static boolean overlapsFixed(MinecraftServer server, Vec3 pos, float superRadius)
    {
        for (FixedBody planet : SpaceLayout.fixedBodies(server))
        {
            double reach = planet.radius + superRadius + OVERLAP_CLEARANCE;
            if (pos.distanceToSqr(planet.position) <= reach * reach)
            {
                return true;
            }
        }
        return false;
    }

    private static boolean overlapsGenerated(MinecraftServer server, Vec3 pos, float superRadius)
    {
        double range = superRadius + GeneratedPlanets.maxBodyRadius() + OVERLAP_CLEARANCE;
        for (GeneratedPlanets.Generated g : GeneratedPlanets.generatedNear(server, pos, range))
        {
            double reach = superRadius + g.radius + OVERLAP_CLEARANCE;
            if (pos.distanceToSqr(g.position) <= reach * reach)
            {
                return true;
            }
        }
        return false;
    }

    // reject a relocation spot that would sit on a STAR or a BLACK HOLE. Stars and black holes yield to a super body's
    // INITIAL positions (SuperPlanetPositions.overlaps), but a RELOCATED body is not in that pure check, so the sampler
    // must clear the hazards itself; because the hazard fields never move, a spot cleared here stays clear afterwards.
    // Uses each hazard's DISC radius plus the super clearance; the star/hole derivations near pos are a bounded cell walk.
    private static boolean overlapsHazard(MinecraftServer server, Vec3 pos, float superRadius)
    {
        double starScan = superRadius + StarPositions.MAX_RADIUS + OVERLAP_CLEARANCE;
        for (StarPositions.Star s : StarPositions.starsNear(server, pos, starScan))
        {
            double reach = superRadius + s.radius + OVERLAP_CLEARANCE;
            if (pos.distanceToSqr(s.position) <= reach * reach)
            {
                return true;
            }
        }
        double holeScan = superRadius + BlackHolePositions.MAX_RADIUS + OVERLAP_CLEARANCE;
        for (BlackHolePositions.BlackHole hole : BlackHolePositions.blackHolesNear(server, pos, holeScan))
        {
            double reach = superRadius + hole.radius + OVERLAP_CLEARANCE;
            if (pos.distanceToSqr(hole.position) <= reach * reach)
            {
                return true;
            }
        }
        return false;
    }

    // last-ditch fallback for the sampler: the mid-radius ring point on the angle that maximises the minimum distance to
    // the others. Scans a coarse set of angles; pure arithmetic, no allocation beyond the returned Vec3.
    private static Vec3 furthestAngle(Vec3 earth, List<Vec3> others)
    {
        double dist = (MIN_FROM_EARTH + MAX_FROM_EARTH) * 0.5;
        double y = (MIN_Y + MAX_Y) * 0.5;
        double bestAngle = 0.0;
        double bestMin = -1.0;
        for (int i = 0; i < 360; ++i)
        {
            double angle = Math.toRadians(i);
            double x = earth.x + Math.cos(angle) * dist;
            double z = earth.z + Math.sin(angle) * dist;
            Vec3 p = new Vec3(x, y, z);
            double min = Double.MAX_VALUE;
            for (Vec3 o : others)
            {
                if (o != null)
                {
                    min = Math.min(min, p.distanceTo(o));
                }
            }
            if (min > bestMin)
            {
                bestMin = min;
                bestAngle = angle;
            }
        }
        return new Vec3(earth.x + Math.cos(bestAngle) * dist, y, earth.z + Math.sin(bestAngle) * dist);
    }
}
