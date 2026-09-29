package net.shurui.shuruisutilities.dragons;

import java.util.List;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3f;

import net.shurui.shuruisutilities.compat.dmz.DragonDamage;
import net.shurui.shuruisutilities.compat.dmz.DragonHurt;

/**
 * Haze Shenron (2 stars): Pollution. A cloud of toxic purple gas that damages whoever stands in it and makes it hard
 * to see out of.
 *
 * <h2>A place, not a projectile</h2>
 * The cloud is anchored where it was cast and stays there for its duration, re-checking who is inside on every
 * damage tick. Walking in part way through poisons you and walking out clears it, which is what makes it a hazard to
 * move around rather than a burst that happens to be green.
 *
 * <h2>Vision</h2>
 * Two layers, both only while inside: the purple screen overlay ({@code PollutionOverlay}, driven by
 * {@link PollutedEffect}) and vanilla blindness for the actual loss of sight. The overlay alone would tint the
 * screen without obscuring anything; blindness alone would go black rather than purple. Together they read as gas.
 */
public final class DragonMoveHaze
{
    private DragonMoveHaze() {}

    /** The cloud's purple. Shared by the particles, the effect pip and the screen overlay so they match exactly. */
    public static final int CLOUD_RGB = 0x8A2BE2;

    /** Cloud radius, in blocks. */
    public static final double RADIUS = 6.0;

    /** How long the cloud lingers, in ticks. */
    public static final int DURATION_TICKS = 200;

    /** Ticks between damage applications; with the duration above, 10 damage ticks in total. */
    public static final int DAMAGE_INTERVAL_TICKS = 20;

    public static final int DAMAGE_TICKS = DURATION_TICKS / DAMAGE_INTERVAL_TICKS;

    /** How long the "in the gas" marker lasts per application: longer than the gap so it never flickers. */
    private static final int MARKER_TICKS = 40;

    /** How often the cloud is drawn, in ticks. A lingering cloud drawn every tick is pure waste. */
    private static final int FX_INTERVAL_TICKS = 4;

    static boolean cast(LivingEntity caster, ServerLevel level)
    {
        // The same swelling ball every other area move has. The gas particles alone never marked where the cloud
        // actually ended, so this move looked like it had no body while the rest had one.
        DragonOrbSwell.start(caster, level, RADIUS, CLOUD_RGB, 0x4B0082, 0x2A0044, DURATION_TICKS);

        // Anchored where the caster stands, and deliberately not refunded for an empty room: laying gas down before
        // anyone arrives is a legitimate use of a lingering hazard.
        DragonEffectTicker.add(new GasCloud(caster, level));
        return true;
    }

    /** One lingering cloud: draws itself, and poisons whoever is inside each damage tick. */
    static final class GasCloud implements DragonEffectTicker.ActiveEffect
    {
        private final LivingEntity caster;
        private final ServerLevel level;
        private int ticksLeft = DURATION_TICKS;

        GasCloud(LivingEntity caster, ServerLevel level)
        {
            this.caster = caster;
            this.level = level;
        }

        /** The cloud travels WITH its dragon rather than sitting where it was cast. */
        private Vec3 centre()
        {
            return caster.position();
        }

        @Override
        public boolean tick()
        {
            if (!caster.isAlive() || ticksLeft <= 0)
                return false;
            ticksLeft--;

            if (DragonAreaFx.due(ticksLeft, FX_INTERVAL_TICKS))
                drawCloud();

            if (ticksLeft % DAMAGE_INTERVAL_TICKS == 0)
            {
                float perTick = DragonDamage.perTick(caster, DAMAGE_TICKS);
                for (LivingEntity victim : inside())
                {
                    PollutedEffect.apply(victim, MARKER_TICKS);
                    // Blindness is what actually costs them their sight; the overlay supplies the colour.
                    victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, MARKER_TICKS, 0, false, false, false));
                    if (perTick > 0.0f)
                        DragonHurt.hurt(caster, victim, perTick);
                }
            }
            return ticksLeft > 0;
        }

        /** Everything currently in the cloud, measured from the caster, since the cloud moves with them. */
        private List<LivingEntity> inside()
        {
            net.minecraft.world.phys.AABB box =
                    new net.minecraft.world.phys.AABB(centre(), centre()).inflate(RADIUS);
            return level.getEntitiesOfClass(LivingEntity.class, box,
                    e -> e.isAlive()
                            && e != caster
                            && !DragonMoveEffects.isImmuneShadowDragon(e)
                            && e.position().distanceTo(centre()) <= RADIUS);
        }

        /**
         * Purple smoke: large slow dust for the colour, with a little grey smoke mixed through it for volume.
         *
         * <p>Dust carries the colour (smoke particles cannot be tinted) while the smoke supplies the drifting body
         * that makes it read as gas rather than as floating dots. Both go through {@link DragonAreaFx}, so nothing
         * is sent when there is nobody near enough to see it.
         */
        private void drawCloud()
        {
            DustParticleOptions dust = new DustParticleOptions(
                    new Vector3f(((CLOUD_RGB >> 16) & 0xFF) / 255.0f,
                            ((CLOUD_RGB >> 8) & 0xFF) / 255.0f,
                            (CLOUD_RGB & 0xFF) / 255.0f),
                    3.0f);
            Vec3 at = centre().add(0.0, 1.0, 0.0);
            DragonAreaFx.burst(level, dust, at, 10, RADIUS * 0.5, RADIUS * 0.3, RADIUS * 0.5, 0.0);
            DragonAreaFx.burst(level, net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE, at,
                    4, RADIUS * 0.45, RADIUS * 0.25, RADIUS * 0.45, 0.005);
        }
    }
}
