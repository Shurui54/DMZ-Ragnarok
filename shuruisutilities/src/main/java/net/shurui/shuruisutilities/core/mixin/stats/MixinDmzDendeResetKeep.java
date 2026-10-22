package net.shurui.shuruisutilities.core.mixin.stats;

import com.dragonminez.common.stats.StatsData;

import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.shurui.shuruisutilities.character.RaceSwitchKeep;

/**
 * Dende's reset (NPC action 2, DMZ's {@code resetPlayerProgress}) is the only way an existing character gets back to the
 * race carousel. Keep a copy of the character first, so picking a shadow dragon race there keeps it (see
 * {@link RaceSwitchKeep}). DMZ's own static method, {@code remap = false}; {@code require = 0} drops only the keep.
 * DMZ 2.2 made the handler return whether the action succeeded, hence the boolean callback.
 */
@Mixin(targets = "com.dragonminez.common.network.C2S.NPCActionC2S", remap = false)
public abstract class MixinDmzDendeResetKeep
{
    @Inject(method = "handleDende(Lnet/minecraft/server/level/ServerPlayer;Lcom/dragonminez/common/stats/StatsData;I)Z",
            at = @At("HEAD"), require = 0, remap = false)
    private static void su$stashBeforeReset(ServerPlayer player, StatsData data, int action, CallbackInfoReturnable<Boolean> ci)
    {
        if (action == 2)
            RaceSwitchKeep.stashBeforeDendeReset(player, data);
    }
}
