package net.shurui.shuruisutilities.compat.dmz;


import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Does this player have DragonMineZ's flight skill?
 *
 * <p>Used by the teleport path. Landing someone on the nearest solid ground is right for a player who would
 * otherwise be buried or dropped into a void, but it is wrong for someone who can fly: teleporting to a
 * friend hovering over a city should put you next to THEM, not on a roof a hundred blocks below. So a flier
 * keeps the requested position and everyone else is still made safe.
 *
 * <p>Every lookup is wrapped. DragonMineZ being present is not proof its API matches this build (the
 * standing workspace rule), so any failure answers "cannot fly", which falls back to the safe-landing
 * behaviour that was already there rather than throwing inside a teleport.
 */
public final class DmzFlight
{
    /** DMZ's own name for the skill, as read from its MovementSkillsHandler. */
    private static final String FLY_SKILL = "fly";

    // latched so a version drift warns once rather than on every teleport.
    private static boolean warned;

    private DmzFlight()
    {
    }

    /** True only if DMZ is present, readable, and this player actually owns the flight skill. */
    public static boolean canFly(ServerPlayer player)
    {
        if (player == null || !ModList.get().isLoaded("dragonminez"))
        {
            return false;
        }
        try
        {
            // DMZ is a mandatory dependency, so its capability API is compiled against directly. (This used to reach a
            // reflective com.dragonminez.common.stats.StatsHelper.getStats, which neither 2.1.3 nor 2.2 has, so the
            // check always fell back to "no flight".) A failure still degrades to "safe-land as before".
            return com.dragonminez.common.stats.StatsProvider.get(com.dragonminez.common.stats.StatsCapability.INSTANCE, player)
                    .resolve()
                    .map(stats -> stats.getSkills() != null && stats.getSkills().hasSkill(FLY_SKILL))
                    .orElse(false);
        }
        catch (Throwable t)
        {
            if (!warned)
            {
                warned = true;
                LoggingHandler.sulog.warn("[Teleport] Could not read DragonMineZ flight skill; teleports will "
                        + "safe-land everyone as before. ({})", t.toString());
            }
            return false;
        }
    }
}
