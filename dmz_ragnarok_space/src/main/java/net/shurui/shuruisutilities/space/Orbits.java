package net.shurui.shuruisutilities.space;

/**
 * Pure, stateless orbital MATHS: given a ring radius, a per-body starting phase and a wall-clock epoch in milliseconds, it
 * returns where a body sits on its ring right now. NOTHING is stored and there are no Minecraft imports, so this is unit
 * testable on its own and identical on the client and the server. The one thing a caller must supply consistently is the
 * epoch: see {@link OrbitClock}, which resolves the SAME wall-clock instant on every shard and every client so a body is
 * drawn, landed on and collided with at one agreed position.
 *
 * <h3>Why wall-clock, not game time</h3>
 * A body's angle advances with REAL time, not per-server game ticks, because the shard hosts' game clocks run hours apart
 * (see the shard-clock reference): driving an orbit off game time would put the same planet in a wildly different place on
 * each shard, breaking the one-server illusion. {@link OrbitClock} feeds this the shard database clock when the shard
 * network is live, so every server agrees to the millisecond, and pushes that same instant to clients.
 *
 * <h3>The period model (Kepler-like, slow, tunable)</h3>
 * A ring's orbital period grows with its radius like {@code T ~ r^1.5} (Kepler's third law in shape, not scale), anchored
 * so the innermost ring ({@link #REFERENCE_RADIUS}) takes {@link #BASE_PERIOD_MILLIS} (about two real hours) for one full
 * turn and outer rings take proportionally longer. The result is a slow, readable drift: a planet visibly moves over a
 * play session without ever whipping around. The anchor and exponent are the two knobs; both are constants here so the
 * layout is stable, and both are pure so the self-test can pin the numbers.
 */
public final class Orbits
{
    private Orbits()
    {
    }

    private static final double TWO_PI = Math.PI * 2.0;

    // the ring radius (blocks) whose period is BASE_PERIOD_MILLIS. Chosen as the innermost fixed ring floor so the inner
    // ring is the fastest orbit and outer rings are slower, which reads as a real solar system.
    public static final double REFERENCE_RADIUS = 6000.0;

    // one full turn of the innermost ring, in milliseconds: about two real hours. Slow on purpose (the owner's ask).
    public static final long BASE_PERIOD_MILLIS = 2L * 60L * 60L * 1000L;

    // Kepler-like exponent: period scales with radius^1.5.
    private static final double KEPLER_EXPONENT = 1.5;

    // clamp the period into a sane band so a degenerate radius can never yield a zero or an astronomically large period.
    private static final double MIN_PERIOD_MILLIS = 10L * 60L * 1000L;          // 10 minutes
    private static final double MAX_PERIOD_MILLIS = 1000L * 60L * 60L * 1000L;  // ~1000 hours

    /**
     * The orbital period, in milliseconds, for a ring of the given radius. Kepler-like: {@code T = BASE * (r/REF)^1.5},
     * clamped into {@link #MIN_PERIOD_MILLIS}..{@link #MAX_PERIOD_MILLIS}. Monotonically increasing in radius (a farther
     * ring always orbits slower), which the self-test verifies.
     */
    public static double periodMillis(double radius)
    {
        double r = Math.max(1.0, radius);
        double t = BASE_PERIOD_MILLIS * Math.pow(r / REFERENCE_RADIUS, KEPLER_EXPONENT);
        return Math.max(MIN_PERIOD_MILLIS, Math.min(MAX_PERIOD_MILLIS, t));
    }

    /**
     * A stable starting phase in {@code [0, 2pi)} from a 64-bit hash. This is the body's angle at epoch 0, so two bodies
     * with different hashes start at different bearings and the whole layout is deterministic and stable across restarts.
     */
    public static double phase0(long hash)
    {
        long m = hash & 0xFFFFFFFFL;
        return (m / 4294967296.0) * TWO_PI;
    }

    /**
     * The orbital angle, in radians, at a given epoch: {@code phase0 + 2pi * frac(epoch / period)}. The fractional
     * revolution is isolated with {@code floor} so precision is not lost at a large epoch (a wall-clock millisecond count
     * is ~1.7e12; dividing by a ~7.2e6 ms period leaves the fractional turn accurate to well under a millisecond).
     */
    public static double angle(double phase0, double periodMillis, long epochMillis)
    {
        double revs = epochMillis / periodMillis;
        double frac = revs - Math.floor(revs);
        return phase0 + TWO_PI * frac;
    }

