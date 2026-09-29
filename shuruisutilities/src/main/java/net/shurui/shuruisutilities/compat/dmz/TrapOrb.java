package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

/**
 * A ki ball that sits ON a victim instead of being thrown at one: the body of the Sphere of Destruction.
 *
 * <p>Deliberately DMZ's own blast entity rather than particles, so it is the same modelled, coloured, glowing ball
 * every other ki attack is made of - a Big Bang held still around whoever it caught.
 *
 * <p>Returns a plain {@link Entity} so the caller never names a DMZ type; discarding it is vanilla.
 */
public final class TrapOrb
{
    private TrapOrb() {}

    /**
     * Put an orb in the world, drawn centred on the point given.
     *
     * @param diameter how wide the ball is drawn, in blocks
     * @return the orb, or null when DMZ is absent or refused it
     */
    public static Entity spawn(LivingEntity owner, ServerLevel level, double x, double y, double z, float diameter,
                               int colorMain, int colorBorder, int colorOutline)
    {
        if (owner == null || !ModList.get().isLoaded("dragonminez"))
            return null;
        Entity orb = TrapOrbImpl.spawn(owner, level, diameter, colorMain, colorBorder, colorOutline);
        place(orb, x, y, z);
        return orb;
    }

    /** Put an orb at a point, compensating for the renderer's half-a-bounding-box lift. See {@link #follow}. */
    public static void place(Entity orb, double x, double y, double z)
    {
        if (orb != null && orb.isAlive())
            orb.moveTo(x, y - orb.getBbHeight() * 0.5, z);
    }

    /**
     * Move an orb to a point so that it LOOKS like it moved, rather than appearing there.
     *
     * <h2>Why placing it is not enough</h2>
     * DMZ registers its ki blast with an update interval of TEN, so a client is only told where one is twice a
     * second: an orb whose position we drive by hand jumps between five-tick-old snapshots however smoothly the
     * server moves it. {@code hasImpulse} is the flag that makes {@code ServerEntity} send a position (and velocity)
     * packet on the very next tick regardless of that interval.
     *
     * <p>The step is also written as the orb's VELOCITY, which is what the client advances it by between packets, so
     * the motion is continuous rather than a stack of small teleports. The server still sets the position outright
     * every tick, so the velocity is presentation only and can never let the orb drift.
     */
    public static void moveSmoothly(Entity orb, double x, double y, double z)
    {
        if (orb == null || !orb.isAlive())
            return;
        net.minecraft.world.phys.Vec3 step = new net.minecraft.world.phys.Vec3(x, y, z).subtract(drawnCentre(orb));
        place(orb, x, y, z);
        orb.hasImpulse = true;
        hintVelocity(orb, step);
    }

    /**
     * Tell the client the orb is MOVING, without the server thinking it is.
     *
     * <p>The step is sent as a motion packet rather than written into the entity's own delta, because a ki
     * projectile's tick raytraces along its delta and blows itself up on the first block it finds: an orb given a
     * real velocity would detonate against a wall the god happened to be charging beside, and a thrown one would pop
     * on any terrain between it and its target instead of arriving. Server-side the orb stays perfectly still and is
     * repositioned by hand each tick, so this is presentation only and can never move it anywhere.
     */
    private static void hintVelocity(Entity orb, net.minecraft.world.phys.Vec3 step)
    {
        try
        {
            if (orb.level() instanceof ServerLevel level)
                level.getChunkSource().broadcast(orb,
                        new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(orb.getId(), step));
        }
        catch (Throwable ignored)
        {
        }
    }

    /** Where an orb's ball is actually drawn, which is not where the entity is. */
    public static net.minecraft.world.phys.Vec3 drawnCentre(Entity orb)
    {
        return orb == null ? net.minecraft.world.phys.Vec3.ZERO
                : orb.position().add(0.0, orb.getBbHeight() * 0.5, 0.0);
    }

    /** Grow or shrink an orb. */
    public static void resize(Entity orb, float diameter)
    {
        if (orb != null && orb.isAlive())
            TrapOrbImpl.resize(orb, diameter);
    }

    /**
     * Keep the orb on its victim as they are dragged about.
     *
     * <p>Dropped by half the orb's own bounding box, because {@code KiProjectileRenderer} translates up by
     * {@code getBbHeight() / 2} before drawing: put the entity on the victim's chest and the BALL appears above
     * their head instead of around them.
     */
    public static void follow(Entity orb, LivingEntity victim)
    {
        if (orb != null && victim != null && orb.isAlive())
            moveSmoothly(orb, victim.getX(), victim.getY() + victim.getBbHeight() * 0.5, victim.getZ());
    }
}
