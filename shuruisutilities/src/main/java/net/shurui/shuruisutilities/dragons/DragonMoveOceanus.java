package net.shurui.shuruisutilities.dragons;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.compat.dmz.DragonDamage;
import net.shurui.shuruisutilities.compat.dmz.DragonHurt;

/**
 * Oceanus Shenron (6 stars): Mighty Hurricane Fury. A tornado that sucks up entities AND projectiles, damages what
 * is caught inside it, then throws everything back out, damaging again on the way.
 *
 * <h2>Two damage moments, one total</h2>
 * A victim takes damage while it is inside the funnel and again when it is thrown. Both come out of the SAME total:
 * the whole move deals {@code (melee + strike + ki) / 2}, split as {@link #INSIDE_SHARE} across the spin ticks and
 * the remainder on the throw. Treating the throw as a separate full-damage hit would silently make this move worth
 * roughly double every other one.
 *
 * <h2>Projectiles are swallowed too</h2>
 * Anything extending {@link Projectile} inside the funnel has its velocity overwritten toward the centre and is then
 * flung out with everything else, so firing into the tornado does not simply pass through it. That is the part that
 * makes it read as weather rather than as a damage aura with particles.
 */
public final class DragonMoveOceanus
{
    private DragonMoveOceanus() {}

    /** Funnel radius, in blocks. */
    public static final double RADIUS = 7.0;

    /** How tall the funnel reaches. */
    public static final double HEIGHT = 10.0;

    /** How long the tornado holds before it throws, in ticks. Eight seconds. */
    public static final int SPIN_TICKS = 160;

    /** Ticks between damage applications while spinning. */
    public static final int DAMAGE_INTERVAL_TICKS = 20;

    public static final int DAMAGE_TICKS = SPIN_TICKS / DAMAGE_INTERVAL_TICKS;

    /** The share of the move's total damage dealt while spinning; the rest lands on the throw. */
    public static final float INSIDE_SHARE = 0.6f;

    /** How hard caught things are thrown outward and up when the funnel collapses. */
    private static final double THROW_OUT = 2.2;
    private static final double THROW_UP = 1.1;

    /**
     * Wind-up before the funnel takes hold, in ticks. Handed to DMZ's hurricane as its cast time, so the charge is
     * the asset's own rather than a timer of ours pretending to be one.
     */
    private static final int CAST_TICKS = 20;

    /** Travel speed handed to DMZ's hurricane. Slow: this move is a place, not a projectile. */
    private static final float STORM_SPEED = 0.15f;

