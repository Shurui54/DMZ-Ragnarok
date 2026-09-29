package net.shurui.dev.sdu.mixin;

import net.shurui.dev.sdu.compat.dmz.AddonNamespaceMute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Suppresses DMZ JSON-load diagnostics about our own addons. DMZ collects problems via
// JsonLoadReport.error/update, logs them AND mirrors them into chat on login (DataReportEvents.onPlayerLogin
// reads entries() directly). A log4j filter can't stop the chat mirror, so we cancel at the source: if
// source/file/message references one of our modids, drop the entry. DMZ's own reports untouched. Common
// mixin: JSON load + report happen server-side too.
@Mixin(targets = "com.dragonminez.common.diagnostics.JsonLoadReport", remap = false)
public abstract class JsonLoadReportMixin {

    @Inject(method = "error(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    private static void sdu$muteAddonError(String source, String file, String message, CallbackInfo ci) {
        if (AddonNamespaceMute.matchesAny(source, file, message) || AddonNamespaceMute.isBenignIgnored(message)
                || AddonNamespaceMute.matchesUnknownAddonRewardType(message)) {
            ci.cancel();
        }
    }

    @Inject(method = "update(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    private static void sdu$muteAddonUpdate(String source, String file, String message, CallbackInfo ci) {
        if (AddonNamespaceMute.matchesAny(source, file, message) || AddonNamespaceMute.isBenignIgnored(message)
                || AddonNamespaceMute.matchesUnknownAddonRewardType(message)) {
            ci.cancel();
        }
    }
}
