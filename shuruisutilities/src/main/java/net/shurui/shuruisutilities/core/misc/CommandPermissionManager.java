package net.shurui.shuruisutilities.core.misc;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.permissions.KeylessPermissionHelper;
import net.shurui.shuruisutilities.permissions.PermissionSettings;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;

// transition class to the new Permissions API
public class CommandPermissionManager
{
    // command node -> DefaultPermissionLevel
    protected static Map<String, DefaultPermissionLevel> commandPermissionMap = new WeakHashMap<>();

    // dotted command node -> the vanilla op level (0-4) its own requires() and its parents' ask for. Filled by
    // getAllUsage and only consumed keyless, where it becomes the node's op gate (see registerCommandPermissions).
    private static final Map<String, Integer> vanillaLevels = new java.util.HashMap<>();

    public static void registerCommandPermission(String commandNode, DefaultPermissionLevel permissionLevel)
    {
        commandPermissionMap.put(commandNode, permissionLevel);
        APIRegistry.perms.registerPermission("command." + commandNode, denyByDefault(permissionLevel), "");
    }

    // deny-by-default: a player runs a command only if a group or user perm explicitly grants command.<node>,
    // even for commands normally open to everyone. ops still get everything via the operators group. this maps
    // ALL onto OP (default group denies, operators allows); NONE stays as-is (denied to all).
    //
    // Keyless (APIRegistry.perms is the KeylessPermissionHelper, no engine and no grants) the node is registered at
    // the command's own VANILLA level instead: with nothing able to grant command.<node>, deny-by-default would lock
    // every non-op out of every command, public ones included. SU elevates a player's command source at parse time,
    // so this registered level is the only op gate a keyless command has; registerCommandPermissions also hands the
    // helper the exact vanilla op level (2, 3 or 4) of each op-gated node, so a level 2 op keeps what vanilla gave.
    private static DefaultPermissionLevel denyByDefault(DefaultPermissionLevel level)
    {
        if (APIRegistry.perms instanceof KeylessPermissionHelper)
            return level;
        return level == DefaultPermissionLevel.ALL ? DefaultPermissionLevel.OP : level;
    }

    public static void registerCommandPermissionDiscription(String commandNode, DefaultPermissionLevel permissionLevel, String disc)
    {
        commandPermissionMap.put(commandNode, permissionLevel);
        APIRegistry.perms.registerPermission("command." + commandNode, denyByDefault(permissionLevel), disc);
    }

    // internal use only
    public static void registerCommandPermissions()
    {
        for (Map.Entry<String, DefaultPermissionLevel> node : getAllUsage().entrySet())
        {
            if (!commandPermissionMap.containsKey(node.getKey()))
            {
                registerCommandPermission(node.getKey(), node.getValue());
                if (APIRegistry.perms instanceof KeylessPermissionHelper keyless)
                    keyless.setRequiredOpLevel("command." + node.getKey(),
                            vanillaLevels.getOrDefault(node.getKey(), fromDefaultPermissionLevel(DefaultPermissionLevel.OP)));
                // LoggingHandler.sulog.debug("Command: " + org.apache.commons.lang3.StringUtils.rightPad(node.getKey(), 30) + " - Permission: " + node.getValue().name());
            }
            else
            {
                LoggingHandler.sulog.debug("Command permission tried to be set twice: " + node.getKey());
            }
        }
        vanillaLevels.clear();
        probeSources = null; // they hold this server and its overworld; a later server builds its own
    }

    public static int fromDefaultPermissionLevel(DefaultPermissionLevel level)
    {
        switch (level)
        {
        case ALL:
            return 0;
        case OP:
        default:
            return 4;
        }
    }

    // One probe source per permission level, built on first use. Each createCommandSourceStack constructs a whole
    // FakePlayer (entity id, capability attach, Curios slot maps), and this probe runs for every command node at the
    // first server tick: about 1,600 fake players in one tick, measured at 550 ms. canUse only reads the level.
    private static CommandSourceStack[] probeSources;

    private static CommandSourceStack probe(int level)
    {
        if (probeSources == null)
        {
            CommandFaker faker = new CommandFaker();
            CommandSourceStack base = faker.createCommandSourceStack(0);
            probeSources = new CommandSourceStack[5];
            for (int i = 0; i <= 4; i++)
                probeSources[i] = base.withPermission(i);
        }
        return probeSources[level];
    }

    public static DefaultPermissionLevel getDefaultCommandPermFromNode(CommandNode<CommandSourceStack> commandNode)
    {
    	try {
            if (commandNode.canUse(probe(0)))
            {
            	return DefaultPermissionLevel.ALL;
            }
            else if (commandNode.canUse(probe(1)) ||
                    commandNode.canUse(probe(2)) ||
                    commandNode.canUse(probe(3)) ||
                    commandNode.canUse(probe(4)))
            {
            	return DefaultPermissionLevel.OP;
            }
            return DefaultPermissionLevel.ALL;
    	}catch(UnsupportedOperationException e) {}
    	return DefaultPermissionLevel.OP;
    }

