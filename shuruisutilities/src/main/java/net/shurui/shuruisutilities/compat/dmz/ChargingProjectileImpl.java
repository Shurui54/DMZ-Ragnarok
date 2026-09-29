package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.init.entities.ki.AbstractKiProjectile;
import com.dragonminez.common.init.entities.ki.KiBlastEntity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** The only class naming DMZ's projectile for charge cleanup. */
final class ChargingProjectileImpl
{
    private ChargingProjectileImpl() {}

    /**
     * Remove the ball a player is charging.
     *
     * <p>Needed because suppressing DMZ's cast stops it CONSUMING the charging projectile, which DMZ would normally
     * have turned into the fired attack. Left alone it just sits on the caster forever - the orb stuck at their feet.
     */
    static int discardChargingFor(ServerPlayer player)
    {
        int removed = 0;
        try
        {
            if (!(player.level() instanceof ServerLevel level))
                return 0;
            for (Entity entity : level.getEntities(player, player.getBoundingBox().inflate(12.0),
                    e -> e instanceof AbstractKiProjectile))
            {
                AbstractKiProjectile projectile = (AbstractKiProjectile) entity;
                if (projectile.getOwner() != player)
                    continue;
                // Only the ball still being charged; anything already fired is a live attack and must be left alone.
                if (projectile.isFiring())
                    continue;
                projectile.discard();
                removed++;
            }
        }
        catch (Throwable ignored)
        {
        }
        return removed;
    }

    /**
     * True when this entity is a ki projectile that is STILL BEING CHARGED rather than fired.
     *
     * <p>{@code isFiring()} is DMZ's own line between the orb a caster is holding and a live attack in flight,
     * and it is the same test {@link #discardChargingFor} uses to avoid deleting somebody's actual shot.</p>
     */
    static boolean isStillCharging(Entity entity)
    {
        try
        {
            return entity instanceof AbstractKiProjectile projectile && !projectile.isFiring();
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    /**
     * Centre an area move's charging orb on its caster and swell it past full charge.
     *
     * <h2>Why the orb has to be re-centred</h2>
     * {@code setupKiBlastPlayer} offsets the ball by {@code calcForwardOffset} = half the owner's width plus HALF THE
     * BALL'S SIZE, and drops it by {@code calcCenterOffsetY} = minus half its size. Those are the right numbers for a
     * ball held out in front of you, and completely wrong for one that is meant to engulf you: at the diameters an
     * area move now uses, they shove the orb ten blocks forward and ten blocks down, well clear of the effect it is
     * supposed to be showing. Zeroing all three puts it back on the caster.
     *
     * <h2>Overcharge</h2>
     * DMZ lets a held charge run to 175%. Past 100 the orb keeps growing, so holding it visibly means something
     * rather than only changing a damage number.
     */
    static void engulfCaster(ServerPlayer player, float baseDiameter, float chargePercent)
    {
        try
        {
            if (!(player.level() instanceof ServerLevel level))
                return;
            float overcharge = 1.0f + 0.5f * Math.max(0.0f, chargePercent - 100.0f) / 75.0f;
            float diameter = baseDiameter * overcharge;
            for (Entity entity : level.getEntities(player, player.getBoundingBox().inflate(16.0),
                    e -> e instanceof KiBlastEntity))
            {
                KiBlastEntity blast = (KiBlastEntity) entity;
                if (blast.getOwner() != player || blast.isFiring())
                    continue;
                if (blast.getSize() != diameter)
                    blast.setSize(diameter);
                // THE ORB IS DRAWN HALF A BOUNDING BOX ABOVE THE ENTITY. KiProjectileRenderer translates up by
                // getBbHeight() / 2 before it scales, and a ki blast's box is 0.8 blocks per unit of size - so a
                // twenty-wide orb was being drawn eight blocks over the caster's head. Offsetting the entity down by
                // the same amount is what puts the drawn ball back on the player it belongs to.
                blast.setCastOffsets(0.0f, -blast.getBbHeight() * 0.5f, 0.0f);
            }
        }
        catch (Throwable ignored)
        {
        }
    }
}
