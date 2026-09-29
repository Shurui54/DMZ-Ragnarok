package net.shurui.shuruisutilities.core.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.Difficulty;

import net.shurui.shuruisutilities.core.SUConfig;

/**
 * Decouples hostile spawning from the spawn-monsters property so it follows difficulty (and config) only.
 *
 * root cause: live server.properties has spawn-monsters=false. on a dedicated server updateMobSpawningFlags()
 * runs setSpawnSettings(isSpawningMonsters(), ...) on every level, and DedicatedServer.isSpawningMonsters()
 * ANDs the property in, so a false property zeros spawnEnemies on EVERY level even at HARD. Result: animals only.
 *
 * fix: drop the property gate. config-driven via forceHostileSpawns. true (default) = return true so hostiles
 * are on regardless of global difficulty (also decouples from a global peaceful, which was still suppressing
 * them). false = fall back to difficulty != PEACEFUL, exactly what the parent returns. either way the property
 * is ignored. vanilla's updateMobSpawningFlags reads this, so spawnEnemies is set right at startup and re-applied
 * on every /difficulty change, no extra bookkeeping. per-region deny flags + per-dimension difficulty stay the
 * fine-grained controls.
 */
@Mixin(DedicatedServer.class)
public abstract class MixinDedicatedServer
{
    @Inject(method = "isSpawningMonsters", at = @At(value = "HEAD"), cancellable = true)
    private void suSpawnMonstersFollowDifficulty(CallbackInfoReturnable<Boolean> cir)
    {
        if (SUConfig.forceHostileSpawns)
        {
            cir.setReturnValue(true);
            return;
        }
        MinecraftServer self = (MinecraftServer) (Object) this;
        cir.setReturnValue(self.getWorldData().getDifficulty() != Difficulty.PEACEFUL);
    }
}
