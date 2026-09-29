package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;

import net.minecraft.world.entity.player.Player;

/**
 * The only class here that names a DragonMineZ type. Reached solely through {@link DmzKiWeaponType}.
 */
final class DmzKiWeaponTypeImpl
{
    private DmzKiWeaponTypeImpl() {}

    static String of(Player player)
    {
        try
        {
            if (StatsCapability.INSTANCE == null)
                return null;
            StatsData data = player.getCapability(StatsCapability.INSTANCE).resolve().orElse(null);
            if (data == null || data.getStatus() == null)
                return null;
            return data.getStatus().getKiWeaponType();
        }
        catch (Throwable t)
        {
            return null;
        }
    }
}
