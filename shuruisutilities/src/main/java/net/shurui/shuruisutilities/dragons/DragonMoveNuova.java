package net.shurui.shuruisutilities.dragons;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import net.shurui.shuruisutilities.compat.dmz.DragonDamage;
import net.shurui.shuruisutilities.compat.dmz.DragonHurt;

/**
 * Nuova Shenron (4 stars): a searing aura that burns anything within a radius of him.
 *
 * <h2>The overlay and the noises come from vanilla fire</h2>
 * Victims are genuinely set alight ({@code setSecondsOnFire}). That gets the orange burning overlay on their screen
 * and the crackling burn sounds for free, correct for every viewer, with no custom overlay, no packet and no sound
 * file. It is also self-correcting: step out of the fire and the overlay goes with it.
 *
 * <h2>Why vanilla's fire damage is suppressed</h2>
 * Being on fire would ALSO deal vanilla's own burn damage, landing on top of this move's figure and breaking the
 * rule that a move deals {@code (melee + strike + ki) / 2} in total. {@link DragonBurnGuard} cancels vanilla fire
 * damage for anyone currently burning under this move, so the flames are the presentation and every point of damage
 * still comes from the formula.
 *
 * <h2>An aura, not a burst</h2>
 * The radius is re-evaluated every damage tick rather than captured once at cast, so walking into Nuova's aura part
 * way through burns you and walking out of it stops. That is what "burning damage when in a certain radius" asks
 * for, and it means the victim list is not fixed at cast time.
 */
public final class DragonMoveNuova
{
    private DragonMoveNuova() {}

    /** The orange the heat is drawn and tinted in. Shared by the particles, the effect pip and the screen shimmer. */
    public static final int HEAT_RGB = 0xFF7A1E;

    /** How far the heat reaches. */
    public static final double RADIUS = 8.0;

    /** How long the aura burns for, in ticks. */
    public static final int DURATION_TICKS = 200;

    /** Ticks between burn applications; with the duration above, 10 damage ticks in total. */
    public static final int DAMAGE_INTERVAL_TICKS = 20;

    public static final int DAMAGE_TICKS = DURATION_TICKS / DAMAGE_INTERVAL_TICKS;

    /** Seconds of fire applied per tick: slightly longer than the gap between ticks so the flames never flicker out. */
    private static final int FIRE_SECONDS = 2;

    /** How often the heat haze is drawn, in ticks. Emission is throttled through {@link DragonAreaFx}. */
    private static final int FX_INTERVAL_TICKS = 4;

    /** Wind-up handed to DMZ's area entity, so the charge belongs to the asset. */
    private static final int CAST_TICKS = 20;

    /** How long the "standing in the heat" marker lasts per application. */
    private static final int MARKER_TICKS = 30;

    // Everyone currently burning under this move. Read by DragonBurnGuard to know whose vanilla fire damage to
    // suppress. Runtime only, and entries are removed as the aura ends or a victim leaves the radius.
    private static final Set<UUID> burning = new HashSet<>();

    /** True when this entity is burning because of Nuova's aura, rather than from ordinary fire. */
    public static boolean isBurningFromAura(LivingEntity entity)
    {
        return entity != null && burning.contains(entity.getUUID());
    }

    static boolean cast(LivingEntity caster, ServerLevel level)
    {
        // The dome, swelling out of the caster to the edge of the burn and holding for its whole duration.
        //
        // This was DMZ's KiAreaEntity, which could never have worked: an area entity that is not firing is exactly
        // what TickHandler's post-cast tidy-up looks for and deletes, so the dome was destroyed within a tick of
        // being spawned. (It was also being drawn one block wide whatever the radius, since KI_AREA renders through
        // the projectile renderer, which reads getSize and not getAreaRadius.)
        DragonOrbSwell.start(caster, level, RADIUS, HEAT_RGB, 0xC43A00, 0x7A1F00, DURATION_TICKS);

        // No emptiness check at cast: this is an aura that lasts, so casting it before an enemy arrives is a
        // legitimate use and must not be refunded as a miss.
        DragonEffectTicker.add(new HeatAura(caster, level));
        return true;
    }

    /** Nuova's aura: burns whoever is inside the radius at each damage tick, for as long as it lasts. */
    static final class HeatAura implements DragonEffectTicker.ActiveEffect
    {
        private final LivingEntity caster;
        private final ServerLevel level;
        private final Set<UUID> touched = new HashSet<>();
        private int ticksLeft = DURATION_TICKS;

        HeatAura(LivingEntity caster, ServerLevel level)
        {
            this.caster = caster;
            this.level = level;
        }

        @Override
        public boolean tick()
        {
            if (!caster.isAlive() || ticksLeft <= 0)
            {
                clear();
                return false;
            }
            ticksLeft--;

            // Heat haze: a slow orange shimmer at the boundary, so the edge of the aura is readable from outside.
            // Throttled and skipped entirely when nobody is near (see DragonAreaFx).
            if (DragonAreaFx.due(ticksLeft, FX_INTERVAL_TICKS))
            {
                DragonAreaFx.ring(level, net.minecraft.core.particles.ParticleTypes.FLAME,
                        caster.position(), RADIUS, 0.2, 6, 1, 0.25, 0.01);
                DragonAreaFx.burst(level, net.minecraft.core.particles.ParticleTypes.SMALL_FLAME,
                        caster.position().add(0.0, 1.0, 0.0), 6, RADIUS * 0.4, 0.8, RADIUS * 0.4, 0.01);
            }

            if (ticksLeft % DAMAGE_INTERVAL_TICKS == 0)
            {
                float perTick = DragonDamage.perTick(caster, DAMAGE_TICKS);
                List<LivingEntity> inside = DragonMoveEffects.targets(caster, level, RADIUS);

                // Anyone who was burning last tick but has since left the radius stops burning, so the aura is a
                // place you can escape rather than a brand you carry away.
                Set<UUID> stillInside = new HashSet<>();
                for (LivingEntity victim : inside)
                    stillInside.add(victim.getUUID());
                touched.removeIf(id ->
                {
                    if (stillInside.contains(id))
                        return false;
                    burning.remove(id);
                    return true;
                });

                for (LivingEntity victim : inside)
                {
                    victim.setSecondsOnFire(FIRE_SECONDS);
                    // Marks them as inside the heat, which is what puts the orange shimmer on their screen.
                    ScorchedEffect.apply(victim, MARKER_TICKS);
                    burning.add(victim.getUUID());
                    touched.add(victim.getUUID());
                    if (perTick > 0.0f)
                        DragonHurt.hurt(caster, victim, perTick);
                }
            }

            if (ticksLeft <= 0)
            {
                clear();
                return false;
            }
            return true;
        }

        @Override
        public void cancel()
        {
            clear();
        }

        /** Stop suppressing fire damage for everyone this aura touched; their flames burn out normally. */
        private void clear()
        {
            for (UUID id : touched)
                burning.remove(id);
            touched.clear();
        }
    }
}
