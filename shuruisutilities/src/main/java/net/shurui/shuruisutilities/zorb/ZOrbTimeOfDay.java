package net.shurui.shuruisutilities.zorb;

/**
 * When a region may spawn Z orb chains. Stored by NAME in {@link ZOrbConfig}. A dimension with no day cycle reads
 * as {@link #ANY} regardless (the spawner checks {@code level.isDay()} only for DAY / NIGHT).
 */
public enum ZOrbTimeOfDay
{
    /** Any time. */
    ANY,
    /** Only while {@code level.isDay()} is true. */
    DAY,
    /** Only while {@code level.isDay()} is false. */
    NIGHT;

    /** Resolve a stored name to a value, defaulting to {@link #ANY} on anything unknown (never throws). */
    public static ZOrbTimeOfDay byName(String name)
    {
        if (name != null)
        {
            for (ZOrbTimeOfDay t : values())
                if (t.name().equalsIgnoreCase(name))
                    return t;
        }
        return ANY;
    }
}
