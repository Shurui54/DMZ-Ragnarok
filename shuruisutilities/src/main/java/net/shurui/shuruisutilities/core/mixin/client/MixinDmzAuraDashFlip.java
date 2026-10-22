package net.shurui.shuruisutilities.core.mixin.client;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.player.Player;

import net.shurui.shuruisutilities.client.combat.DashAuraState;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Lays a dashing player's aura along their direction of travel instead of standing it upright behind them.
 *
 * <p>A dash reads as speed only if the aura trails the direction of travel. DragonMineZ 2.1 drew every aura as an
 * upright billboard, so this mixin used to reorient that billboard by hand (redirects on its pose calls). DragonMineZ
 * 2.2 rebuilt the aura renderer and now does exactly that itself for fast flight: when
 * {@code AuraRenderer.isFastFlying(Player)} is true it lays the 2D aura along the player's view axis, leans the 3D aura
 * and draws the flight trail. So a dash now simply reports as fast flight to the aura renderer, and DMZ draws the laid
 * over aura with the player's real colour, form cosmetics and shader, in both aura modes, with no extra draw.
 *
 * <p>A space pod pilot's aura is shaped by {@link MixinDmzAuraRenderer} instead (exhaust out of the pod's rear), so a
 * pilot is never reported as dashing here. {@code require = 0} and a wrapped body: if DMZ moves this, the dash aura
 * goes back to standing upright rather than taking the client down. The bind log is the only proof it wove.
 */
@Mixin(targets = "com.dragonminez.client.render.effects.AuraRenderer", remap = false)
public abstract class MixinDmzAuraDashFlip
{
    private static final AtomicBoolean SU_DASH_FLIP_BIND_LOGGED = new AtomicBoolean(false);

    @Inject(method = "isFastFlying(Lnet/minecraft/world/entity/player/Player;)Z", at = @At("HEAD"), cancellable = true,
            require = 0, remap = false)
    private static void su$dashLaysAuraOver(Player player, CallbackInfoReturnable<Boolean> cir)
    {
        if (SU_DASH_FLIP_BIND_LOGGED.compareAndSet(false, true))
        {
            try
            {
                LoggingHandler.sulog.info("[Dash] MixinDmzAuraDashFlip bound (AuraRenderer.isFastFlying, dash aura laid over)");
            }
            catch (Throwable ignored)
            {
            }
        }
        try
        {
            if (player != null && !su$ridingSpacePod(player) && DashAuraState.isDashing(player.getId()))
                cir.setReturnValue(true);
        }
        catch (Throwable ignored)
        {
        }
    }

    // True while this player is in a DMZ space pod, directly or nested.
    private static boolean su$ridingSpacePod(Player player)
    {
        for (net.minecraft.world.entity.Entity vehicle = player.getVehicle(); vehicle != null;
                vehicle = vehicle.getVehicle())
        {
            if (vehicle instanceof com.dragonminez.common.init.entities.SpacePodEntity)
                return true;
        }
        return false;
    }
}
