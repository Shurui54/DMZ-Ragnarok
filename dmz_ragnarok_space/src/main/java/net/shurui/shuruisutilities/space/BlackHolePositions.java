package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * Pure, stateless derivation of the BLACK HOLE field from the world position alone, in the exact idiom of
 * {@link StarPositions} / {@link GeneratedPlanets} / {@link AsteroidPositions}: NOTHING is stored. A black hole's
 * existence, its cell key, its position and its visual radius are all a pure function of the integer cell coordinates,
 * recomputed from the hash every time, so the whole set is stable across restarts and re-derivable if every black hole
 * entity is deleted.
 *
 * <h3>Distribution: rarer than stars</h3>
 * Space is divided into fixed {@link #sectorSize}-block cubic cells; a fraction {@link #density} of cells hold one black
 * hole. Both are config-baked (PlanetSpawnModule.bakeConfig). The DEFAULTS make black holes clearly RARER THAN STARS:
 * stars run at density {@value StarPositions#DEFAULT_DENSITY} over a {@value StarPositions#DEFAULT_SECTOR_SIZE}-block
 * sector, whereas a black hole sits in a {@value #DEFAULT_SECTOR_SIZE}-block sector (2x wider per axis, 8x the volume)
 * at density {@value #DEFAULT_DENSITY}. Per unit volume that is about one black hole for every ten stars, so a black
 * hole is a genuine rarity: at the default 8000 draw distance a player sees around five stars but only about one black
 * hole, something they might cross once in a long trip and must respect when they do.
 *
 * <h3>Danger zones (all derived from the visual radius)</h3>
 * <ul>
 *   <li>PULL radius = visual radius x PlanetSpawnModule.blackHoleInfluenceFactor (config, default 30, capped at 50).
 *       Inside it the player is dragged toward the centre, harder the closer they are (see SpaceHazardModule). The pull
 *       reaches well beyond the visible disc so a player feels the tug before they can see they are in trouble, which is
 *       the point. (The old fixed {@link #PULL_RADIUS_FACTOR} is legacy; the live reach is the config factor.)</li>
 *   <li>EVENT HORIZON radius = visual radius x {@link #HORIZON_RADIUS_FACTOR}. Crossing it KILLS. It sits just inside
 *       the visible disc so "you fell into the black" reads correctly.</li>
 * </ul>
 *
 * <h3>Never overlapping anything</h3>
 * A candidate is rejected if it would overlap any FIXED planet body, a SUPER body, a GENERATED planet body, an ASTEROID
 * lump, or fall too near the space origin/arrival column (the generated/asteroid checks are shared with the star via
 * {@link HazardExclusion}). The fixed-planet and super-body clearances are sized to the hole's ACTUAL pull reach (the
 * config cap, via INFLUENCE_CLEARANCE_FACTOR), so a pull field never reaches across a body a player is flying to. It does
 * NOT consult stars: the star field yields to black holes instead (see StarPositions class doc), which keeps the two
 * apart without either derivation recursing into the other. Two black-hole DISCS can never overlap: adjacent cells are
 * kept apart by the deterministic {@link CellOverlap} contest (the old "one body per cell" argument only ruled out
 * within-cell overlap, never two bodies meeting across a shared cell face). Their far larger PULL fields can still
 * overlap, which is unavoidable at this sector size and harmless (the pull simply sums).
 */
public final class BlackHolePositions
{
    private BlackHolePositions()
    {
    }

    // Stable id namespace, distinct from stars/planets/asteroids so keys never collide. Suffix is the sector hash in hex.
    public static final String ID_PREFIX = "subh:";

    // Config-baked defaults. See the class-doc distribution justification. volatile: written on the config thread, read
    // on the server thread.
    static final int DEFAULT_SECTOR_SIZE = 6144;
    static final double DEFAULT_DENSITY = 0.2;
    public static volatile int sectorSize = DEFAULT_SECTOR_SIZE;
    static volatile double density = DEFAULT_DENSITY;

    // Vertical band, matching the star/generated band.
    private static final double MIN_Y = 200.0;
    private static final double MAX_Y = 1600.0;

    // Visual disc half-extent range, in blocks. Smaller than a star (a black hole reads as a compact dark void, not a
    // huge glowing ball), but its pull reaches far past the disc. 40..80 half-extent = 80..160 blocks across.
    public static final float MIN_RADIUS = 40.0F;
    public static final float MAX_RADIUS = 80.0F;

    // Danger-zone multipliers over the visual radius. The lethal event horizon sits at 0.55x the disc, just inside what
    // the player sees. PULL_RADIUS_FACTOR (and the pullRadius() helper below) is LEGACY: the live pull reach is no longer
    // 6x, it is radius * PlanetSpawnModule.blackHoleInfluenceFactor (config, default 30, capped at 50), read by the
    // gravity code (SpaceHazardModule) directly. Placement clears at that config CAP via INFLUENCE_CLEARANCE_FACTOR below,
    // not this constant. PULL_RADIUS_FACTOR is kept only so the class-doc reference resolves and nothing that still
    // imports it breaks; do not use it to size a new pull field. (Deletion of the dead helper is batched.)
    public static final double PULL_RADIUS_FACTOR = 6.0;
    public static final double HORIZON_RADIUS_FACTOR = 0.55;

    // Clearance around a fixed planet's half-extent when rejecting an overlap, and the cleared radius around the space
    // origin. The fixed clearance is generous because the pull field is large and must not reach across a planet a
    // player is trying to land on.
    private static final double FIXED_CLEARANCE = 512.0;
    private static final double ORIGIN_CLEARANCE = 1200.0;

    // How many visual radii a black hole is kept clear of a FIXED planet (and a super body), sized to the hole's ACTUAL
    // pull reach rather than its little disc. The live pull field is radius * PlanetSpawnModule.blackHoleInfluenceFactor
    // (config, default 30), which the gravity code caps at 50. We clear at the CAP, not the live config value, on purpose:
    //   - It guarantees clearance >= the real reach for ANY operator factor (<= 50), so a hole is never placed where its
    //     pull could reach a fixed planet a player is flying to, even at the maximum setting.
    //   - It is a compile-time CONSTANT, identical on client and server, so the black-hole field derives the same on both
    //     sides with no new synced number (the sync only carries sector size + density). Reading the live factor here
    //     would need it synced or the two sides would disagree about which holes exist.
    // Kept in step with PlanetSpawnModule's blackHoleInfluenceFactor defineInRange upper bound (50); if that cap changes,
    // change this with it. At the 40..80 radius range this clears 2000..4000 blocks around each fixed planet, a large but
    // LOCAL bubble (there are only a few fixed planets, all near the origin), so black holes still populate the rest of
    // space freely; it does NOT drive any placement loop, since a black hole is a single per-cell derivation, not a
    // retry-until-valid sampler.
    private static final double INFLUENCE_CLEARANCE_FACTOR = 50.0;

    // Clearance in blocks added around another BLACK HOLE's disc when two adjacent-cell holes contest for the same space.
    // This keeps the visible DISCS from overlapping; note two holes' far larger PULL fields can still overlap, which is
    // unavoidable (a 6144 sector cannot separate two 2000..4000-block pull fields) and harmless (the pull simply sums).
    private static final double HOLE_CLEARANCE = 128.0;

    /**
     * A single derived black hole: its stable key, position and visual radius. Everything is a pure function of the
     * cell, never stored. The pull and event-horizon radii are computed from the visual radius on demand.
     */
    public static final class BlackHole
    {
        public final String key;
        public final Vec3 position;
        public final float radius;

        BlackHole(String key, Vec3 position, float radius)
        {
            this.key = key;
            this.position = position;
            this.radius = radius;
        }

        public double pullRadius()
        {
            return radius * PULL_RADIUS_FACTOR;
        }

        public double horizonRadius()
        {
            return radius * HORIZON_RADIUS_FACTOR;
        }
    }

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

    public static String keyFor(int cx, int cy, int cz)
    {
        long h = hash(cx, cy, cz);
        return ID_PREFIX + Long.toHexString(h & 0xFFFFFFFFFFFFL);
    }

    /**
     * The black hole for a single cell, or null if the cell is empty, its Y falls outside the band, it would overlap a
     * fixed planet, a super body, the origin column or a generated planet/asteroid, OR it loses the deterministic
     * cross-cell overlap contest to a higher-ranked adjacent black hole (so two hole discs can never spawn inside each
     * other across a shared cell face). Pure function of the cell coordinates and the current fixed / super / generated
     * sets. Does NOT consult stars (they yield to it), so this never recurses.
     */
    public static BlackHole blackHoleFor(MinecraftServer server, int cx, int cy, int cz)
    {
        BlackHole me = candidateFor(server, cx, cy, cz);
        if (me == null)
        {
            return null;
        }
        Vec3 p = me.position;
        long h = hash(cx, cy, cz);
        CellOverlap.Cand self = new CellOverlap.Cand(p.x, p.y, p.z, me.radius + HOLE_CLEARANCE, h, cx, cy, cz);
        boolean survives = CellOverlap.survives(self, sectorSize, MAX_RADIUS + HOLE_CLEARANCE, "black_hole",
                (ncx, ncy, ncz) ->
                {
                    BlackHole n = candidateFor(server, ncx, ncy, ncz);
                    if (n == null)
                    {
                        return null;
                    }
                    Vec3 np = n.position;
                    return new CellOverlap.Cand(np.x, np.y, np.z, n.radius + HOLE_CLEARANCE, hash(ncx, ncy, ncz),
                            ncx, ncy, ncz);
                });
        return survives ? me : null;
    }

    // the raw black-hole candidate for a cell (every gate EXCEPT the cross-cell hole contest), or null. Split out of
    // blackHoleFor so the contest can derive a neighbour without recursing into the contest.
    private static BlackHole candidateFor(MinecraftServer server, int cx, int cy, int cz)
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
        if (y < MIN_Y || y > MAX_Y)
        {
            return null;
        }
        Vec3 pos = new Vec3(x, y, z);

        // reject against fixed planets and super bodies using the INFLUENCE reach, not just the disc, so a black hole's
        // tug never reaches across a planet a player is landing on (INFLUENCE_CLEARANCE_FACTOR is the config cap, so this
        // holds at any operator influence setting). Also reject against generated planets and asteroids (shared with the
        // star via HazardExclusion) using the DISC radius: the full influence clearance around every generated planet
        // (~4000 blocks) would nearly forbid black holes given the generated density, so a hole keeps only its disc clear
        // of a generated body a player mines/lands on, and the pull can still reach a generated planet (see the audit
        // note). Super bodies are few and far apart, so the full influence clearance against them is workable.
        float influenceReach = (float) (radius * INFLUENCE_CLEARANCE_FACTOR);
        if (overlapsFixedOrOrigin(server, pos, influenceReach)
                || SuperPlanetPositions.overlaps(pos, influenceReach)
                || HazardExclusion.overlapsGeneratedOrAsteroid(server, pos, radius))
        {
            return null;
        }

        return new BlackHole(keyFor(cx, cy, cz), pos, radius);
    }

    private static boolean overlapsFixedOrOrigin(MinecraftServer server, Vec3 pos, float reach)
    {
        if (Math.abs(pos.x) <= ORIGIN_CLEARANCE && Math.abs(pos.z) <= ORIGIN_CLEARANCE)
        {
            return true;
        }
        // B2: keep black holes out of the reserved inner solar system, so a hole never sits among the sun and the inner
        // rings. Purely derived, no saved state, so this is safe.
        if (PlanetPositions.insideInnerSystem(pos))
        {
            return true;
        }
        for (FixedBody planet : SpaceLayout.fixedBodies(server))
        {
            Vec3 c = planet.position;
            double half = planet.radius + FIXED_CLEARANCE + reach;
            if (Math.abs(pos.x - c.x) <= half
                    && Math.abs(pos.y - c.y) <= half
                    && Math.abs(pos.z - c.z) <= half)
            {
                return true;
            }
        }
        return false;
    }

    // true if any black hole's DISC (centre within reach) overlaps the point pos expanded by `pad`. Used by
    // StarPositions so a star yields to a black hole. Walks the black hole's own cell plus neighbours; cheap and
    // non-recursive (blackHoleFor never consults stars).
    static boolean overlapsAnyHole(MinecraftServer server, Vec3 pos, double pad)
    {
        int sector = sectorSize;
        int cx = Math.floorDiv((int) Math.floor(pos.x), sector);
        int cy = Math.floorDiv((int) Math.floor(pos.y), sector);
        int cz = Math.floorDiv((int) Math.floor(pos.z), sector);
        for (int dx = -1; dx <= 1; ++dx)
        {
            for (int dy = -1; dy <= 1; ++dy)
            {
                for (int dz = -1; dz <= 1; ++dz)
                {
                    BlackHole hole = blackHoleFor(server, cx + dx, cy + dy, cz + dz);
                    if (hole == null)
                    {
                        continue;
                    }
                    double reach = hole.radius + pad;
                    if (Math.abs(pos.x - hole.position.x) <= reach
                            && Math.abs(pos.y - hole.position.y) <= reach
                            && Math.abs(pos.z - hole.position.z) <= reach)
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Every black hole whose position falls within {@code range} blocks of {@code around}. Walks the cubic cells the
     * range could touch and derives each one. Pure function of the position and the fixed-planet set.
     */
    public static List<BlackHole> blackHolesNear(MinecraftServer server, Vec3 around, double range)
    {
        List<BlackHole> out = new ArrayList<>();
        int sector = sectorSize;
        int minCx = Math.floorDiv((int) Math.floor(around.x - range), sector);
        int maxCx = Math.floorDiv((int) Math.floor(around.x + range), sector);
        int minCy = Math.floorDiv((int) Math.floor(around.y - range), sector);
        int maxCy = Math.floorDiv((int) Math.floor(around.y + range), sector);
        int minCz = Math.floorDiv((int) Math.floor(around.z - range), sector);
        int maxCz = Math.floorDiv((int) Math.floor(around.z + range), sector);
        double rangeSq = range * range;

        for (int cx = minCx; cx <= maxCx; ++cx)
        {
            for (int cy = minCy; cy <= maxCy; ++cy)
            {
                for (int cz = minCz; cz <= maxCz; ++cz)
                {
                    BlackHole hole = blackHoleFor(server, cx, cy, cz);
                    if (hole == null)
                    {
                        continue;
                    }
                    if (hole.position.distanceToSqr(around) <= rangeSq)
                    {
                        out.add(hole);
                    }
                }
            }
        }
        return out;
    }
}
