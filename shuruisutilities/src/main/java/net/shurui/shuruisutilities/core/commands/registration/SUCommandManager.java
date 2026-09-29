package net.shurui.shuruisutilities.core.commands.registration;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.commands.ShuruisUtilitiesCommandBuilder;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

public class SUCommandManager
{

    public static interface ConfigurableCommand
    {
        public void loadData();
    }

    // from RegisterCommandsEvent.getBuildContext(). needed by registry-backed args (ItemArgument,
    // BlockStateArgument, ResourceArgument) in 1.20.1. modules must setBuildContext() before registering
    // commands that use those arg types.
    private static CommandBuildContext buildContext;

    public static void setBuildContext(CommandBuildContext context)
    {
        buildContext = context;
    }

    public static CommandBuildContext getBuildContext()
    {
        return buildContext;
    }

    protected static Set<SUCommandData> loadedSUcommands = new HashSet<>();
    protected static Set<String> registeredSUcommands = new HashSet<>();
    protected static Set<String> registeredAiliases = new HashSet<>();
    protected static Set<String> loadedConfigurableCommand = new HashSet<>();

    // command name/alias -> owning module (e.g. warp -> teleport). lets the permissions GUI file the
    // auto-generated command.<name> nodes under the right module tab instead of one big "commands" list.
    // keys are the bare tokens as they appear after command. in a node.
    protected static final java.util.Map<String, String> commandModules = new java.util.HashMap<>();

    // alias token -> primary command token (pw -> pwarp). lets the permission layer treat an alias as the main
    // command: aliases check against the primary's command.<primary> node, never registered/shown separately.
    protected static final java.util.Map<String, String> aliasToPrimary = new java.util.HashMap<>();

    // pre-rg branded subcommand roots -> the /rg segment perm-node prefix their hidden redirect lands on. Lets the
    // old /sdu, /sdd, /srb (+/raidboss), /sdt (+/tournament) stay executable (their perm node canonicalises onto the
    // new tree, e.g. sdu -> rg.npc) and keeps command.<oldroot> out of the perms editor. Static and constant: unlike
    // aliasToPrimary these are not SU command builders, so they are never (re)recorded and survive a command reload.
    private static final java.util.Map<String, String> legacyRootCanon = java.util.Map.of(
            "sdu", "rg.npc",
            "sdd", "rg.dungeon",
            "srb", "rg.raid",
            "raidboss", "rg.raid",
            "sdt", "rg.tourney",
            "tournament", "rg.tourney");

    // every hidden legacy top-level literal (the branded roots above + the old su* leaf command names) that must stay
    // executable but be kept OUT of the client command tree / tab completion. MixinCommands skips these when it
    // rebuilds the per-player tree; the perm check alone cannot hide them because their canonical node is granted.
    public static final java.util.Set<String> hiddenLegacyRoots = java.util.Set.of(
            "sdu", "sdd", "srb", "sdt", "raidboss", "tournament",
            "sugui", "suinfo", "surace", "sureload", "sureset", "susettings", "suconfig",
            "sutesting", "suworldinfo", "suentity", "sugrave");

    // read-only view for the permissions editor
    public static java.util.Map<String, String> getCommandModules()
    {
        return java.util.Collections.unmodifiableMap(commandModules);
    }

    public static boolean isAlias(String token)
    {
        if (token == null)
            return false;
        String t = token.toLowerCase(java.util.Locale.ROOT);
        // legacy branded roots count as aliases too, so getAllUsage skips them and never emits a command.<oldroot>.
        return aliasToPrimary.containsKey(t) || legacyRootCanon.containsKey(t);
    }

    // canonicalise a perm node (the part after command.) so an alias resolves to its primary: only the first
    // segment is remapped, rest untouched. pw -> pwarp, pw.set -> pwarp.set, warp.set -> warp.set. non-aliases
    // returned unchanged.
    public static String canonicalPermissionNode(String node)
    {
        if (node == null || node.isEmpty())
            return node;
        int dot = node.indexOf('.');
        String head = dot < 0 ? node : node.substring(0, dot);
        String primary = aliasToPrimary.get(head.toLowerCase(java.util.Locale.ROOT));
        // legacy branded roots canonicalise onto their /rg segment (sdu -> rg.npc) so the hidden old command still
        // resolves against a granted node. su* leaf old names are already covered by aliasToPrimary via getAliases.
        if (primary == null)
            primary = legacyRootCanon.get(head.toLowerCase(java.util.Locale.ROOT));
        if (primary == null)
            return node;
        return dot < 0 ? primary : primary + node.substring(dot);
    }

