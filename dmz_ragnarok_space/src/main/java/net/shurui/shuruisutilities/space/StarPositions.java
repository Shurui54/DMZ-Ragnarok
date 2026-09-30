package net.shurui.shuruisutilities.space;

import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * Pure, stateless derivation of the STAR field from the world position alone, in the exact idiom of
 * {@link GeneratedPlanets} and {@link AsteroidPositions}: NOTHING is stored. A star's existence, its cell key, its
 * position, its visual radius and its colour tint are all a pure function of the integer cell coordinates, recomputed
 * from the hash every time. Stars are NOT entities: the client re-derives this same set and draws each star (a huge,
 * full-bright cube textured with the vanilla sun texture and tinted by {@link #colourTint}), and the server re-derives
 * the burn hazard from the same set (SpaceHazardModule), so the whole field is stable across restarts with nothing
 * spawned, tracked or saved.
 *
 * <h3>Distribution: rarer than planets</h3>
 * Space is divided into fixed {@link #sectorSize}-block cubic cells. A fraction {@link #density} of cells hold one star
 * at a hashed offset inside the cell. Both are config-baked (see PlanetSpawnModule.bakeConfig) so an operator can make
 * stars more or less common, or turn them off. The DEFAULTS make stars clearly RARER THAN PLANETS: the generated-planet
 * field runs at density 0.5 over a 2048-block sector (a body or two always in view), whereas a star sits in a
 * {@value #DEFAULT_SECTOR_SIZE}-block sector at density {@value #DEFAULT_DENSITY}: roughly one star per four populated
 * cells, each cell half again as wide as a planet cell. Because bodies are confined to a thin Y band, only the XZ
 * footprint of the draw disc scales, so at the default 8000 draw distance this puts about five stars in view at once:
 * a star reads as a landmark you steer toward or away from rather than routine scenery, but is never absent.
 *
 * <h3>Never overlapping anything</h3>
 * A candidate star is rejected if it would overlap any FIXED planet body, a SUPER body's starting spot, a GENERATED
 * planet body, an ASTEROID lump, a BLACK HOLE (the other hazard), or fall too near the space origin/arrival column. The
 * fixed/origin check is here; the generated-planet and asteroid checks are shared with the black hole via
 * {@link HazardExclusion}; the black hole check is {@link BlackHolePositions#overlapsAnyHole}. Two stars can never
 * overlap each other either: there is at most one per cell, and two stars in ADJACENT cells that would meet across the
 * shared cell face are separated by the deterministic {@link CellOverlap} contest (the older "the cell is far larger
 * than any star" claim only ruled out within-cell overlap). Because every rejection is a pure function of the cell hash
 * and the current derived bodies, a rejected cell is rejected identically every time, so the field stays deterministic.
 *
 * <p>MUTUAL EXCLUSION WITH BLACK HOLES IS ONE-WAY, ON PURPOSE. A star YIELDS to a black hole (it rejects if it would
 * overlap one), but a black hole derives WITHOUT consulting stars. That asymmetry is what breaks the recursion: if both
 * checked each other, {@code starFor} would call {@code blackHoleFor} which would call {@code starFor} forever. Since
 * the star always yields, the pair can still never overlap while each derivation terminates.
 */
public final class StarPositions
{
    private StarPositions()
    {
    }

    // Stable id namespace, distinct from generated planets (sugen:) and asteroids (ast:) so the three never collide as
    // keys. The suffix is the sector hash in hex.
    public static final String ID_PREFIX = "sustar:";

    // Config-baked defaults. See the class-doc distribution justification. volatile: written on the config thread, read
    // on the server thread.
    static final int DEFAULT_SECTOR_SIZE = 3072;
    static final double DEFAULT_DENSITY = 0.24;
    public static volatile int sectorSize = DEFAULT_SECTOR_SIZE;
    static volatile double density = DEFAULT_DENSITY;

    // Vertical band a star may occupy, matching the generated-planet band so stars read alongside the rest of the field
    // rather than in an empty corner. Kept inside the space play band (min_y -64, height 2048).
    private static final double MIN_Y = 200.0;
    private static final double MAX_Y = 1600.0;

    // Visual half-extent range of a star, in blocks. Sized at roughly 1.5x a planet so a star is clearly the biggest
    // thing out there and unmistakable, without dominating the whole sky. Planet half-extents are 24..55, so 36..82 keeps
    // a star about 1.5x the planet it sits near (36..82 half-extent = 72..164 blocks across). The proximity-burn field
    // scales off this radius (see SpaceHazardModule.STAR_BURN_FIELD_FACTOR), so shrinking the star shrinks its danger
    // zone with it and the proportions stay right.
    public static final float MIN_RADIUS = 36.0F;
    public static final float MAX_RADIUS = 82.0F;

    // Clearance in blocks added around a fixed planet's half-extent when rejecting an overlapping star, and the radius
    // around the space origin kept clear so a star never sits on the arrival column. Both mirror GeneratedPlanets.
    private static final double FIXED_CLEARANCE = 256.0;
    private static final double ORIGIN_CLEARANCE = 1200.0;

    // Extra clearance, in blocks, between a star's outer edge and a black hole's outer edge, so the two hazards never
    // sit on top of each other.
    private static final double HAZARD_CLEARANCE = 256.0;

    /**
     * A single derived star: its stable key, world position, visual radius and colour tint. Everything is a pure
     * function of the cell, never stored.
     */
    public static final class Star
    {
        public final String key;
        public final Vec3 position;
        public final float radius;
        public final int tint;

        Star(String key, Vec3 position, float radius, int tint)
        {
            this.key = key;
            this.position = position;
            this.radius = radius;
            this.tint = tint;
        }
    }

    // splitmix64-style hash of three cell coordinates, matching the rest of the space layout so adjacent cells scatter.
    private static long hash(int cx, int cy, int cz)
    {
        long z = (cx * 0x9E3779B97F4A7C15L) ^ (cy * 0xC2B2AE3D27D4EB4FL) ^ (cz * 0x165667B19E3779F9L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double unit(long h, int shift)
    {
        return ((h >>> shift) & 0xFFFFFFL) / 16777216.0;
    }

    // stable key string for a cell, used as the spawn dedupe key. The sector hash in hex, prefixed so it never collides
    // with a generated planet, asteroid or DMZ dimension id.
    public static String keyFor(int cx, int cy, int cz)
    {
        long h = hash(cx, cy, cz);
        return ID_PREFIX + Long.toHexString(h & 0xFFFFFFFFFFFFL);
    }

    // Clearance in blocks added around another STAR's half-extent when two adjacent-cell stars contest for the same
    // space, so the survivor keeps a visible gap from the one that yielded rather than touching it.
    private static final double STAR_CLEARANCE = 128.0;

    /**
     * The star for a single cell, or null if the cell is empty (density roll failed), its Y falls outside the band, it
     * would overlap a fixed planet, the origin column, a SUPER body, a black hole or a generated planet/asteroid, OR it
     * loses the deterministic cross-cell overlap contest to a higher-ranked adjacent star (so two stars can never spawn
     * inside each other across a shared cell face). Pure function of the cell coordinates and the current fixed / super /
     * black-hole / generated sets.
     */
    public static Star starFor(MinecraftServer server, int cx, int cy, int cz)
    {
        Star me = candidateFor(server, cx, cy, cz);
        if (me == null)
        {
            return null;
        }
        Vec3 p = me.position;
        long h = hash(cx, cy, cz);
        CellOverlap.Cand self = new CellOverlap.Cand(p.x, p.y, p.z, me.radius + STAR_CLEARANCE, h, cx, cy, cz);
        boolean survives = CellOverlap.survives(self, sectorSize, MAX_RADIUS + STAR_CLEARANCE, "star",
                (ncx, ncy, ncz) ->
                {
                    Star n = candidateFor(server, ncx, ncy, ncz);
                    if (n == null)
                    {
                        return null;
                    }
                    Vec3 np = n.position;
                    return new CellOverlap.Cand(np.x, np.y, np.z, n.radius + STAR_CLEARANCE, hash(ncx, ncy, ncz),
                            ncx, ncy, ncz);
                });
        return survives ? me : null;
    }

    // the raw star candidate for a cell (every gate EXCEPT the cross-cell star contest), or null. Split out of starFor so
    // the contest can derive a neighbour without recursing into the contest.
    private static Star candidateFor(MinecraftServer server, int cx, int cy, int cz)
    {
        long h = hash(cx, cy, cz);
        if (unit(h, 0) >= density)
        {
            return null;
        }

        float radius = MIN_RADIUS + (float) (unit(h, 32) * (MAX_RADIUS - MIN_RADIUS));

        int sector = sectorSize;
        double ox = unit(h, 24) * sector;
        double oy = unit(h, 40) * sector;
        double oz = unit(h, 8) * sector;
        double x = (double) cx * sector + ox;
        double z = (double) cz * sector + oz;
        double y = (double) cy * sector + oy;
        if (y - radius < MIN_Y || y + radius > MAX_Y)
        {
            return null;
        }
        Vec3 pos = new Vec3(x, y, z);

        // yield to a SUPER body's starting spot too (SuperPlanetPositions.overlaps is a pure function over the seven
        // initial ring positions, so the client rejects identically). A relocated super clears stars at relocation time
        // and the star field never moves, so the two stay apart afterwards; see SuperPlanetPositions.sampleRelocation.
        if (overlapsFixedOrOrigin(server, pos, radius)
                || SuperPlanetPositions.overlaps(pos, radius)
                || BlackHolePositions.overlapsAnyHole(server, pos, radius + HAZARD_CLEARANCE)
                || HazardExclusion.overlapsGeneratedOrAsteroid(server, pos, radius))
        {
            return null;
        }

        return new Star(keyFor(cx, cy, cz), pos, radius, colourTint(h));
    }

    // packed 0xRRGGBB tint, a PURE function of the cell hash so it is stable across restarts and identical on client and
    // server (never real randomness). The renderer multiplies it over the grayscale sun sheet, so the palette is chosen to
    // read as a STAR rather than as random confetti. EVERY star now falls into exactly one of THREE families: REDDISH,
    // ORANGE or BLUE. The white / cream / pale-gold band is GONE on purpose: the user reported stars reading cream, and
    // that came straight from the two old "white" bands (blue-white ~6% and warm white / pale gold ~16%, i.e. ~22% of the
    // sky literally near-white) plus the yellow end of the old yellow/orange band. Cream is a SATURATION failure as much as
    // a hue one: a near-white colour is one whose r, g and b sit close together, so each family below is built with real
    // CHANNEL SEPARATION (a dominant channel well clear of the others) rather than a bright neutral, and no family lets all
    // three channels ride high at once. Greens and purples are still never produced. One family is picked by a hash window,
    // with per-star jitter inside it so no two stars are exactly alike. Blue stays the RARE, striking exception (the user's
    // standing ask), so it takes only the first ~12% of the window; the rest of the sky is a warm ember field of orange and
    // red. The sun sheet is mapped to a 1.0 luminance lift in the renderer (no brightening), so these values read close to
    // their true picked colour, not a third darker.
    private static int colourTint(long h)
    {
        // family window. unit() reads a 24-bit window at the given shift; shift 40 keeps all 24 bits inside the 64-bit word
        // so cls spans a full 0..1 range and every band is reachable (a shift >= 48 leaves too few bits and collapses cls
        // toward 0, which historically pinned every star into the first band). BLUE first and narrow so it stays rare.
        double cls = unit(h, 40);
        int r;
        int g;
        int b;
        if (cls < 0.12)
        {
            // BLUE (rare): a proper blue, blue channel dominant and red kept LOW so it can never drift to white. Green sits
            // in the middle for a cool star-blue rather than a flat primary block. Separation b - r is ~150+, so it reads
            // unmistakably blue, never washed.
            r = 30 + (int) (unit(h, 16) * 55.0);         // 30..85
            g = 90 + (int) (unit(h, 24) * 70.0);         // 90..160
            b = 205 + (int) (unit(h, 8) * 50.0);         // 205..255
        }
        else if (cls < 0.62)
        {
            // ORANGE (plurality): true orange through warm amber. Full red, green held to roughly half..two-thirds of red
            // (below the ~0.8 ratio that would read yellow) and blue kept low, so it stays a saturated orange and never a
            // pale yellow. Separation r - b is ~200.
            r = 235 + (int) (unit(h, 16) * 20.0);        // 235..255
            g = 120 + (int) (unit(h, 24) * 45.0);        // 120..165
            b = 20 + (int) (unit(h, 8) * 30.0);          // 20..50
        }
        else
        {
            // REDDISH: deep red through red-orange. Full red, green low (a fifth to under half of red) and blue very low,
            // so it reads as an angry red star with strong channel separation, never a washed pink.
            r = 200 + (int) (unit(h, 16) * 55.0);        // 200..255
            g = 40 + (int) (unit(h, 24) * 55.0);         // 40..95
            b = 25 + (int) (unit(h, 8) * 35.0);          // 25..60
        }
        return (r << 16) | (g << 8) | b;
    }

    // true if the star centred at pos with the given half-extent overlaps any FIXED planet body's expanded bounding
    // cube, or falls within the cleared radius around the space origin. Uses the live fixed-planet set, exactly as
    // GeneratedPlanets does it.
    private static boolean overlapsFixedOrOrigin(MinecraftServer server, Vec3 pos, float radius)
    {
        if (Math.abs(pos.x) <= ORIGIN_CLEARANCE && Math.abs(pos.z) <= ORIGIN_CLEARANCE)
        {
            return true;
        }
        // B2: keep stars out of the reserved inner solar system (the sun and the approach to the innermost ring), so the
        // sun-and-rings core reads deliberately rather than cluttered. Purely derived, no saved state, so this is safe.
        if (PlanetPositions.insideInnerSystem(pos))
        {
            return true;
        }
        for (FixedBody planet : SpaceLayout.fixedBodies(server))
        {
            Vec3 c = planet.position;
            double half = planet.radius + FIXED_CLEARANCE + radius;
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
     * Every star whose position falls within {@code range} blocks of {@code around}. Walks the cubic cells the range
     * could touch and derives each one. Pure function of the position and the fixed/black-hole sets.
     */
    public static List<Star> starsNear(MinecraftServer server, Vec3 around, double range)
    {
        // Since the space rework the stars in the sky ARE the system suns: each is the centre of a generated star system
        // (one sun, 5 to 9 orbiting planets), enumerated by {@link GeneratedSystems}. This one method is the seam through
        // which the renderer draws them and the burn hazard heats them, unchanged. The old scattered decorative-star
        // derivation ({@link #starFor} / {@link #candidateFor}) is retired and no longer called; the {@link Star} record,
        // the radius band and the id namespace live on here because the system suns and the hazard/scan bounds reuse them.
        return GeneratedSystems.starsNear(server, around, range);
    }
}
