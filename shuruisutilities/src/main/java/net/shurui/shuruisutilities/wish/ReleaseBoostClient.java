package net.shurui.shuruisutilities.wish;

/**
 * Client-side cache of this player's stored ki release bonus, synced from the server over {@link PacketReleaseBoost}
 * (on login and after every wish). {@link net.shurui.shuruisutilities.core.mixin.client.MixinDmzReleaseNodeCap} reads
 * it so the radial release node shows the same ceiling the server enforces, instead of DMZ's bare
 * {@code 50 + potentialunlock * 5}.
 *
 * <p>No server-only references, and no {@code @OnlyIn}: the packet's handle path only ever touches this on the client
 * (matching {@code TabListBannerClient}), and the mixin that reads it is a client mixin, so nothing on the dedicated
 * server ever classloads it.
 */
public final class ReleaseBoostClient
{
    private ReleaseBoostClient() {}

    private static int bonus = 0;

    public static void set(int value)
    {
        bonus = Math.max(0, value);
    }

    public static int get()
    {
        return bonus;
    }
}
