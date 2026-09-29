package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

    // fraction of a body's angular slot the deterministic per-body wobble may span (so the ring is not a perfect polygon)
    // and the fraction of the ring radius the per-body radius jitter may span. Both are kept small and are VERIFIED
    // against the separation floor, with a jitter-free fallback, so they only ever add visual variety, never break the
    // guarantee.
    private static final double WOBBLE_FRACTION = 0.08;
    private static final double JITTER_FRACTION = 0.04;
    // a small margin on the derived ring radius so the typical (jittered) layout sits a hair past the floor rather than
    // exactly on it.
    private static final double RADIUS_MARGIN = 1.03;

    // Fixed cruising altitude for every body. Space is min_y -64, height 2048 (ceiling 1984). 900 leaves a
    // comfortable band above and below each body so a visual radius of a few dozen blocks never pokes out of the
    // playable Y range, and it sits well above the return altitude (288) so arriving next to a planet never
    // instantly re-triggers the descend-out check.
    public static final double PLANET_Y = 900.0;

    // stable 64-bit hash of the key; splitmix64-style finaliser on the String hashCode so nearby ids (which have
    // nearby String hashes) still scatter to very different angles/radii. Deterministic and JVM-stable.
    private static long hash(String planetKey)
    {
        long z = planetKey.hashCode() * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // world position of the body for this key. Reads the coordinated layout snapshot (built by the server, mirrored on
    // the client), falling back to a stable per-key ring hash for a key that is not currently a body (an unloaded moon
    // parent, or a lookup before the first layout is built).
    public static Vec3 position(String planetKey)
    {
        Vec3 p = layout.get(planetKey);
        return p != null ? p : fallbackPosition(planetKey);
    }

    // the old independent per-key ring hash, kept ONLY as the fallback for a key absent from the coordinated layout. It
    // gives that key a stable, bounded position but makes no separation promise (only the coordinated layout does).
    private static Vec3 fallbackPosition(String planetKey)
    {
        long h = hash(planetKey);
        double angle = ((h & 0xFFFFFFFFL) / 4294967296.0) * (Math.PI * 2.0);
        double jitter = ((h >>> 32) / 4294967296.0) * radiusJitter;
        double radius = ringRadius + jitter;
        return new Vec3(Math.cos(angle) * radius, PLANET_Y, Math.sin(angle) * radius);
    }

    /**
     * Build (or reuse) the coordinated fixed-body layout for the given deduped body-key set and install it as the
     * snapshot {@link #position} reads. Called by {@link PlanetRegistry#bodies} on the server. The bodies are spread
     * EVENLY by angle on a ring whose radius is derived so the closest (adjacent) pair is at least {@link #minSeparation}
     * apart, plus a small deterministic per-body angular wobble and radius jitter for variety. The result is VERIFIED
     * against the separation floor and, if the jitter ever breaches it, rebuilt as a jitter-free regular polygon, which
     * meets the floor exactly by construction. Deterministic: the same key set and config always give the same layout,
     * so every player gets the same sky and positions do not shift between sessions.
     */
    static void buildLayout(List<String> keys)
    {
        // Compare the SET, not the sequence. The keys arrive in whatever order DMZ's destination registry happens to
        // hand them back, and that order is not stable across reloads, so a List.equals guard read a pure re-ordering
        // as a change: a live 2h43m server rebuilt and re-logged this 58 times and produced the byte-identical layout
        // every single time (the build sorts internally, so order never mattered to the result). Set comparison makes
        // the guard mean what it was always meant to mean.
        if (layoutKeys.size() == keys.size() && new HashSet<>(layoutKeys).containsAll(keys))
        {
            return; // unchanged set: reuse the snapshot (and do not re-log).
        }
        int n = keys.size();
        Map<String, Vec3> map;
        if (n == 0)
        {
            map = Collections.emptyMap();
        }
        else if (n == 1)
        {
            map = new HashMap<>(1);
            map.put(keys.get(0), fallbackPosition(keys.get(0)));
        }
        else
        {
            // deterministic order for the angular slots: sort by the same 64-bit key hash (tie-broken by the id) so the
            // bodies scatter around the ring rather than clustering alphabetically, and the order is stable across runs.
            List<String> sorted = new ArrayList<>(keys);
            sorted.sort(Comparator.comparingLong(PlanetPositions::hash).thenComparing(k -> k));
            double sep = Math.max(minSeparation, 1.0);
            double slot = Math.PI * 2.0 / n;
            // ring radius so a jitter-free regular polygon's adjacent chord equals sep exactly (2R sin(slot/2) = sep),
            // floored at ringRadius and nudged out by a small margin.
            double baseR = Math.max(ringRadius, sep / (2.0 * Math.sin(slot / 2.0))) * RADIUS_MARGIN;
            map = layoutOn(sorted, slot, baseR, JITTER_FRACTION, WOBBLE_FRACTION);
            double min = minPairwise(map);
            if (min < sep)
            {
                // the visual jitter pushed a pair under the floor: fall back to the jitter-free polygon, which is exactly
                // sep on its adjacent pairs and more on the rest, so it always clears the floor.
                map = layoutOn(sorted, slot, baseR, 0.0, 0.0);
                min = minPairwise(map);
            }
            LoggingHandler.sulog.info(
                    "[SpacePlanets] fixed layout: {} bodies on ring R={} (sep floor {}), min pairwise distance {} blocks.",
                    n, Math.round(baseR), Math.round(sep), Math.round(min));
        }
        layout = map;
        layoutKeys = new ArrayList<>(keys);
    }

    // place the sorted keys on the ring: body i at angle i*slot (plus a bounded per-key wobble) and radius R (plus a
    // bounded per-key jitter). jitterFrac/wobbleFrac == 0 gives the exact regular polygon used as the verified fallback.
    private static Map<String, Vec3> layoutOn(List<String> sorted, double slot, double R, double jitterFrac,
                                              double wobbleFrac)
    {
        Map<String, Vec3> map = new HashMap<>(sorted.size());
        for (int i = 0; i < sorted.size(); ++i)
        {
            String key = sorted.get(i);
            long h = hash(key);
            double wob = (((h >>> 8) & 0xFFFFL) / 65536.0 - 0.5) * 2.0 * wobbleFrac * slot;
            double angle = i * slot + wob;
            double jit = (((h >>> 32) & 0xFFFFL) / 65536.0) * jitterFrac * R;
            double radius = R + jit;
            map.put(key, new Vec3(Math.cos(angle) * radius, PLANET_Y, Math.sin(angle) * radius));
        }
        return map;
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
