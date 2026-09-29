package net.shurui.shuruisutilities.util.selections;

import net.shurui.shuruisutilities.compat.worldedit.WEIntegration;
import net.shurui.shuruisutilities.core.commands.ShuruisUtilitiesCommandBuilder;
import net.shurui.shuruisutilities.core.moduleLauncher.ModuleLauncher;
import net.shurui.shuruisutilities.util.PlayerInfo;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

//Depreciated

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.ChatFormatting;
import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;
import org.jetbrains.annotations.NotNull;

public class CommandWand extends ShuruisUtilitiesCommandBuilder
{

    public CommandWand(boolean enabled)
    {
        super(enabled);
    }

    @Override
    public @NotNull String getPrimaryAlias()
    {
        return "SELwand";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> setExecution()
    {
        return baseBuilder
                .then(Commands.literal("unbind").executes(CommandContext -> execute(CommandContext, "unbind")))
                .then(Commands.literal("rebind").executes(CommandContext -> execute(CommandContext, "bind")))
                .executes(CommandContext -> execute(CommandContext, "bind"));
    }

    @Override
    public int processCommandPlayer(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException
    {
        if (ModuleLauncher.getModuleList().contains(WEIntegration.weModule))
        {
            ChatOutputHandler.chatNotification(ctx.getSource(),
                    "WorldEdit is installed. Please use WorldEdit selections (//wand, //set, etc)");
            ChatOutputHandler.chatNotification(ctx.getSource(),
                    "Please refer to http://wiki.sk89q.com/wiki/WorldEdit/Selection for more info.");
            return Command.SINGLE_SUCCESS;
        }

        // Get the wand item (or hands)
        Item wandItem;
        String wandId, wandName;
        Player player = getServerPlayer(ctx.getSource());
        if (getServerPlayer(ctx.getSource()).getMainHandItem() != null)
        {
            wandName = player.getMainHandItem().getDisplayName().getString();
            wandItem = player.getMainHandItem().getItem();
            wandId = ForgeRegistries.ITEMS.getKey(wandItem).getPath();
        }
        else
        {
            wandName = "your hands";
            wandId = "hands";
        }

        PlayerInfo info = PlayerInfo.get(player.getGameProfile().getId());

        // Check for unbind
        if ((params.equals("unbind")) && ((info.isWandEnabled() && info.getWandID().equals(wandId))))
        {
            ChatOutputHandler.sendMessage(ctx.getSource(),
                    ChatFormatting.LIGHT_PURPLE + "Wand unbound from " + wandName);
            info.setWandEnabled(false);

        }
        else
        {
            // Bind wand
            info.setWandEnabled(true);
            info.setWandID(wandId);
            ChatOutputHandler.chatConfirmation(ctx.getSource(), wandId);
            ChatOutputHandler.chatConfirmation(ctx.getSource(), "Wand bound to " + wandName);
        }
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