    // module a command belongs to, from the class package: first segment after net.shurui.shuruisutilities.
    // (...teleport.commands.CommandWarp -> teleport), normalised to line up with the su.<module> perm namespace.
    private static String moduleOf(ShuruisUtilitiesCommandBuilder builder)
    {
        // a command moved into the Ragnarok Key names the module it had in core (see getModuleOverride)
        String override = builder.getModuleOverride();
        if (override != null && !override.isEmpty())
            return override;
        String pkg = builder.getClass().getName();
        String base = "net.shurui.shuruisutilities.";
        int i = pkg.indexOf(base);
        String seg = i < 0 ? "" : pkg.substring(i + base.length());
        int dot = seg.indexOf('.');
        seg = (dot < 0 ? seg : seg.substring(0, dot)).toLowerCase(java.util.Locale.ROOT);
        switch (seg)
        {
            case "permissions": return "perm";
            case "util":        return "commands";
            case "trade":       return "economy";
            default:            return seg.isEmpty() ? "commands" : seg;
        }
    }

    private static void recordModule(SUCommandData command)
    {
        String module = moduleOf(command.getBuilder());
        String primary = command.getName() == null ? null : command.getName().toLowerCase(java.util.Locale.ROOT);
        if (primary != null)
            commandModules.put(primary, module);
        if (command.getAliases() != null)
            for (String alias : command.getAliases())
                if (alias != null)
                {
                    String a = alias.toLowerCase(java.util.Locale.ROOT);
                    commandModules.put(a, module);
                    // alias -> primary so the permission layer folds the alias onto the main command
                    if (primary != null && !a.equals(primary))
                        aliasToPrimary.put(a, primary);
                }
    }

    public static SUAliasesManager aliaseManager;

    public SUCommandManager()
    {
        aliaseManager = new SUAliasesManager();
    }

    public static void registerCommand(ShuruisUtilitiesCommandBuilder commandBuilder, CommandDispatcher<CommandSourceStack> dispatcher)
    {
        final SUCommandData command = new SUCommandData(commandBuilder);
        loadedSUcommands.add(command);
        recordModule(command);
        if (!registeredSUcommands.contains(command.getName()))
        {
        	if(SUConfig.enableCommandAliases) {
        		aliaseManager.loadCommandAliases(command);
        	}
            register(command, dispatcher);
        }
    }

    public static void clearRegisteredCommands()
    {
        LoggingHandler.sulog.debug("ShuruisUtilities clearing commands");
        loadedSUcommands.clear();
        registeredSUcommands.clear();
        registeredAiliases.clear();
        commandModules.clear();
        aliasToPrimary.clear();
    }

    public static void loadConfigurableCommand() {
    	for (SUCommandData command : loadedSUcommands) {
    		if (command.getBuilder() instanceof ConfigurableCommand)
                ((ConfigurableCommand) command.getBuilder()).loadData();
    	}
    }

