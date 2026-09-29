package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.util.TransformationsHelper;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.form.FormLevelGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-side enforcement of the per-form minimum-level gate ({@link net.shurui.dev.sdu.form.FormLevelGateConfig})
 * for STACK forms (kaioken and the ultimate / Potential Unleashed form). DMZ routes every stack transform to
 * {@code StackFormModeHandler.attemptTransform}, with the target form from
 * {@link TransformationsHelper#getNextAvailableStackForm} and its group from the active-or-selected stack group,
 * which is how the ultimate form is entered too. We inject at HEAD and refuse when the target's minimum level is
 * above the character's level.
 *
 * <p>{@code require = 0}: a DMZ internals change disables the guard rather than crashing; the tick safety net
 * ({@code FormLevelGateEnforcer}) still catches anything that slips through.
 */
@Mixin(targets = "com.dragonminez.server.events.players.actionmode.StackFormModeHandler", remap = false)
public abstract class StackFormModeHandlerMixin {

    @Inject(method = "attemptTransform", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void sdu$blockBelowMinLevel(ServerPlayer player, StatsData data, CallbackInfo ci) {
        try {
            FormConfig.FormData nextForm = TransformationsHelper.getNextAvailableStackForm(data);
            if (nextForm == null) {
                return;
            }
            String group = data.getCharacter().hasActiveStackForm()
                    ? data.getCharacter().getActiveStackFormGroup()
                    : data.getCharacter().getSelectedStackFormGroup();
            if (FormLevelGate.blocks(data, group, nextForm.getName())) {
                FormLevelGate.notifyBlocked(data, group, nextForm.getName(), true);
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // fail open: never break a transform because of the gate
        }
    }

    /**
     * Alignment USE gate for stack forms (kaioken / ultimate), the twin of the level gate above: refuse the stack
     * transform when the target form's alignment use-window excludes this character's current DMZ alignment. The
     * tick net ({@code FormAlignmentGateEnforcer}) drops anyone who slips in by another path.
     */
    @Inject(method = "attemptTransform", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void sdu$blockOutsideAlignment(ServerPlayer player, StatsData data, CallbackInfo ci) {
        try {
            FormConfig.FormData nextForm = TransformationsHelper.getNextAvailableStackForm(data);
            if (nextForm == null) {
                return;
            }
            String group = data.getCharacter().hasActiveStackForm()
                    ? data.getCharacter().getActiveStackFormGroup()
                    : data.getCharacter().getSelectedStackFormGroup();
            if (net.shurui.dev.sdu.form.FormAlignmentGate.blocksUse(data, group, nextForm.getName())) {
                net.shurui.dev.sdu.form.FormAlignmentGate.notifyUseBlocked(data, group, nextForm.getName(), true);
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // fail open: never break a transform because of the gate
        }
    }
}
