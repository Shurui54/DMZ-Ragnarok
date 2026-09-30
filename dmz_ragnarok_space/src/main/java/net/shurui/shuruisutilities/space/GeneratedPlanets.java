package net.shurui.shuruisutilities.space;

import net.shurui.shuruisutilities.world.space.SurfaceTravelData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * Pure, stateless derivation of the GENERATED planet bodies in space, from world position alone, the same idiom as
 * {@link AsteroidPositions}: NOTHING is stored. A generated planet's existence, stable id, position, surface size, tint
 * and name are all a pure function of the integer sector coordinates, recomputed from the hash every time, so the set
 * is stable across restarts and re-derivable if every body entity is deleted. Claims and surface-generated flags are
 * the only persisted things, and they live in {@link GeneratedPlanetClaims} keyed by the id below, never on an entity.
 *
 * <h3>Distribution</h3>
 * Space is divided into fixed {@link #sectorSize}-block cubic cells; a fraction {@link #density} of them hold one
 * planet at a hashed offset inside the cell. Both are config-baked (PlanetSpawnModule). Occupancy roll and offset are
 * pure functions of the same cell hash, so every player derives the identical planet for a cell, which the idempotent
 * spawn in PlanetSpawnModule relies on.
 *
 * <h3>Never overlapping anything</h3>
 * A candidate is rejected if its body would overlap any FIXED body (Earth, Namek, ...), a SUPER body's start, or the
 * origin/arrival column. The fixed centres/half-extents come from the same {@link PlanetRegistry}/{@link
 * PlanetPositions} the bodies are drawn from, so the check uses the geometry the player sees. Two generated planets
 * never overlap either: at most one per cell, and two bodies in ADJACENT cells that would meet across the shared face
 * are separated by the deterministic {@link CellOverlap} contest (the old "sector far larger than any body" argument
 * only ruled out within-cell overlap; adjacent-cell bodies near a shared face could touch).
 */
public final class GeneratedPlanets
{
    private GeneratedPlanets()
    {
    }

    // Stable id namespace for a generated planet. Distinct from any DMZ dimension id (the fixed PlanetRegistry keys), so
    // the two can never be confused. Suffix is the sector hash in hex.
    public static final String ID_PREFIX = "sugen:";

    // Edge length of a cubic sector cell, in blocks. Config-baked (PlanetSpawnModule.bakeConfig). Still much larger than
    // any body (~55 blocks half-extent) and at most one body per cell, so two generated bodies never overlap whatever
    // the value. volatile: written on the config thread, read on the server thread.
    public static volatile int sectorSize = 2048;

    // Fraction of cells that hold a generated planet, 0..1. Config-baked. Uses the low hash window so the
    // offset/size/tint windows stay independent.
    static volatile double density = 0.5;

    // Vertical band a generated body's position may occupy, config-baked. Kept inside the space play band (min_y -64,
    // height 2048) and spanning the fixed planet altitude (900) so bodies read alongside the fixed ring.
    static volatile double minY = 200.0;
    static volatile double maxY = 1600.0;

    // Back-compat accessor for the sector edge, so old call sites read the live value.
    public static int sectorSize()
    {
        return sectorSize;
    }

    // Surface size range, in blocks per side, for the stamped surface disc: 100x100 (a small world) to 500x500 (a proper
    // landmass). A moon keeps its OWN size (MoonBody.SURFACE_SIZE, default 400), outside this range. The largest disc
    // still fits its 65,536-block surface cell with kilometres to spare (see SurfaceDimension).
    public static final int MIN_SURFACE = 100;
    public static final int MAX_SURFACE = 500;

    // Historical surface-size range planets were stamped under BEFORE MIN/MAX_SURFACE widened from 20..200 to 100..500.
    // Kept ONLY so backfillStampedGeometry can reconstruct the true stamped size of a pre-stamped-geometry planet: it
    // has no stored size and its terrain on disk was laid under THIS range, so legacySurfaceSizeForId reproduces it
    // exactly. Do NOT delete these or fold the legacy formula back into surfaceSizeForId: that makes the backfill lock a
    // NEW-range size over OLD-range terrain and reintroduces the horizontal-boundary / body-radius desync the
    // stamped-size store exists to prevent (players walk off the true rim into void inside the "legal" boundary). Change
    // the live range again and the same hazard recurs for any not-yet-backfilled planet, needing its own legacy tier.
    public static final int LEGACY_MIN_SURFACE = 20;
    public static final int LEGACY_MAX_SURFACE = 200;

    // Visual half-extent range of the SPACE BODY (the rotating cube), in blocks. Scaled from the same size roll (via
    // bodyRadiusForSurfaceSize) so a bigger surface reads as a bigger body. Smallest (32) sits just above the smallest
    // fixed body (24); largest (96) is ~1.75x the largest fixed body (55). 96 stays well inside the space Y band (bodies
    // at Y 200..1600) and keeps the planet-buster clash trigger max(50, radius*2) at a sane 192 at the top.
    private static final float MIN_BODY_RADIUS = 32.0F;
    private static final float MAX_BODY_RADIUS = 96.0F;

    // Clearance added around a fixed planet's half-extent when rejecting an overlapping generated body, and the radius
    // kept clear around the space origin so a body never sits on the arrival column. Mirror the asteroid field's
    // PLANET_CLEARANCE.
    private static final double FIXED_CLEARANCE = 128.0;
    private static final double ORIGIN_CLEARANCE = 1200.0;

    /**
     * A single generated planet: stable id, position, tint, space-body visual radius and surface size (blocks per side).
     * Everything is a pure function of the sector, never stored.
     */
    public static final class Generated
    {
        public final String id;
        public final Vec3 position;
        public final int tint;
        public final float radius;
        public final int surfaceSize;
        // the "cx,cy,cz" cell this planet occupies. Carried so the destruction store, which keys the destroyed flag and
        // generation counter by cell (a planet id cannot be inverted to its cell), is reachable without a search.
        public final String cellKey;

        Generated(String id, Vec3 position, int tint, float radius, int surfaceSize, String cellKey)
        {
            this.id = id;
            this.position = position;
            this.tint = tint;
            this.radius = radius;
            this.surfaceSize = surfaceSize;
            this.cellKey = cellKey;
        }

        // half-extent of the square surface, in blocks from the cell centre.
        public int surfaceHalf()
        {
            return surfaceSize / 2;
        }
    }

    // splitmix64-style hash of three cell coordinates, matching AsteroidPositions so adjacent cells scatter to unrelated
    // values. Deterministic and JVM-stable (integer arithmetic only).
    private static long hash(long cx, long cy, long cz)
    {
        long z = (cx * 0x9E3779B97F4A7C15L) ^ (cy * 0xC2B2AE3D27D4EB4FL) ^ (cz * 0x165667B19E3779F9L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // The cell hash mixed with the cell's GENERATION counter, the single seam through which generation enters the whole
    // derivation (id, position, tint, radius all flow from this). GENERATION 0 RETURNS THE BASE HASH UNCHANGED, byte for
    // byte, so every existing world keeps every planet: the counter starts at 0 and only a destruction bumps it. A
    // generation > 0 folds the counter through the same finaliser, producing an uncorrelated hash and so a genuinely
    // different planet in that cell.
    private static long cellHash(long cx, long cy, long cz, int generation)
    {
        long base = hash(cx, cy, cz);
        if (generation == 0)
        {
            return base;
        }
        long z = base ^ ((long) generation * 0xD6E8FEB86659FD93L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // the "cx,cy,cz" cell key, fixed across a generation bump. The destruction store keys the destroyed flag and
    // generation counter by this, since a planet id embeds only the one-way mixed hash and cannot be inverted to a cell.
    public static String cellKey(int cx, int cy, int cz)
    {
        return cx + "," + cy + "," + cz;
    }

    // parse a "cx,cy,cz" cell key back to its three coordinates, or null if malformed. Inverse of cellKey, needed by the
    // wreck derivation: recovering a former planet's geometry (formerBody) feeds the raw coordinates to cellHash. Kept
    // beside cellKey so format and parser cannot drift apart.
    public static int[] parseCellKey(String cellKey)
    {
        if (cellKey == null)
        {
            return null;
        }
        String[] parts = cellKey.split(",");
        if (parts.length != 3)
        {
            return null;
        }
        try
        {
            return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) };
        }
        catch (NumberFormatException ex)
        {
            return null;
        }
    }

    // stable 64-bit hash of a string id, splitmix64 finaliser over the String hashCode. Used by SurfaceDimension to
    // place a planet id's surface cell. Deterministic and JVM-stable.
    static long hashOf(String id)
    {
        long z = id.hashCode() * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // 0..1 double from a 64-bit hash's given byte-window, matching AsteroidPositions.
    private static double unit(long h, int shift)
    {
        return ((h >>> shift) & 0xFFFFFFL) / 16777216.0;
    }

    /**
     * Whether a body id may be destroyed: a generated planet ({@link #ID_PREFIX} {@code sugen:}) or a moon ({@link
     * MoonBody#ID_PREFIX} {@code sumoon:}). Every FIXED body keys on a DIMENSION id, which starts with neither prefix,
     * so a fixed world can never pass and is INDESTRUCTIBLE. A moon is a claimable, landable slot that regenerates after
     * a bust (see {@link MoonBody#hasMoon} and the debris timer in PlanetSpawnModule), so it is destructible on purpose.
     * Route every destroy decision through here so nothing can accidentally destroy a fixed body.
     */
    public static boolean isDestructible(String id)
    {
        if (id == null)
        {
            return false;
        }
        // The MAIN central sun is NEVER destructible (owner rule): refuse it explicitly, not merely by prefix, so no
        // future id scheme could ever let it through. It is not a sugen:/sumoon:/sustar: id anyway, but this is the
        // belt-and-braces the rule asks for. A GENERATED SYSTEM sun (sustar:) IS destructible; a fixed body never is.
        if (PlanetPositions.SUN_KEY.equals(id))
        {
            return false;
        }
        return id.startsWith(ID_PREFIX) || MoonBody.isMoon(id) || GeneratedSystems.isSystemStar(id);
    }

    // A light grey tint for a moon adapted into the {@link Generated} shape below. A moon has no hash-rolled colour (its
    // appearance is the pack's moon art), so this is read only by the doom shatter (PacketPlanetDoom), where neutral grey
    // reads as a shattered moon. Packed 0xRRGGBB, same as colourTint.
    private static final int MOON_TINT = (200 << 16) | (200 << 8) | 200;

    /**
     * Adapt an orbiting {@link MoonBody.Moon} into the {@link Generated} shape the single planet-destruction pipeline
     * (in-flight steering, the doom sequence, {@link PlanetDestruction#destroy(net.minecraft.server.level.ServerLevel,
     * Generated, net.minecraft.server.level.ServerPlayer, boolean)}, {@link PlanetSalvage#capture}) already consumes, so
     * a moon flows through it with no parallel copy. The {@code cellKey} is the MOON ID itself: a moon has no cell, so
     * its id is its own synthetic single-occupant cell key. That key is harmless everywhere a real cell key is parsed,
     * because {@link #parseCellKey} returns null for a {@code sumoon:} id, so the wreck derivation just skips a moon. The
     * pipeline only reads id, position, radius, surfaceSize, cellKey and tint, all supplied here, so nothing downstream
     * can tell a moon from a generated planet.
     */
    public static Generated forMoon(String moonId, Vec3 position, float radius, int surfaceSize)
    {
        return new Generated(moonId, position, MOON_TINT, radius, surfaceSize, moonId);
    }

    /**
     * Adapt a {@link GeneratedSystems} planet into the {@link Generated} shape the whole pipeline consumes, so a system
     * planet flows through drawing, landing, claims, salvage, the planet-info readout and destruction with no parallel
     * copy. Like {@link #forMoon}, the {@code cellKey} is the PLANET ID itself: a system planet has no old-scheme
     * {@code cx,cy,cz} cell, so its id is its own synthetic single-occupant cell key. That key is harmless everywhere a
     * real cell key is parsed, because {@link #parseCellKey} returns null for a {@code sugen:} hex id (it is not the
     * {@code "cx,cy,cz"} form), so the wreck derivation skips it: a destroyed system planet leaves no rubble and never
     * regenerates, which is the intended behaviour for a body that belongs to a star.
     */
    public static Generated forSystemPlanet(String planetId, Vec3 position, int tint, float radius, int surfaceSize)
    {
        return new Generated(planetId, position, tint, radius, surfaceSize, planetId);
    }

    /**
     * Adapt a system STAR into the {@link Generated} shape so the ONE {@link PlanetDestruction} pipeline (in-flight
     * steering, the doom ramp, the destroy) can treat a sun uniformly. A star has no landable surface, so its surface
     * size is 0; the destroy step detects the star id ({@link GeneratedSystems#isSystemStar}) and cascades to the
     * system's planets rather than reading a surface. The {@code cellKey} is the star key itself, the same single-occupant
     * convention as a moon and a system planet.
     */
    public static Generated forStar(String starKey, Vec3 position, float radius, int tint)
    {
        return new Generated(starKey, position, tint, radius, 0, starKey);
    }

    // stable id for a sector cell AT AN EXPLICIT GENERATION: the cell coordinates and generation hashed to a short hex
    // tail, never colliding with a DMZ dimension id. Generation 0 reproduces the exact pre-generation id, so existing
    // worlds' planet ids are unchanged; a bumped generation derives a wholly different id, so its claim and surface
    // start fresh. Generation is passed in (never looked up here) so this stays pure and the caller owns the thread that
    // reads the destruction store.
    public static String idFor(int cx, int cy, int cz, int generation)
    {
        long h = cellHash(cx, cy, cz, generation);
        return ID_PREFIX + Long.toHexString(h & 0xFFFFFFFFFFFFL);
    }

    // a raw generated candidate plus the cell hash it is ranked by. The hash is the SAME value id/position/tint/radius
    // flow from, so it is a free, deterministic rank for the cross-cell overlap contest.
    private record Cand(Generated g, long h)
    {
    }

    // Clearance added around another GENERATED planet's half-extent when two adjacent-cell bodies contest the same
    // space, so the survivor keeps a visible gap from the one that yielded (a touching pair, like FIXED_CLEARANCE).
    private static final double GENERATED_CLEARANCE = 96.0;

    /**
     * The generated planet for a sector cell, or null if the cell is empty (density roll failed), its Y is outside the
     * band, its body would overlap a fixed planet or the origin column, or it loses the cross-cell overlap contest to a
     * higher-ranked adjacent planet (so two generated planets never spawn inside each other, which the old "one body per
     * cell" argument did not guarantee across a shared face). Pure function of the cell and the fixed-planet set.
     */
    public static Generated generatedFor(MinecraftServer server, int cx, int cy, int cz)
    {
        Cand me = candidateFor(server, cx, cy, cz);
        if (me == null)
        {
            return null;
        }
        // SYSTEM-ERA LEGACY GATE. The old scattered per-cell planet field is REPLACED by {@link GeneratedSystems}: an
        // old-scheme cell planet now exists ONLY if a player invested in it (claimed or stamped), in which case it stays
        // EXACTLY where it was, unmoving, with its surface cells, claims and salvage untouched. A never-visited old planet
        // simply stops existing (a documented data change, matching how a density change would move a purely-derived
        // planet). Read through the same synced seam client and server both use, so both agree on which legacy cells
        // survive. New generated planets come from GeneratedSystems (systems around suns), surfaced through generatedNear
        // / bodyContaining below, never through this cell-keyed entry point.
        if (!SpaceLayout.isClaimedOrStamped(server, me.g.id))
        {
            return null;
        }
        // cross-cell overlap contest: yield to a higher-ranked overlapping neighbour, contested on radius +
        // GENERATED_CLEARANCE so survivors keep a gap. Derives only RAW neighbour candidates (candidateFor), so it
        // cannot recurse.
        Vec3 p = me.g.position;
        CellOverlap.Cand self = new CellOverlap.Cand(p.x, p.y, p.z, me.g.radius + GENERATED_CLEARANCE, me.h, cx, cy, cz);
        boolean survives = CellOverlap.survives(self, sectorSize, MAX_BODY_RADIUS + GENERATED_CLEARANCE, "generated",
                (ncx, ncy, ncz) ->
                {
                    Cand n = candidateFor(server, ncx, ncy, ncz);
                    if (n == null)
                    {
                        return null;
                    }
                    Vec3 np = n.g.position;
                    return new CellOverlap.Cand(np.x, np.y, np.z, n.g.radius + GENERATED_CLEARANCE, n.h, ncx, ncy, ncz);
                });
        return survives ? me.g : null;
    }

    // the raw generated candidate for a cell (every gate EXCEPT the cross-cell contest), or null. Split out so the
    // contest can derive a neighbour without recursing into the contest.
    private static Cand candidateFor(MinecraftServer server, int cx, int cy, int cz)
    {
        // resolve the generation ONCE through the shared accessor, so client (synced snapshot) and server (SavedData)
        // feed the identical generation into every downstream derivation and both land on the identical planet.
        String cellKey = cellKey(cx, cy, cz);
        int generation = SpaceLayout.generationFor(server, cellKey);
        long h = cellHash(cx, cy, cz, generation);

        // occupancy: only a `density` fraction of cells hold a body.
        if (unit(h, 0) >= density)
        {
            return null;
        }

        // hashed offset inside the cell on each axis, then clamp Y into the band.
        int sector = sectorSize;
        double ox = unit(h, 24) * sector;
        double oy = unit(h, 40) * sector;
        double oz = unit(h, 8) * sector;
        double x = (double) cx * sector + ox;
        double z = (double) cz * sector + oz;
        double y = (double) cy * sector + oy;
        if (y < minY || y > maxY)
        {
            return null;
        }
        Vec3 pos = new Vec3(x, y, z);

        // surface size is derived from the ID (surfaceSizeForId), not the sector hash, and the body radius scaled from
        // it. Routing both through the id keeps the space body, landing/stamp and horizontal boundary in lockstep with
        // nothing stored: loaded or not, surfaceSizeForId(id) always returns the value the body was sized from.
        String id = idFor(cx, cy, cz, generation);

        // a DESTROYED planet is suppressed at the source, so every consumer (renderer draw list, landing check, compass,
        // generatedNear/bodyContaining) inherits the suppression with no filter of its own. The destroyed flag is read
        // through the SAME shared accessor as the generation, so server and client never disagree about what is gone.
        if (SpaceLayout.isDestroyed(server, id))
        {
            return null;
        }

        // A planet a player has invested in (claimed or stamped) is EXEMPT from the layout-shaping rejections below, so it
        // can NEVER be suppressed by something moving over it. This matters now that fixed bodies ORBIT: a fixed body
        // sweeping within its clearance of a claimed planet would otherwise make generatedFor drop the claimed planet for
        // the minutes the body is near, hiding a planet a guild owns (and briefly making it unlandable). Read through the
        // same synced seam client and server both use, so both agree on which planets are exempt. Never-visited planets
        // are not exempt: they may still be biased out of the inner system or rejected against a body, exactly as before.
        boolean exempt = SpaceLayout.isClaimedOrStamped(server, id);

        // B2 INNER-SYSTEM BIAS. Keep the sun-and-rings inner system clear of derived clutter: a generated planet with NO
        // persisted state (never claimed, never stamped) inside the reserved radius is dropped, exactly as a density
        // change would move a purely-derived planet. A CLAIMED or STAMPED planet is EXEMPT (isClaimedOrStamped) and stays
        // exactly where it is, so the rework never orphans a planet a player invested in. Read through the shared seam so
        // client (synced owners / stamped sizes) and server (the claim store) agree on which inner cells survive, never
        // "drawn but cannot land". Placed before the sizing/overlap gates because it needs only the id and position.
        if (PlanetPositions.insideInnerSystem(pos) && !exempt)
        {
            return null;
        }

        // route the body's size through the STAMPED size (falls back to the derived size for a fresh planet or a
        // client-side null server), so an already-stamped planet's body keeps the size its terrain was built at even
        // after the derived constants change. bodyRadiusForSurfaceSize is parametric so it never saturates or mismaps.
        int surfaceSize = GeneratedPlanetClaims.stampedSizeForId(server, id);
        float radius = bodyRadiusForSurfaceSize(surfaceSize);

        // never overlap a fixed planet or the origin column, exactly like the asteroid field. A claimed/stamped planet is
        // exempt from the fixed/super overlap (an orbiting fixed body must never hide it), but the origin column is
        // rejected for everyone (nothing is ever placed on the arrival column; a persisted planet is never there anyway).
        if (overlapsFixedOrOrigin(server, pos, radius, exempt))
        {
            return null;
        }

        int tint = colourTint(h);
        return new Cand(new Generated(id, pos, tint, radius, surfaceSize, cellKey), h);
    }

    /**
     * The geometry of the planet that USED to occupy a cell at a given generation, re-derived WITHOUT the destroyed
     * suppression. The crux of the wreck feature: generatedFor drops a destroyed planet (via {@link
     * SpaceLayout#isDestroyed}), yet the wreck must be centred where the planet was and scaled to its size. The cell and
     * destruction generation fully determine the hash, so this reproduces the SAME position, radius and tint byte for
     * byte with nothing stored. Both sides call it with the identical (cell, generation), so both recover the identical
     * former body; they cannot drift because the only inputs are the shared cell/generation and synced {@link
     * #sectorSize}. It skips the density / Y-band / overlap / destroyed gates on purpose: the planet demonstrably
     * existed (it was destroyed), so its geometry is wanted regardless.
     */
    public static final class FormerBody
    {
        public final String id;
        public final Vec3 position;
        public final float radius;
        public final int tint;

        FormerBody(String id, Vec3 position, float radius, int tint)
        {
            this.id = id;
            this.position = position;
            this.radius = radius;
            this.tint = tint;
        }
    }

    public static FormerBody formerBody(int cx, int cy, int cz, int generation)
    {
        // identical maths to generatedFor's position/id/radius/tint, minus the existence gates. Keep in lockstep: if
        // generatedFor's geometry changes, this must change with it.
        long h = cellHash(cx, cy, cz, generation);
        int sector = sectorSize;
        double ox = unit(h, 24) * sector;
        double oy = unit(h, 40) * sector;
        double oz = unit(h, 8) * sector;
        Vec3 pos = new Vec3((double) cx * sector + ox, (double) cy * sector + oy, (double) cz * sector + oz);
        String id = idFor(cx, cy, cz, generation);
        // a wreck is a pure re-derivation with no server in hand, so its radius uses the derived size through the shared
        // mapping. Its stamped-geometry record is being cleared with the destruction, so nothing persisted to prefer.
        float radius = bodyRadiusForSurfaceSize(surfaceSizeForId(id));
        return new FormerBody(id, pos, radius, colourTint(h));
    }

    // packed 0xRRGGBB tint hashed off the cell hash, each channel floored well above black so a body never renders as a
    // near-black blob. Same shape as PlanetPositions.tint.
    private static int colourTint(long h)
    {
        int r = 96 + (int) (unit(h, 16) * 150.0);
        int g = 96 + (int) (unit(h, 40) * 150.0);
        int b = 96 + (int) (unit(h, 8) * 150.0);
        return (r << 16) | (g << 8) | b;
    }

    // true if the body at pos overlaps any FIXED planet's expanded bounding cube or falls within the cleared radius
    // around the space origin. Uses the live fixed-planet set, like AsteroidPositions.overlapsAnyPlanet.
    private static boolean overlapsFixedOrOrigin(MinecraftServer server, Vec3 pos, float radius, boolean exempt)
    {
        // clear the origin/arrival column so a body never sits where a player materialises. Applies to EVERY planet,
        // exempt or not: a persisted planet is never on the arrival column, so this can only ever reject a derived one.
        if (Math.abs(pos.x) <= ORIGIN_CLEARANCE && Math.abs(pos.z) <= ORIGIN_CLEARANCE)
        {
            return true;
        }
        // a claimed/stamped planet is EXEMPT from the moving-body overlaps below (an orbiting fixed body, or a relocated
        // super body's ring, must never suppress a planet a player owns). Only never-visited derived planets yield here.
        if (exempt)
        {
            return false;
        }
        // never overlap one of the seven fixed super-ball bodies. Their positions are pure (no server, see
        // SuperPlanetPositions), so the client rejects identically and both sides derive the same field around one.
        if (SuperPlanetPositions.overlaps(pos, radius))
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
     * Every generated planet whose body falls within {@code range} of {@code around}. Walks the cells the range could
     * touch and derives each. Pure function of position and the fixed-planet set.
     */
    public static List<Generated> generatedNear(MinecraftServer server, Vec3 around, double range)
    {
        List<Generated> out = new ArrayList<>();
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
                    Generated g = generatedFor(server, cx, cy, cz);
                    if (g == null)
                    {
                        continue;
                    }
                    if (g.position.distanceToSqr(around) <= rangeSq)
                    {
                        out.add(g);
                    }
                }
            }
        }
        // union in the SYSTEM planets (the sun-centred replacement for the old scattered field). They carry the same
        // sugen: id shape and Generated fields, so every consumer of this list (renderer, landing sweep, planet-info ray,
        // star map, salvage) treats a system planet exactly like a legacy one. Suppression of destroyed planets and
        // destroyed system stars happens at the GeneratedSystems source, so nothing extra is filtered here.
        out.addAll(GeneratedSystems.planetsNear(server, around, range));
        return out;
    }

    /**
     * The generated planet a space position is INSIDE (body cube plus landing margin), or null. Walks only that cell and
     * its neighbours, cheap enough for the per-player landing check. Used by SpaceTravelModule.
     */
    public static Generated bodyContaining(MinecraftServer server, Vec3 p, double margin)
    {
        int sector = sectorSize;
        int cx = Math.floorDiv((int) Math.floor(p.x), sector);
        int cy = Math.floorDiv((int) Math.floor(p.y), sector);
        int cz = Math.floorDiv((int) Math.floor(p.z), sector);
        for (int dx = -1; dx <= 1; ++dx)
        {
            for (int dy = -1; dy <= 1; ++dy)
            {
                for (int dz = -1; dz <= 1; ++dz)
                {
                    Generated g = generatedFor(server, cx + dx, cy + dy, cz + dz);
                    if (g == null)
                    {
                        continue;
                    }
                    Vec3 c = g.position;
                    double reach = g.radius + margin;
                    if (Math.abs(p.x - c.x) <= reach && Math.abs(p.y - c.y) <= reach && Math.abs(p.z - c.z) <= reach)
                    {
                        return g;
                    }
                }
            }
        }
        // no legacy (claimed/stamped) planet here: fall through to the SYSTEM planets, so a player flying into a system
        // planet lands on it exactly as on a legacy one.
        return GeneratedSystems.planetContaining(server, p, margin);
    }

    // The largest visual half-extent any generated body can have, exposed so the planet-buster trigger can pad its ray
    // search: a body whose CENTRE sits just past the legal name-range can still show a near face inside it, so the caller
    // widens the ray cap by this much, then applies the exact surface-distance rule itself. An accessor, not a bare
    // field, so the band stays this class's single source of truth.
    public static float maxBodyRadius()
    {
        return MAX_BODY_RADIUS;
    }

    /**
     * Map a surface size (blocks per side) onto the space-body visual half-extent, the SINGLE place the surface range is
     * tied to the body-radius band. Parametric on {@link #MIN_SURFACE}..{@link #MAX_SURFACE} and {@link
     * #MIN_BODY_RADIUS}..{@link #MAX_BODY_RADIUS}, so a surface-range change moves the mapping here alone and every body-
     * radius consumer follows. The fraction is CLAMPED to 0..1 so a persisted stamped size predating a range change maps
     * to the nearest band endpoint rather than saturating or going negative.
     */
    public static float bodyRadiusForSurfaceSize(int surfaceSize)
    {
        double span = (double) (MAX_SURFACE - MIN_SURFACE);
        double sizeFrac = span <= 0.0 ? 0.0 : (surfaceSize - MIN_SURFACE) / span;
        sizeFrac = Math.max(0.0, Math.min(1.0, sizeFrac));
        return MIN_BODY_RADIUS + (float) (sizeFrac * (MAX_BODY_RADIUS - MIN_BODY_RADIUS));
    }

    /**
     * The FIRST generated planet whose body cube a ray enters, walking from {@code origin} along {@code dir} up to
     * {@code maxDistance}, or null. Used by the planet-buster trigger to pick the planet a player is aiming at. A proper
     * slab-method ray/AABB test per candidate (no ray stepping, so a thin body is never skipped); the candidate set is
     * the {@link #generatedNear} bodies, inheriting destroyed-suppression and fixed-overlap rejection, so a hit is always
     * a real, live, landable body. Padded by one max body radius because a body whose CENTRE is just past {@code
     * maxDistance} can still show a near face crossed within it. Pure function of position, direction and the fixed set.
     */
    public static Generated bodyAlongRay(MinecraftServer server, Vec3 origin, Vec3 dir, double maxDistance)
    {
        // server may be null: {@link #generatedNear} derives the candidate set purely (reading the synced fixed-body set
        // and config via SpaceLayout), so this ray test runs identically on the client. The planet-info overlay uses it
        // to detect the aimed-at body with the SAME geometry the server uses, so they never disagree. Only a degenerate
        // (zero-length) direction is rejected.
        if (dir.lengthSqr() < 1.0E-9)
        {
            return null;
        }
        Vec3 d = dir.normalize();
        Generated best = null;
        double bestEntry = Double.MAX_VALUE;
        for (Generated g : generatedNear(server, origin, maxDistance + MAX_BODY_RADIUS))
        {
            double entry = rayCubeEntry(origin, d, g.position, g.radius, maxDistance);
            // nearest positive entry wins, so a body being looked THROUGH never masks the one in front of it.
            if (entry >= 0.0 && entry < bestEntry)
            {
                bestEntry = entry;
                best = g;
            }
        }
        return best;
    }

    // slab-method ray/AABB intersection: the distance along unit ray d from o at which it ENTERS the cube centred at c
    // with the given half-extent, clamped to [0, maxDistance], or -1 if it does not enter within that reach. Standard
    // three-slab test; an origin inside the cube yields 0, a ray parallel to a slab misses unless the origin sits within
    // that slab's extent.
    //
    // Public because it is the ONE copy the whole targeting stack shares: bodyAlongRay for generated planets,
    // PlanetInfoTarget for orbiting-moon cubes, so both are measured "nearest along the ray" by the same maths. Do NOT
    // copy this into a third place.
    public static double rayCubeEntry(Vec3 o, Vec3 d, Vec3 c, double half, double maxDistance)
    {
        double[] od = {o.x, o.y, o.z};
        double[] dd = {d.x, d.y, d.z};
        double[] cc = {c.x, c.y, c.z};
        double tmin = 0.0;
        double tmax = maxDistance;
        for (int i = 0; i < 3; ++i)
        {
            double min = cc[i] - half;
            double max = cc[i] + half;
            if (Math.abs(dd[i]) < 1.0E-9)
            {
                // parallel to this slab: no hit unless the origin is already between the slab planes.
                if (od[i] < min || od[i] > max)
                {
                    return -1.0;
                }
            }
            else
            {
                double inv = 1.0 / dd[i];
                double t0 = (min - od[i]) * inv;
                double t1 = (max - od[i]) * inv;
                if (t0 > t1)
                {
                    double tmp = t0;
                    t0 = t1;
                    t1 = tmp;
                }
                if (t0 > tmin)
                {
                    tmin = t0;
                }
                if (t1 < tmax)
                {
                    tmax = t1;
                }
                if (tmin > tmax)
                {
                    return -1.0;
                }
            }
        }
        return tmin;
    }

    /**
     * Resolve a LIVE generated planet id back to its {@link Generated} (which carries the cell key the destruction store
     * needs), by searching the cells around a set of anchor positions. A planet id embeds only a one-way hash of its
     * cell, so it cannot be inverted; instead we walk cells near each anchor and match the id. Anchors are every player
     * in the space dimension plus every recorded surface-body position ({@link SurfaceTravelData}), covering the
     * realistic cases (flying near the target, or standing on its surface). Returns null if no anchor is near enough, in
     * which case the caller reports "fly closer to it".
     */
    public static Generated findGenerated(MinecraftServer server, String id)
    {
        if (server == null || id == null)
        {
            return null;
        }
        java.util.List<Vec3> anchors = new ArrayList<>();
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers())
        {
            if (SpaceDimension.isSpace(player.level()))
            {
                anchors.add(player.position());
            }
            if (SurfaceTravelData.hasBody(player))
            {
                anchors.add(new Vec3(SurfaceTravelData.bodyX(player), SurfaceTravelData.bodyY(player),
                        SurfaceTravelData.bodyZ(player)));
            }
        }
        // a few sectors covers a planet the admin can plausibly see.
        double range = sectorSize * 4.0;
        for (Vec3 anchor : anchors)
        {
            for (Generated g : generatedNear(server, anchor, range))
            {
                if (g.id.equals(id))
                {
                    return g;
                }
            }
        }
        return null;
    }

    // A short, pronounceable name derived from the id so a guild can refer to a planet without raw hex. Alternates
    // consonants and vowels from independent windows of the id hash, capitalised. Stable per id, 6..8 letters.
    private static final char[] CONSONANTS = "bcdfghjklmnprstvwz".toCharArray();
    private static final char[] VOWELS = "aeiou".toCharArray();

    public static String nameFor(String id)
    {
        // a moon's name derives from its PARENT (e.g. "Overworld Moon"), not a syllable roll, so route moon ids to
        // MoonBody. This being the single nameFor entry point keeps the command, guild GUI and landing message in sync.
        if (MoonBody.isMoon(id))
        {
            return MoonBody.nameFor(id);
        }
        long h = hashOf(id + "#name");
        StringBuilder sb = new StringBuilder();
        // 3 or 4 consonant-vowel syllables -> 6..8 letters.
        int syllables = 3 + (int) ((h >>> 60) & 1);
        for (int i = 0; i < syllables; ++i)
        {
            long slice = h >>> (i * 8);
            sb.append(CONSONANTS[(int) (slice & 0xFF) % CONSONANTS.length]);
            sb.append(VOWELS[(int) ((slice >>> 4) & 0xFF) % VOWELS.length]);
        }
        String name = sb.toString();
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    // The single source of truth for a generated planet's surface size, keyed on the id string. generatedFor sizes both
    // the surface and the space body from this, and the stamp and horizontal boundary read it too, so all four agree
    // with nothing stored and regardless of whether the body entity is loaded. Pure function of the id (100..500, even).
    public static int surfaceSizeForId(String id)
    {
        // a moon is fixed-size, not hash-rolled: route its size through the one moon value so the stamp, boundary, leave
        // standoff and guild GUI all read it here. MoonBody.SURFACE_SIZE is config-baked (default 400), outside the
        // generated range. This is the CURRENT size; an already-stamped moon reads its persisted size back through
        // GeneratedPlanetClaims.stampedSizeForId, so a moon stamped at the old 20 keeps 20.
        if (MoonBody.isMoon(id))
        {
            return MoonBody.SURFACE_SIZE;
        }
        long h = hashOf(id + "#surface");
        double sizeRoll = ((h >>> 20) & 0xFFFFFFL) / 16777216.0;
        int surfaceSize = MIN_SURFACE + (int) (sizeRoll * (MAX_SURFACE - MIN_SURFACE));
        surfaceSize -= (surfaceSize & 1);
        return surfaceSize;
    }

    /**
     * The surface size a planet was stamped at under the HISTORICAL {@link #LEGACY_MIN_SURFACE}..{@link
     * #LEGACY_MAX_SURFACE} range, byte-identical to what {@link #surfaceSizeForId} produced before the range widened. For
     * one caller, {@link GeneratedPlanetClaims#backfillStampedGeometry}: an already-stamped planet with no stored size was
     * built by a pre-stamped-geometry build, so its terrain uses the OLD range and its true size is this. The arithmetic
     * mirrors {@link #surfaceSizeForId} exactly and differs ONLY in the two range constants. A MOON short-circuits to
     * {@link MoonBody#LEGACY_SURFACE_SIZE} (20), not the new {@link MoonBody#SURFACE_SIZE} (400), for the same reason. Do
     * not fold this back into surfaceSizeForId; see the LEGACY_MIN_SURFACE comment.
     */
    public static int legacySurfaceSizeForId(String id)
    {
        // a moon too old to persist geometry was stamped 20 wide (LEGACY_SURFACE_SIZE), NOT the current 400: its terrain
        // ends at the 20 rim, so the backfill must lock 20 or the boundary/garrison/salvage read 400 over 20-wide ground.
        // The moon's own legacy tier; do not collapse it back to the current size.
        if (MoonBody.isMoon(id))
        {
            return MoonBody.LEGACY_SURFACE_SIZE;
        }
        long h = hashOf(id + "#surface");
        double sizeRoll = ((h >>> 20) & 0xFFFFFFL) / 16777216.0;
        int surfaceSize = LEGACY_MIN_SURFACE + (int) (sizeRoll * (LEGACY_MAX_SURFACE - LEGACY_MIN_SURFACE));
        surfaceSize -= (surfaceSize & 1);
        return surfaceSize;
    }
}
