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
 * for BASE forms. DMZ funnels every base-form transform (radial pick, the transform key charge, auto-oozaru) to
 * {@code FormModeHandler.attemptTransform}, computing the form to enter as {@link TransformationsHelper#getNextAvailableForm}.
 * We inject at HEAD: when that target form carries a minimum level above the character's level we refuse the
 * transform outright, so the player never enters it, and tell them the level to reach.
 *
 * <p>{@code require = 0}: a DMZ internals change disables the guard rather than crashing. The tick safety net
 * ({@code FormLevelGateEnforcer}) still drops anyone who slips into a gated form by another path.
 */
@Mixin(targets = "com.dragonminez.server.events.players.actionmode.FormModeHandler", remap = false)
public abstract class FormModeHandlerMixin {

    @Inject(method = "attemptTransform", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void sdu$blockBelowMinLevel(ServerPlayer player, StatsData data, CallbackInfo ci) {
        try {
            FormConfig.FormData nextForm = TransformationsHelper.getNextAvailableForm(data);
            if (nextForm == null) {
                return;
            }
            String group = TransformationsHelper.getTransformTargetGroup(data);
            if (FormLevelGate.blocks(data, group, nextForm.getName())) {
                FormLevelGate.notifyBlocked(data, group, nextForm.getName(), false);
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // fail open: never break a transform because of the gate
        }
    }

    /**
     * Alignment USE gate for base forms, the twin of the level gate above: refuse the transform when the target
     * form carries an alignment use-window that excludes this character's current DMZ alignment, and tell them the
     * range. The tick net ({@code FormAlignmentGateEnforcer}) drops anyone who slips in by another path.
     */
    @Inject(method = "attemptTransform", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void sdu$blockOutsideAlignment(ServerPlayer player, StatsData data, CallbackInfo ci) {
        try {
            FormConfig.FormData nextForm = TransformationsHelper.getNextAvailableForm(data);
            if (nextForm == null) {
                return;
            }
            String group = TransformationsHelper.getTransformTargetGroup(data);
            if (net.shurui.dev.sdu.form.FormAlignmentGate.blocksUse(data, group, nextForm.getName())) {
                net.shurui.dev.sdu.form.FormAlignmentGate.notifyUseBlocked(data, group, nextForm.getName(), false);
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // fail open: never break a transform because of the gate
        }
    }
}
