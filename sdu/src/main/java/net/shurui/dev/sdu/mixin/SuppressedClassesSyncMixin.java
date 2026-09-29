package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.config.ConfigManager;
import net.shurui.dev.sdu.race.SuppressedDefaultsConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Makes class suppression reach the CLIENT. DmzCompat.applySuppression() strips suppressed classes from DMZ's
// in-memory maps, but DMZ does not send players those maps: both its login sync (StatsCapability) and every
// reload push read each config straight off DISK through getSpecificConfigJson. So a race whose stats.json still
// listed a suppressed class (half_saiyan still carried "tank" on all three shards, 2026-09-12) put that class in
// every player's character screen, and deleting it from the file did not hold because shard sync and the race
// bundle kept writing it back.
//
// Filtering the JSON as it is read, rather than rewriting the file, is deliberate: a rewrite would change the
// file's hash, ShardConfigSync would publish it, its key merge would restore the class from the network copy,
// the reload would strip it again, and the shards would loop. Nothing reads through here to write back.
@Mixin(value = ConfigManager.class, remap = false)
public abstract class SuppressedClassesSyncMixin {

    @Inject(
            method = "getSpecificConfigJson(Ljava/lang/String;)Ljava/lang/String;",
            at = @At("RETURN"),
            cancellable = true,
            remap = false,
            require = 0)
    private static void sdu$stripSuppressedClasses(String name, CallbackInfoReturnable<String> cir) {
        String json = cir.getReturnValue();
        String stripped = SuppressedDefaultsConfig.stripSuppressedClasses(name, json);
        if (stripped != json) {
            cir.setReturnValue(stripped);
        }
    }
}
