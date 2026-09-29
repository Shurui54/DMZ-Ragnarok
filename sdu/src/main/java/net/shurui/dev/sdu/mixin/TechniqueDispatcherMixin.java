package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.techniques.KiAttackData;
import com.dragonminez.common.stats.techniques.TechniqueDispatcher;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.shurui.dev.sdu.api.key.HakaiHooks;
import net.shurui.dev.sdu.compat.DmzTechniques;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Intercepts the cast of our shuruis_hakai technique. When an operator (hasPermissions(2)) fires it, the Ragnarok
// Key's hakai (freeze + erase + permanent ban, reached through HakaiHooks) runs INSTEAD of a normal ki projectile,
// and the fire counts as successful (cooldown + ki cost, no stray charge entity). Keyless the hook refuses, so the
// cast is a failed fire and nothing happens. Any other technique / any non-operator falls through to DMZ.
//
// DMZ class + method, remap=false. The mixin only checks id + operator gate and delegates; the sequence itself is
// private and lives in the key (it cannot move with the mixin, the key has no mixin config).
@Mixin(value = TechniqueDispatcher.class, remap = false)
public abstract class TechniqueDispatcherMixin {

    @Inject(method = "executeKiAttack", at = @At("HEAD"), cancellable = true, remap = false)
    private static void sdu$shuruisHakai(LivingEntity owner, Level level, KiAttackData data, StatsData statsData,
                                         float chargeMultiplier, CallbackInfoReturnable<Boolean> cir) {
        if (data == null || !DmzTechniques.SHURUIS_HAKAI_ID.equals(data.getId())) {
            return;
        }
        if (!(owner instanceof ServerPlayer caster) || !caster.hasPermissions(2)) {
            return; // gate: only operators can ever trigger the erase
        }
        cir.setReturnValue(HakaiHooks.get().cast(caster, level, statsData));
    }
}