    /**
     * The starting phase that makes {@link #angle} equal {@code currentAngle} AT {@code epochMillis}, so an orbit built
     * from it passes through a body's CURRENT bearing right now and drifts on from there. This is how a super dragon ball
     * body's stored position becomes its orbit start: derive the phase once from where the body sits at first load, and
     * every later instant follows deterministically. Pure and side-effect free, so the self-test can pin the continuity.
     */
    public static double phaseForContinuity(double currentAngle, double periodMillis, long epochMillis)
    {
        double revs = epochMillis / periodMillis;
        double frac = revs - Math.floor(revs);
        return currentAngle - TWO_PI * frac;
    }

    /**
     * The XZ position on a ring of the given radius around a centre, at the given angle. Returns {@code {x, z}}; the Y is
     * the caller's (every body shares the body plane). At angle 0 a body sits at {@code +x}.
     */
    public static double[] ringXZ(double centreX, double centreZ, double radius, double angle)
    {
        return new double[] { centreX + Math.cos(angle) * radius, centreZ + Math.sin(angle) * radius };
    }

    /**
     * Convenience: the XZ position of a body on a ring at a given epoch, combining {@link #angle} and {@link #ringXZ}.
     */
    public static double[] positionXZ(double centreX, double centreZ, double radius, double phase0, long epochMillis)
    {
        double a = angle(phase0, periodMillis(radius), epochMillis);
        return ringXZ(centreX, centreZ, radius, a);
    }

    /**
     * A body on a ring of {@code radius} around a centre, on an orbital plane INCLINED by {@code inclination} radians
     * about the LINE OF NODES at azimuth {@code nodeAngle}, at the given epoch. Returns {@code {x, y, z}} in world space.
     *
     * <p>This is the vertical-variance core: a system's planets share one tilted plane, so a planet sits above or below
     * the sun's flat body plane by an amount that swings sinusoidally as it orbits (largest a quarter turn from the
     * nodes, zero on them). Two properties matter and both hold exactly:
     * <ul>
     *   <li>THE 3D RING RADIUS IS PRESERVED. The tilt is a pure rotation (Rodrigues) of the flat ring vector about an
     *       axis, and a rotation preserves length, so {@code |pos - centre| == radius} for every angle, inclination and
     *       node. A claimed or stamped system planet, which is keyed by id and re-derived to its live position, can
     *       therefore be inclined without changing how far it orbits its sun.</li>
     *   <li>INCLINATION 0 IS BYTE-IDENTICAL TO THE FLAT RING. At {@code inclination == 0} this reduces to
     *       {@code (centreX + radius*cos u, centreY, centreZ + radius*sin u)} for any node angle, i.e. exactly
     *       {@link #positionXZ} at {@code centreY}, so a body left un-inclined never moves.</li>
     * </ul>
     * The centre Y ({@code centreY}) is the plane's reference height; the returned Y is that plus the tilt lift.
     */
    public static double[] inclinedPosition(double centreX, double centreY, double centreZ, double radius, double phase0,
                                            long epochMillis, double inclination, double nodeAngle)
    {
        double u = angle(phase0, periodMillis(radius), epochMillis);
        double ci = Math.cos(inclination);
        double si = Math.sin(inclination);
        double cu = Math.cos(u);
        double su = Math.sin(u);
        double cN = Math.cos(nodeAngle);
        double sN = Math.sin(nodeAngle);
        // Rodrigues tilt of the flat ring vector (radius*cos u, 0, radius*sin u) about the node axis (cos N, 0, sin N)
        // by the inclination. k folds the "parallel component" term; the Y lift is the perpendicular-to-node component.
        double cuN = Math.cos(u - nodeAngle);   // (node . flat) / radius
        double snU = Math.sin(u - nodeAngle);   // (node x flat)_y / radius
        double k = (1.0 - ci) * cuN;
        double x = centreX + radius * (cu * ci + cN * k);
        double y = centreY + radius * (snU * si);
        double z = centreZ + radius * (su * ci + sN * k);
        return new double[] { x, y, z };
    }
}
