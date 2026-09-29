package net.shurui.shuruisutilities.zorb;

/**
 * The path a chain of Z orbs is laid along. Stored by NAME in {@link ZOrbConfig} (Gson serialises enums by name),
 * so appending a value is safe and reordering never matters; only the names are the persisted contract.
 */
public enum ZOrbShape
{
    /** The default: a wandering trail, each step drifting up to the configured heading drift from the last. */
    TRAIL,
    /** A straight line from the start heading. */
    LINE,
    /** A trail that also hops up and back down in a shallow sine arc every few orbs. */
    ARC;

    /** Resolve a stored name to a shape, defaulting to {@link #TRAIL} on anything unknown (never throws). */
    public static ZOrbShape byName(String name)
    {
        if (name != null)
        {
            for (ZOrbShape s : values())
                if (s.name().equalsIgnoreCase(name))
                    return s;
        }
        return TRAIL;
    }
}
