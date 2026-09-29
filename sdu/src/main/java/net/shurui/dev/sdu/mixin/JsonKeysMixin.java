package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.diagnostics.JsonKeys;
import com.google.gson.JsonObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

// Silences DMZ 2.1.2's "unknown field 'X' (ignored)" JSON advisories. The suite stores editor data as extra
// fields inside DMZ's own definition files (quests/sagas, wishes, dragons, ball sets, forms, races), which
// DMZ ignores correctly, but its diagnostics still flag each one and flood the log. checkObject does ONLY
// that unknown-key sweep, so cancelling it disables exactly those; real problems (reportBadType, parse/load
// failures) still surface.
@Mixin(value = JsonKeys.class, remap = false)
public abstract class JsonKeysMixin {

    @Inject(method = "checkObject", at = @At("HEAD"), cancellable = true)
    private static void sdu$muteUnknownFieldAdvisories(String source, String file, String path,
                                                       JsonObject obj, Set<String> allowed, CallbackInfo ci) {
        ci.cancel();
    }
}
