package net.shurui.shuruisutilities.client.hud;

/**
 * The viewing player's own zeni balance, as last pushed by the server, for the stat HUD readout.
 *
 * <p>Just a number, no uuid: the server only sends a player their own balance. Starts at -1 meaning "not yet told",
 * drawn as blank rather than zero, so a player with money never flashes 0 on join.
 */
public final class ZeniClientCache
{
    private ZeniClientCache() {}

    private static volatile long balance = -1L;

    public static void set(long value)
    {
        balance = value;
    }

    /** Last known balance, or -1 when the server has not sent one yet. */
    public static long get()
    {
        return balance;
    }

    public static boolean known()
    {
        return balance >= 0L;
    }

    /** Cleared on disconnect so a stale balance cannot carry into the next server. */
    public static void clear()
    {
        balance = -1L;
    }
}
