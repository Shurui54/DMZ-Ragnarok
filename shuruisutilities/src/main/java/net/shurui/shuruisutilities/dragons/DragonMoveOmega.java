package net.shurui.shuruisutilities.dragons;

import java.util.List;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;


/**
 * Omega Shenron (the base race): the Minus Energy Power Ball. Everything the other six dragons do, landing at once.
 *
 * <h2>Every effect, one damage total</h2>
 * On detonation each victim gets poison, lightning, freeze and burn together, but the DAMAGE is still the single
 * shared {@code (melee + strike + ki) / 2} figure. Running the six moves' full damage side by side would make this
 * one cast worth six, so what is inherited here is each dragon's EFFECT, not each dragon's damage.
 *
 * <h2>Effects reused, not reimplemented</h2>
 * The freeze marker, the burn and the pollution marker are the same mechanisms the individual dragons use, so a
 * change to how freezing looks or how the gas tints the screen shows up here automatically instead of drifting into
 * a second, slightly different copy.
 */
public final class DragonMoveOmega
{
    private DragonMoveOmega() {}

    /** Blast radius, in blocks. */
    public static final double RADIUS = 9.0;

    /** How long the inherited effects last on a victim, in ticks. */
    public static final int EFFECT_TICKS = 100;

    /**
     * Everything the other dragons do, landing where the BALL hits.
     *
     * <p>Called from {@code DragonProjectileHit} when a projectile carrying this move's technique id connects. The
     * ball itself is DMZ's - it launches, steers and renders it - so all that happens here is the payload.
     */
    public static void onHit(LivingEntity caster, LivingEntity victim, ServerLevel level)
    {
        applyEveryDragonsEffect(level, victim);

        // Splash: everyone else caught in the blast takes the same treatment, so it reads as a detonation rather
        // than a single-target hit.
        if (caster != null)
            for (LivingEntity other : DragonMoveEffects.targets(caster, level, RADIUS))
                if (other != victim && other.distanceTo(victim) <= RADIUS)
                    applyEveryDragonsEffect(level, other);

        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                victim.getX(), victim.getY() + 1.0, victim.getZ(), 120, RADIUS * 0.4, 1.5, RADIUS * 0.4, 0.1);
        level.playSound(null, victim.blockPosition(), SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.4f, 0.5f);
    }

    /** Poison, lightning, freeze and burn on one victim, using each dragon's own mechanism. */
    private static void applyEveryDragonsEffect(ServerLevel level, LivingEntity victim)
    {
        // Haze: the gas marker, which also drives the purple screen overlay, plus the blindness that goes with it.
        PollutedEffect.apply(victim, EFFECT_TICKS);
        victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, EFFECT_TICKS, 0, false, false, false));
        victim.addEffect(new MobEffectInstance(MobEffects.POISON, EFFECT_TICKS, 1, false, true, true));

        // Rage: the slow, and a visual-only bolt so it neither starts fires nor adds vanilla's own damage.
        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, EFFECT_TICKS, 2, false, true, true));
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt != null)
        {
            bolt.moveTo(victim.getX(), victim.getY(), victim.getZ());
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);
        }

        // Eis: the vanilla freeze meter, which is what draws the powder snow overlay.
        victim.setTicksFrozen(victim.getTicksRequiredToFreeze() + EFFECT_TICKS);

        // Nuova: genuinely alight, for the orange overlay and the burn sounds. NOT registered with the burn guard,
        // so vanilla's small fire tick does apply here; unlike Nuova's own aura this move is a single detonation
        // rather than a damage budget spread over time, so a few points of fire is not double-counting a total.
        victim.setSecondsOnFire(EFFECT_TICKS / 20);
    }
}
