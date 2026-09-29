package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.skills.Skills;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.compat.DmzBridge;

/** The only class naming DMZ types for live flight state. See {@link DmzFlightState}. */
final class DmzFlightStateImpl
{
    private DmzFlightStateImpl() {}

    /** DMZ's own id for the skill, as used by every one of its flight handlers. */
    private static final String FLY = "fly";

    static boolean isFlying(ServerPlayer player)
    {
        try
        {
            Skills skills = skills(player);
            return skills != null && skills.isSkillActive(FLY);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * At the skill's ceiling.
     *
     * <p>Compared against DMZ's own {@code getMaxSkillLevel} rather than a number of ours: the maximum is DMZ's to
     * decide and it moves with their config, so hard-coding one here would silently mean "maxed" stopped meaning
     * maxed the first time they retuned it.
     */
    static boolean isFlightMaxed(ServerPlayer player)
    {
        try
        {
            Skills skills = skills(player);
            if (skills == null || !skills.hasSkill(FLY))
                return false;
            int max = skills.getMaxSkillLevel(FLY);
            return max > 0 && skills.getSkillLevel(FLY) >= max;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static Skills skills(ServerPlayer player)
    {
        StatsData stats = DmzBridge.stats(player);
        return stats == null ? null : stats.getSkills();
    }
}
