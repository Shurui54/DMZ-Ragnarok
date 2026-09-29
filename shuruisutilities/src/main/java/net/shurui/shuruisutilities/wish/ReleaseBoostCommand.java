package net.shurui.shuruisutilities.wish;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The command behind Super Shenron's "raise your limit" wish, a permanent +25 to the maximum ki release ceiling. Not
 * meant to be typed.
 *
 * <p>Same delivery mechanism as {@link net.shurui.shuruisutilities.ritual.SsgKnowledgeCommand}: DragonMineZ persists
 * wishes through a type adapter that only knows its own kinds, so a bespoke {@code Wish} subclass would be written out
 * and then fail to read back. See {@link SuperWishes} for the entry.
 *
 * <h2>Why the bonus lives outside DMZ and is applied by mixin</h2>
 * DMZ has no stored release ceiling to bump. It recomputes {@code 50 + potentialunlock * 5} at three sites, and the
 * skill it reads is clamped to a config maximum, so raising the skill is not possible. {@link ReleaseBoostStore} keeps
 * the earned bonus in the SU property store (surviving a character reset, readable offline) and the three release
 * mixins add it on top of that formula.
 *
 * <h2>Why it stacks, and why it is nonetheless capped</h2>
 * Each wish adds another {@link ReleaseBoostStore#BOOST_PER_WISH}, so a player who gathers the balls again is
 * rewarded again rather than hitting a dead wish. But release is a DIRECT linear multiplier on battle power: it scales
 * all damage dealt AND the ki drain, so an unbounded stack is genuinely game breaking. {@link #MAX_TOTAL_BOOST} is the
 * ceiling on the stored total, and past it the wish declines politely rather than paying out and then being clamped
 * silently by the mixins.
 */
public final class ReleaseBoostCommand
{
    /** The command DMZ's wish entry runs. {@code %player%} is substituted by DMZ before execution. */
    public static final String COMMAND = "dmzragnarok_wishrelease %player%";

    /**
     * The hard ceiling on the stored release bonus. Chosen so the stack tops out at a strong but not absurd figure:
     * with DMZ's base 50 ceiling this lets a fully wished player reach 150 (+100) release rather than a runaway total.
     */
    public static final int MAX_TOTAL_BOOST = 100;

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event)
    {
        event.getDispatcher().register(Commands.literal("dmzragnarok_wishrelease")
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
     * Add one wish's worth of release bonus to {@code player}, unless the stored total is already at the cap.
     *
     * @return true when the bonus was actually raised
     */
    public static boolean grant(ServerPlayer player)
    {
        if (player == null)
            return false;
        if (ReleaseBoostStore.bonus(player) >= MAX_TOTAL_BOOST)
        {
            // Refuse rather than pay out and let the mixins clamp it silently: the player is told their release is
            // already at its peak, so a spent wish never disappears without explanation.
            player.sendSystemMessage(Component.translatable("wish.dmz_ragnarok.release.capped"));
            return false;
        }
        int total = ReleaseBoostStore.grant(player);
        // Push the fresh total to the client so the radial release node's ceiling matches the server this instant,
        // not only after the next login resync.
        NetworkUtils.sendTo(new PacketReleaseBoost(total), player);
        player.sendSystemMessage(Component.translatable("wish.dmz_ragnarok.release.granted", total));
        LoggingHandler.sulog.info("[wish] {} wished for a release increase, bonus now +{}.",
                player.getGameProfile().getName(), total);
        return true;
    }
}
