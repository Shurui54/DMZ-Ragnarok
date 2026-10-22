package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Client side of DMZ's login config sync.
//
// 2.1 needed a workaround here: applySpecificSyncedConfig seeded its SERVER_SYNCED_* maps from the client's own local
// maps, so a client's custom races leaked into character selection on servers that lacked them, and this mixin
// pre-seeded the maps empty. DragonMineZ 2.2 rebuilt the sync around a fresh SyncedConfigs batch (empty maps, swapped
// in at the end marker), which fixes that upstream, so only the cache clear below remains.
//
// After a synced config applies, clear the client model-resolution cache. An sdu race edit lands as this synced
// config apply (no resource reload), so a changed customModel would otherwise resolve to stale geo until relog. It
// re-populates on the next render. Every RETURN, because 2.2 returns early (true) for the end-of-batch marker, which
// is exactly when the new configs become live.
//
// remap=false, require=0.
@Mixin(value = ConfigManager.class, remap = false)
public abstract class ConfigSyncSeedMixin {

    @Inject(
            method = "applySpecificSyncedConfig(Ljava/lang/String;Ljava/lang/String;)Z",
            at = @At("RETURN"),
            remap = false,
            require = 0)
    private static void sdu$clearModelCacheAfterSync(String name, String json, CallbackInfoReturnable<Boolean> cir) {
        net.shurui.dev.sdu.compat.dmz.DmzModelCache.clear();
        // also drop cached forced-hair-code decodes: a form edit can change forcedHairCode/hairType, which
        // arrives as this same synced-config apply, so the stale decode mustn't persist past the sync
        net.shurui.dev.sdu.compat.dmz.HairCodeCache.clear();
    }
}
