package net.shurui.shuruisutilities.core.mixin.client;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.player.AbstractClientPlayer;

import net.shurui.shuruisutilities.client.combat.DashAuraState;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Reports a dashing player as flying fast, so DragonMineZ's pose controller gives them its fast flight clip.
 *
 * <p>{@code DashAnimation} puts the player into DMZ's flight branch; this decides WHICH flight clip that branch picks.
 * Left alone the answer is the hovering idle, because DMZ works "fast" out from its own flight vector, which our dash
 * never touches: the player is being moved by us, not by DMZ's flight, so as far as that vector is concerned they are
 * standing still. A dash drawn in the hover pose looks exactly like being dragged rather than moving.
 *
 * <p>Deliberately narrow. It only ever turns a false into a true, and only while our own dash state says the player is
 * dashing, so DMZ's own flight keeps answering for itself in every other case.
 *
 * <p>The overload matters: the class has a private no argument {@code isFlyingFast} as well, and only the one taking a
 * player is the render side question. The full descriptor pins it.
 *
 * <p>{@code require = 0} so a DMZ build that reshapes this leaves the dash in the hover pose rather than failing the
 * mixin config and taking every client down with it. The bind log is the only proof it wove.
 */
@Mixin(targets = "com.dragonminez.client.events.FlySkillEvent", remap = false)
public abstract class MixinDmzFlySkillFast
{
    private static final AtomicBoolean SU_FLY_FAST_BIND_LOGGED = new AtomicBoolean(false);

    @Inject(
            method = "isFlyingFast(Lnet/minecraft/client/player/AbstractClientPlayer;)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false)
    private void su$dashCountsAsFastFlight(AbstractClientPlayer player, CallbackInfoReturnable<Boolean> cir)
    {
        if (SU_FLY_FAST_BIND_LOGGED.compareAndSet(false, true))
        {
            try
            {
                LoggingHandler.sulog.info("[Dash] MixinDmzFlySkillFast bound (FlySkillEvent.isFlyingFast, "
                        + "dash uses the fast flight pose)");
            }
            catch (Throwable ignored)
            {
            }
        }
        try
        {
            // A REAL dash only. Fast flight is DETECTED through this method, so answering true for it as well would be
            // a loop: the detector would keep seeing the state it had itself set and would never let go.
            if (player != null && DashAuraState.isRealDash(player.getId()))
                cir.setReturnValue(Boolean.TRUE);
        }
        catch (Throwable ignored)
        {
        }
    }
}
