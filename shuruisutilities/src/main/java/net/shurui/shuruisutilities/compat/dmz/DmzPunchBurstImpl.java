package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.init.MainParticles;

import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The only class here that names a DragonMineZ type. Reached solely through {@link DmzPunchBurst}.
 */
final class DmzPunchBurstImpl
{
    private DmzPunchBurstImpl() {}

    /** Centre, then six around it on the three axes: a solid core with a shell, out of a particle with a fixed size. */
    private static final double[][] OFFSETS = {
            { 0.0D,  0.0D,  0.0D},
            { 1.0D,  0.0D,  0.0D}, {-1.0D,  0.0D,  0.0D},
            { 0.0D,  1.0D,  0.0D}, { 0.0D, -1.0D,  0.0D},
            { 0.0D,  0.0D,  1.0D}, { 0.0D,  0.0D, -1.0D},
    };

    static void play(ServerLevel level, Vec3 at, double radius)
    {
        try
        {
            SimpleParticleType punch = MainParticles.PUNCH_PARTICLE.get();
            for (double[] o : OFFSETS)
            {
                // count 0 so the three trailing arguments are forwarded as this particle's own velocity, which is
                // exactly nothing: the burst stays where it was struck instead of drifting apart.
                level.sendParticles(punch,
                        at.x + o[0] * radius, at.y + o[1] * radius, at.z + o[2] * radius,
                        0, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
        catch (Throwable ignored)
        {
            // A decoration must never be able to take the manoeuvre down with it.
        }
    }
}
