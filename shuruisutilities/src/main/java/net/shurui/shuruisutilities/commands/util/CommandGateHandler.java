package net.shurui.shuruisutilities.commands.util;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.util.events.ServerEventHandler;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * The command execution gates every server runs, keyed or keyless (S18a: split out of the Commands module's event
 * handler, whose AFK and temp ban halves moved into the Ragnarok Key). Constructed by core itself, so it no longer
 * depends on the Commands module being loaded:
 * <ul>
 *   <li>{@link CommandExecutionGuard#gate}: refuses a command the player has no grant for (keyless, the grant is the
 *       node's vanilla level recorded by the command permission manager).</li>
 *   <li>the Xaero map teleport gate ({@code su.maptp}).</li>
 *   <li>the spam guard flag for the suite's repeatable commands ({@link CommandSpamGuard}).</li>
 * </ul>
 */
public class CommandGateHandler extends ServerEventHandler
{
    public CommandGateHandler()
    {
        super();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void commandEvent(CommandEvent event)
    {
        // Permission gate first: everything below it assumes the player was allowed to run the command at all.
        CommandExecutionGuard.gate(event);
        gateMapTeleport(event);
        markSpamGuard(event);
    }

    // If a PLAYER just ran one of the suite's repeatable commands (guild/money/pay/raid/rg tourney roots),
    // flag it so the detectRateSpam mixin declines to count it against the vanilla chat-spam counter. Runs
    // synchronously right before vanilla's detectRateSpam() in the same handleChatCommand lambda, so the flag
    // is consumed on the next line (see CommandSpamGuard). Cancelled commands are left flagged too: a
    // cancelled command still reaches detectRateSpam in vanilla and should not count either.
    private static void markSpamGuard(CommandEvent event)
    {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player))
            return;
        String root = rootCommandName(event);
        if (CommandSpamGuard.isAllowedRoot(root))
            CommandSpamGuard.markAllowed(player.getUUID());
    }

    // gate Xaero's map teleport behind su.maptp. the map runs /tp, /teleport or /execute as the player to move
    // them; su.maptp is OP-by-default so ops pass and an admin can grant it to others. only PLAYER sources are
    // affected (command blocks/functions/console aren't entities).
    // resolve the real ServerPlayer and check the SU node on it, NOT the parsed source level: MixinCommandsG
    // elevates the parse-time source to level 4 so the parsed source reports hasPermission(2)==true even for a
    // non-op. matches the player-limit-bypass pattern.
    private void gateMapTeleport(CommandEvent event)
    {
        if (event.isCanceled())
            return;
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player))
            return;

        String root = rootCommandName(event);
        if (root == null)
            return;
        if (root.equals("tp") || root.equals("teleport") || root.equals("execute"))
        {
            if (APIRegistry.perms.checkUserPermission(UserIdent.get(player),
                    net.shurui.shuruisutilities.permissions.PermissionSettings.PERM_MAP_TELEPORT))
                return;
            event.setCanceled(true);
            ChatOutputHandler.chatWarning(player, "You do not have permission to teleport via the map.");
        }
    }

    // first whitespace-delimited token of the raw input, lower-cased, or null if empty
    private static String rootCommandName(CommandEvent event)
    {
        String raw = event.getParseResults().getReader().getString().trim();
        if (raw.isEmpty())
            return null;
        int space = raw.indexOf(' ');
        return (space < 0 ? raw : raw.substring(0, space)).toLowerCase();
    }
}
