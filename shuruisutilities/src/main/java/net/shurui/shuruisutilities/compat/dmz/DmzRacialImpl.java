package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.RaceCharacterConfig;
import com.dragonminez.common.stats.StatsData;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.compat.DmzBridge;

/** The only class naming DMZ types for reading a race's racial ability id. See {@link DmzRacial}. */
final class DmzRacialImpl
{
    private DmzRacialImpl() {}

    static String idOf(ServerPlayer player)
    {
        try
        {
            StatsData stats = DmzBridge.stats(player);
            if (stats == null || stats.getCharacter() == null)
                return null;
            String race = stats.getCharacter().getRace();
            if (race == null || race.isBlank())
                return null;
            RaceCharacterConfig config = ConfigManager.getRaceCharacter(race);
            String racial = config == null ? null : config.getRacialSkill();
            return racial == null || racial.isBlank() ? null : racial.trim();
        }
        catch (Throwable t)
        {
            return null;
        }
    }
}
