package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/**
 * Plays a move's charge and fire animations DIRECTLY, instead of relying on DMZ's projectile to do it.
 *
 * <h2>Why not let the projectile do it</h2>
 * DMZ triggers a technique's animation from {@code AbstractKiProjectile.triggerAnimationPacket}, so the clip only
 * plays when a projectile exists. That forces a choice between "has an animation" and "has no unwanted ball", and
 * several of these moves want the former without the latter - hakai should erase its target, not lob a ball at it.
 *
 * <p>Sending the same packet ourselves decouples the two: a move can animate with no projectile at all. This is
 * exactly what the G.O.D. hakai already does, and its animation is the one that has always worked, which is what
 * makes this the mechanism to use rather than another guess.
 */
public final class MoveAnimation
{
    private MoveAnimation() {}

    /** Hold a charge pose ({@code <prefix>_cast}). */
    public static void charge(ServerPlayer caster, String animationPrefix)
    {
        if (caster != null && animationPrefix != null && ModList.get().isLoaded("dragonminez"))
            MoveAnimationImpl.play(caster, animationPrefix + "_cast", true);
    }

    /** Play the release ({@code <prefix>_fire}), not held, so it runs once and ends. */
    public static void fire(ServerPlayer caster, String animationPrefix)
    {
        if (caster != null && animationPrefix != null && ModList.get().isLoaded("dragonminez"))
            MoveAnimationImpl.play(caster, animationPrefix + "_fire", false);
    }

    /**
     * Hold a RAW clip, with no {@code _cast} suffix appended. For animations that are a single clip rather than a
     * ki technique's cast/fire pair, such as DMZ's {@code base.block} pose.
     */
    public static void chargeRaw(ServerPlayer caster, String clip)
    {
        if (caster != null && clip != null && ModList.get().isLoaded("dragonminez"))
            MoveAnimationImpl.play(caster, clip, true);
    }

    /** Play a RAW clip once (not held), with no {@code _fire} suffix appended. See {@link #chargeRaw}. */
    public static void fireRaw(ServerPlayer caster, String clip)
    {
        if (caster != null && clip != null && ModList.get().isLoaded("dragonminez"))
            MoveAnimationImpl.play(caster, clip, false);
    }

    /** Drop any held pose. */
    public static void stop(ServerPlayer caster)
    {
        if (caster != null && ModList.get().isLoaded("dragonminez"))
            MoveAnimationImpl.stop(caster);
    }
}
