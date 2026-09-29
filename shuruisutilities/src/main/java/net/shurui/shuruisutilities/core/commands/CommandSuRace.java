package net.shurui.shuruisutilities.core.commands;

import java.util.List;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;
import net.shurui.shuruisutilities.compat.dmz.RaceBundleCompat;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

import org.jetbrains.annotations.NotNull;

/**
 * {@code /rgrace refresh [raceId|all]}: operator command to force SU's bundled DMZ race definitions onto disk, even
 * over an install that already has (possibly stale or admin-edited) folders. Takes a backup before overwriting, reports
 * per race what it did, and reloads DMZ once at the end if anything changed. All player-facing text is routed through
 * {@link ChatOutputHandler}, which resolves each literal to a {@code Component.translatable} lang key (server-safe: no
 * client-only classes). DMZ is only ever touched through {@link RaceBundleCompat}'s ModList guard.
 */
public class CommandSuRace extends ShuruisUtilitiesCommandBuilder
{
    public CommandSuRace(boolean enabled)
    {
        super(enabled);
    }

    private static final SuggestionProvider<CommandSourceStack> RACE_SUGGESTIONS = (ctx, builder) ->
    {
        List<String> ids = RaceBundleCompat.bundledRaceIds();
        ids.add("all");
        return SharedSuggestionProvider.suggest(ids, builder);
    };

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> setExecution()
    {
        return baseBuilder
                .then(Commands.literal("refresh")
                        .executes(ctx -> refresh(ctx, null))
                        .then(Commands.argument("race", StringArgumentType.word())
                                .suggests(RACE_SUGGESTIONS)
                                .executes(ctx -> refresh(ctx, StringArgumentType.getString(ctx, "race")))))
                // Rebuilds the OTHER half of the shadow dragon setup: the per-player transformation state and the
                // technique registry. Kept beside refresh because "Omega does not work" can be caused by either, and
                // an admin chasing it should not have to know which.
                .then(Commands.literal("transforms")
                        .executes(this::repairTransforms))
                .executes(ctx -> execute(ctx, "blank"));
    }

    @Override
    public int execute(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException
    {
        ChatOutputHandler.chatNotification(ctx.getSource(), "Usage: /surace refresh [raceId|all] | /surace transforms");
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /rgrace transforms}: re-apply the shadow dragon transformation to every online player entitled to it,
     * and re-register the shadow dragon and role techniques.
     *
     * <p>Repairs only. It never GRANTS an entitlement, so it cannot be used to hand someone Omega who did not earn
     * it; a player with no entitlement is skipped entirely. The underlying apply only ever raises the skill level,
     * so this is safe to run repeatedly and is a no-op for anyone already working.
     */
    private int repairTransforms(CommandContext<CommandSourceStack> ctx)
    {
        CommandSourceStack src = ctx.getSource();

        boolean techniques = net.shurui.shuruisutilities.compat.dmz.ShadowDragonRepair.reregisterTechniques();
        ChatOutputHandler.chatNotification(src, techniques
                ? "Shadow dragon and role techniques re-registered."
                : "Techniques were NOT re-registered (DragonMineZ missing, or see the log).");

        // The Omega form is the Ragnarok Key's (feature shadowform): without it there is nothing to re-apply.
        if (!net.shurui.shuruisutilities.api.key.ShadowFormHooks.available())
        {
            ChatOutputHandler.chatWarning(src, "The shadow dragon transformation requires the Ragnarok Key; "
                    + "nothing to re-apply.");
            return Command.SINGLE_SUCCESS;
        }
        List<net.shurui.shuruisutilities.compat.dmz.ShadowDragonRepair.RepairLine> lines =
                net.shurui.shuruisutilities.api.key.ShadowFormHooks.get().repairOnline(src.getServer());
        if (lines.isEmpty())
        {
            ChatOutputHandler.chatWarning(src, "No online player holds the Omega entitlement; nothing to repair.");
            return Command.SINGLE_SUCCESS;
        }
        ChatOutputHandler.chatNotification(src, "Shadow dragon transformation repair:");
        for (net.shurui.shuruisutilities.compat.dmz.ShadowDragonRepair.RepairLine line : lines)
            ChatOutputHandler.chatConfirmation(src, "%s: %s", line.player, line.outcome);
        return Command.SINGLE_SUCCESS;
    }

    private int refresh(CommandContext<CommandSourceStack> ctx, String raceId)
    {
        CommandSourceStack src = ctx.getSource();
        if (!RaceBundleCompat.isRefreshAvailable())
        {
            ChatOutputHandler.chatError(src, "Race refresh is unavailable (DragonMineZ not present or the feature is disabled).");
            return Command.SINGLE_SUCCESS;
        }
        if (raceId != null && !raceId.equalsIgnoreCase("all") && !RaceBundleCompat.isBundledRace(raceId))
        {
            ChatOutputHandler.chatError(src, "Unknown bundled race: %s", raceId);
            return Command.SINGLE_SUCCESS;
        }

        List<RaceBundleCompat.RaceRefreshLine> results = RaceBundleCompat.forceRefresh(raceId);
        if (results.isEmpty())
        {
            ChatOutputHandler.chatWarning(src, "No bundled races were processed.");
            return Command.SINGLE_SUCCESS;
        }

        ChatOutputHandler.chatNotification(src, "Bundled race refresh:");
        for (RaceBundleCompat.RaceRefreshLine line : results)
        {
            switch (line.outcome)
            {
                case RaceBundleCompat.RaceRefreshLine.WROTE_FRESH:
                    ChatOutputHandler.chatConfirmation(src, "%s: wrote missing race (v%s).", line.raceId, String.valueOf(line.toVersion));
                    break;
                case RaceBundleCompat.RaceRefreshLine.UPGRADED:
                    ChatOutputHandler.chatConfirmation(src, "%s: restored to v%s (was v%s), backup taken.", line.raceId, String.valueOf(line.toVersion), versionLabel(line.fromVersion));
                    break;
                case RaceBundleCompat.RaceRefreshLine.ALREADY_CURRENT:
                    ChatOutputHandler.chatNotification(src, "%s: already current (v%s).", line.raceId, String.valueOf(line.toVersion));
                    break;
                default:
                    ChatOutputHandler.chatError(src, "%s: failed (see server log).", line.raceId);
                    break;
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    // -1 is the "pre-versioning / unknown" sentinel; show it as a word rather than a bare -1.
    private static String versionLabel(int version)
    {
        return version < 0 ? "unknown" : String.valueOf(version);
    }

    @Override
    public @NotNull String getPrimaryAlias()
    {
        return "rgrace";
    }

    // Legacy hidden alias: /surace still works, folded onto command.rgrace and hidden from the client tree.
    @Override
    protected String[] getDefaultSecondaryAliases()
    {
        return new String[] { "surace" };
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
}
