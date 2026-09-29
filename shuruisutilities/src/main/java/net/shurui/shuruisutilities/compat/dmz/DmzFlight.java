package net.shurui.shuruisutilities.compat.dmz;

import java.lang.reflect.Method;

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
            // Reached reflectively rather than compiled against. DMZ being present is not proof its API
            // matches this build, and a teleport is the last place that should throw: a rename here has to
            // degrade to "safe-land as before", not break the command.
            Class<?> helper = Class.forName("com.dragonminez.common.stats.StatsHelper");
            Method get = helper.getMethod("getStats", net.minecraft.world.entity.player.Player.class);
            Object stats = get.invoke(null, player);
            if (stats == null)
            {
                return false;
            }
            Object skills = stats.getClass().getMethod("getSkills").invoke(stats);
            if (skills == null)
            {
                return false;
            }
            Object has = skills.getClass().getMethod("hasSkill", String.class).invoke(skills, FLY_SKILL);
            return Boolean.TRUE.equals(has);
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