    // The lowest vanilla op level (0-4) whose source can use this node: its own requires() only. A node no probe can
    // use answers 0, as getDefaultCommandPermFromNode answers ALL for it; a node that refuses to be probed answers 4.
    private static int vanillaLevelOf(CommandNode<CommandSourceStack> commandNode)
    {
        try
        {
            for (int level = 0; level <= 4; level++)
                if (commandNode.canUse(probe(level)))
                    return level;
            return 0;
        }
        catch (UnsupportedOperationException e)
        {
            return 4;
        }
    }

    // strip a commandNode from the start up to the first $
    public static String stripNode(String node)
    {

        int index = node.indexOf("$");
        if (index != -1)
        {
            node = node.substring(0, index);
        }
        return node;
    }

    // all command nodes under root, as full usage strings
    public static Map<String, DefaultPermissionLevel> getAllUsage()
    {
        CommandDispatcher<CommandSourceStack> dispatcher = ServerLifecycleHooks.getCurrentServer().getCommands().getDispatcher();
        final TreeMap<String, DefaultPermissionLevel> result = new TreeMap<>();
        // identity set of nodes on the current DFS path so redirect cycles (a node redirecting to an ancestor)
        // terminate instead of recursing forever. shared children via multiple paths still get visited since
        // nodes are removed on the way back up.
        final Set<CommandNode<CommandSourceStack>> visitPath = Collections.newSetFromMap(new IdentityHashMap<>());
        getAllUsage(dispatcher.getRoot(), result, "", dispatcher, DefaultPermissionLevel.ALL, 0, visitPath);
        return result;
    }

    private static void getAllUsage(final CommandNode<CommandSourceStack> node, final Map<String, DefaultPermissionLevel> result, final String prefix,
            CommandDispatcher<CommandSourceStack> dispatcher, DefaultPermissionLevel parentLevel, int parentVanillaLevel,
            final Set<CommandNode<CommandSourceStack>> visitPath)
    {
        if (!visitPath.add(node))
        {
            // already on the current path; following it again would be a redirect cycle
            return;
        }
        try
        {
            // skip secondary aliases and their whole subtree: the alias folds onto its primary, whose
            // command.<primary> node is generated when the primary is walked from root. keeps command.<alias>
            // out of the perms so the editor shows only the main command's toggle.
            if (!prefix.isEmpty() && prefix.indexOf(' ') < 0
                    && net.shurui.shuruisutilities.core.commands.registration.SUCommandManager.isAlias(prefix))
            {
                return;
            }
            if (node instanceof ArgumentCommandNode && !PermissionSettings.fullcommandNode)
            {
                // LoggingHandler.sulog.debug("Found Command Argument: "+ node.getUsageText()+ " For Command: "+ prefix.replace(' ', '.'));
                return;
            }
            if (!prefix.equals(""))
            {
                if (parentLevel == DefaultPermissionLevel.ALL && getDefaultCommandPermFromNode(node) == DefaultPermissionLevel.OP)
                {
                    parentLevel = DefaultPermissionLevel.OP;
                }
                if(commandPermissionMap.containsKey(prefix)) {
                	parentLevel = commandPermissionMap.get(prefix);
                }
                // The vanilla level of this node: the strictest of its parents' and its own requires(). An explicit
                // registration overrides it the same way it overrides parentLevel (ALL = 0, OP = SU's OP level 4).
                parentVanillaLevel = Math.max(parentVanillaLevel, vanillaLevelOf(node));
                if (commandPermissionMap.containsKey(prefix))
                    parentVanillaLevel = fromDefaultPermissionLevel(commandPermissionMap.get(prefix));
                String key = prefix.replace(' ', '.').replace("<", "").replace(">", "");
                result.put(key, parentLevel);
                vanillaLevels.put(key, parentLevel == DefaultPermissionLevel.ALL ? 0
                        : Math.max(1, parentVanillaLevel));
            }

            // do NOT follow redirects. a redirect (/execute run <any command>, the /execute if|unless chains
            // that loop back into execute, command aliases) points back into the tree. re-expanding the
            // target's subtree under this prefix makes the perm paths combinatorial (execute's tree x every
            // command), building millions of TreeMap entries and OOMing on startup. the target's own nodes are
            // already produced when walked from root, so following redirects is pure blow-up. real children only.
            for (final CommandNode<CommandSourceStack> child : node.getChildren())
            {
                getAllUsage(child, result, prefix.isEmpty() ? child.getUsageText() : prefix + CommandDispatcher.ARGUMENT_SEPARATOR + child.getUsageText(),
                        dispatcher, parentLevel, parentVanillaLevel, visitPath);
            }
        }
        finally
        {
            visitPath.remove(node);
        }
    }
}
