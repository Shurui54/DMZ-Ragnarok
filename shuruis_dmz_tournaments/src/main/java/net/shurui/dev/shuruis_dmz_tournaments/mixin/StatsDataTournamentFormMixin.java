package net.shurui.dev.shuruis_dmz_tournaments.mixin;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dragonminez.common.stats.character.Character;

import net.shurui.dev.shuruis_dmz_tournaments.Config;
import net.shurui.dev.shuruis_dmz_tournaments.character.TournamentFighters;

/**
 * Forces a FLAT tournament stat multiplier for a tournament fighter's active form.
 *
 * <p>DragonMineZ multiplies each base stat by {@code getTotalMultiplier} at read time, whose form component
 * {@code getFormMultiplier} looks the value up from the form CONFIG by race/group/form. A forced tournament
 * transform wants every race and form to come out at exactly the tournament multiplier, so this overrides
 * {@code getFormMultiplier} to return that flat value (all six stats) whenever the owner is a fighter with an
 * active form. Non-fighters and the base state fall through to stock.</p>
 *
 * <p>Shipped form configs are untouched. Targets a DragonMineZ class, so every injector is
 * {@code require = 0}: if the target moves, the addon degrades to the stock multiplier rather than crashing.</p>
 */
@Mixin(targets = "com.dragonminez.common.stats.StatsData", remap = false)
public abstract class StatsDataTournamentFormMixin {

    @Shadow @Final private Player player;
    @Shadow @Final private Character character;

    @Inject(method = "getFormMultiplier", at = @At("HEAD"), cancellable = true, require = 0)
    private void su$flatTournamentFormMultiplier(String statName, CallbackInfoReturnable<Double> cir) {
        if (this.player == null || this.character == null) return;
        // only while transformed; base/empty form keeps the stock 1.0 path, so a fighter isn't buffed pre-transform
        if (!this.character.hasActiveForm()) return;
        String active = this.character.getActiveForm();
        if (active == null || active.equalsIgnoreCase("base")) return;
        if (!TournamentFighters.isFighter(this.player)) return;

        double mult = Config.FORCED_FORM_STAT_MULT.get();
        if (mult <= 0.0) return;
        cir.setReturnValue(mult);
    }
}
