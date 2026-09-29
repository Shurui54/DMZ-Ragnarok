package net.shurui.shuruisutilities.space;

/**
 * Lets {@link PlanetGarrisonDefenderConfineGoal} hold either garrison chassis on its planet's surface disc without
 * being typed to one entity class. Implemented by {@link PlanetGarrisonDefenderEntity} (rgnpc-faced) and
 * {@link PlanetSaiyanGarrisonEntity} (DMZ custom-character saiyan).
 */
public interface PlanetGarrisonHome
{
    /** Empty string before it is assigned at spawn. */
    String getHomePlanetId();
}
