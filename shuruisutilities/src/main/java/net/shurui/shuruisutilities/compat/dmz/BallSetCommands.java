package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonBallSetDefinition;
import com.dragonminez.common.init.block.custom.DragonBallBlock;
import com.dragonminez.server.events.DragonBallsHandler;
import com.dragonminez.server.world.data.DragonBallSavedData;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Four operator commands for keeping a DragonMineZ ball set tidy, all of the owner's shape {@code /<verb> <set>
 * confirm}. The stated purpose is "ensure only one set exists of each", plus manual control over the dormancy
 * (petrified) state the wish cycle drives.
 *
 * <ul>
 *   <li>{@code /scatter <set> confirm}: wipe every Active and Pending record and every placed ball for the set,
 *       across every hosted level, clear its dormancy, reset the first spawn flag, then scatter once. After this
 *       exactly one set exists, awake.</li>
 *   <li>{@code /gather <set> confirm}: wipe the scattered set from the world (records and blocks), clear its
 *       dormancy, and hand the caller one of every ball in the set. The set now exists only in the caller's
 *       inventory, nowhere in the world. Must be run by a player (there is nobody to hand items to otherwise).</li>
 *   <li>{@code /awaken <set> confirm}: clear the set's dormancy record so its balls are usable and visible again,
 *       without waiting out the timer.</li>
 *   <li>{@code /unawaken <set> confirm}: force the set dormant now, on its normal per set duration, exactly as a
 *       wish would.</li>
 * </ul>
 *
 * <p>These are PLAIN Forge commands with a real {@code .requires(hasPermission(2))} gate, modelled on {@link
 * CeruleanCleanupCommand}, NOT SU's SUCommand framework (whose {@code .requires} gates nothing). The {@code confirm}
 * literal is mandatory on all four, matching the owner's syntax and guarding against a mistyped set wiping content.
 *
 * <p>The {@code <set>} argument suggests the live set ids from {@link DragonBallDefinitions#getBallSets()} (earth,
 * namek, blackstar, super, cerulean). {@code corrupted} is deliberately NOT one of these: it is not a DMZ ball set
 * at all (it is SU's defiled event blocks), so naming it is refused with an explanation rather than acted on.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class BallSetCommands
{
    private BallSetCommands() {}

    // The id a player might reach for that is NOT a DMZ ball set, so it gets a tailored refusal.
    private static final String CORRUPTED = "corrupted";

    // Suggests the live DMZ ball set ids for the <set> argument.
    private static final SuggestionProvider<CommandSourceStack> SET_SUGGESTIONS = (ctx, builder) ->
    {
        try
        {
            List<String> ids = new ArrayList<>();
            for (DragonBallSetDefinition def : DragonBallDefinitions.getBallSets())
                if (def != null && def.getId() != null)
                    ids.add(def.getId());
            return SharedSuggestionProvider.suggest(ids, builder);
        }
        catch (Throwable t)
        {
            return builder.buildFuture();
        }
    };

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event)
    {
        register(event, "scatter", BallSetCommands::scatter);
        register(event, "gather", BallSetCommands::gather);
        register(event, "awaken", BallSetCommands::awaken);
        register(event, "unawaken", BallSetCommands::unawaken);
        // Debug/self-test: report the dormancy state of every live set (active, or dormant with time left). Proves the
        // dormancy fix in game the same way the boot log proves it headlessly. No <set>: it lists them all at once.
        event.getDispatcher().register(
                Commands.literal("dormancystatus")
                        .requires(source -> source.hasPermission(2))
                        .executes(BallSetCommands::dormancyStatus));
    }

    private static int dormancyStatus(CommandContext<CommandSourceStack> ctx)
    {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        List<String> ids = knownSetIds();
        if (ids.isEmpty())
        {
            source.sendSuccess(() -> Component.literal("No DragonMineZ ball sets are loaded.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return Command.SINGLE_SUCCESS;
        }
        for (String setId : ids)
        {
            source.sendSuccess(() -> Component.literal(setId + ": ").withStyle(ChatFormatting.GRAY)
                    .append(BallDormancy.radarStatus(server, setId)), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private interface SetAction
    {
        int run(CommandSourceStack source, String setId);
    }

    private static void register(RegisterCommandsEvent event, String verb, SetAction action)
    {
        event.getDispatcher().register(
                Commands.literal(verb)
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("set", StringArgumentType.word())
                                .suggests(SET_SUGGESTIONS)
                                .then(Commands.literal("confirm")
                                        .executes(ctx -> dispatch(ctx, action)))));
    }

    // Resolve and validate the set argument, then hand off to the verb's action. Keeps the corrupted refusal and
    // the unknown set error in one place so every verb answers them identically.
    private static int dispatch(CommandContext<CommandSourceStack> ctx, SetAction action)
    {
        CommandSourceStack source = ctx.getSource();
        String setId = StringArgumentType.getString(ctx, "set");
        if (CORRUPTED.equalsIgnoreCase(setId))
        {
            source.sendFailure(Component.literal("The corrupted balls are not a DragonMineZ set: they are the "
                    + "defiled event's own blocks, so these commands do not manage them. Use the wish tracking "
                    + "tools for the corrupted set.").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (findSet(setId) == null)
        {
            source.sendFailure(Component.literal("Unknown dragon ball set '" + setId + "'. Known sets: "
                    + String.join(", ", knownSetIds()) + ".").withStyle(ChatFormatting.RED));
            return 0;
        }
        return action.run(source, setId);
    }

    /* scatter: one clean set, awake                                  */

    private static int scatter(CommandSourceStack source, String setId)
    {
        MinecraftServer server = source.getServer();
        int[] removed = wipeEverywhere(server, setId);

        // Reset the first spawn flag on every hosted level the set lives in, so the scatter below lays a fresh set.
        for (ServerLevel level : server.getAllLevels())
        {
            if (level == null)
                continue;
            try
            {
                DragonBallSavedData.get(level).setFirstSpawnComplete(setId, false);
                DragonBallSavedData.get(level).setDirty();
            }
            catch (Throwable ignored)
            {
            }
        }

        // Clear any dormancy first, so the scattered set is awake and the summon gate does not refuse it.
        BallDormancy.forceWake(server, setId);

        // Scatter once. scatterDragonBalls is a no-op on a level the set does not support, so calling it on every
        // level lays the set only in its home dimension(s), wherever this shard hosts them.
        int scattered = 0;
        for (ServerLevel level : server.getAllLevels())
        {
            if (level == null)
                continue;
            try
            {
                DragonBallSetDefinition def = findSet(setId);
                if (def != null && def.supportsDimension(level.dimension()))
                {
                    DragonBallsHandler.scatterDragonBalls(level, setId);
                    scattered++;
                }
            }
            catch (Throwable t)
            {
                LoggingHandler.sulog.warn("[ballset] scatter of {} in {} failed: {}", setId,
                        level.dimension().location(), t.toString());
            }
        }

        final int ra = removed[0];
        final int rp = removed[1];
        final int dims = scattered;
        source.sendSuccess(() -> Component.literal("Scattered a single fresh " + setId + " set (removed " + ra
                + " active + " + rp + " pending first) across " + dims + " home dimension(s).")
                .withStyle(ChatFormatting.GREEN), true);
        LoggingHandler.sulog.info("[ballset] {} scattered {} (wiped {} active + {} pending, {} dims)",
                source.getTextName(), setId, ra, rp, dims);
        return Command.SINGLE_SUCCESS;
    }

    /* gather: pull the whole set into the caller's hands           */

    private static int gather(CommandSourceStack source, String setId)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null)
        {
            source.sendFailure(Component.literal("Run /gather as a player: there is nobody to hand the balls to "
                    + "from the console.").withStyle(ChatFormatting.RED));
            return 0;
        }
        MinecraftServer server = source.getServer();
        int[] removed = wipeEverywhere(server, setId);
        BallDormancy.forceWake(server, setId);

        // Hand the caller one of every ball in the set. The set now exists only in their inventory (nowhere in the
        // world), which is the tidy "one set" outcome for a gather. Items that will not fit drop at their feet.
        int given = SuDragonBallItems.giveSet(player, setId);

        final int ra = removed[0];
        final int rp = removed[1];
        final int g = given;
        source.sendSuccess(() -> Component.literal("Gathered the " + setId + " set into your inventory (" + g
                + " ball(s) given, removed " + ra + " active + " + rp + " pending from the world).")
                .withStyle(ChatFormatting.GREEN), true);
        LoggingHandler.sulog.info("[ballset] {} gathered {} ({} given, wiped {} active + {} pending)",
                source.getTextName(), setId, given, ra, rp);
        return Command.SINGLE_SUCCESS;
    }

    /* awaken / unawaken: manual dormancy control                   */

    private static int awaken(CommandSourceStack source, String setId)
    {
        MinecraftServer server = source.getServer();
        boolean was = BallDormancy.forceWake(server, setId);
        if (was)
        {
            source.sendSuccess(() -> Component.literal("Woke the " + setId + " set: its balls are usable and on "
                    + "radar again.").withStyle(ChatFormatting.GREEN), true);
            LoggingHandler.sulog.info("[ballset] {} woke {}", source.getTextName(), setId);
        }
        else
        {
            source.sendSuccess(() -> Component.literal("The " + setId + " set was not dormant. Nothing to do.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int unawaken(CommandSourceStack source, String setId)
    {
        MinecraftServer server = source.getServer();
        BallDormancy.forceDormant(server, setId);
        source.sendSuccess(() -> Component.literal("Turned the " + setId + " set to stone: dormant on its normal "
                + "duration, hidden from radar and unusable until it wakes.").withStyle(ChatFormatting.GREEN), true);
        LoggingHandler.sulog.info("[ballset] {} forced {} dormant", source.getTextName(), setId);
        return Command.SINGLE_SUCCESS;
    }

    /* shared helpers                                                */

    // Remove every placed ball block (silent, no drop) and clear the active + pending records for one set across
    // every hosted level. Returns {active,pending} removed totals. Mirrors CeruleanCleanupCommand.wipe.
    private static int[] wipeEverywhere(MinecraftServer server, String setId)
    {
        int removedActive = 0;
        int removedPending = 0;
        for (ServerLevel level : server.getAllLevels())
        {
            if (level == null)
                continue;
            try
            {
                DragonBallSavedData data = DragonBallSavedData.get(level);
                Map<Integer, List<BlockPos>> active = data.getActiveBalls(setId);
                for (List<BlockPos> list : active.values())
                {
                    if (list == null)
                        continue;
                    for (BlockPos pos : new ArrayList<>(list))
                    {
                        BlockState state = level.getBlockState(pos);
                        if (state.getBlock() instanceof DragonBallBlock ball && setId.equals(ball.getBallSetId()))
                            level.removeBlock(pos, false); // false: no drop, so nobody is handed a ball
                        removedActive++;
                    }
                    list.clear();
                }
                Map<Integer, List<BlockPos>> pending = data.getPendingBalls(setId);
                for (List<BlockPos> list : pending.values())
                {
                    if (list != null)
                    {
                        removedPending += list.size();
                        list.clear();
                    }
                }
                data.setDirty();
            }
            catch (Throwable t)
            {
                LoggingHandler.sulog.warn("[ballset] could not wipe {} in {}: {}", setId,
                        level.dimension().location(), t.toString());
            }
        }
        return new int[] { removedActive, removedPending };
    }

    private static DragonBallSetDefinition findSet(String setId)
    {
        if (setId == null || setId.isEmpty())
            return null;
        try
        {
            return DragonBallDefinitions.getBallSet(setId);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private static List<String> knownSetIds()
    {
        List<String> ids = new ArrayList<>();
        try
        {
            for (DragonBallSetDefinition def : DragonBallDefinitions.getBallSets())
                if (def != null && def.getId() != null)
                    ids.add(def.getId());
        }
        catch (Throwable ignored)
        {
        }
        return ids;
    }
}
