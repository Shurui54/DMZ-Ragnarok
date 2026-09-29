package net.shurui.shuruisutilities.compat.dungeons;

import java.lang.reflect.Method;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import net.shurui.shuruisutilities.compat.dmz.DragonBallSets;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * "Is this player somewhere, and carrying something, that makes them fair game?"
 *
 * <p>A dungeon floor is where the dragon balls are worth taking off somebody, so a player who walks in holding
 * one has opted into being attacked whatever their own PvP toggle says. Carrying it is the consent: nobody is
 * made hittable for being in a dungeon, only for being in a dungeon WITH a ball on them.
 *
 * <p>Reflection into the dungeons tree, the same shape as {@code TournamentPvpBridge} and for the same reason:
 * no compile edge and no mods.toml edge from SU onto an addon, so this file is safe to load whether or not the
 * dungeons module is there. Missing or broken dungeons module means the answer is always false, which leaves
 * PvP exactly where the player's own toggle put it.
 *
 * <p>Fails CLOSED on every path. Forcing PvP ON is the dangerous direction to be wrong in, so anything this
 * cannot positively establish reads as "no".
 */
public final class DungeonPvpBridge
{
    private DungeonPvpBridge() {}

    private static boolean resolved;
    private static Method isAnyDungeonM;   // DungeonDimensions.isAnyDungeon(Level) (static)
    private static boolean warnedOnce;

    /**
     * True when this player is standing in any dungeon dimension while carrying at least one dragon ball.
     *
     * <p>The whole inventory counts, not just the hands: a ball stuffed in the back of a backpack is still a ball
     * they are walking out of the dungeon with, and letting it be hidden by moving it off the hotbar would make
     * the rule trivially avoidable.
     */
    public static boolean carryingBallInDungeon(Player player)
    {
        if (player == null || player.level() == null)
            return false;
        if (!inDungeon(player))
            return false;
        return carryingDragonBall(player);
    }

    /** True when the player's current dimension is the legacy dungeon dim or any themed floor dim. */
    public static boolean inDungeon(Player player)
    {
        resolve();
        if (isAnyDungeonM == null || player == null || player.level() == null)
            return false;
        try
        {
            Object r = isAnyDungeonM.invoke(null, player.level());
            return r instanceof Boolean b && b;
        }
        catch (Throwable t)
        {
            warnOnce(t);
            return false;
        }
    }

    /** True when any slot of the player's inventory holds a dragon ball (DMZ's or one of ours). */
    public static boolean carryingDragonBall(Player player)
    {
        try
        {
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++)
            {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && DragonBallSets.isDragonBall(stack))
                    return true;
            }
            return false;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static synchronized void resolve()
    {
        if (resolved)
            return;
        resolved = true;
        try
        {
            Class<?> c = Class.forName("net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonDimensions");
            isAnyDungeonM = c.getMethod("isAnyDungeon", net.minecraft.world.level.Level.class);
        }
        catch (Throwable ignored)
        {
            isAnyDungeonM = null;
        }
    }

    private static void warnOnce(Throwable t)
    {
        if (warnedOnce)
            return;
        warnedOnce = true;
        LoggingHandler.sulog.warn("Dungeon dimension query failed; dungeon dragon-ball PvP force-enable disabled: "
                + t);
    }
}
