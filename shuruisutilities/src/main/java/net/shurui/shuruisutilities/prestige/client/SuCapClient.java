package net.shurui.shuruisutilities.prestige.client;

/**
 * Client-side cache of the local player's SU prestige stat-cap boost percent, pushed by
 * {@link net.shurui.shuruisutilities.prestige.PacketSuCapMult}. Read by the two DragonMineZ cap mixins
 * ({@code Stats.clampStatValue}, {@code StatsData.getConfiguredMaxValue}) via
 * {@link net.shurui.shuruisutilities.prestige.PrestigeCaps#getCapMultiplier} so the client-side stat clamp
 * and the +stat button gating match the widened server cap.
 */
public final class SuCapClient
{
    private SuCapClient() {}

    private static volatile int pct = 0;

    public static void setPct(int p)
    {
        pct = Math.max(0, p);
    }

    public static int pct()
    {
        return pct;
    }

    /** The stat-cap boost as a multiplicative factor, e.g. +100% =&gt; 2.0 (1.0 = no boost). */
    public static double multiplier()
    {
        return 1.0 + Math.max(0, pct) / 100.0;
    }
}
