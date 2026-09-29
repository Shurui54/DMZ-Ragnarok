package net.shurui.shuruisutilities.client.hud;

/**
 * The viewing player's own Shards balance, as last pushed by the server, for the stat HUD readout.
 *
 * <p>Just a number, no uuid, exactly like {@link ZeniClientCache}: the server only ever sends a player their own
 * balance, so the only account this can describe is the one holding it. A client therefore cannot learn anybody
 * else's balance from the wire.
 *
 * <p>Starts at -1 meaning "the server has not said", and the HUD draws NOTHING AT ALL in that state, not an empty
 * housing and not a zero. That one rule covers three different situations with no extra plumbing:
 * <ul>
 *   <li>The currency module is off, or the server's key tier does not include it. The server never sends the
 *       packet, so the element never appears. That is the "draw nothing when the feature does not exist"
 *       requirement, met by silence rather than by a second gate the client could get wrong.</li>
 *   <li>The brief window between joining and the first push.</li>
 *   <li>A server that does not run this suite at all.</li>
 * </ul>
 *
 * <p>Cleared on disconnect by {@link ShardClientEvents}, so the last server's figure can never be shown on the
 * next one. Note that {@link ZeniClientCache} has no such clear; do not copy that omission here, because a shards
 * balance is bought with real money and showing a stale one is a support ticket.
 *
 * <p>Client only.
 */
public final class ShardClientCache
{
    private ShardClientCache() {}

    /** Volatile: written from the client network thread's enqueued work, read from the render thread. */
    private static volatile long balance = -1L;

    public static void set(long value)
    {
        balance = value;
    }

    /** Last known balance, or -1 when the server has not sent one. */
    public static long get()
    {
        return balance;
    }

    /** Whether the server has told this client its balance. False means draw nothing. */
    public static boolean known()
    {
        return balance >= 0L;
    }

    public static void clear()
    {
        balance = -1L;
    }
}
