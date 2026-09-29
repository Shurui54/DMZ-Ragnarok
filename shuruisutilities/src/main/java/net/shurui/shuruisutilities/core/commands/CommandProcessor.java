package net.shurui.shuruisutilities.core.commands;

import net.shurui.shuruisutilities.api.permissions.SUPermissions;
import net.shurui.shuruisutilities.util.CommandUtils;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.CommandSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.rcon.RconConsoleSource;
import net.minecraft.world.level.BaseCommandBlock;

public class CommandProcessor extends CommandUtils
{
    // Command processing

    public int execute(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException
    {
        // NOTE. This used to refuse, silently, any command reaching it from a CustomNPCs script. Every SU command
        // goes through this one method, so that was the whole command surface: an NPC script running /warp, /eco,
        // /guild or anything else of ours did nothing at all and said nothing about why. That breaks the server
        // owner's own content, and it protects nothing that permissions do not already cover, since a script runs
        // as whatever source it was given and a command block, which is allowed, can do exactly the same things.
        // Removed 2026-08-30.
        try
        {
            if (params == null)
            {
                ChatOutputHandler.chatError(ctx.getSource(), "Command sent with null args Please report this!");
                LoggingHandler.sulog
                        .error("Command Sent with null args by: " + ctx.getSource().getDisplayName().getString());
                LoggingHandler.sulog.error("Please report this to the devs");
                return Command.SINGLE_SUCCESS;
            }
            CommandSource source = CommandUtils.GetSource(ctx.getSource());
            if (source instanceof ServerPlayer)
            {
                processCommandPlayer(ctx, params);
            }
            else if (source instanceof BaseCommandBlock)
            {
                processCommandBlock(ctx, params);
            }
            else if (source instanceof RconConsoleSource)
            {
                processCommandConsole(ctx, params);
            }
            else
            {
                processCommandConsole(ctx, params);
            }
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.error("Command Exception: " + e.getMessage());
            e.printStackTrace();
            if (e instanceof CommandSyntaxException)
            {
                throw new CommandSyntaxException(((CommandSyntaxException) e).getType(),
                        ((CommandSyntaxException) e).getRawMessage(), ((CommandSyntaxException) e).getInput(),
                        ((CommandSyntaxException) e).getCursor());
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    public int processCommandPlayer(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException
    {
        ChatOutputHandler.chatError(ctx.getSource(), "This command cannot be used as player");
        return Command.SINGLE_SUCCESS;
    }

    public int processCommandConsole(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException
    {
        ChatOutputHandler.chatError(ctx.getSource(), SUPermissions.MSG_NO_CONSOLE_COMMAND);
        return Command.SINGLE_SUCCESS;
    }

    public int processCommandBlock(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException
    {
        return processCommandConsole(ctx, params);
    }
}
