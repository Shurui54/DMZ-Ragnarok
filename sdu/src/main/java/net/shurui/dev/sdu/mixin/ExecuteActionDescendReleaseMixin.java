package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Resources;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Staff report 2026-10: an upgraded Android dropping out of Kaioken x2 lost all of its power release.
//
// DMZ's FORCE_DESCEND (ExecuteActionC2S, inside the enqueueWork lambda lambda$handle$0(ServerPlayer, StatsData)) sets
// the power release to 0 when the player descends from BASE, which it tests as
//     (!activeForm && !activeStackForm) || (androidUpgraded && activeForm == "androidbase")
// An upgraded Android never leaves its "androidbase" form, so the second half is true on EVERY descend, including the
// one that only drops a stack form (Kaioken) and leaves the Android in its base. For every other race the first half
// already requires no stack form, so the release survives leaving Kaioken. This makes the Android match: the reset
// still happens when the Android descends with no stack form active (its real "back to base"), and is skipped while
// a stack form is the thing being descended.
//
// Ordinal 0 is the FORCE_DESCEND call; ordinal 1 (INSTANT_RELEASE) is untouched. The redirect captures the lambda's own
// arguments (ServerPlayer, StatsData) after the call's. remap=false, require=0: DMZ classes are not obfuscated, and a
// broken binding only drops the fix, so launch-test rather than trust a green build. Server side only.
@Mixin(targets = "com.dragonminez.common.network.C2S.ExecuteActionC2S", remap = false)
public abstract class ExecuteActionDescendReleaseMixin {

    @Redirect(
        method = "lambda$handle$0(Lnet/minecraft/server/level/ServerPlayer;Lcom/dragonminez/common/stats/StatsData;)V",
        at = @At(value = "INVOKE",
                target = "Lcom/dragonminez/common/stats/character/Resources;setPowerRelease(I)V",
                ordinal = 0, remap = false),
        require = 0,
        remap = false
    )
    private void sdu$keepReleaseOnStackDescend(Resources resources, int value, ServerPlayer player, StatsData data) {
        try {
            String stack = data.getCharacter().getActiveStackForm();
            if (stack != null && !stack.isEmpty())
                return; // only the stack form is being dropped: keep the release, as DMZ does for every other race
        } catch (Throwable ignored) {
            // Unreadable: fall through to DMZ's own behaviour.
        }
        resources.setPowerRelease(value);
    }
}
