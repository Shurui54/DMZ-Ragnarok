package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

/**
 * Guard for the hakai presentation: Shurui's Hakai sound, its held Big Bang charge pose, and the DMZ stun that locks
 * the target while it runs.
 *
 * <p>The two hakai share their PRESENTATION deliberately so both read as the same act of erasure. They share no
 * consequence: this one never bans and never touches HakaiSequence.
 */
public final class HakaiPresentation
{
    private HakaiPresentation() {}

    private static boolean available()
    {
        return ModList.get().isLoaded("dragonminez");
    }

    /** Shurui's Hakai sound, at the same volume it plays there. */
    public static void playSound(ServerLevel level, LivingEntity at)
    {
        if (available())
            HakaiPresentationImpl.playSound(level, at, SoundSource.PLAYERS);
    }

    /** Hold the Big Bang charge pose on the caster. */
    public static void startChargePose(ServerPlayer caster)
    {
        if (available())
            HakaiPresentationImpl.startChargePose(caster);
    }

    /** Drop the held pose. */
    public static void stopChargePose(ServerPlayer caster)
    {
        if (available())
            HakaiPresentationImpl.stopChargePose(caster);
    }

    /** Lock the target in place for {@code ticks} - DMZ stun plus zeroed motion. */
    public static void lock(LivingEntity target, int ticks)
    {
        if (available())
            HakaiPresentationImpl.lock(target, ticks);
    }

    /** Re-zero motion; called each tick so the target cannot drift while held. */
    public static void holdStill(LivingEntity target)
    {
        if (target == null)
            return;
        target.setDeltaMovement(0.0, 0.0, 0.0);
        target.hurtMarked = true;
    }
}
