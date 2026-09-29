package net.shurui.shuruisutilities.core.commands;

import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;
import org.jetbrains.annotations.NotNull;

public class CommandSUWorldInfo extends ShuruisUtilitiesCommandBuilder
{

    public CommandSUWorldInfo(boolean enabled)
    {
        super(enabled);
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> setExecution()
    {
        return baseBuilder.executes(CommandContext -> execute(CommandContext, "blank"));
    }

    @Override
    public int execute(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException
    {
        ChatOutputHandler.chatNotification(ctx.getSource(), "Showing all world provider names:");
        for (Level world : ServerLifecycleHooks.getCurrentServer().getAllLevels())
        {
            ChatOutputHandler.chatNotification(ctx.getSource(), "%s - %s", world.dimension().location().getPath(), world.dimension().location().toString());
        }
        return Command.SINGLE_SUCCESS;
    }

    @Override
    public @NotNull String getPrimaryAlias()
    {
        return "rgworldinfo";
    }

    // Legacy hidden alias: /suworldinfo still works, folded onto command.rgworldinfo and hidden from the client tree.
    @Override
    protected String[] getDefaultSecondaryAliases()
    {
        return new String[] { "suworldinfo" };
    }

    @Override
    public boolean canConsoleUseCommand()
    {
        return true;
    }

    @Override
    public DefaultPermissionLevel getPermissionLevel()
    {
        return DefaultPermissionLevel.OP;
    }
}
