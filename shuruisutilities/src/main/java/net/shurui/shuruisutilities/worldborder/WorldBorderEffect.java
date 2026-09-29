package net.shurui.shuruisutilities.worldborder;

import net.shurui.shuruisutilities.core.commands.registration.SUCommandParsingException;
import net.shurui.shuruisutilities.util.events.player.PlayerMoveEvent;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

public abstract class WorldBorderEffect
{

    protected int triggerDistance = 0;

    public WorldBorderEffect()
    {
    }

    public WorldBorderEffect(int triggerDistance)
    {
        this.triggerDistance = triggerDistance;
    }

    public double getTriggerDistance()
    {
        return triggerDistance;
    }

    /** Set by {@code /wb ... effect add} (the command lives in the Ragnarok Key, outside this package). */
    public void setTriggerDistance(int triggerDistance)
    {
        this.triggerDistance = triggerDistance;
    }

    public abstract void provideArguments(CommandContext<CommandSourceStack> ctx) throws SUCommandParsingException;

    public abstract String getSyntax();

    public void activate(WorldBorder border, ServerPlayer player)
    {
        /* do nothing */
    }

    public void deactivate(WorldBorder border, ServerPlayer player)
    {
        /* do nothing */
    }

    public void tick(WorldBorder border, ServerPlayer player)
    {
        /* do nothing */
    }

    public void playerMove(WorldBorder border, PlayerMoveEvent event)
    {
        /* do nothing */
    }

}
