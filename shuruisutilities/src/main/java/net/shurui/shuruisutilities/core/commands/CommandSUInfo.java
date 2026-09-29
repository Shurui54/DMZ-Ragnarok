package net.shurui.shuruisutilities.core.commands;

import org.apache.commons.lang3.StringUtils;

import net.shurui.shuruisutilities.commons.BuildInfo;
import net.shurui.shuruisutilities.core.mixin.SUMixinConfig;
import net.shurui.shuruisutilities.core.moduleLauncher.ModuleLauncher;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;
import org.jetbrains.annotations.NotNull;

public class CommandSUInfo extends ShuruisUtilitiesCommandBuilder
{

    public CommandSUInfo(boolean enabled)
    {
        super(enabled);
    }

    @Override
    public @NotNull String getPrimaryAlias()
    {
        return "rginfo";
    }

    // Legacy hidden alias: /suinfo still works, folded onto command.rginfo and hidden from the client tree.
    @Override
    protected String[] getDefaultSecondaryAliases()
    {
        return new String[] { "suinfo" };
    }

    @Override
    public DefaultPermissionLevel getPermissionLevel()
    {
        return DefaultPermissionLevel.OP;
    }

    @Override
    public boolean canConsoleUseCommand()
    {
        return true;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> setExecution()
    {
        return baseBuilder
                .then(Commands.literal("reload").executes(CommandContext -> execute(CommandContext, "reload")))
                .then(Commands.literal("modules").executes(CommandContext -> execute(CommandContext, "modules")))
                .then(Commands.literal("mixin").executes(CommandContext -> execute(CommandContext, "mixin")))
                .then(Commands.literal("edit").executes(CommandContext -> execute(CommandContext, "edit")))
                .executes(CommandContext -> execute(CommandContext, "blank"));
    }

    @Override
    public int execute(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException
    {
        if (params.equals("blank"))
        {
            ChatOutputHandler.chatNotification(ctx.getSource(), "Running ShuruisUtilities %s (%s)-%s", BuildInfo.getCurrentVersion(), BuildInfo.getBuildHash(), BuildInfo.getBuildType());
            if (BuildInfo.isOutdated())
                ChatOutputHandler.chatError(ctx.getSource(),
                        String.format("Outdated! Latest build is #%s", BuildInfo.getLatestVersion()));
            ChatOutputHandler.chatConfirmation(ctx.getSource(), "/suinfo reload: Reload SU configs");
            ChatOutputHandler.chatConfirmation(ctx.getSource(), "/suinfo modules: Show loaded modules");
            ChatOutputHandler.chatConfirmation(ctx.getSource(), "/suinfo mixin: Show loaded mixin patches");
            return Command.SINGLE_SUCCESS;
        }

        switch (params)
        {
        case "reload":
            CommandSuReload.reload(ctx.getSource());
            return Command.SINGLE_SUCCESS;
        case "modules":
            ChatOutputHandler.chatConfirmation(ctx.getSource(),
                    "Loaded SU modules: " + StringUtils.join(ModuleLauncher.getModuleList(), ", "));
            return Command.SINGLE_SUCCESS;
        case "mixin":
            ChatOutputHandler.chatNotification(ctx.getSource(), "Injected patches:");
            for (String patch : SUMixinConfig.getInjectedPatches())
                ChatOutputHandler.chatConfirmation(ctx.getSource(), "- " + patch);
            return Command.SINGLE_SUCCESS;
        case "edit":
            if (ctx.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)
                net.shurui.shuruisutilities.hub.HubServer.openAdmin(sp);
            else
                ChatOutputHandler.chatError(ctx.getSource(), "This command must be run by a player.");
            return Command.SINGLE_SUCCESS;
        }
        return Command.SINGLE_SUCCESS;
    }
}