    static boolean cast(LivingEntity caster, ServerLevel level)
    {
        // THE VISUAL IS BURTER'S HURRICANE, RECOLOURED. DMZ's SPBlueHurricaneEntity is a modelled, animated funnel
        // with its own wind-up; MixinDmzHurricaneTexture swaps its texture for a pale water blue when the owner is a
        // player, so this move gets flowing water and Burter keeps his electric blue. The funnel is the whole visual
        // now: the ring of particles that used to stand in for it is gone, and so is the ball, since neither adds
        // anything next to the model.
        // Zero damage: the tornado below deals every point this move is worth. The cast time is still handed over
        // because the funnel discards itself at castTime + 140, which is what lines its life up with SPIN_TICKS.
        net.shurui.shuruisutilities.compat.dmz.DmzVisuals.hurricane(
                caster, level, caster.position(), 0.0f, STORM_SPEED, SPIN_TICKS - 140);

        // The dragon turns with their own storm. Render-only, so their camera stays where they pointed it; see
        // PacketSpinState for why this cannot be an animation clip.
        if (caster instanceof net.minecraft.server.level.ServerPlayer player)
            PacketSpinState.broadcast(player, SPIN_TICKS + CAST_TICKS);

        DragonEffectTicker.add(new Tornado(caster, level));
        level.playSound(null, caster.blockPosition(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.PLAYERS, 1.0f, 0.7f);
        return true;
    }

    /** The funnel: holds position, drags things in, damages them, then throws everything clear. */
    static final class Tornado implements DragonEffectTicker.ActiveEffect
    {
        private final LivingEntity caster;
        private final ServerLevel level;
        private final Set<UUID> caught = new HashSet<>();
        private int ticksLeft = SPIN_TICKS;

        Tornado(LivingEntity caster, ServerLevel level)
        {
            this.caster = caster;
            this.level = level;
        }

        /** The funnel travels with its dragon rather than staying where it was cast. */
        private Vec3 centre()
        {
            return caster.position();
        }

        @Override
        public boolean tick()
        {
            if (ticksLeft <= 0)
            {
                throwEverythingOut();
                stopSpin();
                return false;
            }
            ticksLeft--;

            // The caster wades: heavily slowed and unable to jump while the storm is up, so it reads as something
            // they are dragging along rather than a free-movement buff. Slowness 4 leaves roughly a block a second.
            caster.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 30, 3, false, false, false));
            caster.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.JUMP, 30, 128, false, false, false));

            List<Entity> inside = inside();
            for (Entity entity : inside)
            {
                caught.add(entity.getUUID());
                pullIn(entity);
            }

            if (ticksLeft % DAMAGE_INTERVAL_TICKS == 0)
            {
                float spinTotal = DragonDamage.total(caster) * INSIDE_SHARE;
                float perTick = DAMAGE_TICKS <= 1 ? spinTotal : spinTotal / DAMAGE_TICKS;
                if (perTick > 0.0f)
                    for (Entity entity : inside)
                        if (entity instanceof LivingEntity living)
                            DragonHurt.hurt(caster, living, perTick);
            }

            if (ticksLeft <= 0)
            {
                throwEverythingOut();
                stopSpin();
                return false;
            }
            return true;
        }

        @Override
        public void cancel()
        {
            // Ending early still throws, so nothing is left frozen mid-spin with its motion overwritten.
            throwEverythingOut();
            stopSpin();
        }

        /** Drop the caster's model spin whenever the funnel ends, however it ends. */
        private void stopSpin()
        {
            if (caster instanceof net.minecraft.server.level.ServerPlayer player)
                PacketSpinState.broadcastEnd(player);
        }

        /**
         * Everything the funnel has hold of: living entities it may hit, plus any projectile, so shots fired into it
         * are caught rather than passing through.
         */
        private List<Entity> inside()
        {
            AABB box = new AABB(centre(), centre()).inflate(RADIUS, HEIGHT * 0.5, RADIUS);
            List<Entity> found = new ArrayList<>();
            for (Entity entity : level.getEntities((Entity) null, box, e -> true))
            {
                if (entity == caster || !entity.isAlive())
                    continue;
                if (entity instanceof LivingEntity living)
                {
                    if (DragonMoveEffects.isImmuneShadowDragon(living))
                        continue;
                    found.add(entity);
                }
                else if (entity instanceof Projectile)
                {
                    found.add(entity);
                }
            }
            return found;
        }

        /** Drag one entity toward the funnel's axis and lift it, so it spirals rather than merely being slowed. */
        private void pullIn(Entity entity)
        {
            Vec3 toAxis = new Vec3(centre().x - entity.getX(), 0.0, centre().z - entity.getZ());
            double distance = toAxis.length();
            Vec3 inward = distance < 1.0e-3 ? Vec3.ZERO : toAxis.scale(0.18 / distance);
            // Tangential component, so things circle the axis instead of collapsing straight onto it.
            Vec3 swirl = new Vec3(-toAxis.z, 0.0, toAxis.x).normalize().scale(0.22);
            double lift = entity.getY() < centre().y + HEIGHT ? 0.18 : 0.0;

            entity.setDeltaMovement(entity.getDeltaMovement().add(inward).add(swirl).add(0.0, lift, 0.0));
            entity.hasImpulse = true;
            // Falling has to be reset every tick or the client re-applies fall damage the moment the funnel drops it.
            entity.fallDistance = 0.0f;
        }

        /** Collapse: fling everything caught outward and up, and deal the throw's share of the damage. */
        private void throwEverythingOut()
        {
            if (caught.isEmpty())
                return;
            float throwDamage = DragonDamage.total(caster) * (1.0f - INSIDE_SHARE);
            for (UUID id : caught)
            {
                Entity entity = level.getEntity(id);
                if (entity == null || !entity.isAlive())
                    continue;
                Vec3 outward = new Vec3(entity.getX() - centre().x, 0.0, entity.getZ() - centre().z);
                if (outward.lengthSqr() < 1.0e-4)
                    outward = new Vec3(1.0, 0.0, 0.0); // dead centre: pick a direction rather than divide by zero
                outward = outward.normalize().scale(THROW_OUT);
                entity.setDeltaMovement(outward.x, THROW_UP, outward.z);
                entity.hasImpulse = true;
                entity.fallDistance = 0.0f;

                if (throwDamage > 0.0f && entity instanceof LivingEntity living)
                    DragonHurt.hurt(caster, living, throwDamage);
            }
            caught.clear();
        }

    }
}
