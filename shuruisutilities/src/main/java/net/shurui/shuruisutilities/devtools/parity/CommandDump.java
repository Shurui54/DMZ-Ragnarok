package net.shurui.shuruisutilities.devtools.parity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/**
 * The full Brigadier command tree: every literal/argument path, whether it is executable, any redirect, and the
 * permission gate on each node.
 *
 * <p>The gate is discovered by probing each node's requirement predicate with command sources at permission levels
 * 0 through 4 ({@link CommandSourceStack#withPermission(int)}) and reporting the minimum level that passes. This is
 * exactly the kind of gate we must not silently change across the split: a command that used to need op 2 and now
 * needs op 4 (or nothing) is a regression the diff will catch. Requirements that consult more than permission (a
 * module switch, a key gate) evaluate deterministically at dump time too, which is the intended behaviour to record.
 * Any predicate that throws is recorded as {@code gate=error} rather than crashing the dump.
 */
final class CommandDump {

    private CommandDump() {}

    static String dump(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        CommandSourceStack base;
        try {
            base = server.createCommandSourceStack();
        } catch (Throwable t) {
            base = null;
        }
        walk(dispatcher.getRoot(), "", base, lines);
        return ParityDump.sortedBlock("brigadier command tree", lines);
    }

    private static void walk(CommandNode<CommandSourceStack> node, String prefix,
                             CommandSourceStack base, List<String> out) {
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            String seg = describeSegment(child);
            String path = prefix.isEmpty() ? seg : prefix + " " + seg;
            StringBuilder line = new StringBuilder("CMD ").append(path);
            line.append(" | ").append(kind(child));
            line.append(" | gate=").append(gate(child, base));
            if (child.getCommand() != null) {
                line.append(" | exec");
            }
            if (child.getRedirect() != null) {
                line.append(" | redirect=").append(child.getRedirect().getName());
            }
            out.add(line.toString());
            // Recurse into children. Do not follow redirects (they point back into the same tree and would loop).
            if (child.getRedirect() == null) {
                walk(child, path, base, out);
            }
        }
    }

    private static String describeSegment(CommandNode<CommandSourceStack> node) {
        if (node instanceof ArgumentCommandNode<?, ?> arg) {
            return "<" + arg.getName() + ">";
        }
        return node.getName();
    }

    private static String kind(CommandNode<CommandSourceStack> node) {
        if (node instanceof LiteralCommandNode) {
            return "literal";
        }
        if (node instanceof ArgumentCommandNode<?, ?> arg) {
            String typeName;
            try {
                typeName = arg.getType().getClass().getName();
            } catch (Throwable t) {
                typeName = "?";
            }
            return "arg:" + typeName;
        }
        return node.getClass().getSimpleName();
    }

    /** Minimum permission level (0..4) whose source passes the node requirement; "any", ">4" or "error". */
    private static String gate(CommandNode<CommandSourceStack> node, CommandSourceStack base) {
        Predicate<CommandSourceStack> req = node.getRequirement();
        if (req == null) {
            return "any";
        }
        if (base == null) {
            return "unknown";
        }
        try {
            for (int level = 0; level <= 4; level++) {
                CommandSourceStack src = base.withPermission(level);
                if (req.test(src)) {
                    return level == 0 ? "any" : ("op" + level);
                }
            }
            return ">4";
        } catch (Throwable t) {
            return "error";
        }
    }
}
