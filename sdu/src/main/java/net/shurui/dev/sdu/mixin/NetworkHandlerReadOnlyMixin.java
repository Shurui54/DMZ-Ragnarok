package net.shurui.dev.sdu.mixin;

import net.shurui.dev.sdu.client.PlayerInfoView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Read-only /dmzinfo viewer: while the staff member is viewing another player, drop every DragonMineZ
// client-to-server packet. This is the single choke point DMZ's menus (and its radial form select) send
// through, so blocking here means no click in the viewed V menu can spend TP, buy/upgrade a skill or
// technique, pick a form, change difficulty, start/claim a quest, etc. The staff member's own data and the
// target's are both untouchable through this screen.
//
// Registered ONLY in the CLIENT mixin array (this is the client send path); remap = false (DMZ's own class);
// require = 0 so a DMZ reshape of sendToServer degrades to no block rather than crashing. The parameter is
// the erased MSG (Object), which the bytecode genuinely declares, so no @Coerce is needed.
@Mixin(value = com.dragonminez.common.network.NetworkHandler.class, remap = false)
public abstract class NetworkHandlerReadOnlyMixin {

    @Inject(method = "sendToServer", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void sdu$blockWhileViewing(Object msg, CallbackInfo ci) {
        if (PlayerInfoView.active()) {
            ci.cancel();
        }
    }
}
