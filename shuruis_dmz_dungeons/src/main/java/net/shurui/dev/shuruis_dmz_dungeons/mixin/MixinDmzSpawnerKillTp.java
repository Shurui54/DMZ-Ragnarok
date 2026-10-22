package net.shurui.dev.shuruis_dmz_dungeons.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraftforge.event.entity.living.LivingDeathEvent;

import net.shurui.dev.shuruis_dmz_dungeons.event.SpawnerRewardEvents;

/**
 * An advanced spawner's TP range IS the kill TP for its mobs.
 *
 * <p>DragonMineZ pays every mob kill {@code tpPerHit + maxHealth * tpHealthRatio} in {@code TPGainEvents.onEntityDeath}.
 * The spawner's TP Min/Max was paid ON TOP of that, so on a high health spawner mob the configured range was a rounding
 * error next to DragonMineZ's health based share (staff measured roughly 28 to 29 percent of the mob's health, which is
 * the configured ratio times prestige), and the Balance range, paid alone, looked like the only setting that did
 * anything. For a mob the spawner stamped with a TP range, DragonMineZ's kill grant is skipped, and
 * {@link SpawnerRewardEvents} pays the rolled range instead. That grant goes through the same
 * {@code addTrainingPoints} path, so prestige, TP boosts and the anti farm scaling apply to it exactly as they did to
 * DragonMineZ's. Mobs without a TP range (and every mob not from a spawner) keep DragonMineZ's kill TP unchanged; the
 * per hit TP is untouched.
 *
 * <p>remap=false: DMZ's own class and method; the Forge event parameter needs no remap. require=0 per house rule for
 * DMZ targets, so a rename upstream degrades to the old (additive) behaviour instead of failing to load.
 */
@Mixin(targets = "com.dragonminez.server.events.players.TPGainEvents", remap = false)
public class MixinDmzSpawnerKillTp {

    @Inject(method = "onEntityDeath", at = @At("HEAD"), cancellable = true, require = 0)
    private static void sdd$spawnerRangeReplacesKillTp(LivingDeathEvent event, CallbackInfo ci) {
        if (SpawnerRewardEvents.replacesDmzKillTp(event.getEntity())) {
            ci.cancel();
        }
    }
}
