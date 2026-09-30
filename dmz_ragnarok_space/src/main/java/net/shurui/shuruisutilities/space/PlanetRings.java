package net.shurui.shuruisutilities.space;

/**
 * The ONE public, pure, server-safe answer to "does this body have rings, and what do they look like". Both the CLIENT
 * (the space-view renderer and the star map) and the SERVER can call it, and the surface-sky renderer uses it to draw a
 * ringed planet's rings across its own sky. Everything is a deterministic pure function of the body key, so client and
 * server always agree with nothing stored and nothing synced.
 *
 * <p>WHY THIS EXISTS AS ITS OWN CLASS. The space-view renderer (SpaceBodyRenderer) has long decided rings from the body
 * key inside private helpers (its {@code ringCount} / {@code ringTilt}); those numbers were not reachable from anywhere
 * else, so a second view that wanted to draw the same rings had to guess. This class reproduces that EXACT decision
 * (same band-count roll, same 15..40 degree tilt hash, same inner radius and band step) so a body reads identically
 * ringed in every view, and exposes it once. It deliberately adds only ONE rule the private copy did not need: a MOON is
 * never ringed (moons are drawn by their own path, and a ringed moon was never intended), and the central sun is never
 * ringed.
 *
 * <p>The band COUNT roll keeps roughly one planet in five ringed (the top ~20% of a per-key hash window), which sits in
 * the intended 15..25% range; a bigger, gas-giant-like planet is exactly the kind the roll tends to pick because the roll
 * is independent of size and the ringed look reads best on a large body. The tilt, inner factor and band step match the
 * renderer's flat annulus ({@code radius * (2.1 + i * 0.55)} per band, tilted by the per-key angle), so a caller drawing
 * its own rings lands them where the space view does.
 */
public final class PlanetRings
{
    private PlanetRings()
    {
    }

    // ring geometry constants, mirrored from SpaceBodyRenderer.drawPlanetRings so a second view draws identical rings.
    // A band i sits at an outer half-extent of radius * (INNER_FACTOR + i * BAND_STEP), laid in the body's equatorial
    // plane and tilted by tiltDegrees(key). Kept here as the single public source so the surface-sky view matches.
    public static final float INNER_FACTOR = 2.1F;
    public static final float BAND_STEP = 0.55F;

    // the per-key axial tilt band for a ringed planet, mirrored from SpaceBodyRenderer.ringTilt: below 15 reads flat,
    // above 40 hides the band edge-on.
    private static final float RING_TILT_MIN = 15.0F;
    private static final float RING_TILT_MAX = 40.0F;

    /**
     * An immutable description of a ringed body's rings: how many bands, the axial tilt, the inner-radius and band-step
     * factors (both relative to the body's visual radius) and a stable per-key seed a caller may use to vary a ring's
     * colour or banding. A body with no rings has no {@code Rings}: {@link #of} returns null.
     */
    public static final class Rings
    {
        public final int bands;
        public final float tiltDegrees;
        public final float innerFactor;
        public final float bandStep;
        public final long bandSeed;

        Rings(int bands, float tiltDegrees, float innerFactor, float bandStep, long bandSeed)
        {
            this.bands = bands;
            this.tiltDegrees = tiltDegrees;
            this.innerFactor = innerFactor;
            this.bandStep = bandStep;
            this.bandSeed = bandSeed;
        }

        // the outer half-extent (in blocks) of band index i for a body of the given visual radius. i is 0-based.
        public float bandOuterRadius(float bodyRadius, int i)
        {
            return bodyRadius * (innerFactor + i * bandStep);
        }
    }

    /**
     * The rings of a body key, or null if it has none (a ringless planet, any moon, the sun, or an empty key). Pure
     * function of the key: the same key always gives the same answer on the client and the server.
     */
    public static Rings of(String bodyKey)
    {
        int bands = bands(bodyKey);
        if (bands <= 0)
        {
            return null;
        }
        return new Rings(bands, tiltDegrees(bodyKey), INNER_FACTOR, BAND_STEP, bandSeed(bodyKey));
    }

    /** Whether this body wears rings. */
    public static boolean isRinged(String bodyKey)
    {
        return bands(bodyKey) > 0;
    }

    /**
     * The number of ring bands for a body key: 0 (no rings), 1, 2 or 3. Mirrors SpaceBodyRenderer.ringCount EXACTLY so
     * the space view and any other view agree, and adds the two rules a shared helper must have that the private copy did
     * not need: a MOON and the SUN are never ringed.
     */
    public static int bands(String bodyKey)
    {
        if (bodyKey == null || bodyKey.isEmpty())
        {
            return 0;
        }
        // never rings on a moon (drawn by its own path) or the central sun.
        if (MoonBody.isMoon(bodyKey) || PlanetPositions.SUN_KEY.equals(bodyKey))
        {
            return 0;
        }
        // the exact roll SpaceBodyRenderer.ringCount uses: an independent mix of the key hash, only the top ~20% ringed,
        // split 1/2/3 weighted toward a single ring.
        int h = bodyKey.hashCode() * 0x9E3779B1;
        h ^= (h >>> 15);
        int roll = Math.floorMod(h, 1000);
        if (roll < 800)
        {
            return 0;
        }
        int within = roll - 800;   // 0..199
        if (within < 120)
        {
            return 1;
        }
        if (within < 170)
        {
            return 2;
        }
        return 3;
    }

    /**
     * The axial tilt, in degrees (15..40), a ringed body's rings sit at. Mirrors SpaceBodyRenderer.ringTilt, including its
     * packHash mix, so the rings tilt identically in every view. Deterministic and JVM-stable.
     */
    public static float tiltDegrees(String bodyKey)
    {
        int h = packHash(bodyKey, "ringtilt");
        double u = (h & 0x7FFFFFFF) / (double) 0x7FFFFFFF;
        return (float) (RING_TILT_MIN + u * (RING_TILT_MAX - RING_TILT_MIN));
    }

    /** A stable per-key seed for varying a ring's colour or banding in a caller's own drawing. Pure function of the key. */
    public static long bandSeed(String bodyKey)
    {
        long z = (bodyKey + "#ringband").hashCode() * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // the same salted hash SpaceBodyRenderer.packHash uses, so tiltDegrees matches the renderer bit for bit.
    private static int packHash(String key, String salt)
    {
        int h = (key + '#' + salt).hashCode();
        return h ^ (h >>> 16);
    }
}
