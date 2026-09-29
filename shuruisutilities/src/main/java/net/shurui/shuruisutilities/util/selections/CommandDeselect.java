package net.shurui.shuruisutilities.util.selections;

import net.shurui.shuruisutilities.core.commands.ShuruisUtilitiesCommandBuilder;
import net.shurui.shuruisutilities.util.PlayerInfo;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;
import org.jetbrains.annotations.NotNull;

public class CommandDeselect extends ShuruisUtilitiesCommandBuilder
{

    public CommandDeselect(boolean enabled)
    {
        super(enabled);
    }

    @Override
    public @NotNull String getPrimaryAlias()
    {
        return "SELdesel";
    }

    @Override
    public String @NotNull [] getDefaultSecondaryAliases()
    {
        return new String[] { "/deselect", "/sel" };
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> setExecution()
    {
        return baseBuilder.executes(CommandContext -> execute(CommandContext, "blank"));
    }

    @Override
    public int processCommandPlayer(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException
    {
        PlayerInfo info = PlayerInfo.get(getServerPlayer(ctx.getSource()).getGameProfile().getId());
        info.setSel1(null);
        info.setSel2(null);
        SelectionHandler.sendUpdate(getServerPlayer(ctx.getSource()));
        ChatOutputHandler.chatConfirmation(ctx.getSource(), "Selection cleared.");
        return Command.SINGLE_SUCCESS;
    }

    @Override
    public boolean canConsoleUseCommand()
    {
        return false;
    }

    @Override
    public DefaultPermissionLevel getPermissionLevel()
    {
        return DefaultPermissionLevel.ALL;
    }
}
