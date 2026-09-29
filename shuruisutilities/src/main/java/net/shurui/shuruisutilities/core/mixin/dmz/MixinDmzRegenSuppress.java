package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerPlayer;

import com.dragonminez.common.stats.StatsData;

/**
 * Stops ki and stamina refilling while a player is holding themselves supersonic.
 *
 * <h2>Why the drain needs this</h2>
 * The sound barrier break is paid for by the tick, and it ends when either bar runs out - so the cost only means
 * something if the bars are actually going DOWN. DMZ tops both up once a second from its own tick handler, and at a
 * high enough regen that alone decides how long a run lasts, which turns a late-game manoeuvre into a permanent one
 * for whoever has the most regen. Suppressing the refill for the duration is what makes the price the price.
 *
 * <p>Only while crashing, and only the two pools the manoeuvre spends: health and poise regenerate as usual, because
 * neither is what is being spent.
 *
 * <p>{@code require = 0}: a DMZ rename here degrades to a run that is merely cheaper, never to a broken load.
 *
 * <p>The handlers spell out DMZ's parameter types exactly, {@code StatsData} included, even though only the player is
 * read. Mixin's argument capture is all or nothing: a handler may take the target's parameters or take none of them,
 * but standing {@code Object} in for one of them is an {@code InvalidInjectionException} thrown in the APPLY phase.
 * {@code require = 0} does not cover that, so the loose version was not the safe version, it was a hard client crash.
 * Taking none of them is the other legal form but leaves no handle on the player, so the full signature it is.
 */
@Mixin(targets = "com.dragonminez.server.events.players.TickHandler", remap = false)
public abstract class MixinDmzRegenSuppress
{
    @Inject(method = "regenerateEnergy", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void su$noEnergyRegenWhileSupersonic(ServerPlayer player, StatsData data, boolean charging,
                                                        double foodMod, CallbackInfo ci)
    {
        if (net.shurui.shuruisutilities.combat.SonicCrash.isCrashing(player))
            ci.cancel();
    }

    @Inject(method = "regenerateStamina", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void su$noStaminaRegenWhileSupersonic(ServerPlayer player, StatsData data, double foodMod,
                                                         CallbackInfo ci)
    {
        if (net.shurui.shuruisutilities.combat.SonicCrash.isCrashing(player))
            ci.cancel();
    }
}
