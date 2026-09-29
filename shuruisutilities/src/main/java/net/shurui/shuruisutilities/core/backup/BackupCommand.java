package net.shurui.shuruisutilities.core.backup;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * {@code /subackups}: see what SU's backups are costing, and clear the excess out.
 *
 * <h2>Why an operator needs this and not just the automatic sweep</h2>
 * The boot sweep trims to the standard retention and says nothing more. This is for the case that prompted it: a
 * server that has already accumulated a pile and wants it gone NOW, at a retention the operator chooses, without a
 * restart and without an admin picking their way through directories by hand next to the live permission data. That
 * last part is the real argument for putting it in game: every delete here goes through the same path guard the
 * rotation uses, so it cannot be aimed at the live SUData the way an `rm` in the wrong directory can.
 *
 * <h2>The last-good copies are never candidates</h2>
 * Only the timestamped rings and interrupted {@code .tmp} copies are ever removed. {@code SUData_backup} and
 * {@code GroupB_backup} are what a restore reaches for first and there is exactly one of each, so no retention
 * number, including 1, will delete them.
 *
 * <p>Registered unconditionally, like {@code /terrainregen}, because a server that is having disk trouble because of
 * its backups is exactly the server whose modules an operator may have switched off.
 */
public final class BackupCommand
{
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event)
    {
        event.getDispatcher().register(Commands.literal("subackups")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> report(ctx.getSource()))
                .then(Commands.literal("status").executes(ctx -> report(ctx.getSource())))
                .then(Commands.literal("clean")
                        .executes(ctx -> clean(ctx.getSource(), BackupMaintenance.KEEP))
                        .then(Commands.argument("keep", IntegerArgumentType.integer(1, 50))
                                .executes(ctx -> clean(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "keep"))))));
    }

    private static int report(CommandSourceStack source)
    {
        int[] counts = BackupMaintenance.counts();
        long bytes = BackupMaintenance.totalSnapshotBytes();

        source.sendSuccess(() -> Component.literal("SU backups").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("  SUData snapshots:  " + counts[0]), false);
        source.sendSuccess(() -> Component.literal("  store snapshots:   " + counts[1]), false);
        source.sendSuccess(() -> Component.literal("  race backups:      " + counts[2]), false);
        source.sendSuccess(() -> Component.literal("  holding " + BackupMaintenance.humanBytes(bytes))
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal(
                "  A snapshot is only taken when the data has actually changed, so a quiet server adds none.")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        source.sendSuccess(() -> Component.literal("  /subackups clean [keep]  to trim to the newest few.")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int clean(CommandSourceStack source, int keep)
    {
        BackupMaintenance.Result result = BackupMaintenance.sweep(keep);
        if (result.total() == 0)
        {
            source.sendSuccess(() -> Component.literal("Nothing to clean: already at " + keep + " or fewer.")
                    .withStyle(ChatFormatting.GRAY), false);
            return Command.SINGLE_SUCCESS;
        }
        // Broadcast to ops (the true flag): this deletes things, so it belongs in the admin log rather than only in
        // the chat of whoever typed it.
        source.sendSuccess(() -> Component.literal("Removed " + result.total() + " old backup folder(s), freeing "
                + BackupMaintenance.humanBytes(result.bytesFreed()) + ".").withStyle(ChatFormatting.GREEN), true);
        source.sendSuccess(() -> Component.literal("  SUData " + result.suDataRemoved()
                + ", stores " + result.groupBRemoved()
                + ", races " + result.raceRemoved()
                + ", interrupted copies " + result.tempRemoved()).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("  The last-good SUData_backup and GroupB_backup were kept.")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        return Command.SINGLE_SUCCESS;
    }
}
