package net.shurui.shuruisutilities.grave.commands;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.audit.AuditLog;
import net.shurui.shuruisutilities.compat.dmz.DragonBallTotemInfo;
import net.shurui.shuruisutilities.core.commands.ShuruisUtilitiesCommandBuilder;
import net.shurui.shuruisutilities.grave.GraveData;
import net.shurui.shuruisutilities.grave.GraveManager;
import net.shurui.shuruisutilities.grave.GraveStorage;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;
import org.jetbrains.annotations.NotNull;

/**
 * OP admin command: {@code /rggrave}. Three subcommands:
 *
 * <ul>
 *   <li>{@code cleannames}: sweep orphaned grave name-marker ArmorStands (see
 *       {@link GraveManager#sweepOrphanedMarkers(MinecraftServer)}).</li>
 *   <li>{@code listballs [all] [page]}: audit list of every grave totem holding a dragon ball, in the caller's
 *       current dimension or (with {@code all}) every loaded dimension. Each line gives the dimension, the exact
 *       block coordinates, which set and star each held ball is, the recorded owner, and the totem's age. The
 *       coordinate is clickable: it pre-fills the removal command for that totem so an admin can review before
 *       committing.</li>
 *   <li>{@code removeball <dimension> <x> <y> <z> [confirm]}: remove exactly ONE totem the admin has personally
 *       decided is a duplicate. Without {@code confirm} it only NAMES what stands there (a dry run). With
 *       {@code confirm} it removes that single totem and writes the removal to the {@code [audit]} trail.</li>
 * </ul>
 *
 * <h2>Why there is no bulk clear</h2>
 *
 * <p>A bug-created duplicate totem and a legitimately earned death/logout totem are byte-identical in the grave
 * data: there is no marker to tell them apart. A "clear all ball totems" operation would therefore destroy earned
 * totems along with duplicates, so it deliberately does not exist. Removal is single-target, coordinate-explicit,
 * and confirmation-gated on purpose. The list exists to let an operator triage the duplicates by hand.
 *
 * <p>Registration and gating: this command is constructed inside the {@code Commands} module block of
 * {@code ModuleCommands.registerCommands}, which returns early when {@code PublicContent.moduleEntitled("Commands")}
 * is false, so it declines to REGISTER on a tier that was never granted the commands module. OP is enforced by the
 * base builder's {@code requires(...)} gate, the same way the {@code cleannames} path already was.
 */
public class CommandGrave extends ShuruisUtilitiesCommandBuilder
{
    // how many totems to print per page of listballs; keeps a big world's output from flooding chat.
    private static final int PAGE_SIZE = 8;

    public CommandGrave(boolean enabled)
    {
        super(enabled);
    }

    @Override
    public @NotNull String getPrimaryAlias()
    {
        return "rggrave";
    }

    // Legacy hidden alias: /sugrave still works, folded onto command.rggrave and hidden from the client tree.
    @Override
    protected String[] getDefaultSecondaryAliases()
    {
        return new String[] { "sugrave" };
    }

    @Override
    public boolean canConsoleUseCommand()
    {
        return true;
    }

