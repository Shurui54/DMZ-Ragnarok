package net.shurui.shuruisutilities.client.hud;

import net.shurui.shuruisutilities.energy.EnergyKind;

/**
 * The viewing player's own active role bar, as last pushed by the server, for the stat HUD track.
 *
 * <p>Just a kind and a number, no uuid: the server only sends a player their own bar (same as {@link
 * ZeniClientCache}). Null {@link #kind()} means "no role", drawn as no track rather than an empty one, and is also
 * the starting state.
 */
public final class EnergyClientCache
{
    private EnergyClientCache() {}

    private static volatile EnergyKind kind;
    private static volatile float value;

    public static void set(EnergyKind newKind, float newValue)
    {
        kind = newKind;
        value = newValue;
    }

    /** The active bar's kind, or null when the player has no role. */
    public static EnergyKind kind()
    {
        return kind;
    }

    /** Value on the flat 0..100 scale. Meaningless while {@link #kind()} is null. */
    public static float value()
    {
        return value;
    }

    /** Cleared on disconnect so a stale bar cannot carry into the next server. */
    public static void clear()
    {
        kind = null;
        value = 0.0f;
    }
}
