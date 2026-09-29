package net.shurui.shuruisutilities.commands.util;

import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.CommandEvent;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.core.commands.registration.SUCommandManager;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Refuses a command the player is not allowed to run, at the moment they run it.
 *
 * <h2>The hole this closes</h2>
 * {@code MixinCommandsG} rewrites every entity-backed command source to permission level 4 before the line is parsed,
 * so that SU's permission system decides who may run what instead of vanilla's op level. That half works: the
 * elevation happens, but until now nothing on the execution path ever asked SU whether the player was actually
 * allowed. The only thing standing between an ordinary player and a staff command was that
 * {@code MixinCommands.sendCommands} left it out of the client's command tree.
 *
 * <p>A command tree is a tab-completion hint, not a lock. Typing the command anyway parsed against a level 4 source,
 * passed vanilla's {@code requires(hasPermission(2))}, and ran. Every op-gated command was reachable by anyone who
 * knew its name: {@code /tellraw} to post messages as staff, and equally {@code /give}, {@code /gamemode},
 * {@code /op}, {@code /ban}. SU's own commands were in the same position, because the builder gates them with
 * {@code requires(source -> source.hasPermission(level))} and that is exactly the check the elevation defeats.
 *
 * <p>So the gate belongs here, on the same rule visibility already uses: if the command would not be sent to your
 * client, you may not run it. One check, applied to everything, rather than a list of dangerous commands that would
 * have to be kept in step with every mod added to the pack.
 *
 * <h2>Why permissions and not op level</h2>
 * Checking real op level instead would have been simpler and wrong for this server: staff ranks are permission
 * grants, not entries in the op list, so demanding op would have taken working commands away from moderators. This
 * asks the permission system the same question the client tree asks, which means a rank keeps precisely what it was
 * already shown.
 *
 * <p>Actual operators skip the check outright. That is a lockout valve, not a convenience: if a permissions file is
 * ever damaged or half migrated, the owner has to be able to log in and repair it.
 */
public final class CommandExecutionGuard
{
    private CommandExecutionGuard() {}

    /**
     * Cancel the command if the player has no grant for it. Safe to call on any {@link CommandEvent}; anything that
     * is not a real player running a real command is left alone.
     */
    static void gate(CommandEvent event)
    {
        if (event.isCanceled())
            return;
        // No permission backend loaded yet, so there is no grant to read. Falling open matches how the client tree
        // behaves in the same state: it drops back to vanilla op levels rather than hiding everything.
        if (APIRegistry.perms == null)
            return;

        CommandSourceStack source = event.getParseResults().getContext().getSource();
        // Console, command blocks and functions are not entities, so they never took the elevation and are not what
        // this is defending against. A FakePlayer is mod machinery acting on its own behalf, not someone typing.
        if (!(source.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer)
            return;
        if (isOperator(player))
            return;

        String denied = firstDeniedNode(event, player);
        if (denied == null)
            return;

        event.setCanceled(true);
        ChatOutputHandler.chatError(player, "You do not have permission to use that command.");
        // Worth a line in the log: reaching here means someone typed the full name of a command that was never
        // offered to them, which is a thing staff want to know about.
        LoggingHandler.sulog.warn("[Commands] {} was denied /{}", player.getGameProfile().getName(),
                denied.replace('.', ' '));
    }

    /**
     * The first node on the parsed path the player has no grant for, or null if the whole path is allowed.
     *
     * <p>Every prefix is checked, not just the command name, because permissions are registered per node path:
     * a rank can hold {@code command.rg} while {@code command.rg.admin} stays with staff, and only walking the path
     * respects that.
     */
    private static String firstDeniedNode(CommandEvent event, ServerPlayer player)
    {
        return firstDeniedNode(event.getParseResults(), UserIdent.get(player), player.server);
    }

    /**
     * The first node on a parsed command path {@code ident} has no grant for, or null if the whole path is allowed.
     *
     * <p>The same rule {@link #gate} applies to a local player, exposed so the cross-shard command forwarder can
     * ask it about a player who is NOT online here: the sender of a command that arrived from another shard. It is
     * answered against this server's own (network synced) permission data, keyed on the sender's {@link UserIdent},
     * which is why a forwarded command can be gated with the sender's real authority rather than run as console.
     */
    public static String firstDeniedNode(com.mojang.brigadier.ParseResults<CommandSourceStack> parse,
            UserIdent ident, MinecraftServer server)
    {
        CommandContextBuilder<CommandSourceStack> top = parse.getContext();
        for (CommandContextBuilder<CommandSourceStack> context = top; context != null; context = context.getChild())
        {
            // A child context exists because something redirected. Two very different things do that, and they need
            // opposite treatment:
            //
            //   /execute ... run <command>  the child is a command in its own right, and is the way a player holding
            //                               su.maptp could otherwise launder a staff command through /execute.
            //   /sdu admin                  a legacy alias redirecting onto its primary; the child holds subcommands
            //                               of that primary, not root commands.
            //
            // Telling them apart is just asking the dispatcher whether the child's first literal is a command root.
            // If it is not, the alias root was already checked on the way in and there is nothing further to decide.
            if (context != top && !startsAtCommandRoot(context, server))
                continue;

            String denied = firstDeniedOnPath(context, ident);
            if (denied != null)
                return denied;
        }
        return null;
    }

    private static String firstDeniedOnPath(CommandContextBuilder<CommandSourceStack> context, UserIdent ident)
    {
        StringBuilder path = new StringBuilder();
        for (ParsedCommandNode<CommandSourceStack> parsed : context.getNodes())
        {
            // Argument nodes carry the values the player typed, which are not what a grant is about.
            if (!(parsed.getNode() instanceof LiteralCommandNode))
                continue;
            if (path.length() > 0)
                path.append('.');
            path.append(parsed.getNode().getName());

            // Fold aliases onto the primary command, exactly as the client tree does, so an alias is allowed
            // whenever the command it stands for is.
            String node = SUCommandManager.canonicalPermissionNode(path.toString());
            if (!APIRegistry.perms.checkUserPermission(ident, "command." + node))
                return path.toString();
        }
        return null;
    }

    private static boolean startsAtCommandRoot(CommandContextBuilder<CommandSourceStack> context, MinecraftServer server)
    {
        for (ParsedCommandNode<CommandSourceStack> parsed : context.getNodes())
        {
            CommandNode<CommandSourceStack> node = parsed.getNode();
            if (!(node instanceof LiteralCommandNode))
                continue;
            return server != null && server.getCommands().getDispatcher().getRoot().getChild(node.getName()) != null;
        }
        return false;
    }

    private static boolean isOperator(ServerPlayer player)
    {
        return isOperator(player.getGameProfile(), player.server);
    }

    /**
     * Whether a profile is on the op list, answerable for a player who is NOT online here.
     *
     * <p>The op list is keyed by {@code GameProfile}, so this works for the sender of a forwarded command who is
     * online on another shard. It mirrors the lockout valve in {@link #gate}: a real operator is never blocked by a
     * damaged or half migrated permissions file, on the destination just as on the origin.
     */
    public static boolean isOperator(com.mojang.authlib.GameProfile profile, MinecraftServer server)
    {
        try
        {
            return profile != null && server != null && server.getPlayerList().isOp(profile);
        }
        catch (Throwable t)
        {
            return false;
        }
    }
}
