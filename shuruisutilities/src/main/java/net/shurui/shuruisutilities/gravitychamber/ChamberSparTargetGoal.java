package net.shurui.shuruisutilities.gravitychamber;

import java.util.EnumSet;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Keeps a {@link ChamberSparEntity} locked onto its one bound opponent. Sits at top priority in the dummy's target
 * selector, above the inherited saga target goals, so the dummy fights the sparring player and no one else. If the
 * opponent logs off or leaves the level the goal simply finds no target, and the manager ends the bout on its own.
 */
public class ChamberSparTargetGoal extends Goal
{
    private final ChamberSparEntity dummy;

    public ChamberSparTargetGoal(ChamberSparEntity dummy)
    {
        this.dummy = dummy;
        this.setFlags(EnumSet.of(Flag.TARGET));
    }

    private ServerPlayer opponent()
    {
        if (dummy.getOpponent() == null || dummy.getServer() == null)
        {
            return null;
        }
        ServerPlayer player = dummy.getServer().getPlayerList().getPlayer(dummy.getOpponent());
        if (player == null || !player.isAlive() || player.level() != dummy.level())
        {
            return null;
        }
        return player;
    }

    @Override
    public boolean canUse()
    {
        return opponent() != null;
    }

    @Override
    public boolean canContinueToUse()
    {
        ServerPlayer player = opponent();
        return player != null && player.equals(dummy.getTarget());
    }

    @Override
    public void start()
    {
        dummy.setTarget(opponent());
    }

    @Override
    public void stop()
    {
        dummy.setTarget(null);
    }
}
