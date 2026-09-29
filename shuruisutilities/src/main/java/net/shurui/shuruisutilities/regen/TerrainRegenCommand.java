package net.shurui.shuruisutilities.regen;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * {@code /terrainregen}: see what the repair engine is actually doing, and make it do it now.
 *
 * <p>This exists because the system is invisible from inside the game. A crater that does not come back can mean the
 * gamerule is off, or that nothing was captured, or that the repair ran and was declined, or simply that thirty seconds
 * have not passed yet, and all four look exactly the same while you stand in the hole. Several rounds of diagnosis were
 * spent on that ambiguity. The counters below separate the cases in one command:
 *
 * <ul>
 *   <li><b>captured</b> far below the size of the hole means the CAPTURE hooks are missing a destruction path.</li>
 *   <li><b>captured</b> right but <b>restored</b> zero means the drain is not running or nothing is due.</li>
 *   <li><b>declined</b> large means the space was taken back before the repair reached it, which is the engine
 *       deliberately refusing to overwrite what is there now.</li>
 * </ul>
 *
 * <p>{@code delay} exists for the same reason: testing against a thirty second timer means every failed attempt costs
 * half a minute before you know it failed.
 */
public final class TerrainRegenCommand
{
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event)
    {
        event.getDispatcher().register(Commands.literal("terrainregen")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("now").executes(ctx -> now(ctx.getSource())))
                .then(Commands.literal("delay")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 3600))
                                .executes(ctx -> delay(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "seconds"))))));
    }

    private static int status(CommandSourceStack source)
    {
        ServerLevel level = source.getLevel();
        boolean on = TerrainRegenRule.isEnabled(level);
        long[] tally = TerrainRegenService.tallyOf(level);
        source.sendSuccess(() -> Component.literal("Terrain regen in " + level.dimension().location())
                .withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal("  gamerule terrainRegen: " + (on ? "on" : "off"))
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        source.sendSuccess(() -> Component.literal("  waiting to be put back: "
                + TerrainRegenService.pendingCount(level)), false);
        source.sendSuccess(() -> Component.literal("  since restart - captured " + tally[0]
                + ", restored " + tally[1] + ", declined " + tally[2]
                + ", reconnected " + tally[3]), false);
        source.sendSuccess(() -> Component.literal("  waiting to reconnect: "
                + TerrainRegenService.pendingReconnectCount(level)), false);
        source.sendSuccess(() -> Component.literal("  NPCs waiting for their ground back: "
                + TerrainRegenService.pendingNpcCount(level)), false);
        source.sendSuccess(() -> Component.literal("  delay: "
                + (TerrainRegenService.restoreDelayTicks() / 20) + "s"), false);
        return 1;
    }

    private static int now(CommandSourceStack source)
    {
        ServerLevel level = source.getLevel();
        int restored = TerrainRegenService.restoreNow(level);
        source.sendSuccess(() -> Component.literal("Put back " + restored + " block(s); "
                + TerrainRegenService.pendingCount(level) + " still owed.").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int delay(CommandSourceStack source, int seconds)
    {
        TerrainRegenService.setRestoreDelayTicks(seconds * 20);
        source.sendSuccess(() -> Component.literal("Terrain regen delay set to " + seconds
                + "s. This applies to blocks destroyed from now on.").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }
}
