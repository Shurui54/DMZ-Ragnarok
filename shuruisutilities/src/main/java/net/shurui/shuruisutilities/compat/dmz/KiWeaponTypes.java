package net.shurui.shuruisutilities.compat.dmz;

import net.minecraftforge.fml.ModList;

/**
 * Guard for adding our ki weapon types to DMZ's combat config, which is what makes them real ki weapons rather than
 * items pretending to be.
 */
public final class KiWeaponTypes
{
    private KiWeaponTypes() {}

    /** @return how many new types were added. */
    public static int register()
    {
        return ModList.get().isLoaded("dragonminez") ? KiWeaponTypesImpl.register() : 0;
    }
}
