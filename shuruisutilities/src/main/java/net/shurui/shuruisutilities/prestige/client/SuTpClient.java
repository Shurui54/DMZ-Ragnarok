package net.shurui.shuruisutilities.prestige.client;

/**
 * Client-side cache of the local player's SU TP-gain bonus, pushed by
 * {@link net.shurui.shuruisutilities.prestige.PacketSuTpMult}. Two parts stack:
 * <ul>
 *   <li>{@code pct} - the additive prestige-level bonus + the {@code su.tpgain} value-permission, and</li>
 *   <li>{@code boost} - a multiplicative factor for the live global {@code /tpboost} window (1.0 = none).</li>
 * </ul>
 * Read by the DragonMineZ "TP Multiplier" X-menu tooltip mixin so the shown multiplier matches the TP
 * actually earned in game (SU multiplies TP on top of DMZ's own components).
 */
public final class SuTpClient
{
    private SuTpClient() {}

    private static volatile int pct = 0;
    private static volatile double boost = 1.0;

    /** Set both the additive prestige/permission percent and the multiplicative global-boost factor at once. */
    public static void set(int p, double b)
    {
        pct = Math.max(0, p);
        boost = b > 0.0 ? b : 1.0;
    }

    public static void setPct(int p)
    {
        pct = Math.max(0, p);
    }

    public static void setBoost(double b)
    {
        boost = b > 0.0 ? b : 1.0;
    }

    public static int pct()
    {
        return pct;
    }

    public static double boost()
    {
        return boost;
    }

    /**
     * The SU TP-gain bonus as a single multiplicative factor: the additive prestige/permission percent times
     * the multiplicative global {@code /tpboost} factor, e.g. +25% prestige and a +50% global boost =&gt;
     * 1.25 * 1.5 = 1.875 (1.0 = no bonus at all).
     */
    public static double multiplier()
    {
        return (1.0 + Math.max(0, pct) / 100.0) * (boost > 0.0 ? boost : 1.0);
    }
}
