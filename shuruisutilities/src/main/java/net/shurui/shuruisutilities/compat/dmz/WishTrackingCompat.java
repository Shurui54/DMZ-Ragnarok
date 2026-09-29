package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;

import net.shurui.shuruisutilities.corrupted.ShadowDragonStorage;

/**
 * Guard entry point for the wish-tracking DMZ integration. This class holds no DMZ imports: it only checks that
 * DMZ is present before touching {@link WishTrackingBridge}, which is the sole class that references DMZ types.
 * Follows the optional-dependency pattern.
 */
public final class WishTrackingCompat
{
    private WishTrackingCompat() {}

    private static boolean registered;
    private static WishTrackingBridge bridge;

    public static void init()
    {
        if (registered)
            return;
        if (!ModList.get().isLoaded("dragonminez"))
            return;
        bridge = new WishTrackingBridge();
        MinecraftForge.EVENT_BUS.register(bridge);
        registered = true;
    }

    /**
     * Admin escape hatch: undo the swap and restore DMZ's original generateDragonBalls setting. No-op when DMZ is
     * absent. Returns true when the disarm actually ran.
     */
    public static boolean disarm(MinecraftServer server)
    {
        if (bridge == null)
            return false;
        bridge.disarm(server);
        return true;
    }

    /**
     * Full wish-cycle reset, shared by the automatic post-summon reset (once the seven corrupted balls are consumed
     * to summon the shadow dragons) and the {@code /wishtracking reset} command. It disarms the swap and zeroes the
     * server-wide use count, so the next cycle starts from scratch. The disarm restores DMZ's generateDragonBalls
     * world gen flag (normal balls generate again, so players are never left with no balls) and clears any corrupted
     * balls still placed in the world; that half is a no-op when DMZ is absent, but the use count and armed flag are
     * still cleared so the saved state stays consistent. Returns true when the DMZ-side disarm actually ran.
     */
    public static boolean resetCycle(MinecraftServer server)
    {
        ShadowDragonStorage storage = ShadowDragonStorage.get(server);
        boolean disarmed = false;
        if (bridge != null)
        {
            // restores DMZ's dragon ball world gen, clears the placed corrupted balls, and un-arms the swap
            bridge.disarm(server);
            disarmed = true;
        }
        else
        {
            // DMZ absent: there is no world gen flag to restore, but keep the armed flag from lingering set
            storage.setArmed(false);
            // bridge.disarm already broadcasts on the DMZ-present branch; mirror it here so the flag flip reaches
            // clients (Earth radar dial) even when the bridge is not registered.
            net.shurui.shuruisutilities.corrupted.DefiledBallsSync.broadcast(server);
        }
        storage.setUses(0);
        return disarmed;
    }

    /**
     * Admin escape hatch: force the armed state and the ball swap now, without waiting for the use count to reach
     * the threshold. No-op when DMZ is absent. Returns true when the arm actually ran.
     */
    public static boolean forceArm(MinecraftServer server)
    {
        if (bridge == null)
            return false;
        bridge.forceArm(server);
        return true;
    }

    /**
     * Re-apply the suppression on server start when the saved data is still armed. DMZ reloads its config from
     * disk each startup, so this keeps normal dragon balls off after a restart. No-op when DMZ is absent.
     */
    public static void onServerStarted(MinecraftServer server)
    {
        if (!net.shurui.shuruisutilities.core.SUConfig.wishTrackingEnabled)
            return;
        if (bridge == null)
            return;
        // Held back for this release (see ReleaseToggles). A server that was already ARMED when the event was
        // switched off is the one case that must not simply be left alone: armed means DMZ's own ball world gen
        // is suppressed, and with the event off nobody can finish the corrupted set that replaced it, so the
        // world would have no dragon balls at all and no way to get any. Disarm instead of re-suppressing, which
        // puts the admin's original generateDragonBalls value back and clears any corrupted balls still placed.
        if (!net.shurui.shuruisutilities.core.ReleaseToggles.CORRUPTED_DRAGON_BALL_EVENT)
        {
            if (ShadowDragonStorage.get(server).isArmed())
                bridge.disarm(server);
            return;
        }
        bridge.reapplyOnStart(server);
    }
}
