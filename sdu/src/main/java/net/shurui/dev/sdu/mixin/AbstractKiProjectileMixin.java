package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.init.entities.ki.AbstractKiProjectile;
import com.dragonminez.common.init.entities.ki.KiBlastEntity;
import net.shurui.dev.sdu.compat.BidenBlastSound;
import net.shurui.dev.sdu.compat.DmzTechniques;
import net.shurui.dev.sdu.compat.KiLineOfSight;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Firing sound for the custom biden_blast technique. big_bang (which biden_blast clones) is a charged
// GIANT_BALL whose setup never calls playInitialSound, so hooking that never fires. Instead hook tick and
// play on the first tick (technique id is set before spawn). DMZ class + vanilla tick, so class is
// remap=false but the tick inject is remap=true.
@Mixin(value = AbstractKiProjectile.class, remap = false)
public abstract class AbstractKiProjectileMixin {

    // Owner request: cap large ball attacks (spirit bomb, supernova, death ball, big bang and any
    // player-authored orb) at size 15. DMZ has no config key for this, so clamp the single choke
    // point. setSize(float) is the only writer of the synched SIZE value, and getSize() drives all
    // three consumers: the render diameter (KiProjectileRenderer scales by getSize()), the hitbox
    // (m_6972_ = super.m_6972_(pose).scale(getSize())) and the grief/damage radius on detonation
    // (maxDetonationRadius = getSize() / 2 * 5), so clamping here caps visual, collision and grief
    // together. The unit is the ball's DIAMETER in blocks. Guarded to KiBlastEntity so beams
    // (KiWaveEntity), area and explosion orbs are untouched. Normal small blasts sit at 0.4 to 0.8
    // and the predefined giant balls at 2.5 to 7.0, all far below 15, so only oversized orbs bite.
    private static final float MAX_BALL_SIZE = 15.0f;

    @ModifyVariable(method = "setSize", at = @At("HEAD"), argsOnly = true, index = 1)
    private float sdu$capBallSize(float size) {
        if (((Object) this) instanceof KiBlastEntity && size > MAX_BALL_SIZE) {
            return MAX_BALL_SIZE;
        }
        return size;
    }

    // Ki attacks must not damage through solid blocks. applyDamageOrHeal(Entity, float) is the one method every ki
    // projectile's entity damage goes through (ball detonation and charge pulse, beams, disks, area waves, Final
    // Explosion), and DMZ checks no line of sight anywhere on the way to it. Refusing here returns false, which DMZ
    // already reads as "no hit" (no onSuccessfulHit, no hurt-cooldown stamp). Heals pass straight through. The rule
    // and its allow-when-unsure fallbacks live in KiLineOfSight, a normal class so its Minecraft calls remap.
    @Inject(method = "applyDamageOrHeal", at = @At("HEAD"), cancellable = true)
    private void sdu$noDamageThroughBlocks(Entity target, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (KiLineOfSight.blockedByTerrain((AbstractKiProjectile) (Object) this, target)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), remap = true)
    private void sdu$bidenBlastSound(CallbackInfo ci) {
        AbstractKiProjectile self = (AbstractKiProjectile) (Object) this;
        if (self.tickCount != 1) {
            return;
        }
        if (DmzTechniques.BIDEN_BLAST_ID.equals(self.getTechniqueId())) {
            BidenBlastSound.play(self);
        }
    }
}
