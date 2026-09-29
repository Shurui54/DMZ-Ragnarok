package net.shurui.shuruisutilities.corrupted.client;

/**
 * Client-side cache of the server-wide "the dragon balls are defiled" flag, pushed by
 * {@link net.shurui.shuruisutilities.corrupted.network.PacketDefiledSync}. The authoritative state is
 * {@code ShadowDragonStorage.hasDefiledBallsPresent} on the server; that class is server-only, so render code must never
 * read it and reads this standalone holder instead. Only {@link net.shurui.shuruisutilities.client.hud.RadarBackgrounds}
 * consults it today, to swap the Earth radar's dial to the shadow-dragon art while defiled balls are out in the world.
 *
 * <p><b>Fail-safe.</b> Until the packet has arrived this reports NOT defiled. That is deliberate: on a single-player
 * world, an older server, or any moment before the sync arrives, the Earth radar keeps DragonMineZ's stock dial rather
 * than flashing the shadow art from a missing packet.
 *
 * <p>State is a single volatile boolean, written only on the client network thread's enqueued work and read on the
 * client render thread, so a plain volatile is enough to keep reads consistent.
 */
public final class DefiledBallsClient
{
    private DefiledBallsClient() {}

    // Per-set defiled flags, false before any sync (fail closed). Written on the client network thread's enqueued
    // work, read on the render thread; plain volatiles keep each read consistent.
    private static volatile boolean earthDefiled = false;
    private static volatile boolean namekDefiled = false;

    /** Replace the cached flags with the server's authoritative values for this world. */
    public static void apply(boolean earth, boolean namek)
    {
        earthDefiled = earth;
        namekDefiled = namek;
    }

    /** True when the Earth ball set is defiled (shadow-dragon dial). Fail-closed before the first sync. */
    public static boolean isEarthDefiled()
    {
        return earthDefiled;
    }

    /** True when the Namek ball set is defiled (shadow-dragon dial). Fail-closed before the first sync. */
    public static boolean isNamekDefiled()
    {
        return namekDefiled;
    }

    /** Drop the cache (e.g. on disconnect) so the next session starts fail-closed again. */
    public static void clear()
    {
        earthDefiled = false;
        namekDefiled = false;
    }
}
