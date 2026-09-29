package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

/**
 * Which ki weapon a player currently has summoned, as its raw DragonMineZ type string.
 *
 * <p>Guard half of the usual pair; {@link DmzKiWeaponTypeImpl} is the only class naming a DMZ type
 * (the optional-dependency pattern). Returns null when there is no DMZ, no character, or nothing summoned.
 */
public final class DmzKiWeaponType
{
    private DmzKiWeaponType() {}

    public static String of(Player player)
    {
        if (player == null || !ModList.get().isLoaded("dragonminez"))
            return null;
        return DmzKiWeaponTypeImpl.of(player);
    }
}
