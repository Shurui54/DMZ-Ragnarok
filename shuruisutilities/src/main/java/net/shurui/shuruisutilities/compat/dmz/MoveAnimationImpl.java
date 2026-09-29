package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.TriggerAnimationS2C;

import net.minecraft.server.level.ServerPlayer;

/** The only class naming DMZ's animation packet. */
final class MoveAnimationImpl
{
    private MoveAnimationImpl() {}

    static void play(ServerPlayer caster, String clip, boolean hold)
    {
        try
        {
            TriggerAnimationS2C packet = new TriggerAnimationS2C(
                    caster.getUUID(), TriggerAnimationS2C.AnimationType.KI_ANIMATION, hold ? 1 : 0, -1, clip);
            NetworkHandler.sendToTrackingEntityAndSelf(packet, caster);
        }
        catch (Throwable ignored)
        {
        }
    }

    static void stop(ServerPlayer caster)
    {
        try
        {
            TriggerAnimationS2C packet = new TriggerAnimationS2C(
                    caster.getUUID(), TriggerAnimationS2C.AnimationType.KI_ANIMATION_STOP, 0, -1, "");
            NetworkHandler.sendToTrackingEntityAndSelf(packet, caster);
        }
        catch (Throwable ignored)
        {
        }
    }
}
