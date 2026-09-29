package net.shurui.shuruisutilities.worldborder.effect;

import net.shurui.shuruisutilities.core.commands.registration.SUCommandParsingException;
import net.shurui.shuruisutilities.util.PlayerInfo;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.shurui.shuruisutilities.worldborder.WorldBorder;
import net.shurui.shuruisutilities.worldborder.WorldBorderEffect;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

/**
 * Expected syntax: <interval> (in seconds)
 */
public class EffectKick extends WorldBorderEffect
{

    private int timeout = 0;

    @Override
    public void provideArguments(CommandContext<CommandSourceStack> ctx) throws SUCommandParsingException
    {
        timeout = IntegerArgumentType.getInteger(ctx, "timeout");
    }

    @Override
    public void activate(WorldBorder border, ServerPlayer player)
    {
        if (!player.getServer().isDedicatedServer())
        {
            LoggingHandler.sulog.warn("[WorldBorder] Kick effect is not supported on integrated servers!");
            return;
        }
        ChatOutputHandler.chatError(player.createCommandSourceStack(), "You have %s seconds to return inside the world border, or you will get kicked!", timeout);
        PlayerInfo pi = PlayerInfo.get(player);
        pi.startTimeout(this.getClass().getName(), timeout * 1000L);
    }

    @Override
    public void tick(WorldBorder border, ServerPlayer player)
    {
        PlayerInfo pi = PlayerInfo.get(player);
        if (pi.checkTimeout(this.getClass().getName()))
        {
            player.connection.disconnect(Component.literal("You left the world border"));
            // For safety restart the timeout
            pi.startTimeout(this.getClass().getName(), timeout);
        }
    }

    public String toString()
    {
        return "kick trigger: " + triggerDistance + "interval: " + timeout;
    }

    public String getSyntax()
    {
        return "<interval>";
    }

}
