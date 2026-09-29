package net.shurui.shuruisutilities.worldborder.effect;

import net.shurui.shuruisutilities.core.commands.registration.SUCommandParsingException;
import net.shurui.shuruisutilities.core.moduleLauncher.ModuleLauncher;
import net.shurui.shuruisutilities.util.PlayerInfo;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.worldborder.WorldBorder;
import net.shurui.shuruisutilities.worldborder.WorldBorderEffect;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Expected syntax: <interval> <message>
 */
public class EffectMessage extends WorldBorderEffect
{

    public String message = "You left the worldborder. Please return!";

    public int interval = 6000;

    @Override
    public void provideArguments(CommandContext<CommandSourceStack> ctx) throws SUCommandParsingException
    {
        interval = IntegerArgumentType.getInteger(ctx, "interval");
        message = StringArgumentType.getString(ctx, "message");
    }

    @Override
    public void activate(WorldBorder border, ServerPlayer player)
    {
        if (interval <= 0)
            doEffect(player);
    }

    @Override
    public void tick(WorldBorder border, ServerPlayer player)
    {
        if (interval <= 0)
            return;
        PlayerInfo pi = PlayerInfo.get(player);
        if (pi.checkTimeout(this.getClass().getName()))
        {
            doEffect(player);
            pi.startTimeout(this.getClass().getName(), interval * 1000L);
        }
    }

    public void doEffect(ServerPlayer player)
    {
    	if(ModuleLauncher.getModuleList().contains("Chat")) {
    		ChatOutputHandler.chatError(player,
                    net.shurui.shuruisutilities.api.key.ChatHooks.get().processChatReplacements(
                            player.createCommandSourceStack(), message));
    		return;
    	}
        ChatOutputHandler.chatError(player, message);
    }

    @Override
    public String toString()
    {
        return "message trigger: " + triggerDistance + "interval: " + interval + " message: " + message;
    }

    @Override
    public String getSyntax()
    {
        return "<interval> <message>";
    }

}
