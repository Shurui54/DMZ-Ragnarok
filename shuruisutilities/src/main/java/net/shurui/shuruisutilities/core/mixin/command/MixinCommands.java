package net.shurui.shuruisutilities.core.mixin.command;

import java.util.HashMap;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import com.google.common.collect.Maps;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

@Mixin(Commands.class)
public class MixinCommands
{
    // rebuild the client command tree using SU permissions instead of vanilla op levels, so players only see
    // commands they can actually run
    @Inject(method = "sendCommands(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), cancellable = true, require = 1)
    public void sendCommands(ServerPlayer player, CallbackInfo callback)
    {
        // Always build the client command tree ourselves, so the pre-rg legacy roots are stripped from tab
        // completion whether or not SU permissions are available. sendCommands fires on join and on every
        // permission change.
        //
        // When SU permissions are loaded we gate visibility on SU grants (usePerms = true). When they are not
        // (permissions module absent, or SU still initialising before the module comes up) we cannot evaluate SU
        // grants, so we fall back to vanilla's own op-level visibility via CommandNode.canUse. We do NOT defer to
        // the vanilla sendCommands here: the branded legacy roots (/sdu, /sdd, /srb, /raidboss, /sdt, /tournament)
        // are registered as .redirect(...) literals with no .requires gate, so vanilla's canUse returns true for
        // everyone and they would leak back into tab completion. Building the tree ourselves lets the
        // hiddenLegacyRoots skip apply in both modes.
        final boolean usePerms = APIRegistry.perms != null;
        final Map<CommandNode<CommandSourceStack>, CommandNode<SharedSuggestionProvider>> map = Maps.newHashMap();
        final RootCommandNode<SharedSuggestionProvider> rootcommandnode = new RootCommandNode<>();
        // Per-build memo of checkUserPermission results keyed by CANONICAL node string. Aliases fold onto the same
        // canonical node (SUCommandManager.canonicalPermissionNode), so the same "command.<node>" check recurs many
        // times in one tree; this evaluates each distinct one once for this player. Scoped to this single build, so
        // it can never serve a stale answer across permission changes.
        final Map<String, Boolean> permMemo = new HashMap<>();
        map.put(ServerLifecycleHooks.getCurrentServer().getCommands().getDispatcher().getRoot(), rootcommandnode);
        fillUsableCommandsNodesSU(ServerLifecycleHooks.getCurrentServer().getCommands().getDispatcher().getRoot(), rootcommandnode,
                player.createCommandSourceStack(), map, "", usePerms, permMemo);
        player.connection.send(new ClientboundCommandsPacket(rootcommandnode));
        callback.cancel();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void fillUsableCommandsNodesSU(CommandNode<CommandSourceStack> rootNode, CommandNode<SharedSuggestionProvider> outNode,
            CommandSourceStack source, Map<CommandNode<CommandSourceStack>, CommandNode<SharedSuggestionProvider>> nodeMap, String nodeString,
            boolean usePerms, Map<String, Boolean> permMemo)
    {
        for (CommandNode<CommandSourceStack> commandnode : rootNode.getChildren())
        {
            // Hidden legacy command roots (the pre-rg branded names: sdu/sdd/srb/sdt/raidboss/tournament and the old
            // su* leaf commands) stay executable but are kept out of the client command tree and tab completion, so
            // only the /rg tree and the rg* leaves autocomplete. The perm check cannot hide them because their
            // canonical node is granted, so skip them explicitly at the top level of the tree.
            if (nodeString.isEmpty()
                    && net.shurui.shuruisutilities.core.commands.registration.SUCommandManager.hiddenLegacyRoots
                            .contains(commandnode.getName()))
            {
                continue;
            }

            String newNode = nodeString.isEmpty() ? commandnode.getUsageText()
                    : nodeString + CommandDispatcher.ARGUMENT_SEPARATOR + commandnode.getUsageText();
            newNode = newNode.replace("<", "").replace(">", "");

            // With SU permissions loaded, gate on SU grants; otherwise mirror vanilla's own op-level visibility so
            // the player still sees what they can run. Either way the hiddenLegacyRoots skip above has already
            // removed the branded legacy roots from the tree.
            boolean visible = usePerms ? checkPerms(newNode.replace(' ', '.'), source, permMemo) : commandnode.canUse(source);
            if (visible)
            {
                ArgumentBuilder<SharedSuggestionProvider, ?> argumentbuilder = (ArgumentBuilder) commandnode.createBuilder();
                argumentbuilder.requires((p) -> true);
                if (argumentbuilder.getCommand() != null)
                {
                    argumentbuilder.executes((p) -> 0);
                }

                if (argumentbuilder instanceof RequiredArgumentBuilder)
                {
                    RequiredArgumentBuilder<SharedSuggestionProvider, ?> requiredargumentbuilder = (RequiredArgumentBuilder) argumentbuilder;
                    if (requiredargumentbuilder.getSuggestionsProvider() != null)
                    {
                        requiredargumentbuilder.suggests(SuggestionProviders.safelySwap(requiredargumentbuilder.getSuggestionsProvider()));
                    }
                }

                if (argumentbuilder.getRedirect() != null)
                {
                    argumentbuilder.redirect(nodeMap.get(argumentbuilder.getRedirect()));
                }

                CommandNode<SharedSuggestionProvider> commandnode1 = argumentbuilder.build();
                nodeMap.put(commandnode, commandnode1);
                outNode.addChild(commandnode1);
                if (!commandnode.getChildren().isEmpty())
                {
                    fillUsableCommandsNodesSU(commandnode, commandnode1, source, nodeMap, newNode, usePerms, permMemo);
                }
            }
        }
    }

    private static boolean checkPerms(String commandNode, CommandSourceStack source1, Map<String, Boolean> permMemo)
    {
        // Fold aliases onto their primary command so an alias node syncs to the client iff the main command is
        // allowed (its own command.<alias> node is never registered/shown separately).
        String node = net.shurui.shuruisutilities.core.commands.registration.SUCommandManager.canonicalPermissionNode(commandNode);
        Boolean cached = permMemo.get(node);
        if (cached != null)
            return cached;
        boolean result = APIRegistry.perms.checkUserPermission(UserIdent.get(source1), "command." + node);
        permMemo.put(node, result);
        return result;
    }
}
