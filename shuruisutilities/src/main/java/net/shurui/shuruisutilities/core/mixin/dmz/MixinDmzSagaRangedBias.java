package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dragonminez.common.init.entities.ai.AiProfile;
import com.dragonminez.common.init.entities.ai.EnemyBrain;
import com.dragonminez.common.init.entities.ai.SkillEconomy;
import com.dragonminez.common.init.entities.sagas.DBSagasEntity;

import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Make a raid, rift or dungeon NPC actually throw the ki blasts it was configured with.
 *
 * <h2>The problem</h2>
 * Giving an NPC a ki loadout only fills its skill pool. Whether it ever FIRES one is decided by DMZ's saga AI, and
 * that AI is written for DMZ's own encounters: with a target out of melee range it mostly wants to close the
 * distance, so a boss handed four ki attacks spends the fight flying at the player and punching, and the loadout
 * reads as broken when it is merely never chosen.
 *
 * <h2>What this changes</h2>
 * At most, one decision. DragonMineZ 2.2 replaced 2.1's {@code SagasCombatBrain.decide} with
 * {@link EnemyBrain}, which scores every option in {@code decideOffense(LivingEntity, long, AiProfile)} and runs the
 * best one. Before it scores, when the NPC is beyond melee reach with a clear line of sight and has a skill that is
 * off cooldown and allowed by its skill economy, a weighted roll casts that skill instead. Everything else about the
 * brain is left alone, so positioning, combos, teleports and its own native casting all still happen; this only
 * stops a ranged NPC defaulting to "run at them" every single time.
 *
 * <h2>Only our NPCs</h2>
 * Gated on a persistent-data key that the raid manager and the dungeon spawner stamp on the NPCs they spawn, and
 * nothing else writes. A wild saga NPC in the overworld is untouched, so this cannot change how DMZ's own content
 * plays. The key doubles as the strength: it is the probability of the swap.
 *
 * <p>{@code require = 0} per the DMZ mixin rule: if a DMZ update reshapes the brain, this quietly stops applying and
 * NPCs go back to DMZ's own choices, which is a worse fight and never a broken load. The handler takes the target's
 * parameters exactly because mixin argument capture is all or nothing.
 */
@Mixin(value = EnemyBrain.class, remap = false)
public abstract class MixinDmzSagaRangedBias {

    /**
     * Persistent-data key: probability in 0..1 that the NPC casts instead of deciding normally when it is at range.
     * Absent or 0 means untouched, which is every NPC we did not spawn.
     *
     * <p>PRIVATE, and it must stay private. A mixin class may not declare a non-private static field: Mixin throws
     * {@code InvalidMixinException ... contains non-private static field} during APPLY, which is a hard crash the
     * moment the target class is first loaded, and {@code require = 0} does not cover it. The writers use their own
     * copy of this string rather than importing it, which is deliberate: normal code should never load a mixin class.
     */
    private static final String RANGED_BIAS_KEY = "dmz_ragnarok_ranged_bias";

    @Shadow(remap = false) @Final private DBSagasEntity self;
    @Shadow(remap = false) @Final private SkillEconomy economy;

    @Inject(method = "decideOffense", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void su$preferRangedWhenConfigured(LivingEntity target, long now, AiProfile profile, CallbackInfo ci) {
        DBSagasEntity me = this.self;
        if (me == null || target == null) {
            return;
        }
        double bias = me.getPersistentData().getDouble(RANGED_BIAS_KEY);
        if (bias <= 0.0) {
            return;
        }
        // The same gates DMZ's own cast option uses: no casting during the spawn grace, while the cast timer is
        // running, or while blinded.
        if (me.isInSkillGracePeriod() || !me.isSkillCastReady() || me.isBlindedByTaiyoken()) {
            return;
        }
        // "From afar" is the whole point: inside melee reach a ki blast at the target's face is worse than a punch,
        // and DMZ's own brain already casts there when it wants to. Same reach DMZ measures with.
        double dist = Math.sqrt(me.meleeDistanceSqr(target));
        double meleeRange = me.getMeleeReach() + target.getBbWidth() * 0.5D;
        if (dist <= meleeRange || !me.getSensing().hasLineOfSight(target)) {
            return;
        }
        List<DBSagasEntity.KiSkill> ready = new ArrayList<>();
        for (DBSagasEntity.KiSkill skill : me.getSkillPool()) {
            if (skill != null && skill.currentCooldown <= 0 && (this.economy == null || this.economy.canCast(skill, now))) {
                ready.add(skill);
            }
        }
        if (ready.isEmpty() || me.getRandom().nextDouble() >= bias) {
            return;
        }
        me.cancelManeuver();
        me.startSkill(ready.get(me.getRandom().nextInt(ready.size())));
        ci.cancel();
    }
}
