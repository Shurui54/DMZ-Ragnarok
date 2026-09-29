package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.init.MainEffects;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.TriggerAnimationS2C;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/** The only class naming DMZ and sdu types for the hakai presentation. */
final class HakaiPresentationImpl
{
    private HakaiPresentationImpl() {}

    /** The Big Bang held-charge pose, the same clip Shurui's Hakai holds. */
    private static final String BIGBANG_CAST_ANIM = "ki.bigbang_cast";

    static void playSound(ServerLevel level, LivingEntity at, SoundSource source)
    {
        try
        {
            level.playSound(null, at.getX(), at.getY(), at.getZ(),
                    net.shurui.dev.sdu.registry.ModSounds.HAKAI.get(), source, 2.0f, 1.0f);
        }
        catch (Throwable ignored)
        {
            // sdu absent or the sound moved: the erase still happens, just quietly.
        }
    }

    static void startChargePose(ServerPlayer caster)
    {
        try
        {
            // variant 1 = hold, entityId -1, payload is the clip name. Sent to trackers and self.
            TriggerAnimationS2C packet = new TriggerAnimationS2C(
                    caster.getUUID(), TriggerAnimationS2C.AnimationType.KI_ANIMATION, 1, -1, BIGBANG_CAST_ANIM);
            NetworkHandler.sendToTrackingEntityAndSelf(packet, caster);
        }
        catch (Throwable ignored)
        {
        }
    }

    static void stopChargePose(ServerPlayer caster)
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

    static void lock(LivingEntity target, int ticks)
    {
        try
        {
            target.addEffect(new MobEffectInstance(MainEffects.STUN.get(), ticks, 0, false, false));
        }
        catch (Throwable ignored)
        {
        }
        HakaiPresentation.holdStill(target);
    }
}
