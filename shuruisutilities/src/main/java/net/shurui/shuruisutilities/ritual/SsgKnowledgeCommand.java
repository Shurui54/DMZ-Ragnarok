package net.shurui.shuruisutilities.ritual;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The command behind the "knowledge of Super Saiyan God" wish. Not meant to be typed.
 *
 * <p>Same delivery mechanism as the SSJ5 fusion command (the Ragnarok Key's {@code Ssj5FusionCommand}), and for the
 * same reason: DragonMineZ can only persist and reload its own wish kinds, so a wish of ours has to be a
 * {@code CommandWish} pointing at a command. See {@link net.shurui.shuruisutilities.compat.dmz.SuSsgKnowledgeWish} for the
 * entry itself.
 *
 * <p>What it grants is KNOWLEDGE, not the form: it sets {@link WishRitualStore#SSG_KNOWLEDGE}, which is what lets
 * {@link SsgRitualManager} recognise a charge ring around this player. The form itself still has to be earned by
 * performing the ritual and then bought with TP. Wishing twice is harmless; the flag is idempotent and the player is
 * told they already had it rather than silently spending a wish on nothing.
 *
 * <p>Permission level 2, and DMZ runs wish commands from the SERVER's own command source, so a player cannot reach
 * this by typing it.
 */
public final class SsgKnowledgeCommand
{
    /** The command DMZ's wish entry runs. {@code %player%} is substituted by DMZ before execution. */
    public static final String COMMAND = "dmzragnarok_ssgknowledge %player%";

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event)
    {
        event.getDispatcher().register(Commands.literal("dmzragnarok_ssgknowledge")
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
     * Give {@code player} the knowledge if they are a saiyan, at or above the configured level floor, who does not
     * already have it.
     *
     * <p>The single eligibility authority is {@link WishRitualManager#ssgKnowledgeWishBlocked}, shared with the
     * server-side grant gate in {@code MixinDmzGrantWish}. In normal play that gate cancels an ineligible take before
     * this command ever runs; routing the command through the same check means the level floor is still enforced (and
     * the same reasons told) even if the mixin ever degrades to no-op. It sends the specific reason itself.
     *
     * @return true when the flag was newly set
     */
    public static boolean grant(ServerPlayer player)
    {
        if (player == null)
            return false;
        if (WishRitualManager.ssgKnowledgeWishBlocked(player))
            return false;
        WishRitualStore.grantSsgKnowledge(player);
        // Tell the player exactly how many allies the ring needs, from the same config the ritual scan enforces, so the
        // instruction can never disagree with what actually completes the ritual.
        player.sendSystemMessage(Component.translatable("ritual.dmz_ragnarok.ssg.knowledge.granted",
                Math.max(1, SUConfig.ssgRitualRequiredChargers)));
        LoggingHandler.sulog.info("[ritual] {} wished for the knowledge of Super Saiyan God.",
                player.getGameProfile().getName());
        return true;
    }
}
