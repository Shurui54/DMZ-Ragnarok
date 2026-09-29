package net.shurui.shuruisutilities.compat.dmz;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.brigadier.Command;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import com.dragonminez.common.init.block.custom.DragonBallBlock;
import com.dragonminez.server.world.data.DragonBallSavedData;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * A standalone maintenance command to remove the cerulean dragon balls that spawned against intent, plus their stale
 * records, from THIS shard's worlds. It is deliberately independent of the cross-shard ball authority (which ships off
 * by default), because Shurui wants these balls gone regardless of whether that feature is ever enabled.
 *
 * <h2>Why they exist and why this is safe to remove</h2>
 *
 * <p>Cerulean was never meant to spawn yet ("when we add Planet Cereal we can make that a thing"), but its set carried
 * {@code COPIES_NONE = 0}, which DragonMineZ floors to 1 ({@code getCopies()}), so it scattered anyway. The confinement
 * fix ({@code SuDragonBallDefinitions} now points cerulean at the not-yet-existent {@code planet_cereal}) stops any
 * FUTURE scatter, but the balls already placed on the live shards remain. This command clears them: it is not deleting
 * content anyone was meant to have.
 *
 * <h2>Usage</h2>
 *
 * <ul>
 *   <li>{@code /sucerulean report} - a DRY RUN. Scans every level this shard hosts and prints how many cerulean balls
 *       (active and pending), which stars, which dimensions and which coordinates would be removed. Touches nothing.</li>
 *   <li>{@code /sucerulean confirm} - performs the removal: silent, NO DROPS (so nobody is handed a cerulean ball), and
 *       clears the cerulean active, pending and first-spawn records from each level's {@link DragonBallSavedData}.
 *       Writes a run-once marker so an accidental repeat is refused; use {@code confirm force} to run again.</li>
 * </ul>
 *
 * <p>Each shard has its own copy of these balls in its own world files, so run this on each shard (the open world twins
 * and main). It is a safe no-op on a shard that has none, and it says so. Requires operator permission level 2.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class CeruleanCleanupCommand
{
    private CeruleanCleanupCommand() {}

    private static final String SET_ID = "cerulean";
    private static final String MARKER_FILE = "cerulean-cleanup.done";

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event)
    {
        event.getDispatcher().register(
                Commands.literal("sucerulean")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("report")
                                .executes(ctx -> report(ctx.getSource())))
                        .then(Commands.literal("confirm")
                                .executes(ctx -> confirm(ctx.getSource(), false))
                                .then(Commands.literal("force")
                                        .executes(ctx -> confirm(ctx.getSource(), true)))));
    }

    /** One level's cerulean tally, for the dry run readout. */
    private record Found(String dim, int active, int pending, List<String> lines) {}

    private static int report(CommandSourceStack source)
    {
        MinecraftServer server = source.getServer();
        List<Found> found = scan(server);
        int totalActive = found.stream().mapToInt(Found::active).sum();
        int totalPending = found.stream().mapToInt(Found::pending).sum();
        if (totalActive == 0 && totalPending == 0)
        {
            source.sendSuccess(() -> Component.literal("No cerulean dragon balls found on this shard. Nothing to do.")
                    .withStyle(ChatFormatting.GREEN), false);
            return Command.SINGLE_SUCCESS;
        }
        source.sendSuccess(() -> Component.literal("Cerulean cleanup DRY RUN (nothing removed):")
                .withStyle(ChatFormatting.YELLOW), false);
        for (Found f : found)
        {
            source.sendSuccess(() -> Component.literal(
                    "  " + f.dim() + ": " + f.active() + " active, " + f.pending() + " pending")
                    .withStyle(ChatFormatting.GRAY), false);
            for (String line : f.lines())
            {
                source.sendSuccess(() -> Component.literal("    " + line).withStyle(ChatFormatting.DARK_GRAY), false);
            }
        }
        final int a = totalActive;
        final int p = totalPending;
        source.sendSuccess(() -> Component.literal(
                "Total: " + a + " active + " + p + " pending. Run /sucerulean confirm to remove them.")
                .withStyle(ChatFormatting.YELLOW), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int confirm(CommandSourceStack source, boolean force)
    {
        MinecraftServer server = source.getServer();
        File marker = new File(ShuruisUtilities.getSUDirectory(), MARKER_FILE);
        if (marker.isFile() && !force)
        {
            source.sendSuccess(() -> Component.literal(
                    "Cerulean cleanup already ran on this shard. Use /sucerulean confirm force to run again.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return Command.SINGLE_SUCCESS;
        }
        int removedActive = 0;
        int removedPending = 0;
        int levels = 0;
        for (ServerLevel level : server.getAllLevels())
        {
            if (level == null)
            {
                continue;
            }
            int[] counts = wipe(level);
            if (counts[0] > 0 || counts[1] > 0)
            {
                levels++;
                removedActive += counts[0];
                removedPending += counts[1];
                LoggingHandler.sulog.warn("[cerulean-cleanup] {}: removed {} active + {} pending cerulean ball(s).",
                        level.dimension().location(), counts[0], counts[1]);
            }
        }
        writeMarker(marker);
        final int ra = removedActive;
        final int rp = removedPending;
        final int lv = levels;
        if (removedActive == 0 && removedPending == 0)
        {
            source.sendSuccess(() -> Component.literal("No cerulean dragon balls found on this shard. Nothing removed.")
                    .withStyle(ChatFormatting.GREEN), false);
        }
        else
        {
            source.sendSuccess(() -> Component.literal(
                    "Cerulean cleanup done: removed " + ra + " active + " + rp + " pending ball(s) across " + lv
                            + " dimension(s). See the server log for the per-dimension detail.")
                    .withStyle(ChatFormatting.GREEN), false);
        }
        LoggingHandler.sulog.warn("[cerulean-cleanup] Complete on this shard: {} active + {} pending removed across {} "
                + "dimension(s).", removedActive, removedPending, levels);
        return Command.SINGLE_SUCCESS;
    }

    /** Scan (read only) every level for cerulean balls, gathering the dry-run detail. */
    private static List<Found> scan(MinecraftServer server)
    {
        List<Found> out = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels())
        {
            if (level == null)
            {
                continue;
            }
            try
            {
                DragonBallSavedData data = DragonBallSavedData.get(level);
                Map<Integer, List<BlockPos>> active = data.getActiveBalls(SET_ID);
                Map<Integer, List<BlockPos>> pending = data.getPendingBalls(SET_ID);
                int a = 0;
                int p = 0;
                List<String> lines = new ArrayList<>();
                for (Map.Entry<Integer, List<BlockPos>> e : active.entrySet())
                {
                    if (e.getValue() == null)
                    {
                        continue;
                    }
                    for (BlockPos pos : e.getValue())
                    {
                        a++;
                        lines.add("star " + e.getKey() + " active at " + pos.getX() + "," + pos.getY() + ","
                                + pos.getZ());
                    }
                }
                for (Map.Entry<Integer, List<BlockPos>> e : pending.entrySet())
                {
                    if (e.getValue() == null)
                    {
                        continue;
                    }
                    for (BlockPos pos : e.getValue())
                    {
                        p++;
                        lines.add("star " + e.getKey() + " pending near " + pos.getX() + ",_," + pos.getZ());
                    }
                }
                if (a > 0 || p > 0)
                {
                    out.add(new Found(level.dimension().location().toString(), a, p, lines));
                }
            }
            catch (Throwable t)
            {
                LoggingHandler.sulog.warn("[cerulean-cleanup] Could not scan {}: {}",
                        level.dimension().location(), t.toString());
            }
        }
        return out;
    }

    /** Remove every cerulean ball block (silent, no drop) and clear the records in one level. Returns {active,pending}. */
    private static int[] wipe(ServerLevel level)
    {
        int removedActive = 0;
        int removedPending = 0;
        try
        {
            DragonBallSavedData data = DragonBallSavedData.get(level);
            Map<Integer, List<BlockPos>> active = data.getActiveBalls(SET_ID);
            for (List<BlockPos> list : active.values())
            {
                if (list == null)
                {
                    continue;
                }
                for (BlockPos pos : new ArrayList<>(list))
                {
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof DragonBallBlock ball && SET_ID.equals(ball.getBallSetId()))
                    {
                        level.removeBlock(pos, false); // false: no drop, so nobody is handed a cerulean ball
                    }
                    removedActive++;
                }
                list.clear();
            }
            Map<Integer, List<BlockPos>> pending = data.getPendingBalls(SET_ID);
            for (List<BlockPos> list : pending.values())
            {
                if (list != null)
                {
                    removedPending += list.size();
                    list.clear();
                }
            }
            data.setFirstSpawnComplete(SET_ID, false); // marks dirty; cerulean cannot re-scatter (no home dimension)
            data.setDirty();
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[cerulean-cleanup] Could not wipe {}: {}",
                    level.dimension().location(), t.toString());
        }
        return new int[] { removedActive, removedPending };
    }

    private static void writeMarker(File marker)
    {
        try
        {
            marker.getParentFile().mkdirs();
            Files.write(marker.toPath(),
                    ("cerulean cleanup ran at " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            LoggingHandler.sulog.warn("[cerulean-cleanup] Could not write the run-once marker: {}", e.toString());
        }
    }
}
