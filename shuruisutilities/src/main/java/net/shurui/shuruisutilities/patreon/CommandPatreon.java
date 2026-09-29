package net.shurui.shuruisutilities.patreon;

import org.jetbrains.annotations.NotNull;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;
import net.shurui.shuruisutilities.core.commands.ShuruisUtilitiesCommandBuilder;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

/**
 * {@code /patreon link} starts the account-link flow (the player gets a clickable link). {@code /patreon status}
 * shows the player's current supporter tier. {@code /patreon unlink <player>} (admin) clears a player's cached
 * entitlement on this server. Every path is cleanly inert when the feature is unconfigured.
 */
public class CommandPatreon extends ShuruisUtilitiesCommandBuilder
{
    public CommandPatreon(boolean enabled)
    {
        super(enabled);
    }

    @Override
    public @NotNull String getPrimaryAlias()
    {
        return "patreon";
    }

    @Override
    public boolean canConsoleUseCommand()
    {
        return true;
    }

    @Override
    public DefaultPermissionLevel getPermissionLevel()
    {
        return DefaultPermissionLevel.ALL;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> setExecution()
    {
        return baseBuilder
                .then(Commands.literal("link").executes(ctx -> execute(ctx, "link")))
                .then(Commands.literal("claim")
                        .then(Commands.argument("code", StringArgumentType.greedyString())
                                .executes(ctx -> execute(ctx, "claim"))))
                .then(Commands.literal("status").executes(ctx -> execute(ctx, "status")))
                .then(Commands.literal("unlink").requires(s -> hasPermission(s, ModulePatreon.PERM_ADMIN, "patreon.unlink"))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> execute(ctx, "unlink"))))
                .executes(ctx -> execute(ctx, "help"));
    }

    private boolean guardConfigured(CommandContext<CommandSourceStack> ctx)
    {
        if (PatreonManager.isConfigured())
            return true;
        ChatOutputHandler.chatError(ctx.getSource(), "Patreon linking is not available on this server.");
        return false;
    }

    @Override
    public int processCommandPlayer(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException
    {
        ServerPlayer player = getServerPlayer(ctx.getSource());
        switch (params)
        {
            case "link":
            {
                if (!guardConfigured(ctx))
                    break;
                if (PatreonManager.isServerKeyed())
                {
                    // This server holds the key, so it can hand the player a ready-made one-click link.
                    player.sendSystemMessage(Component.translatable("message.dmz_ragnarok.core.patreon.link.requesting")
                            .withStyle(ChatFormatting.GRAY));
                    PatreonManager.startLink(player);
                }
                else
                {
                    // Everywhere else: send them to the browser to start it themselves, then claim the code in game.
                    PatreonManager.sendStartInstructions(player);
                }
                break;
            }
            case "claim":
            {
                if (!guardConfigured(ctx))
                    break;
                player.sendSystemMessage(Component.translatable("message.dmz_ragnarok.core.patreon.claim.working")
                        .withStyle(ChatFormatting.GRAY));
                PatreonManager.claim(player, StringArgumentType.getString(ctx, "code"));
                break;
            }
            case "status":
            {
                if (!guardConfigured(ctx))
                    break;
                sendStatus(player);
                // opportunistic refresh so a repeat /patreon status reflects the latest tier
                PatreonManager.refreshNow(player.getUUID());
                break;
            }
            case "unlink":
                return doUnlink(ctx);
            default:
                sendUsage(ctx);
                break;
        }
        return Command.SINGLE_SUCCESS;
    }

    @Override
    public int processCommandConsole(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException
    {
        switch (params)
        {
            case "unlink":
                return doUnlink(ctx);
            case "link":
            case "claim":
            case "status":
                ChatOutputHandler.chatError(ctx.getSource(), "Only a player can use /patreon " + params + ".");
                break;
            default:
                sendUsage(ctx);
                break;
        }
        return Command.SINGLE_SUCCESS;
    }

    private int doUnlink(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        boolean had = PatreonManager.clearLocal(target.getUUID());
        ChatOutputHandler.chatConfirmation(ctx.getSource(), had
                ? "Cleared " + target.getGameProfile().getName() + "'s cached Patreon tier."
                : target.getGameProfile().getName() + " had no cached Patreon tier.");
        return Command.SINGLE_SUCCESS;
    }

    private void sendStatus(ServerPlayer player)
    {
        String tier = PatreonManager.effectiveTier(player.getUUID());
        if (tier.isEmpty())
        {
            player.sendSystemMessage(Component.translatable("message.dmz_ragnarok.core.patreon.status.none")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        player.sendSystemMessage(Component.translatable("message.dmz_ragnarok.core.patreon.status.tier",
                Component.literal(PatreonAPI.displayName(tier)).withStyle(ChatFormatting.GOLD))
                .withStyle(ChatFormatting.GREEN));
        if (PatreonManager.isInGraceHold(player.getUUID()))
            player.sendSystemMessage(Component.translatable("message.dmz_ragnarok.core.patreon.status.grace")
                    .withStyle(ChatFormatting.YELLOW));
    }

    private void sendUsage(CommandContext<CommandSourceStack> ctx)
    {
        ChatOutputHandler.chatConfirmation(ctx.getSource(),
                "Usage: /patreon <link|claim <code>|status|unlink <player>>");
    }
}
