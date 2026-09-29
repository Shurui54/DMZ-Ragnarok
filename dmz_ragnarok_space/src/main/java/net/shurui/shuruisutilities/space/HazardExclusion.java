package net.shurui.shuruisutilities.space;

import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * Shared hazard-overlap check so {@link StarPositions} and {@link BlackHolePositions} reject against the SAME bodies
 * with one implementation. A star or black hole must never generate overlapping a GENERATED planet or an ASTEROID lump
 * (fixed-planet and origin-column checks live in each hazard class, whose clearances differ). Pure function of position
 * and the derived bodies, so rejection is deterministic.
 *
 * <p>NON-RECURSIVE: consults only GENERATED planets and ASTEROIDS, never stars or black holes, so a hazard derivation
 * cannot loop back into itself. Star-vs-black-hole separation is handled one-way in {@link StarPositions}.
 */
final class HazardExclusion
{
    private HazardExclusion()
    {
    }

    // Margin added to a generated body's cube / an asteroid lump's sphere. Generous so a hazard never sits flush.
    private static final double GENERATED_CLEARANCE = 128.0;
    private static final double ASTEROID_CLEARANCE = 32.0;

    static boolean overlapsGeneratedOrAsteroid(MinecraftServer server, Vec3 pos, double radius)
    {
        // Passing the hazard radius as bodyContaining's margin turns "pos inside the body" into "the two cubes overlap".
        if (GeneratedPlanets.bodyContaining(server, pos, radius + GENERATED_CLEARANCE) != null)
        {
            return true;
        }

        // Scan out to hazard radius + largest lump + clearance (a safe upper bound), then test each lump precisely.
        double scan = radius + AsteroidPositions.MAX_RADIUS + ASTEROID_CLEARANCE;
        List<AsteroidPositions.Asteroid> lumps = AsteroidPositions.asteroidsNear(server, pos, scan);
        for (AsteroidPositions.Asteroid lump : lumps)
        {
            double reach = radius + lump.radius + ASTEROID_CLEARANCE;
            if (lump.position.distanceToSqr(pos) <= reach * reach)
            {
                return true;
            }
        }
        return false;
    }
}
