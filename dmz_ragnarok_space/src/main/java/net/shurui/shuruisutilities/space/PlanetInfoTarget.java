package net.shurui.shuruisutilities.space;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * The SINGLE ray-targeting rule for the planet-info readout, shared verbatim by BOTH the client floating overlay
 * ({@code PlanetInfoOverlay.computeTargetId}) and the server authority ({@link PlanetInfoServer#push} /
 * {@code readout}). It exists so the two sides can never drift: this space feature has already shipped a bug once where
 * the drawn moon and the landing volume diverged because each side kept its own copy of the geometry, and a second
 * parallel copy of "which body is the player aiming at" is exactly how that recurs. One helper, two call sites.
 *
 * <h3>What it tests</h3>
 * It considers the generated-planet cubes (via {@link GeneratedPlanets#bodyAlongRay}, exactly as before) AND the
 * orbiting-moon cubes, and returns whichever the ray ENTERS FIRST. Without the moon step a moon could never be returned,
 * because the generated candidate set is only the hash-derived {@code sugen:} planets and a moon is never in it; that is
 * the one gap this closes so a moon shows the same readout a generated planet does.
 *
 * <h3>Why the two sides agree on where the moon is</h3>
 * A moon ORBITS, so its cube moves every tick. The clickable volume must sit where the moon is DRAWN or a player aims at
 * the visible moon and hits nothing. Moon positions come from {@link MoonBody#moonFor} over the shared
 * {@link SpaceLayout#fixedBodies} set (server: the live registry; client: the synced snapshot), the identical set
 * {@code MoonBody.hasMoon} gates on and the renderer's {@code SpaceBodyRenderer.drawMoon} anchors its parent to. Both
 * call sites pass the SAME whole {@code gameTime} ({@code level.getGameTime()}), the same integer-tick convention the
 * server landing volume ({@code SpaceTravelModule.moonPlayerIsInside}) already uses; the renderer alone draws with
 * {@code gameTime + partialTick} for a smooth glide, and at the orbit's crawl (a full turn is 7200 ticks) that sub-tick
 * term is well under a tenth of a block, far inside the body cube, so the click volume and the drawn moon stay locked.
 *
 * <h3>Why one slab test</h3>
 * The intersection is {@link GeneratedPlanets#rayCubeEntry}, the one copy {@code bodyAlongRay} and this method share, so
 * a moon and a generated planet are compared for "nearest along the ray" by identical maths rather than a third copy.
 */
public final class PlanetInfoTarget
{
    private PlanetInfoTarget()
    {
    }

    /**
     * A body resolved along the ray, reduced to exactly what {@link PlanetInfoServer#build} and the shared
     * surface-distance gate read: the stable id, the current position, the visual half-extent and the surface size.
     * Both a {@link GeneratedPlanets.Generated} and a {@link MoonBody.Moon} collapse into this, so the view builder and
     * both callers work off one type and every downstream store read (name, toughness, garrison, theme, claim) is
     * already id-keyed and accepts either a {@code sugen:} or a {@code sumoon:} id unchanged.
     */
    public static final class Hit
    {
        public final String id;
        public final Vec3 position;
        public final float radius;
        public final int surfaceSize;

        Hit(String id, Vec3 position, float radius, int surfaceSize)
        {
            this.id = id;
            this.position = position;
            this.radius = radius;
            this.surfaceSize = surfaceSize;
        }
    }

    /**
     * The body the ray enters first, or null if it meets none within {@code maxDistance}. {@code server} may be null on
     * the client: both {@link GeneratedPlanets#bodyAlongRay} and {@link SpaceLayout#fixedBodies} read the synced snapshot
     * when it is, so the client resolves the identical body the server does. {@code gameTime} places the orbiting moons;
     * pass {@code level.getGameTime()} on both sides.
     */
    public static Hit resolve(MinecraftServer server, Vec3 eye, Vec3 look, double maxDistance, long gameTime)
    {
        if (look.lengthSqr() < 1.0E-9)
        {
            return null;
        }
        Vec3 dir = look.normalize();

        // generated planets: the existing walk, untouched, so the generated pick is byte-for-byte what it was before the
        // moon step was added. bodyAlongRay already returned the nearest generated body; we recompute its single entry
        // distance with the shared slab test so it competes against the moons on the same measure.
        Hit best = null;
        double bestEntry = Double.MAX_VALUE;
        GeneratedPlanets.Generated gen = GeneratedPlanets.bodyAlongRay(server, eye, look, maxDistance);
        if (gen != null)
        {
            double entry = GeneratedPlanets.rayCubeEntry(eye, dir, gen.position, gen.radius, maxDistance);
            if (entry >= 0.0)
            {
                bestEntry = entry;
                best = new Hit(gen.id, gen.position, gen.radius, gen.surfaceSize);
            }
        }

        // moons: place each fixed body's moon at THIS game time and slab-test it, keeping whichever the ray enters
        // nearest. hasMoon is true for every entry in fixedBodies, so moonFor returns a moon for each; a generated planet
        // is never in that set, so it is never double-counted here.
        for (FixedBody fb : SpaceLayout.fixedBodies(server))
        {
            MoonBody.Moon moon = MoonBody.moonFor(server, fb.key, fb.position, fb.radius, gameTime);
            if (moon == null)
            {
                continue;
            }
            double entry = GeneratedPlanets.rayCubeEntry(eye, dir, moon.position, moon.radius, maxDistance);
            if (entry >= 0.0 && entry < bestEntry)
            {
                bestEntry = entry;
                best = new Hit(moon.id, moon.position, moon.radius, moon.surfaceSize);
            }
        }
        return best;
    }
}