    @Override
    public DefaultPermissionLevel getPermissionLevel()
    {
        return DefaultPermissionLevel.OP;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> setExecution()
    {
        return baseBuilder
                .then(Commands.literal("cleannames")
                        .executes(ctx -> execute(ctx, "cleannames")))
                .then(Commands.literal("listballs")
                        .executes(ctx -> execute(ctx, "list"))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> execute(ctx, "list")))
                        .then(Commands.literal("all")
                                .executes(ctx -> execute(ctx, "listall"))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> execute(ctx, "listall")))))
                .then(Commands.literal("removeball")
                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> execute(ctx, "removeball"))
                                        .then(Commands.literal("confirm")
                                                .executes(ctx -> execute(ctx, "removeball_confirm"))))));
    }

    @Override
    public int processCommandPlayer(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException
    {
        return route(ctx, params);
    }

    @Override
    public int processCommandConsole(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException
    {
        return route(ctx, params);
    }

    private int route(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException
    {
        switch (params)
        {
            case "list":
                return doList(ctx, false);
            case "listall":
                return doList(ctx, true);
            case "removeball":
                return doRemove(ctx, false);
            case "removeball_confirm":
                return doRemove(ctx, true);
            case "cleannames":
            default:
                return doCleanNames(ctx);
        }
    }

    private int doCleanNames(CommandContext<CommandSourceStack> ctx)
    {
        MinecraftServer server = ctx.getSource().getServer();
        int removed = GraveManager.sweepOrphanedMarkers(server);
        ChatOutputHandler.chatConfirmation(ctx.getSource(),
                "Removed " + removed + " stale death name" + (removed == 1 ? "" : "s") + ".");
        return Command.SINGLE_SUCCESS;
    }

    // listballs

    private int doList(CommandContext<CommandSourceStack> ctx, boolean allDimensions)
    {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();

        List<ServerLevel> levels = new ArrayList<>();
        if (allDimensions)
        {
            for (ServerLevel level : server.getAllLevels())
                if (level != null)
                    levels.add(level);
        }
        else
        {
            ServerLevel here = source.getLevel();
            if (here != null)
                levels.add(here);
        }

        List<TotemLine> lines = new ArrayList<>();
        for (ServerLevel level : levels)
        {
            long now = level.getGameTime();
            for (GraveData data : GraveStorage.get(level).all())
            {
                String balls = describeBalls(data.container());
                if (balls == null)
                    continue; // holds no dragon ball, not an audit target
                lines.add(new TotemLine(level, data, balls, now));
            }
        }

        if (lines.isEmpty())
        {
            ChatOutputHandler.chatNotification(source,
                    allDimensions ? "No ball-holding grave totems in any loaded dimension."
                            : "No ball-holding grave totems in this dimension.");
            return Command.SINGLE_SUCCESS;
        }

        int page = pageArg(ctx);
        int totalPages = (lines.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page > totalPages)
            page = totalPages;
        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, lines.size());

        ChatOutputHandler.chatNotification(source, String.format(
                "Ball-holding grave totems%s: %d total, page %d/%d",
                allDimensions ? " (all loaded dimensions)" : " (this dimension)", lines.size(), page, totalPages));

        for (int i = start; i < end; i++)
            ChatOutputHandler.sendMessage(source, lines.get(i).toComponent());

        return Command.SINGLE_SUCCESS;
    }

    // read the optional page argument; 1 when the caller gave none.
    private int pageArg(CommandContext<CommandSourceStack> ctx)
    {
        try
        {
            return IntegerArgumentType.getInteger(ctx, "page");
        }
        catch (IllegalArgumentException noSuchArg)
        {
            return 1;
        }
    }

    // comma-separated "set star N" for every ball in the container, or null if it holds none.
    private static String describeBalls(Container container)
    {
        if (container == null)
            return null;
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++)
        {
            DragonBallTotemInfo.Info info = DragonBallTotemInfo.describe(container.getItem(i));
            if (info != null)
                parts.add(info.toString());
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    // one audit row: dimension, coords, ball(s), owner, age.
    private static final class TotemLine
    {
        final ServerLevel level;
        final GraveData data;
        final String balls;
        final long nowGameTime;

        TotemLine(ServerLevel level, GraveData data, String balls, long nowGameTime)
        {
            this.level = level;
            this.data = data;
            this.balls = balls;
            this.nowGameTime = nowGameTime;
        }

        String dimId()
        {
            return level.dimension().location().toString();
        }

        MutableComponent toComponent()
        {
            BlockPos pos = data.pos();
            String owner = data.ownerName() == null || data.ownerName().isEmpty() ? "unknown" : data.ownerName();
            String age = ageText();
            String coords = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();

            // the coordinate pre-fills the removal command (no confirm), so an admin reviews the dry run first.
            String prefill = "/rggrave removeball " + dimId() + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
            MutableComponent coordComp = Component.literal("[" + coords + "]")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, prefill))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Click to fill the removal command for this totem"))));

            MutableComponent line = Component.literal(dimId() + " ").withStyle(ChatFormatting.GRAY);
            line.append(coordComp);
            line.append(Component.literal("  " + balls).withStyle(ChatFormatting.GOLD));
            line.append(Component.literal("  owner: " + owner + "  age: " + age).withStyle(ChatFormatting.DARK_GRAY));
            return line;
        }

        String ageText()
        {
            long created = data.createdGameTime();
            if (created == GraveData.UNSTAMPED || nowGameTime < created)
                return "unknown";
            long ticks = nowGameTime - created;
            return ChatOutputHandler.formatTimeDurationReadable(ticks / 20L, false);
        }
    }

    // removeball

    private int doRemove(CommandContext<CommandSourceStack> ctx, boolean confirmed) throws CommandSyntaxException
    {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");

        GraveStorage storage = GraveStorage.get(level);
        GraveData data = storage.get(pos);
        if (data == null)
        {
            ChatOutputHandler.chatError(source, String.format("No grave totem at %s %d, %d, %d.",
                    level.dimension().location(), pos.getX(), pos.getY(), pos.getZ()));
            return Command.SINGLE_SUCCESS;
        }

        String balls = describeBalls(data.container());
        if (balls == null)
        {
            // a grave with no ball is not this command's business; leave it to the normal grave lifecycle.
            ChatOutputHandler.chatError(source, String.format(
                    "The grave at %s %d, %d, %d holds no dragon ball; removeball only targets ball-holding totems.",
                    level.dimension().location(), pos.getX(), pos.getY(), pos.getZ()));
            return Command.SINGLE_SUCCESS;
        }

        String owner = data.ownerName() == null || data.ownerName().isEmpty() ? "unknown" : data.ownerName();
        String descriptor = String.format("%s %d, %d, %d holding %s (owner: %s)",
                level.dimension().location(), pos.getX(), pos.getY(), pos.getZ(), balls, owner);

        if (!confirmed)
        {
            // dry run: NAME what stands there and require an explicit confirm before anything is destroyed.
            ChatOutputHandler.chatWarning(source, "About to remove grave totem: " + descriptor + ".");
            String confirmCmd = "/rggrave removeball " + level.dimension().location() + " "
                    + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " confirm";
            MutableComponent hint = Component.literal("Run ")
                    .withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("[" + confirmCmd + "]")
                            .withStyle(style -> style
                                    .withColor(ChatFormatting.RED)
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, confirmCmd))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                            Component.literal("Click to fill the confirmed removal command")))))
                    .append(Component.literal(" to confirm. This cannot be undone.").withStyle(ChatFormatting.YELLOW));
            ChatOutputHandler.sendMessage(source, hint);
            return Command.SINGLE_SUCCESS;
        }

        // confirmed: remove exactly this one totem and record it. removeGrave clears the fence, head, marker and
        // storage record (it may defer the storage purge a few ticks if the marker's chunk is still draining, but
        // the totem is gone either way). It does NOT drop the contents, which is what we want: this removes a
        // duplicate ball from play, it does not hand it back. Fall back to a bare storage remove only if the
        // grave-manager path itself throws.
        try
        {
            GraveManager.removeGrave(level, pos);
        }
        catch (Throwable t)
        {
            storage.remove(pos);
        }

        AuditLog.log("{} removed dragon ball grave totem at {} holding {} (owner: {})",
                source.getTextName(), String.format("%s %d, %d, %d",
                        level.dimension().location(), pos.getX(), pos.getY(), pos.getZ()),
                balls, owner);

        ChatOutputHandler.chatConfirmation(source, "Removed grave totem: " + descriptor + ".");
        return Command.SINGLE_SUCCESS;
    }
}
