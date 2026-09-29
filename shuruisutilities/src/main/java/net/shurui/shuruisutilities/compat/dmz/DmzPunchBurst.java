package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

/**
 * Guard entry point for DragonMineZ's own punch particle. The sole class naming a DMZ type is
 * {@link DmzPunchBurstImpl} (the optional-dependency pattern).
 */
public final class DmzPunchBurst
{
    private DmzPunchBurst() {}

    /**
     * Fire DMZ's punch particle as one large burst centred on {@code at}.
     *
     * <p>Size is not a parameter DMZ accepts. {@code PunchParticle} fixes its own {@code quadSize} at 0.8 in its
     * constructor and grows it by 0.05 a tick over a ten tick life, and the three velocity arguments are real
     * velocity, not the colour or scale channel some other DMZ particles repurpose them as. So "large" has to be built
     * out of several of them: one at the centre and a spread of others around it, which reads as a single big flash
     * rather than a scatter, and grows into the ring over its life.
     *
     * <p>Each is sent with a count of ZERO, which is what makes the server forward the three arguments as this
     * particle's velocity rather than treating them as a random spread with a speed. That distinction matters here for
     * the same reason it does elsewhere in DMZ's particles, so the offsets are applied to the POSITION and the
     * velocity is deliberately left at nothing: a burst that drifts stops looking like an impact.
     *
     * @param radius how far out the outer particles sit, in blocks
     */
    public static void play(ServerLevel level, Vec3 at, double radius)
    {
        if (level != null && at != null && ModList.get().isLoaded("dragonminez"))
            DmzPunchBurstImpl.play(level, at, radius);
    }
}
