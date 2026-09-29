package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.TriggerImpactFrameS2C;

import net.minecraft.server.level.ServerPlayer;

/** The only class naming DMZ's impact frame packet. See {@link DmzImpactFrame}. */
final class DmzImpactFrameImpl
{
    private DmzImpactFrameImpl() {}

    /**
     * The same shape DMZ's own heaviest hit uses: a hard threshold, a slow lerp back, two ticks, inverted.
     *
     * <p>Copied from {@code CombatEvent}'s impact rather than invented, so this reads as the game's own big hit
     * instead of a second, slightly different flash.
     */
    static void play(ServerPlayer around)
    {
        try
        {
            NetworkHandler.sendToTrackingEntityAndSelf(new TriggerImpactFrameS2C(0.7f, 0.1f, 2, true), around);
        }
        catch (Throwable ignored)
        {
        }
    }
}
