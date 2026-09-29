package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.init.entities.ki.AbstractKiProjectile;

import net.minecraft.world.entity.Entity;

/** The only class naming DMZ's ki projectile; {@link DmzProjectile} is the guard in front of it. */
final class DmzProjectileImpl
{
    private DmzProjectileImpl() {}

    static String techniqueIdOf(Entity entity)
    {
        try
        {
            return entity instanceof AbstractKiProjectile p ? p.getTechniqueId() : null;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    static Entity ownerOf(Entity entity)
    {
        try
        {
            return entity instanceof AbstractKiProjectile p ? p.getOwner() : null;
        }
        catch (Throwable t)
        {
            return null;
        }
    }
}
