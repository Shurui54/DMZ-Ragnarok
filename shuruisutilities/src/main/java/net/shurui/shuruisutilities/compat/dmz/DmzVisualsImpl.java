package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.init.entities.ki.KiBlastEntity;
import com.dragonminez.common.init.entities.ki.KiExplosionVisualEntity;
import com.dragonminez.common.init.entities.ki.SPBlueHurricaneEntity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The only class naming DMZ's ki visual entities; {@link DmzVisuals} is the guard in front of it.
 *
 * <p>WHY DMZ ENTITIES AND NOT PARTICLES. These moves originally drew themselves out of vanilla particle types, which
 * cannot look like a DMZ attack no matter how they are arranged: DMZ's ki visuals are modelled, animated, coloured
 * and lit as a set, and a ring of {@code CLOUD} particles standing in for a hurricane reads as exactly what it is.
 * Every one of these entities also takes a CAST TIME, which is where the charge-up comes from - the wind-up is part
 * of the asset rather than something to fake with a timer.
 *
 * <p>Every method here is best-effort: a DMZ internals change costs the visual, never the move.
 */
final class DmzVisualsImpl
{
    private DmzVisualsImpl() {}

    /** How long a scattered decorative blast flies before it pops, in ticks. */
    private static final int TRAVEL_TICKS = 40;

    /**
     * A purely cosmetic burst: no damage, no owner, just DMZ's explosion visual at a point.
     *
     * <h2>Why every burst here goes through this entity and not {@code KiExplosionEntity}</h2>
     * {@code KiExplosionEntity} is not a visual, it is Final Explosion's body, and it OWNS its caster:
     * {@code onKiTick} zeroes the owner's motion and teleports them onto the explosion every single tick, while
     * {@code setupExplosionPlayer} sets {@code maxLife} to 99999 and never auto-fires - so one spawned for decoration
     * pins its caster in the air until the world unloads. It also never adds itself to the world (unlike
     * {@code setupAreaPlayer}, {@code setupKiBlastPlayer} and {@code setupHurricane}, which all do), which is why the
     * bursts that used it were invisible rather than merely wrong.
     *
     * <p>{@code KiExplosionVisualEntity} is the one DMZ ships for this: a plain entity with no owner, no damage and
     * no physics, which grows from half to full size over 25 ticks and fades out.
     */
    static boolean explosionVisual(ServerLevel level, Vec3 at, int colorMain, int colorBorder, int colorOutline,
                                   float size)
    {
        try
        {
            KiExplosionVisualEntity fx = new KiExplosionVisualEntity(
                    com.dragonminez.common.init.MainEntities.KI_EXPLOSION_VISUAL.get(), level);
            fx.setupExplosion(colorMain, colorBorder, colorOutline, size);
            fx.setPos(at.x, at.y, at.z);
            level.addFreshEntity(fx);
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[visuals] explosion visual failed: {}", t.toString());
            return false;
        }
    }

    /**
     * DMZ's own hurricane, used for Oceanus's Mighty Hurricane Fury.
     *
     * <p>This is the asset the move should always have used: a modelled, animated funnel rather than rings of cloud
     * particles. {@code castTime} is its wind-up.
     */
    static boolean hurricane(LivingEntity owner, ServerLevel level, Vec3 at, float damage, float speed, int castTime)
    {
        try
        {
            SPBlueHurricaneEntity storm = new SPBlueHurricaneEntity(level, owner);
            // setupHurricane positions it on the owner, plays its charge sound AND adds it to the world. Setting a
            // position or adding it again afterwards double-spawns it.
            storm.setupHurricane(owner, damage, speed, castTime);

            // FIRING FROM THE FIRST TICK, or it does not survive to a second one. setupHurricane leaves the funnel in
            // its wind-up state, and a non-firing ki projectile owned by a player is exactly what DMZ's post-cast
            // tidy-up hunts down and discards - which is why the storm was spawning and then vanishing unseen. It
            // also means the caster is not frozen solid for the wind-up, which that branch does to its owner.
            //
            // Zero damage and the heal flag for the same reason as every other borrowed visual: the funnel's own
            // ten-tick pulse would otherwise deal a second helping on top of the tornado's, and even a zero-damage
            // hurt would hand out invulnerability frames that blunt the real one.
            storm.setKiDamage(0.0f);
            storm.setHeal(true);
            storm.setFiring(true);
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[visuals] hurricane failed: {}", t.toString());
            return false;
        }
    }

    /**
     * A single small ki blast fired from a point along a direction. Used for Eis's scatter of blue blasts.
     *
     * <p>DMZ's own blast entity rather than a particle, so it has the modelled core, the coloured glow and the
     * trail that make it read as ki. Damage is passed through because these are real blasts, not decoration.
     */
    static boolean kiBlast(LivingEntity owner, ServerLevel level, Vec3 from, Vec3 direction, float damage,
                           float speed, int color, int colorBorder, int colorOutline, float size)
    {
        try
        {
            KiBlastEntity blast = new KiBlastEntity(level, owner);
            blast.setPos(from.x, from.y, from.z);
            // setupKiBlastPlayer adds it to the world itself; aim it after, not before.
            blast.setupKiBlastPlayer(owner, damage, speed, color, colorBorder, colorOutline, size);
            // IT HAS TO BE MARKED AS FIRED OR IT NEVER LEAVES THE CASTER. setupKiBlastPlayer leaves the ball in its
            // CHARGING state, and a charging ball has its velocity zeroed and its position snapped back onto its
            // owner every tick - so a whole scatter of these simply sat inside the dragon. Worse, DMZ's post-cast
            // tidy-up deletes any non-firing ball a player owns, so one of them vanished outright.
            //
            // Marked as healing with zero damage so the firing branch's area pulse can do nothing: these are
            // decoration, and the move's damage is dealt by its own effect.
            blast.setHeal(true);
            blast.setKiDamage(0.0f);
            blast.setCastTime(0);
            blast.setFiring(true);
            blast.setMaxLife(TRAVEL_TICKS);
            blast.shoot(direction.x, direction.y, direction.z, speed, 0.0f);
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[visuals] ki blast failed: {}", t.toString());
            return false;
        }
    }

    /**
     * A big translucent ball around a point, drawn at the RADIUS the move's effect actually reaches.
     *
     * <h2>The sizing</h2>
     * {@code KiExplosionVisualRenderer} draws a UNIT sphere scaled by {@code getMaxSize()}, and {@code setupExplosion}
     * stores {@code baseSize * 2} as that max size. So the ball's world radius at full growth is
     * {@code baseSize * 2}, and asking for a given radius means passing HALF of it. Getting this wrong is what made
     * the old orb a small ball floating inside a much wider effect.
     */
    static boolean aoeBall(ServerLevel level, Vec3 at, double radius, int colorMain, int colorBorder, int colorOutline)
    {
        return explosionVisual(level, at, colorMain, colorBorder, colorOutline, (float) (radius * 0.5));
    }
}
