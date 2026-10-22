package net.shurui.dev.sdu.config.editor;

import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A row of the Editors tab: another editor that owns settings this screen deliberately does not duplicate. The
 * server lists only links whose {@code visible} test passes for the viewer, and re-checks it before {@code open}.
 *
 * @param id       stable id sent back when clicked
 * @param labelKey translation key of the row label
 * @param tipKey   translation key of the hover help
 * @param visible  may this player see and open it (key, module presence and permission all belong here)
 * @param open     open the editor for this player
 */
public record EditorLink(String id, String labelKey, String tipKey, Predicate<ServerPlayer> visible,
                         Consumer<ServerPlayer> open) {

    /**
     * A link that runs an editor command as the player (for example {@code rg dungeon edit}), so the command's own
     * permission check still decides. Listed only when that command exists on this server (a keyless server never
     * registers a private one) and the player may run every word of it.
     */
    public static EditorLink command(String id, String labelKey, String tipKey, String command) {
        return new EditorLink(id, labelKey, tipKey, p -> ConfigEditorRegistry.canEdit(p) && canRun(p, command), p -> {
            if (p.getServer() != null) {
                p.getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), command);
            }
        });
    }

    private static boolean canRun(ServerPlayer p, String command) {
        if (p.getServer() == null) {
            return false;
        }
        var source = p.createCommandSourceStack();
        com.mojang.brigadier.tree.CommandNode<net.minecraft.commands.CommandSourceStack> node =
                p.getServer().getCommands().getDispatcher().getRoot();
        for (String word : command.split(" ")) {
            node = node.getChild(word);
            if (node == null || !node.canUse(source)) {
                return false;
            }
        }
        return node.getCommand() != null;
    }
}
