package net.shurui.shuruisutilities.wish;

import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.compat.DmzBridge;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The command behind Super Shenron's stat redistribution wish. Not meant to be typed.
 *
 * <p>Same delivery mechanism as {@link net.shurui.shuruisutilities.ritual.SsgKnowledgeCommand}: DragonMineZ persists
 * wishes through a type adapter that only knows its own kinds, so a bespoke {@code Wish} subclass would be written
 * out and then fail to read back. See {@link SuperWishes} for the entry.
 *
 * <h2>DMZ does the work, not us</h2>
 * {@code StatsData.relocateStats(ServerPlayer)} is DMZ's own respec and it was sitting unused. It refunds the six
 * core stats (strength, strike power, resistance, vitality, ki power, energy) down to the race base values and hands
 * the difference back as PENDING ATTRIBUTE POINTS, returning how many it refunded. It also fixes up the max-health
 * modifier when the refund lowers it, and clamps current energy and stamina to the new maxima.
 *
 * <p>Deliberately not reimplemented with the setters: the two places in this suite that zero stats by hand
 * ({@code CharacterSlots} for a fresh character and {@code PrestigeManager}) are WIPES, which is a different
 * operation. A refund has to know each race's base values and the health modifier, and DMZ already does.
 *
 * <p>What it does NOT touch, which is why this is safe to hand a player: bonus stats, forms, training points,
 * skills, alignment, and the release fields all survive. Only purchased core stats come back.
 *
 * <h2>Two things the contract forces on the caller</h2>
 * It returns 0 and changes NOTHING when there is nothing above base to refund, so the player is told rather than
 * left wondering. And it performs no network send of its own, so the resync here is required or the client keeps
 * showing the old stats, the old pending points and a stale health bar.
 */
public final class StatRelocateWishCommand
{
    /** The command DMZ's wish entry runs. {@code %player%} is substituted by DMZ before execution. */
    public static final String COMMAND = "dmzragnarok_wishrelocate %player%";

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event)
    {
        event.getDispatcher().register(Commands.literal("dmzragnarok_wishrelocate")
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

    /**
     * Refund {@code player}'s spent stat points back into pending attribute points.
     *
     * @return true when something was actually refunded
     */
    public static boolean grant(ServerPlayer player)
    {
        if (player == null)
            return false;
        var capOpt = player.getCapability(StatsCapability.INSTANCE).resolve();
        if (capOpt.isEmpty())
        {
            player.sendSystemMessage(Component.translatable("wish.dmz_ragnarok.relocate.no_character"));
            return false;
        }
        StatsData sd = capOpt.get();
        int refunded;
        try
        {
            refunded = sd.relocateStats(player);
        }
        catch (Throwable t)
        {
            // A DMZ change here would otherwise surface as a wish that silently did nothing at all.
            LoggingHandler.sulog.warn("[wish] relocateStats failed for {}: {}",
                    player.getGameProfile().getName(), t.toString());
            return false;
        }
        if (refunded <= 0)
        {
            player.sendSystemMessage(Component.translatable("wish.dmz_ragnarok.relocate.nothing"));
            return false;
        }
        DmzBridge.resyncStats(player);
        player.sendSystemMessage(Component.translatable("wish.dmz_ragnarok.relocate.granted", refunded));
        LoggingHandler.sulog.info("[wish] {} wished for stat redistribution, {} points refunded.",
                player.getGameProfile().getName(), refunded);
        return true;
    }
}
