package net.shurui.shuruisutilities.core.commands;

import org.jetbrains.annotations.NotNull;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;
import net.shurui.shuruisutilities.hub.HubServer;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

/**
 * The one command that opens Shurui's Utilities' GUIs. {@code /rggui} opens the player tool menu (available
 * to everyone; entries are permission-filtered server-side); {@code /rggui admin} opens the admin editor hub
 * (operators only). The player menu can also be opened with the {@code Open menu} keybind.
 */
public class CommandSUGui extends ShuruisUtilitiesCommandBuilder
{
    public CommandSUGui(boolean enabled)
    {
        super(enabled);
    }

    @Override
    public @NotNull String getPrimaryAlias()
    {
        return "rggui";
    }

    // Legacy hidden alias: /sugui still opens the menu. Registered as a full command (preserving the op gate on the
    // `admin` subcommand), folded onto command.rggui, and kept out of the client tree by SUCommandManager.hiddenLegacyRoots.
    @Override
    protected String[] getDefaultSecondaryAliases()
    {
        return new String[] { "sugui" };
    }

    @Override
    public DefaultPermissionLevel getPermissionLevel()
    {
        return DefaultPermissionLevel.ALL;
    }

    @Override
    public boolean canConsoleUseCommand()
    {
        return false;
    }

    @Override
    public void registerExtraPermissions()
    {
        if (APIRegistry.perms == null)
            return;
        APIRegistry.perms.registerPermission(HubServer.GUI_MENU, DefaultPermissionLevel.ALL, "Open the player menu GUI");
        APIRegistry.perms.registerPermission(HubServer.GUI_ADMIN, DefaultPermissionLevel.OP, "Open the admin editor hub");
        APIRegistry.perms.registerPermission("su.gui.permissions", DefaultPermissionLevel.OP, "Open the permissions editor");
        APIRegistry.perms.registerPermission("su.gui.guilds", DefaultPermissionLevel.OP, "Open the guild admin editor");
        APIRegistry.perms.registerPermission("su.gui.economy", DefaultPermissionLevel.OP, "Open the economy editor");
        APIRegistry.perms.registerPermission("su.gui.holograms", DefaultPermissionLevel.OP, "Open the hologram editor");
        APIRegistry.perms.registerPermission("su.gui.crates", DefaultPermissionLevel.OP, "Open the crate editor");
        APIRegistry.perms.registerPermission("su.gui.events", DefaultPermissionLevel.OP, "Open the event editor");
        APIRegistry.perms.registerPermission("su.gui.portals", DefaultPermissionLevel.OP, "Open the portal editor");
        APIRegistry.perms.registerPermission("su.gui.worldborder", DefaultPermissionLevel.OP, "Open the world border editor");
        APIRegistry.perms.registerPermission("su.gui.chat", DefaultPermissionLevel.OP, "Open the chat editor");
        APIRegistry.perms.registerPermission("su.gui.protection", DefaultPermissionLevel.OP, "Open the protection editor");
        APIRegistry.perms.registerPermission("su.gui.regions", DefaultPermissionLevel.OP, "Open the regions editor");
        APIRegistry.perms.registerPermission("su.gui.banitem", DefaultPermissionLevel.OP, "Open the banned-items editor");
        APIRegistry.perms.registerPermission("su.gui.prestige", DefaultPermissionLevel.OP, "Open the prestige editor");
        APIRegistry.perms.registerPermission("su.gui.shrines", DefaultPermissionLevel.OP, "Open the shrine editor");
        APIRegistry.perms.registerPermission("su.gui.tasks", DefaultPermissionLevel.OP, "Open the task pool editor");
        APIRegistry.perms.registerPermission("su.gui.cosmetics", DefaultPermissionLevel.OP, "Open the cosmetic catalogue editor");
        APIRegistry.perms.registerPermission(HubServer.GUI_SHADOWDRAGONS, DefaultPermissionLevel.OP, "Open the boss arena editor");
        // No node for the bounty board: it is gated on su.bounty (ModuleBounty.PERM), the same node the /bounty
        // commands use. A second, board-only node was one more thing to grant, and anywhere it was missed the
        // menu entry vanished while the commands kept working.
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> setExecution()
    {
        return baseBuilder
                .then(Commands.literal("admin").requires(s -> s.hasPermission(2))
                        .executes(ctx -> execute(ctx, "admin")))
                .executes(ctx -> execute(ctx, "player"));
    }

    @Override
    public int execute(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException
    {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
        {
            ChatOutputHandler.chatError(ctx.getSource(), "This command must be run by a player.");
            return Command.SINGLE_SUCCESS;
        }
        if (params.equals("admin"))
            HubServer.openAdmin(player);
        else
            HubServer.openPlayer(player);
        return Command.SINGLE_SUCCESS;
    }
}
