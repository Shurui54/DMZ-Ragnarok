package net.shurui.shuruisutilities.core.mixin.stats;

import com.dragonminez.common.network.C2S.CreateCharacterC2S;
import com.dragonminez.common.stats.StatsData;

import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shurui.shuruisutilities.character.RaceSwitchKeep;

/**
 * After a character is committed, give the Dende-reset copy back when the new race is a shadow dragon race (see
 * {@link RaceSwitchKeep}). The target is the inner {@code ifPresent} lambda where DMZ actually initialises the
 * character (the packet handler only enqueues it); RETURN runs after DMZ's own initialisation and sync. DMZ's own
 * static method, {@code remap = false}; {@code require = 0} drops only the keep. DMZ 2.2 added an unknown-race reject
 * lambda ahead of it, so the initialising lambda moved from {@code lambda$handle$0} to {@code lambda$handle$1}.
 */
@Mixin(targets = "com.dragonminez.common.network.C2S.CreateCharacterC2S", remap = false)
public abstract class MixinDmzCreateCharacterKeep
{
    @Inject(method = "lambda$handle$1(Lcom/dragonminez/common/network/C2S/CreateCharacterC2S;Lnet/minecraft/server/level/ServerPlayer;Lcom/dragonminez/common/stats/StatsData;)V",
            at = @At("RETURN"), require = 0, remap = false)
    private static void su$keepAfterCreate(CreateCharacterC2S msg, ServerPlayer player, StatsData data, CallbackInfo ci)
    {
        RaceSwitchKeep.afterCreate(player, data);
    }
}
