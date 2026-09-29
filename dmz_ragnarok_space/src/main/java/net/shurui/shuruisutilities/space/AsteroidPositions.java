package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * Pure, stateless derivation of the asteroid field from the world position alone. NOTHING about WHERE an asteroid is
 * or HOW BIG it is is stored: an asteroid's existence, its cell key, its offset within the cell, its radius and its
 * per-body shape/material seed are all a pure function of the integer cell coordinates, recomputed from the hash every
 * time. This is the same lesson as {@link PlanetPositions}: placement is disposable because everything about it comes
 * back out of this class keyed by cell coordinates.
 *
 * <p>The one thing that IS persisted is the "has this cell been stamped into blocks yet" flag, which lives in
 * {@link AsteroidStampData} keyed by the cell key below. That flag is the ONLY state; the geometry it guards is fully
 * re-derivable from here, so a lost flag would at worst re-stamp an identical lump over itself.
 *
 * <p>HISTORY: asteroids used to be tinted CUBE ENTITIES, visually indistinguishable from the planet bodies. They are
 * now real block structures stamped into the world by {@link AsteroidStamp}, so this class no longer carries any tint
 * or render data; it is purely the placement/size/exclusion oracle the stamper consumes.
 *
 * <p>DISTRIBUTION IS BY SECTOR, NOT ON A RING. Space is divided into fixed {@link #SECTOR_SIZE}-block cubic cells on
 * all three axes. Each cell is hashed; a fraction {@link #DENSITY} of cells hold exactly one asteroid, placed at a
 * hashed offset inside the cell. That gives even coverage across the whole dimension rather than decorating a single
 * ring like the planets do, so a player flying anywhere in space passes debris regularly.
 *
 * <h3>Sector size and density justification</h3>
 * <ul>
 *   <li>{@link #SECTOR_SIZE} = 384 blocks. One asteroid per populated 384-block cell. Tightened from the old 512 to
 *       make asteroids MORE COMMON per the user's request: a smaller cell packs more populated cells into a player's
 *       view without shrinking or crowding the lumps themselves (their radius range is unchanged). The lumps are up to
 *       MAX_RADIUS across (a few tens of blocks), and 384 still leaves comfortable clear space between adjacent cell
 *       centres so they read as scattered debris, not a wall.</li>
 *   <li>{@link #DENSITY} = 0.6. About three cells in five hold an asteroid, up from half. Combined with the smaller
 *       sector this raises the expected count in a player's view from ~0.9 to ~2.5 lumps (see the arithmetic below),
 *       which reads as "semi-common": you pass one regularly without space becoming a belt.</li>
 * </ul>
 *
 * <h3>Density arithmetic (expected lumps within the spawn sphere)</h3>
 * The stamper only ever considers cells whose centre falls within {@code asteroidSpawnRange} (default 384 blocks) of a
 * player, so the expected count in view is the volume of that sphere divided by a cell's volume, times the density:
 * {@code (4/3 * pi * range^3) / SECTOR_SIZE^3 * DENSITY}.
 * <ul>
 *   <li>OLD (512 / 0.5): {@code (4/3 * pi * 384^3) / 512^3 * 0.5} = ~0.88 lumps in view. On average a player often
 *       saw none at any instant, which is why the user asked for more.</li>
 *   <li>NEW (384 / 0.6): {@code (4/3 * pi * 384^3) / 384^3 * 0.6} = ~2.51 lumps in view, roughly a 2.85x increase.</li>
 * </ul>
 * These are BLOCK structures, so unlike the client-drawn celestial bodies they are bound by server render distance and
 * Distant Horizons and are stamped on demand near players, not derived per frame. The rise is comfortable: a lump is
 * stamped exactly once per cell (recorded in {@link AsteroidStampData}) and the sweep runs once a second, so even the
 * largest lump's block writes are a rare one-off, not a per-tick cost. The radius range is deliberately unchanged so
 * the extra commonness does not also multiply the per-lump write cost.
 *
 * <h3>Never inside a planet or the arrival column</h3>
 * A candidate asteroid position is rejected if it falls within any planet body's bounding cube expanded by a margin,
 * OR within the cleared radius around the space origin (the arrival column a player materialises on). The planet
 * centres and half-extents come from the SAME {@link PlanetRegistry}/{@link PlanetPositions} the bodies are drawn from,
 * so the check uses the exact geometry the player sees, exactly as {@link GeneratedPlanets} does it. Because the
 * rejection is a pure function of the same cell hash, a rejected cell is rejected identically every time, so the field
 * stays deterministic even with the planet and origin cut-outs.
 */
public final class AsteroidPositions
{
    private AsteroidPositions()
    {
    }

    // Edge length of a cubic sector cell, in blocks. See the class-doc justification. Tightened from the old 512 to make
    // asteroids more common: a smaller cell packs more populated cells into a player's view without shrinking the lumps
    // (their radius range is unchanged), and 384 still leaves clear space between adjacent centres so they read as
    // scattered debris rather than a wall.
    public static final int SECTOR_SIZE = 384;

    // Fraction of cells that hold an asteroid, 0..1. See the class-doc justification. Three cells in five populated at
    // the 384-block spacing is the raised "semi-common" density the user asked for (~2.5 lumps in view, up from ~0.9).
    private static final double DENSITY = 0.6;

    // Vertical band asteroids are allowed to occupy, in blocks. Space is min_y -64, height 2048 (ceiling ~1984). A lump
    // is kept between these plus its own radius so it never pokes out of the playable Y range and stays roughly in the
    // band the planets and the player fly through.
    private static final double MIN_Y = 64.0;
    private static final double MAX_Y = 1600.0;

    // Half-extent (radius) range for a lump, in blocks. The user asked for small to medium and VARYING, from a few
    // blocks across up to a few tens of blocks. Radius 3..18 means a diameter of roughly 6 up to ~36 blocks, so the
    // smallest are pebbles and the largest are chunky rocks, all still far below any planet body (24..55 half-extent).
    public static final float MIN_RADIUS = 3.0F;
    public static final float MAX_RADIUS = 18.0F;

    // Extra clearance, in blocks, added around a planet's half-extent when rejecting overlapping asteroids. Keeps
    // debris from spawning flush against a planet's face as well as strictly inside it.
    private static final double PLANET_CLEARANCE = 24.0;

    // Radius around the space origin (0,0 on X/Z) kept clear of asteroids, so a lump never straddles the arrival column
    // a player materialises on when entering space. Mirrors GeneratedPlanets.ORIGIN_CLEARANCE.
    private static final double ORIGIN_CLEARANCE = 1200.0;

    /**
     * The themed material family a lump is built from. Chosen deterministically per body from the cell hash (see
     * {@link #themeFor}), so a given cell always yields the same theme and the stamp stays fully re-derivable. Each
     * theme maps to its own stone palette and ore table in {@link AsteroidStamp}; the WEIGHTS below decide how common
     * each theme is, kept so plain STONE dominates and the exotic ones feel like a find.
     */
    public enum Theme
    {
        // plain space rock: the vanilla-stone mix with the overworld ore table. By far the most common, the baseline
        // "you pass these all the time" asteroid.
        STONE(60),
        // DragonMineZ Namek rock: DMZ's own namek stone/deepslate with its namek ores plus the native kikono/gete ores.
        NAMEK(15),
        // nether rock: netherrack/basalt/blackstone/soul soil with the nether ore set (quartz, gold, ancient debris).
        NETHER(15),
        // end rock: end stone and the purpur family. The rarest, and yields obsidian rather than a nonexistent "end ore".
        END(10);

        // relative weight; the theme is picked proportionally to these across a 0..(sum) roll.
        final int weight;

        Theme(int weight)
        {
            this.weight = weight;
        }
    }

    // sum of all theme weights, the denominator for the weighted theme pick. STONE 60 + NAMEK 15 + NETHER 15 + END 10.
    private static final int THEME_WEIGHT_TOTAL = sumThemeWeights();

    private static int sumThemeWeights()
    {
        int total = 0;
        for (Theme t : Theme.values())
        {
            total += t.weight;
        }
        return total;
    }

    /**
     * A single derived asteroid: its stable cell key, world-space centre, visual radius, material theme and a per-body
     * seed the stamper hashes for shape and materials. Everything is a pure function of the cell coordinates, never
     * stored.
     */
    public static final class Asteroid
    {
        public final String key;
        public final Vec3 position;
        public final float radius;
        // the material family this lump is built from. A pure function of the cell hash (see themeFor), so the theme is
        // identical every time the cell is (re)derived and nothing about it is stored.
        public final Theme theme;
        // per-body seed the stamper derives shape wobble and the stone/ore mix from, so two asteroids never look alike.
        // Purely the cell hash, so the derived structure is identical every time the cell is (re)stamped.
        public final long seed;

        Asteroid(String key, Vec3 position, float radius, Theme theme, long seed)
        {
            this.key = key;
            this.position = position;
            this.radius = radius;
            this.theme = theme;
            this.seed = seed;
        }
    }

    // pick the material theme for a body from an independent window of its cell hash, weighted by Theme.weight so plain
    // STONE stays dominant and the exotic themes are a find. A pure function of the hash, so the theme is stable across
    // re-derivation. Uses the byte-window at shift 48, distinct from the occupancy (0), offset (8/24/40) and radius (32)
    // windows so the theme does not correlate with any of them.
    private static Theme themeFor(long h)
    {
        int roll = (int) (unit(h, 48) * THEME_WEIGHT_TOTAL);
        int acc = 0;
        for (Theme t : Theme.values())
        {
            acc += t.weight;
            if (roll < acc)
            {
                return t;
            }
        }
        // unreachable (roll < total), but fall back to the baseline rock defensively.
        return Theme.STONE;
    }

    // stable key string for a cell, used as the stamp dedupe key in AsteroidStampData. Purely the integer cell
    // coordinates.
    public static String cellKey(int cx, int cy, int cz)
    {
        return "ast:" + cx + ":" + cy + ":" + cz;
    }

    // splitmix64-style hash of three cell coordinates, so adjacent cells scatter to unrelated values. Deterministic
    // and JVM-stable (integer arithmetic only, no String.hashCode surprises). Shared shape with GeneratedPlanets/
    // AsteroidPositions so the whole space layout uses one hash family.
    private static long hash(int cx, int cy, int cz)
    {
        long z = (cx * 0x9E3779B97F4A7C15L) ^ (cy * 0xC2B2AE3D27D4EB4FL) ^ (cz * 0x165667B19E3779F9L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // 0..1 double from a 64-bit hash's given byte-window, for turning independent slices of one hash into independent
    // fields (occupancy, offsets, size) without correlating them.
    private static double unit(long h, int shift)
    {
        return ((h >>> shift) & 0xFFFFFFL) / 16777216.0;
    }

    /**
     * The asteroid for a single cell, or null if the cell is empty (density roll failed) or its body would fall inside
     * a planet or the arrival column. Pure function of the cell coordinates and the current planet set.
     */
    public static Asteroid asteroidFor(MinecraftServer server, int cx, int cy, int cz)
    {
        long h = hash(cx, cy, cz);

        // occupancy: only a DENSITY fraction of cells hold a body. Uses the low window so the offset/size windows above
        // it stay independent.
        if (unit(h, 0) >= DENSITY)
        {
            return null;
        }

        // hashed radius in the debris range first, so the Y band can account for the lump's own half-extent.
        float radius = MIN_RADIUS + (float) (unit(h, 32) * (MAX_RADIUS - MIN_RADIUS));

        // hashed offset inside the cell on each axis, then keep the whole lump inside the allowed Y band.
        double ox = unit(h, 24) * SECTOR_SIZE;
        double oy = unit(h, 40) * SECTOR_SIZE;
        double oz = unit(h, 8) * SECTOR_SIZE;
        double x = (double) cx * SECTOR_SIZE + ox;
        double z = (double) cz * SECTOR_SIZE + oz;
        double y = (double) cy * SECTOR_SIZE + oy;
        if (y - radius < MIN_Y || y + radius > MAX_Y)
        {
            return null;
        }
        Vec3 pos = new Vec3(x, y, z);

        // never inside a planet or on the arrival column: reject if the body overlaps any planet's expanded bounding
        // cube or falls within the origin clearance.
        if (overlapsAnyPlanetOrOrigin(server, pos, radius))
        {
            return null;
        }

        return new Asteroid(cellKey(cx, cy, cz), pos, radius, themeFor(h), h);
    }

    // true if the body centred at pos with the given half-extent overlaps any planet body's bounding cube expanded by
    // PLANET_CLEARANCE, or falls within ORIGIN_CLEARANCE of the space origin column. Uses the live planet set so it
    // tracks whatever bodies actually exist. This is how an asteroid is guaranteed never to generate inside a planet or
    // on the arrival column.
    private static boolean overlapsAnyPlanetOrOrigin(MinecraftServer server, Vec3 pos, float radius)
    {
        // clear the origin/arrival column so a lump never straddles where a player materialises entering space.
        if (Math.abs(pos.x) <= ORIGIN_CLEARANCE && Math.abs(pos.z) <= ORIGIN_CLEARANCE)
        {
            return true;
        }
        for (FixedBody planet : SpaceLayout.fixedBodies(server))
        {
            Vec3 c = planet.position;
            double half = planet.radius + PLANET_CLEARANCE + radius;
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
     * Every asteroid whose cell centre falls within {@code range} blocks of {@code around}. Walks the cubic cells that
     * the range could touch and derives each one. The set is a pure function of the position and the planet set, so
     * the stamper derives the identical asteroid for a given cell no matter which player triggered the sweep.
     */
    public static List<Asteroid> asteroidsNear(MinecraftServer server, Vec3 around, double range)
    {
        List<Asteroid> out = new ArrayList<>();
        int minCx = Math.floorDiv((int) Math.floor(around.x - range), SECTOR_SIZE);
        int maxCx = Math.floorDiv((int) Math.floor(around.x + range), SECTOR_SIZE);
        int minCy = Math.floorDiv((int) Math.floor(around.y - range), SECTOR_SIZE);
        int maxCy = Math.floorDiv((int) Math.floor(around.y + range), SECTOR_SIZE);
        int minCz = Math.floorDiv((int) Math.floor(around.z - range), SECTOR_SIZE);
        int maxCz = Math.floorDiv((int) Math.floor(around.z + range), SECTOR_SIZE);
        double rangeSq = range * range;

        for (int cx = minCx; cx <= maxCx; ++cx)
        {
            for (int cy = minCy; cy <= maxCy; ++cy)
            {
                for (int cz = minCz; cz <= maxCz; ++cz)
                {
                    Asteroid a = asteroidFor(server, cx, cy, cz);
                    if (a == null)
                    {
                        continue;
                    }
                    // only keep bodies actually within the spawn radius, not just in a touched cell.
                    if (a.position.distanceToSqr(around) <= rangeSq)
                    {
                        out.add(a);
                    }
                }
            }
        }

        // wreck fields: every DESTROYED cell leaves a cluster of REAL debris where its planet used to be. The destroyed
        // set is read through the SAME shared accessor the suppression is (SpaceLayout branches server vs client), so the
        // server (SavedData) and the client (synced snapshot) derive the identical wreck and can never disagree about
        // where the debris sits. That agreement is not cosmetic: the client walks THIS field to reject stars and black
        // holes (HazardExclusion), so a wreck the client derived differently would draw a hazard the server does not
        // have. The destroyed set is tiny (one entry per rubble cell, usually empty), so this adds work proportional to
        // that small set, NEVER a scan over space: each rubble cell is culled by its former centre first, and only a cell
        // whose cluster can actually reach `around` pays to derive its bounded lumps.
        java.util.Map<String, Integer> rubble = SpaceLayout.destroyedCells(server);
        if (!rubble.isEmpty())
        {
            for (java.util.Map.Entry<String, Integer> e : rubble.entrySet())
            {
                int[] c = GeneratedPlanets.parseCellKey(e.getKey());
                if (c == null)
                {
                    continue;
                }
                GeneratedPlanets.FormerBody body = GeneratedPlanets.formerBody(c[0], c[1], c[2], e.getValue());
                // cull the whole cluster by its former centre: the farthest a lump can sit is the scatter radius plus a
                // max WRECK lump radius, so if that reach cannot touch the range sphere there is nothing here to derive.
                // Uses WRECK_MAX_RADIUS, not the ordinary field's MAX_RADIUS, since wreck lumps are the smaller dedicated
                // size, keeping this reach a tight upper bound.
                double maxReach = range + body.radius * WRECK_SCATTER_FACTOR + WRECK_MAX_RADIUS;
                if (body.position.distanceToSqr(around) > maxReach * maxReach)
                {
                    continue;
                }
                for (Asteroid a : wreckLumps(server, e.getKey(), e.getValue(), body))
                {
                    if (a.position.distanceToSqr(around) <= rangeSq)
                    {
                        out.add(a);
                    }
                }
            }
        }
        return out;
    }

    // how far the wreck lumps scatter from the former planet centre, as a multiple of the planet's OWN body radius, so a
    // big world leaves a physically bigger debris field and a small one a tight cluster. 5.0 flings the lumps out to
    // roughly five body-widths, about twice the old spread, so the wreck reads as a blown-apart world whose pieces have
    // drifted far and thin rather than a dense cloud sitting on the old centre. It is still scaled off the one planet
    // radius, so it stays one recognisable wreck rather than bleeding into the surrounding field.
    private static final double WRECK_SCATTER_FACTOR = 5.0;

    // the closest a wreck lump sits to the former centre, as a fraction of the scatter radius, so the field is a rough
    // shell of debris rather than everything piled dead centre.
    private static final double WRECK_INNER_FRACTION = 0.1;

    // half-extent (radius) range for a WRECK lump specifically, in blocks. Kept separate from the ordinary field's
    // MIN_RADIUS/MAX_RADIUS (3..18) because a shattered planet should read as small drifting fragments, not the chunky
    // rocks of an undisturbed belt: 3..8 means a diameter of roughly 6 up to ~16 blocks, so even the biggest wreck piece
    // is smaller than a mid-size ordinary asteroid. Combined with the wider scatter above this gives the "smaller and
    // spread wider" look the user asked for. These are wreck-only; the normal field still uses MIN_RADIUS/MAX_RADIUS.
    private static final float WRECK_MIN_RADIUS = 3.0F;
    private static final float WRECK_MAX_RADIUS = 8.0F;

    // wreck lump count band. The exact count is a pure function of the destroyed planet id (hash), like everything else
    // in this package, so every client and the server derive the SAME number of lumps with no stored list. The FIELD
    // size is what scales with the planet radius (WRECK_SCATTER_FACTOR above), so a big world leaves a wider field; the
    // count is deliberately id-only so a re-derive on either side lands on the identical cluster.
    //
    // The band is raised well above the old 8..20 (to 20..44) for two reasons. First, the field is now both wider (5.0
    // scatter) and made of smaller pieces (WRECK_*_RADIUS above), so the old count would read as a sparse handful of
    // specks strewn across a large volume; more lumps keep it looking like a shattered world. Second, and this is the
    // subtle one, the NOMINAL count here is NOT the count you get. Each lump is culled in wreckLumps if it leaves the
    // 64..1600 Y band or overlaps a fixed body or the origin, and scatter is body.radius * WRECK_SCATTER_FACTOR, so
    // doubling the factor also flings lumps roughly twice as far in Y. A large destroyed planet (big body.radius) now
    // throws a meaningful fraction of its lumps clean out of the Y band and loses them, so its SURVIVING count sits well
    // below the nominal band, and more so the bigger the planet. The band is deliberately padded to absorb that loss and
    // still leave a big world a dense-enough wreck. This is expected, not a bug: do not "fix" the Y-band cull to recover
    // the culled lumps, that clamp keeps debris inside the playable column on purpose.
    private static final int WRECK_MIN_COUNT = 20;
    private static final int WRECK_MAX_COUNT = 44;

    // map a destroyed planet's SURFACE theme to the closest asteroid material family, so its wreck reads as debris of the
    // world it was. NAMEK/NETHER/END carry straight across (a destroyed Namek leaves namek rock and namek ores); the
    // plainer surface themes (STONY, OVERWORLD) and King Kai's KAIO have no distinct asteroid family, so their wreck is
    // plain space STONE. Routing through SurfaceStamp.surfaceThemeFor keeps the wreck's theme derived from the SAME hash
    // the ground was, so the two can never disagree.
    private static Theme wreckThemeFor(String planetId)
    {
        switch (SurfaceStamp.surfaceThemeFor(planetId))
        {
            case NAMEK:
                return Theme.NAMEK;
            case NETHER:
                return Theme.NETHER;
            case END:
                return Theme.END;
            default:
                return Theme.STONE;
        }
    }

    /**
     * The full wreck cluster a destroyed cell leaves, at the generation it was destroyed on. Centred on the former
     * planet's position ({@link GeneratedPlanets#formerBody}) and scattered within a radius scaled off that planet's
     * radius, themed off the planet's surface. A pure function of the destroyed planet id, so the server and the client
     * derive the identical lumps from the identical (cell, generation) the destruction store recorded or the sync
     * delivered. Used both by {@link #asteroidsNear} (to stamp and to reject hazards around the debris while the cell is
     * rubble) and by the debris-timer clear (to remove exactly these lumps when the cell bumps generation).
     */
    public static List<Asteroid> wreckFor(MinecraftServer server, String cellKey, int generation)
    {
        int[] c = GeneratedPlanets.parseCellKey(cellKey);
        if (c == null)
        {
            return Collections.emptyList();
        }
        GeneratedPlanets.FormerBody body = GeneratedPlanets.formerBody(c[0], c[1], c[2], generation);
        return wreckLumps(server, cellKey, generation, body);
    }

    // derive the wreck lumps for an already-resolved former body. Split from wreckFor so asteroidsNear can cheaply cull a
    // whole cluster by its former centre BEFORE paying to derive its lumps, without a second copy of the cluster maths.
    private static List<Asteroid> wreckLumps(MinecraftServer server, String cellKey, int generation,
                                             GeneratedPlanets.FormerBody body)
    {
        Theme theme = wreckThemeFor(body.id);
        double scatter = body.radius * WRECK_SCATTER_FACTOR;

        long ch = GeneratedPlanets.hashOf(body.id + "#wreckCount");
        int count = WRECK_MIN_COUNT + (int) (unit(ch, 0) * (WRECK_MAX_COUNT - WRECK_MIN_COUNT + 1));
        if (count > WRECK_MAX_COUNT)
        {
            count = WRECK_MAX_COUNT;
        }

        List<Asteroid> out = new ArrayList<>(count);
        for (int i = 0; i < count; ++i)
        {
            // one hash per lump, sliced into 24-bit windows exactly like asteroidFor does: a spherical direction (azimuth
            // + cos-polar) and a distance out to the scatter radius, then the lump's own radius. The same hash is the
            // lump's shape/material seed the stamper reads, so no two wreck lumps look alike and the cluster is identical
            // on both sides.
            long h = GeneratedPlanets.hashOf(body.id + "#wreck" + i);
            double theta = unit(h, 0) * (Math.PI * 2.0);
            double cosPhi = 2.0 * unit(h, 16) - 1.0;
            double sinPhi = Math.sqrt(Math.max(0.0, 1.0 - cosPhi * cosPhi));
            double dist = scatter * (WRECK_INNER_FRACTION + (1.0 - WRECK_INNER_FRACTION) * unit(h, 32));
            double x = body.position.x + sinPhi * Math.cos(theta) * dist;
            double y = body.position.y + cosPhi * dist;
            double z = body.position.z + sinPhi * Math.sin(theta) * dist;

            float radius = WRECK_MIN_RADIUS + (float) (unit(h, 40) * (WRECK_MAX_RADIUS - WRECK_MIN_RADIUS));

            // keep every wreck lump inside the same Y band and off the same fixed bodies and origin column a normal lump
            // respects. PLANET_CLEARANCE against the destroyed planet no longer applies (that planet is gone), and it was
            // never a FIXED body anyway, so overlapsAnyPlanetOrOrigin already excludes only the fixed set and the origin,
            // which is exactly the wreck rule the task states.
            Vec3 pos = new Vec3(x, y, z);
            if (y - radius < MIN_Y || y + radius > MAX_Y)
            {
                continue;
            }
            if (overlapsAnyPlanetOrOrigin(server, pos, radius))
            {
                continue;
            }

            // wreck keys carry the cell AND the generation, so a later destruction of the SAME cell (a different
            // generation) can never collide with a stale wreck key, and the debris clear matches exactly the lumps it
            // stamped. Namespaced "wreck:" so they never collide with a normal "ast:" cell key.
            out.add(new Asteroid("wreck:" + cellKey + ":" + generation + ":" + i, pos, radius, theme, h));
        }
        return out;
    }
}
