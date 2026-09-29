package net.shurui.shuruisutilities.character;

import java.util.List;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;
import net.shurui.shuruisutilities.core.commands.ShuruisUtilitiesCommandBuilder;
import net.shurui.shuruisutilities.util.ServerUtil;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.NotNull;

/**
 * {@code /character} manages a player's multiple character slots (see {@link CharacterSlots}). Each slot keeps
 * its own DragonMineZ character, inventory, Curios, XP, food and effects; the ender chest is shared. The number
 * of slots a player may have is the value-permission {@code su.character.slots} (default 1).
 */
public class CommandCharacter extends ShuruisUtilitiesCommandBuilder
{
    public CommandCharacter(boolean enabled)
    {
        super(enabled);
    }

    public static final String PERM_USE = "su.character.use";

    @Override
    public @NotNull String getPrimaryAlias()
    {
        return "character";
    }

    @Override
    public String @NotNull [] getDefaultSecondaryAliases()
    {
        return new String[] { "characters", "char" };
    }

    @Override
    public boolean canConsoleUseCommand()
    {
        return false;
    }

    @Override
    public DefaultPermissionLevel getPermissionLevel()
    {
        return DefaultPermissionLevel.ALL;
    }

    @Override
    public void registerExtraPermissions()
    {
        APIRegistry.perms.registerPermission(PERM_USE, DefaultPermissionLevel.ALL, "Use character slots");
        APIRegistry.perms.registerPermissionProperty(CharacterSlots.SLOT_LIMIT_PROP, "1",
                "How many character slots a player may have");
        APIRegistry.perms.registerPermissionProperty(CharacterSlots.SWAP_COOLDOWN_PROP, "600",
                "Seconds between character swaps (0 = no cooldown)");
        APIRegistry.perms.registerPermissionProperty(CharacterSlots.COMBAT_LOCK_PROP, "120",
                "Seconds after combat during which character swaps are blocked (0 = off)");
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> setExecution()
    {
        return baseBuilder
                .executes(ctx -> execute(ctx, "gui"))
                .then(Commands.literal("list").executes(ctx -> execute(ctx, "list")))
                .then(Commands.literal("gui").executes(ctx -> execute(ctx, "gui")))
                .then(Commands.literal("switch")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(1))
                                .executes(ctx -> execute(ctx, "switch"))))
                .then(Commands.literal("new")
                        .executes(ctx -> execute(ctx, "new"))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> execute(ctx, "new_named"))))
                .then(Commands.literal("rename")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(1))
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> execute(ctx, "rename")))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(1))
                                .executes(ctx -> execute(ctx, "delete"))));
    }

    /** The number of slots this player may have, from the {@code su.character.slots} value-permission. */
    public static int maxSlots(ServerPlayer player)
    {
        // Through PrestigeLedger: on a network this grant lives in player data so it travels with the prestige
        // that earned it, rather than staying on the server that paid it out.
        int n = ServerUtil.parseIntDefault(
                net.shurui.shuruisutilities.prestige.PrestigeLedger.slotsRaw(player), 1);
        return Math.max(1, Math.min(n, CharacterSlots.HARD_CAP));
    }

    @Override
    public int processCommandPlayer(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException
    {
        ServerPlayer p = getServerPlayer(ctx.getSource());
        switch (params)
        {
            case "gui" -> CharacterNet.openGui(p);
            case "list" -> sendList(ctx, p);
            case "switch" -> {
                int n = IntegerArgumentType.getInteger(ctx, "slot") - 1;
                report(ctx, CharacterSlots.switchTo(p, n), "Switched to character #" + (n + 1) + ".");
            }
            case "new" -> report(ctx, CharacterSlots.create(p, null, maxSlots(p)), "Created a new character.");
            case "new_named" -> report(ctx, CharacterSlots.create(p, StringArgumentType.getString(ctx, "name"), maxSlots(p)),
                    "Created a new character.");
            case "rename" -> {
                int n = IntegerArgumentType.getInteger(ctx, "slot") - 1;
                report(ctx, CharacterSlots.rename(p, n, StringArgumentType.getString(ctx, "name")), "Renamed.");
            }
            case "delete" -> {
                int n = IntegerArgumentType.getInteger(ctx, "slot") - 1;
                report(ctx, CharacterSlots.delete(p, n), "Deleted character #" + (n + 1) + ".");
            }
            default -> sendList(ctx, p);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void report(CommandContext<CommandSourceStack> ctx, String error, String ok)
    {
        if (error == null)
            ChatOutputHandler.chatConfirmation(ctx.getSource(), ok);
        else
            ChatOutputHandler.chatError(ctx.getSource(), error);
    }

    private void sendList(CommandContext<CommandSourceStack> ctx, ServerPlayer p)
    {
        List<String> names = CharacterSlots.names(p);
        int active = CharacterSlots.activeIndex(p);
        ChatOutputHandler.chatConfirmation(ctx.getSource(),
                "Characters (" + names.size() + "/" + maxSlots(p) + "):");
        for (int i = 0; i < names.size(); i++)
        {
            boolean cur = i == active;
            ChatOutputHandler.chatNotification(ctx.getSource(),
                    (cur ? "§a> " : "§7  ") + "#" + (i + 1) + " " + names.get(i) + (cur ? " §a(active)" : ""));
        }
        ChatOutputHandler.chatNotification(ctx.getSource(),
                "§8/character switch <n> · new [name] · rename <n> <name> · delete <n>");
    }
}
