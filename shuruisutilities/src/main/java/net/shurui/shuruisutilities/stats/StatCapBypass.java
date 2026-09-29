package net.shurui.shuruisutilities.stats;

/**
 * Server-thread bypass flag for the two DMZ stat-cap chokepoints (Stats.clampStatValue and
 * StatsData.getConfiguredMaxValue).
 *
 * <p>While {@link #active()}, MixinDmzStatCapClamp skips its Math.min(capped, max) and MixinDmzStatCapBudget
 * reports an effectively-unbounded max, so a stat set via /dmzstats set|add may exceed the global cap. Entered
 * at the head of DMZ's command modify (MixinDmzStatsCommand), cleared at its return, and re-entered during the
 * per-character override re-assert on login/clone/dim-change/swap (StatCapOverrides).</p>
 *
 * <p>ThreadLocal defaulting false: the command + re-assert both run synchronously on the server thread, so this
 * never affects normal in-GUI stat purchases nor other threads. No permission gate: anyone allowed to run the
 * command may exceed the cap (product decision).</p>
 */
public final class StatCapBypass
{
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private StatCapBypass() {}

    public static void enter()
    {
        BYPASS.set(Boolean.TRUE);
    }

    // always safe to call, even if not entered
    public static void exit()
    {
        BYPASS.set(Boolean.FALSE);
    }

    public static boolean active()
    {
        return BYPASS.get();
    }
}
