package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.skills.Skills;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.compat.DmzBridge;

/** The only class naming DMZ types for reading the ki weapon gate. */
final class KiWeaponSelectImpl
{
    private KiWeaponSelectImpl() {}

    /** DMZ's own ki weapon skill: the gate every ki weapon sits behind. */
    private static final String KI_WEAPON_SKILL = "kimanipulation";

    static boolean hasKiWeaponSkill(ServerPlayer player)
    {
        try
        {
            StatsData stats = DmzBridge.stats(player);
            if (stats == null)
                return false;
            Skills skills = stats.getSkills();
            return skills != null && skills.isSkillActive(KI_WEAPON_SKILL);
        }
        catch (Throwable t)
        {
            return false;
        }
    }
}
