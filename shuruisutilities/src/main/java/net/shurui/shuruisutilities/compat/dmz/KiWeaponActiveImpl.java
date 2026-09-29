package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.combat.logic.player.PlayerAttackHelper;

import net.minecraft.world.entity.player.Player;

/**
 * The only class here that names a DragonMineZ type for the ki-weapon-drawn check. Reached solely through
 * {@link KiWeaponActive}.
 */
final class KiWeaponActiveImpl
{
    private KiWeaponActiveImpl() {}

    static boolean isActive(Player player)
    {
        try
        {
            return PlayerAttackHelper.isKiWeaponActive(player);
        }
        catch (Throwable t)
        {
            return false;
        }
    }
}
