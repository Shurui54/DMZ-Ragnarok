package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.config.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

// TEMPORARY workaround for a DMZ 2.1 config-sync bug. Remove once DMZ seeds these maps empty upstream.
//
// On login config-sync, applySpecificSyncedConfig lazily inits its authoritative SERVER_SYNCED_* maps by
// COPYING the client's local maps first (new HashMap<>(RACE_CHARACTER)), then overlaying the server's
// entries, so the synced map ends up (local races) union (server races). getLoadedRaces / getRaceCharacter
// / getRaceStats / getAllStackForms all read the synced maps, so a client's local custom races/stats/
// stack-forms leak into character selection on servers that lack them. (Per-race SERVER_SYNCED_FORMS is
// already empty-seeded by DMZ, so it's fine.)
//
// We inject at HEAD and, while the maps are still null, pre-seed CHARACTER/STATS/STACK_FORMS empty. DMZ's
// own == null checks then skip the local seed, so only the server's entries populate them. Mid-session
// updates untouched (maps already non-null), harmless if DMZ ever fixes the seeding.
//
// Client-only, remap=false.
@Mixin(value = ConfigManager.class, remap = false)
public abstract class ConfigSyncSeedMixin {

    @Shadow private static Map SERVER_SYNCED_CHARACTER;
    @Shadow private static Map SERVER_SYNCED_STATS;
    @Shadow private static Map SERVER_SYNCED_STACK_FORMS;

    private static boolean sdu$loggedActive = false;

    @Inject(
            method = "applySpecificSyncedConfig(Ljava/lang/String;Ljava/lang/String;)V",
            at = @At("HEAD"),
            remap = false,
            require = 0)
    private static void sdu$serverAuthoritativeSeed(String name, String json, CallbackInfo ci) {
        if (!sdu$loggedActive) {
            sdu$loggedActive = true;
            org.slf4j.LoggerFactory.getLogger("sdu").info(
                    "[sdu] Server-authoritative config-sync fix ACTIVE (empty-seeding SERVER_SYNCED maps).");
        }
        if (SERVER_SYNCED_CHARACTER == null) {
            SERVER_SYNCED_CHARACTER = new HashMap<>();
        }
        if (SERVER_SYNCED_STATS == null) {
            SERVER_SYNCED_STATS = new HashMap<>();
        }
        if (SERVER_SYNCED_STACK_FORMS == null) {
            SERVER_SYNCED_STACK_FORMS = new HashMap<>();
        }
    }

    // after a synced config applies, clear the client model-resolution cache. an sdu race edit lands as this
    // synced-config apply (no resource reload), so a changed customModel would otherwise resolve to stale geo
    // until relog. re-populates next render.
    @Inject(
            method = "applySpecificSyncedConfig(Ljava/lang/String;Ljava/lang/String;)V",
            at = @At("TAIL"),
            remap = false,
            require = 0)
    private static void sdu$clearModelCacheAfterSync(String name, String json, CallbackInfo ci) {
        net.shurui.dev.sdu.compat.dmz.DmzModelCache.clear();
        // also drop cached forced-hair-code decodes: a form edit can change forcedHairCode/hairType, which
        // arrives as this same synced-config apply, so the stale decode mustn't persist past the sync
        net.shurui.dev.sdu.compat.dmz.HairCodeCache.clear();
    }
}
