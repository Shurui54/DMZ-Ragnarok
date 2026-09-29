package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;

/** Guard for reading DMZ ki projectile fields; {@link DmzProjectileImpl} is the only class naming DMZ types. */
public final class DmzProjectile
{
    private DmzProjectile() {}

    /** The technique id that fired this projectile, or null when it is not a DMZ ki projectile. */
    public static String techniqueIdOf(Entity entity)
    {
        if (entity == null || !ModList.get().isLoaded("dragonminez"))
            return null;
        return DmzProjectileImpl.techniqueIdOf(entity);
    }

    /** The entity that fired it, or null. */
    public static Entity ownerOf(Entity entity)
    {
        if (entity == null || !ModList.get().isLoaded("dragonminez"))
            return null;
        return DmzProjectileImpl.ownerOf(entity);
    }
}
