package net.shurui.shuruisutilities.core.commands;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;

/**
 * The one question a Brigadier {@code .requires()} predicate may ask about a PLAYER in this suite: has SU granted
 * them the {@code command.<node>} permission that already decides whether they can see the command at all.
 *
 * <h2>The bug this closes</h2>
 * SU publishes the client command tree itself ({@code MixinCommands.sendCommands}) and gates every entry on
 * {@code command.<node>}, and it cancels execution on the same node ({@code ShuruisUtilities.commandEvent}). A
 * {@code .requires()} predicate that asks a DIFFERENT question is therefore a second, contradictory gate: a player
 * granted the command node sees the subcommand, types it, and Brigadier refuses to parse a node whose predicate
 * says no. The client reports that as "Incorrect argument for command", which reads like a typo and is in fact a
 * permission refusal. That is what a non-op WITH the permission was hitting.
 *
 * <p>The deeper reason those predicates cannot simply ask the player is that nothing in the suite can raise a
 * player's VANILLA permission level: {@code ServerPlayer.getPermissionLevel()} reads the server ops file and
 * nothing else, so {@code player.hasPermissions(2)} stays false however many SU permissions are granted at
 * runtime, and Forge's {@code PermissionAPI} default resolver is exactly that call. SU elevates the SOURCE stack
 * at parse time ({@code MixinServerPlayNetHandler}) which is why predicates written against the SOURCE work, but
 * any predicate that reaches for the player entity is permanently false for everyone but a real op. Note that
 * elevation is skipped on a Bukkit hybrid (see {@code SUMixinConfig}), so on a hybrid this node check is the only
 * thing that lets a non-op past a {@code .requires()} at all.
 *
 * <h2>Safe to consult, unlike an arbitrary node</h2>
 * SU answers an UNKNOWN permission node with ALLOW, so probing a node that may never have been registered would
 * hand the command to everybody. {@code command.*} nodes are the exception: {@code CommandPermissionManager}
 * registers every literal in the dispatcher deny-by-default on the first server tick, unconditionally and whatever
 * the key tier, so an ungranted player gets a real false here. Only ever ask this class about a command node.
 */
public final class CommandGate
{

    private CommandGate()
    {
    }

    /**
     * Whether SU grants this source the {@code command.<commandNode>} permission.
     *
     * <p>Players only, and deliberately: this is meant to WIDEN a predicate that has already decided what console,
     * command blocks and functions may do, so it answers false for every non-player source rather than second
     * guessing the caller. False as well for a {@link FakePlayer}, which is what {@code CommandPermissionManager}
     * probes the tree with while it is registering these very nodes.
     *
     * @param commandNode the dotted path of the command LITERALS, no {@code command.} prefix and no argument names:
     *                    {@code "rg.raid.edit"}, {@code "money.give"}. Argument nodes are not registered, and a
     *                    grant on the literal covers them anyway through the node hierarchy.
     */
    public static boolean grantedCommand(CommandSourceStack source, String commandNode)
    {
        if (source == null || commandNode == null || commandNode.isEmpty())
            return false;
        ServerPlayer player = source.getPlayer();
        if (player == null || player instanceof FakePlayer)
            return false;
        try
        {
            if (APIRegistry.perms == null)
                return false;
            return APIRegistry.perms.checkUserPermission(UserIdent.get(player), "command." + commandNode);
        }
        catch (Throwable t)
        {
            // A predicate runs mid-parse; never let a permission lookup turn a command into a crash. Refusing here
            // costs at most the widening, and the caller's own check still stands.
            return false;
        }
    }
}
