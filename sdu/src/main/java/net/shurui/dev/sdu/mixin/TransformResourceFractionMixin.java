package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.stats.character.Resources;
import com.dragonminez.server.events.players.StatsEvents;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Preserves the PERCENTAGE of each resource across a forward transform instead of the absolute missing amount.
//
// DMZ's restoreMultiplierGains keeps missing_after == missing_before: for each of health/energy/stamina it adds
// (newMax - oldMax) onto the current value. With a x2 vitality form at 40% HP you exit ~70%, with a x5 god form
// ~90-95%, so a big multiplier reads as a near-full heal. Every forward path (charged form/stack transform, the
// radial/keybind instant transforms, and class change) funnels through this one method, so correcting it here
// fixes them all, Kaioken and Ultimate included (both are stack forms routed through the same call).
//
// At HEAD current health/energy/stamina are still the PRE-transform values and snapshot holds the OLD maxima.
// getMaxEnergy/getMaxStamina already return the NEW maxima (stats and multipliers are updated by setActiveForm
// beforehand). getMaxHealth reads the vanilla MAX_HEALTH attribute, which is only raised to the new form's value
// by StatsEvents.applyHealthBonus, so we call that first exactly as the original does at its line 1064, then
// read the new max and set health, otherwise setHealth would clamp against the stale attribute.
//
// frac = current / oldMax, clamped into [0,1]; new current = newMax * frac. Because frac <= 1 and newMax >= oldMax
// on a forward transform, health only rises or holds, so this is never lethal. We still floor health at 1.
//
// This is the UP path only. Descend (ExecuteActionC2S.descendForm/descendStackForm, revertToBaseForm) does NOT
// call restoreMultiplierGains and is deliberately left to DMZ's generic downward clamps; scaling health down on
// descend is how a naive percentage fix kills players.
//
// Any bad snapshot value or DMZ-internal failure falls through WITHOUT cancelling, so the original runs and a
// weird state cannot zero someone out. require=0 and remap=false per sdu's DMZ production-jar convention.
@Mixin(targets = "com.dragonminez.common.stats.StatsData", remap = false)
public abstract class TransformResourceFractionMixin {

    @Shadow(remap = false)
    @Final
    private Resources resources;

    @Shadow(remap = false)
    public abstract float getMaxHealth();

    @Shadow(remap = false)
    public abstract float getMaxEnergy();

    @Shadow(remap = false)
    public abstract float getMaxStamina();

    @Inject(
        method = "restoreMultiplierGains",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private void sdu$fractionRestore(ServerPlayer player, float[] snapshot, CallbackInfo ci) {
        try {
            if (player == null || snapshot == null || snapshot.length < 3) {
                return; // let DMZ's original run
            }
            float oldMaxHealth = snapshot[0];
            float oldMaxEnergy = snapshot[1];
            float oldMaxStamina = snapshot[2];
            if (oldMaxHealth <= 0.0f || oldMaxEnergy <= 0.0f || oldMaxStamina <= 0.0f) {
                return; // guard every division; original handles the odd state
            }
            Resources res = this.resources;
            if (res == null) {
                return;
            }

            // pre-transform current values (unchanged at HEAD).
            float curHealth = player.getHealth();
            float curEnergy = res.getCurrentEnergy();
            float curStamina = res.getCurrentStamina();

            // health: raise the vanilla MAX_HEALTH attribute to the new form first, mirroring DMZ line 1064.
            StatsEvents.applyHealthBonus(player);
            float newMaxHealth = this.getMaxHealth();
            float newMaxEnergy = this.getMaxEnergy();
            float newMaxStamina = this.getMaxStamina();

            float hFrac = sdu$clamp01(curHealth / oldMaxHealth);
            float eFrac = sdu$clamp01(curEnergy / oldMaxEnergy);
            float sFrac = sdu$clamp01(curStamina / oldMaxStamina);

            float newHealth = Math.max(1.0f, Math.min(newMaxHealth, newMaxHealth * hFrac));
            player.setHealth(newHealth);
            res.setCurrentEnergy(Math.min(newMaxEnergy, newMaxEnergy * eFrac));
            res.setCurrentStamina(Math.min(newMaxStamina, newMaxStamina * sFrac));

            org.slf4j.LoggerFactory.getLogger("sdu").info(
                    "Transform fraction-restore: hp {}% ki {}% stm {}% (health {} -> {} / {}).",
                    Math.round(hFrac * 100.0f), Math.round(eFrac * 100.0f), Math.round(sFrac * 100.0f),
                    curHealth, newHealth, newMaxHealth);

            ci.cancel();
        } catch (Throwable t) {
            // any failure: do not cancel, let DMZ's original restore run.
        }
    }

    private static float sdu$clamp01(float v) {
        if (Float.isNaN(v) || v < 0.0f) return 0.0f;
        return Math.min(1.0f, v);
    }
}
