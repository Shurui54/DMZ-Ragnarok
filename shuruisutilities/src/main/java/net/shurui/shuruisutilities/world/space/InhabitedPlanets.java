package net.shurui.shuruisutilities.world.space;

/**
 * REAL (datapack) planet dimensions populated by a NEUTRAL saiyan garrison at their settlement structures (see
 * {@link PlanetGarrison#ensureNeutralSettlements}). NOT generated surface cells (those live on the shared
 * {@link SurfaceDimension} and get a HOSTILE garrison); each id here is a standalone dimension.
 *
 * <p>isNeutral is a deliberate alias of isInhabited, not a second drift-prone list: inhabited planets spawn peaceful,
 * generated surface planets spawn hostile, and those are the same distinction. Adding a planet here makes its garrison
 * neutral automatically.
 */
public final class InhabitedPlanets
{
    private InhabitedPlanets()
    {
    }

    // The set of inhabited (neutral-garrison) planet dimension ids lives ONCE, on SpaceKeys, so it is not duplicated
    // here or drift-prone. Add a planet there to make its garrison neutral.
    public static boolean isInhabited(String dimensionId)
    {
        return dimensionId != null && SpaceKeys.INHABITED_PLANETS.contains(dimensionId);
    }

    /** Alias of {@link #isInhabited}: neutral (peaceful garrison) iff inhabited. */
    public static boolean isNeutral(String planetId)
    {
        return isInhabited(planetId);
    }
}
