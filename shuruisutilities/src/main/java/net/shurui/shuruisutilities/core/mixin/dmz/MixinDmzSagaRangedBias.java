package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dragonminez.common.init.entities.sagas.DBSagasEntity;
import com.dragonminez.common.init.entities.sagas.ai.CombatContext;
import com.dragonminez.common.init.entities.sagas.ai.SagasCombatBrain;

/**
 * Make a raid, rift or dungeon NPC actually throw the ki blasts it was configured with.
 *
 * <h2>The problem</h2>
 * Giving an NPC a ki loadout only fills its skill pool. Whether it ever FIRES one is decided every tick by
 * {@link SagasCombatBrain#decide}, and that brain is written for DMZ's own encounters: with a target out past
 * {@code MELEE_RANGE} it overwhelmingly wants to close the distance, so a boss handed four ki attacks spends the
 * fight flying at the player and punching, and the loadout reads as broken when it is merely never chosen. Every
 * report of "the bosses were not really using ki attacks" comes back to this, not to the pool.
 *
 * <h2>What this changes</h2>
 * At most, one decision: when the brain has settled on something that is NOT already a cast, the NPC is beyond
 * melee range with a clear line of sight, and it has a skill off cooldown, the intent is swapped for a cast on a
 * weighted roll. Everything else about the brain is left alone, so positioning, combos, teleports and its own
 * native casting all still happen; this only stops a ranged NPC defaulting to "run at them" every single time.
 *
 * <h2>Only our NPCs</h2>
 * Gated on a persistent-data key that the raid manager and the dungeon spawner stamp on the NPCs they spawn, and
 * nothing else writes. A wild saga NPC in the overworld is untouched, so this cannot change how DMZ's own content
 * plays. The key doubles as the strength: it is the probability of the swap, so an encounter can be tuned from
 * "occasionally" to "constantly" without another flag.
 *
 * <p>{@code require = 0} per the DMZ mixin rule: if a DMZ update renames or reshapes {@code decide}, this quietly
 * stops applying and NPCs go back to DMZ's own choices, which is a worse fight and never a broken load. The handler
 * takes the target's parameter exactly ({@link CombatContext}) because mixin argument capture is all or nothing.
 */
@Mixin(value = SagasCombatBrain.class, remap = false)
public abstract class MixinDmzSagaRangedBias {

    /**
     * Persistent-data key: probability in 0..1 that a non-cast decision becomes a cast when the NPC is at range.
     * Absent or 0 means untouched, which is every NPC we did not spawn.
     *
     * <p>PRIVATE, and it must stay private. A mixin class may not declare a non-private static field: Mixin throws
     * {@code InvalidMixinException ... contains non-private static field} during APPLY, which is a hard crash the
     * moment the target class is first loaded, and {@code require = 0} does not cover it (that only suppresses a
     * target that cannot be found). This shipped {@code public} in 1.1.272 and crash-looped the server the first
     * time a saga entity ticked. The writers use their own copy of this string rather than importing it, which is
     * deliberate: normal code should never load a mixin class.
     */
    private static final String RANGED_BIAS_KEY = "dmz_ragnarok_ranged_bias";

    @Inject(method = "decide", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void su$preferRangedWhenConfigured(
            CombatContext context, CallbackInfoReturnable<SagasCombatBrain.Intent> cir) {
        SagasCombatBrain.Intent decided = cir.getReturnValue();
        if (decided == null || context == null) {
            return;
        }
        // Already committing to a ki attack, or mid-combo: leave it. Overriding a COMBO would cancel an attack
        // string the entity is part way through, which looks like a stutter rather than a choice.
        if (decided.type == SagasCombatBrain.Type.CAST || decided.type == SagasCombatBrain.Type.COMBO) {
            return;
        }
        DBSagasEntity self = context.self;
        if (self == null || context.target == null) {
            return;
        }
        double bias = self.getPersistentData().getDouble(RANGED_BIAS_KEY);
        if (bias <= 0.0) {
            return;
        }
        // "From afar" is the whole point: inside melee range a ki blast at the target's face is worse than a punch,
        // and DMZ's own brain already casts there when it wants to.
        if (context.dist3D <= SagasCombatBrain.MELEE_RANGE || !context.hasLineOfSight) {
            return;
        }
        if (context.readySkills == null || context.readySkills.isEmpty()) {
            return;
        }
        if (self.getRandom().nextDouble() >= bias) {
            return;
        }
        DBSagasEntity.KiSkill skill =
                context.readySkills.get(self.getRandom().nextInt(context.readySkills.size()));
        if (skill == null) {
            return;
        }
        cir.setReturnValue(SagasCombatBrain.Intent.cast(skill));
    }
}