    public static void register(SUCommandData commandData, CommandDispatcher<CommandSourceStack> dispatcher)
    {
        if (commandData.isRegistered())
        {
            LoggingHandler.sulog.error(String.format("Tried to register command %s, but it is alredy registered", commandData.getName()));
            return;
        }
        if (commandData.getBuilder().setExecution() == null)
        {
            LoggingHandler.sulog.error(String.format("Tried to register command %s with null execution", commandData.getName()));
            return;
        }
        if (commandData.getBuilder().isEnabled())
        {
            if (registeredSUcommands.contains(commandData.getName()))
            {
                LoggingHandler.sulog.error(String.format("Command %s already registered!", commandData.getName()));
                return;
            }
            if (registeredAiliases.contains(commandData.getName()))
            {
                LoggingHandler.sulog.error(String.format("Command %s already registered as an alias!", commandData.getName()));
                return;
            }
            LiteralArgumentBuilder<CommandSourceStack> builder = commandData.getBuilder().getMainBuilder();

            //Register alias under a redirect
//            //don't change main name if not using aliases
//            if(SUConfig.enableCommandAliases) {
//            	//set main name for commands to bypass minecraft command redirect empty trees
//                ObfuscationReflectionHelper.setPrivateValue(LiteralArgumentBuilder.class, builder, commandData.getMainName(), "literal");
//            }
            //LiteralCommandNode<CommandSourceStack> literalcommandnode = dispatcher.register(builder);

            if(checkOverwritingCommands(commandData.getName(),dispatcher)) {
            	LoggingHandler.sulog.warn("Registering command: ["+commandData.getName()+"] that conflicts with an existing command/alias");
            }
            //Register alias as full command. No redirects
            dispatcher.register(builder);
            if(ShuruisUtilities.isDebug())
            	LoggingHandler.sulog.debug("Registered Command: " + commandData.getName());
            registeredSUcommands.add(commandData.getName());
            
            if (SUConfig.enableCommandAliases)
            {
                if (commandData.getAliases()!= null && !commandData.getAliases().isEmpty())
                {
                    try
                    {
                        for (String alias : commandData.getAliases())
                        {
                            if (registeredAiliases.contains(alias))
                            {
                                LoggingHandler.sulog
                                        .error(String.format("Command alias %s already registered!", alias));
                                continue;
                            }
                            if (registeredSUcommands.contains(alias))
                            {
                                LoggingHandler.sulog
                                        .error(String.format("Command alias %s already registered as a main command!", alias));
                                continue;
                            }

                            //Register alias under a redirect
//                            dispatcher.register(Commands.literal(alias).redirect(literalcommandnode)
//                                    .requires(source -> source.hasPermission(PermissionManager
//                                            .fromDefaultPermissionLevel(commandData.getBuilder().getPermissionLevel()))));
                            
                            if(checkOverwritingCommands(alias, dispatcher)) {
                            	LoggingHandler.sulog.warn("Registering alias: ["+alias+"] that conflicts with an existing command/alias");
                            }
                            //Register alias as full command. No redirects
                            ObfuscationReflectionHelper.setPrivateValue(LiteralArgumentBuilder.class, builder, alias, "literal");
                            dispatcher.register(builder);
                            
                            if(ShuruisUtilities.isDebug())
                            	LoggingHandler.sulog.info("Registered Command: " + commandData.getName() + "'s alias: " + alias);
                            registeredAiliases.add(alias);
                        }
                    }
                    catch (NullPointerException e)
                    {
                        LoggingHandler.sulog.error("Failed to register aliases for command: " + commandData.getName());
                    }
                }
            }
            commandData.setRegistered(true);
        }
        commandData.getBuilder().registerExtraPermissions();
    }

    public static int getTotalCommandNumber() {
    	return SUCommandManager.registeredSUcommands.size() + SUCommandManager.registeredAiliases.size();
    }

    // does commandName already exist on the dispatcher root (vanilla or another mod)? if
    // overwriteConflictingCommands is on, remove it so the SU command wins and return false; otherwise leave
    // it and return true so the caller can warn.
    @SuppressWarnings("unchecked")
    public static boolean checkOverwritingCommands(String commandName, CommandDispatcher<CommandSourceStack> dispatcher) {
        CommandNode<CommandSourceStack> root = dispatcher.getRoot();
        Map<String, CommandNode<CommandSourceStack>> children = ObfuscationReflectionHelper.getPrivateValue(CommandNode.class, root, "children");
        CommandNode<CommandSourceStack> existing = children.get(commandName);
        if (!(existing instanceof LiteralCommandNode)) {
            return false;
        }
        if (SUConfig.overwriteConflictingCommands) {
            LoggingHandler.sulog.info("Removing conflicting command/alias: " + commandName);
            // remove from every index brigadier keeps (children + literals); maps are mutable so edit in place
            // rather than swapping the whole map (would drop the tree).
            children.remove(commandName);
            Map<String, ?> literals = ObfuscationReflectionHelper.getPrivateValue(CommandNode.class, root, "literals");
            literals.remove(commandName);
            return false;
        }
        return true;
    }
}
