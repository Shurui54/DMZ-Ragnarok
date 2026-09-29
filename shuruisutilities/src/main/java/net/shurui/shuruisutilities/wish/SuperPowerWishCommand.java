package net.shurui.shuruisutilities.wish;

import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Stats;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.character.StatCapOverrides;
import net.shurui.shuruisutilities.compat.DmzBridge;
import net.shurui.shuruisutilities.stats.StatCapBypass;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The command behind Super Shenron's power wish. Not meant to be typed.
 *
 * <p>It replaced the old Super-ball kit (owner decision): on every server, keyed or not, Super Shenron offers
 * {@link #TP_AMOUNT} training points and a permanent quarter more of each of the six trained DMZ stats, past the stat
 * cap. A character may take it {@link #MAX_USES} times. The count lives in the player's persistent data under
 * {@link #USES_KEY} and travels with the character slot ({@code CharacterSlots} captures and restores it), so every
 * slot has its own three; {@code SuSsj5Wish.visibleTo} hides the row from a character that has used them all, so no
 * one spends the balls on a wish that would be refused.
 *
 * <p>Same delivery mechanism as {@link StatRelocateWishCommand}: a DMZ {@code CommandWish} pointing at an op-level
 * command, run from the server's own source, so a player cannot reach it by typing it.
 *
 * <h2>How the numbers are applied</h2>
 * The training points are added with DMZ's no-share form ({@code addTrainingPoints(float, false)}), which fires no
 * {@code TPGainEvent}, so no multiplier or cap scales the wish: it is exactly {@link #TP_AMOUNT}. Each of the six
 * trained stats then gains a quarter of its current value, rounded down, once per wish.
 *
 * <h2>It bypasses the stat cap (owner decision)</h2>
 * A Super dragon ball wish is not bound by DMZ's stat maximum. The stats are written through DMZ's setter with
 * {@link StatCapBypass} active, the same path {@code /dmzstats set|add} uses, so the clamp stands down. DMZ re-clamps
 * every stat on each capability load (login, respawn, dimension change, slot switch), so each result is then recorded
 * through {@link StatCapOverrides#recordAfterCommand}, still inside the bypass: a value above the cap is stored as the
 * character's override and re-asserted after every re-clamp, exactly like an above-cap command value.
 */
public final class SuperPowerWishCommand
{
    /** The command DMZ's wish entry runs. {@code %player%} is substituted by DMZ before execution. */
    public static final String COMMAND = "dmzragnarok_wishsuperpower %player%";

    /** The training points the wish pays. */
    public static final float TP_AMOUNT = 100_000_000F;

    /** The six trained stats, as DMZ's {@code Stats.setStat} and {@code StatCapOverrides} both take them. */
    private static final String[] STAT_KEYS = { "STR", "SKP", "RES", "VIT", "PWR", "ENE" };

    /** How many times one character (one character slot) may take the wish. */
    public static final int MAX_USES = 3;

    /** Persistent-data key of the per-character use count; absent means none used. */
    public static final String USES_KEY = "dmzr_superpower_wishes";

    /** How many times this character has taken the wish. */
    public static int uses(ServerPlayer player)
    {
        return player == null ? 0 : player.getPersistentData().getInt(USES_KEY);
    }

    /** True once this character has taken the wish {@link #MAX_USES} times. */
    public static boolean exhausted(ServerPlayer player)
    {
        return uses(player) >= MAX_USES;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event)
    {
        event.getDispatcher().register(Commands.literal("dmzragnarok_wishsuperpower")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", StringArgumentType.string())
                        .executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "player")))));
    }

    private static int run(CommandSourceStack source, String name)
    {
        ServerPlayer player = source.getServer().getPlayerList().getPlayerByName(name);
        if (player == null)
            return 0;
        return grant(player) ? 1 : 0;
    }

    /** Pay the wish. Returns true when it was granted. */
    public static boolean grant(ServerPlayer player)
    {
        if (player == null)
            return false;
        if (exhausted(player))
        {
            // Normally unreachable (the row is hidden once the count is spent); a stale list must not pay a fourth.
            player.sendSystemMessage(Component.translatable("wish.dmz_ragnarok.superpower.exhausted", MAX_USES));
            return false;
        }
        var capOpt = player.getCapability(StatsCapability.INSTANCE).resolve();
        if (capOpt.isEmpty())
        {
            player.sendSystemMessage(Component.translatable("wish.dmz_ragnarok.superpower.no_character"));
            return false;
        }
        StatsData sd = capOpt.get();
        try
        {
            sd.getResources().addTrainingPoints(TP_AMOUNT, false);
            Stats s = sd.getStats();
            int[] now = { s.getStrength(), s.getStrikePower(), s.getResistance(), s.getVitality(), s.getKiPower(),
                    s.getEnergy() };
            StatCapBypass.enter();
            try
            {
                for (int i = 0; i < STAT_KEYS.length; i++)
                {
                    int current = Math.max(0, now[i]);
                    long raised = (long) current + current / 4;
                    s.setStat(STAT_KEYS[i], (int) Math.min(Integer.MAX_VALUE, raised));
                    // Above the cap: remembered as this character's override so DMZ's next re-clamp cannot undo it.
                    StatCapOverrides.recordAfterCommand(player, STAT_KEYS[i]);
                }
            }
            finally
            {
                StatCapBypass.exit();
            }
        }
        catch (Throwable t)
        {
            // A DMZ change here would otherwise surface as a wish that silently did nothing at all.
            LoggingHandler.sulog.warn("[wish] super power wish failed for {}: {}",
                    player.getGameProfile().getName(), t.toString());
            return false;
        }
        int used = uses(player) + 1;
        player.getPersistentData().putInt(USES_KEY, used);
        DmzBridge.resyncStats(player);
        player.sendSystemMessage(Component.translatable("wish.dmz_ragnarok.superpower.granted",
                String.format("%,d", (long) TP_AMOUNT)));
        player.sendSystemMessage(Component.translatable("wish.dmz_ragnarok.superpower.remaining",
                MAX_USES - used, MAX_USES));
        LoggingHandler.sulog.info("[wish] {} wished for Super Shenron's power: {} TP and +25% on each stat.",
                player.getGameProfile().getName(), (long) TP_AMOUNT);
        return true;
    }
}
