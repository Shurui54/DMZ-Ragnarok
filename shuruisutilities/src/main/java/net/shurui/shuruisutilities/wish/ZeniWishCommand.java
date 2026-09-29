package net.shurui.shuruisutilities.wish;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.api.key.EconomyHooks;
import net.shurui.shuruisutilities.economy.EconomyManager;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The command behind Super Shenron's "a fortune in zeni" wish. Not meant to be typed.
 *
 * <p>Same delivery mechanism as {@link net.shurui.shuruisutilities.ritual.SsgKnowledgeCommand}, and for the same
 * reason: DragonMineZ persists wishes through a type adapter that only knows its own kinds, so a bespoke
 * {@code Wish} subclass would be written out and then fail to read back. See {@link SuperWishes} for the entry.
 *
 * <p>Permission level 2, and DMZ runs wish commands from the SERVER's own command source, so a player cannot reach
 * this by typing it.
 *
 * <h2>Why this one checks the economy and the other two do not</h2>
 * The Super ball set is public tier, but the economy is NOT: it lives in the Ragnarok Key, and a keyless server has
 * no economy at all. So this is the one wish of the three that a server can be
 * entitled to summon and yet unable to pay out. {@link SuperWishes} keeps the row off the list entirely on such a
 * server rather than offering a wish that can only fail, and this check is the backstop for the gap between the
 * list being built and the wish being taken.
 */
public final class ZeniWishCommand
{
    /** The command DMZ's wish entry runs. {@code %player%} is substituted by DMZ before execution. */
    public static final String COMMAND = "dmzragnarok_wishzeni %player%";

    /** What the wish pays. A flat sum, in the same spirit as the fixed training-point and senzu wishes beside it. */
    public static final long ZENI_AMOUNT = 1_000_000L;

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event)
    {
        // The economy is private, so without it the command is kept out of the tree (the wish row is not offered
        // then either). Asked per use rather than at registration, after the key answer has settled.
        event.getDispatcher().register(Commands.literal("dmzragnarok_wishzeni")
                .requires(source -> source.hasPermission(2) && economyLive())
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
     * Pay {@code player} the wish amount.
     *
     * @return true when the balance was credited
     */
    public static boolean grant(ServerPlayer player)
    {
        if (player == null)
            return false;
        if (!economyLive())
        {
            // Reachable only if the list was built while the economy was live and the wish taken after it went
            // away. Saying so is better than crediting a balance nothing else on this server can read or spend.
            player.sendSystemMessage(Component.translatable("wish.dmz_ragnarok.zeni.no_economy"));
            return false;
        }
        long balance = EconomyManager.earn(player.getGameProfile().getId(), ZENI_AMOUNT);
        player.sendSystemMessage(Component.translatable("wish.dmz_ragnarok.zeni.granted", ZENI_AMOUNT, balance));
        LoggingHandler.sulog.info("[wish] {} wished for {} zeni, balance now {}.",
                player.getGameProfile().getName(), ZENI_AMOUNT, balance);
        return true;
    }

    /** Whether the zeni wish can pay out here: the Ragnarok Key installed the economy ({@link EconomyHooks}). */
    public static boolean economyLive()
    {
        return EconomyHooks.available();
    }
}
