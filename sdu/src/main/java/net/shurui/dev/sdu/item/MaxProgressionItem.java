package net.shurui.dev.sdu.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.shurui.dev.sdu.compat.DmzMaxProgression;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Admin debug item: on use, maxes the holder's whole DMZ progression (techniques, six stats, forms +
 * mastery, skills). OP (permission level 2) or creative gated, never consumed. All DMZ work goes through
 * {@link DmzMaxProgression}, which fails soft per system, so if forms cannot be granted the techniques
 * and stats still apply.
 */
public class MaxProgressionItem extends Item {

    public MaxProgressionItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            // Success on the client too so the arm swings; all real work is server-authoritative.
            return InteractionResultHolder.success(stack);
        }

        // OP-or-creative gate, mirroring sdu's admin-item convention (SduPerms.canEdit == hasPermissions(2)).
        if (!serverPlayer.hasPermissions(2) && !serverPlayer.getAbilities().instabuild) {
            serverPlayer.sendSystemMessage(Component.translatable("message.dmz_ragnarok.npc.maxprogression.denied")
                    .withStyle(ChatFormatting.RED));
            return InteractionResultHolder.fail(stack);
        }

        DmzMaxProgression.Result r = DmzMaxProgression.maxAll(serverPlayer);
        if (!r.dmzAvailable) {
            serverPlayer.sendSystemMessage(Component.translatable("message.dmz_ragnarok.npc.maxprogression.no_stats")
                    .withStyle(ChatFormatting.RED));
            return InteractionResultHolder.fail(stack);
        }

        sendSummary(serverPlayer, r);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.2F);
        return InteractionResultHolder.success(stack);
    }

    /** One clear line per system, red for any that failed, so the admin sees exactly what applied. */
    private static void sendSummary(ServerPlayer player, DmzMaxProgression.Result r) {
        player.sendSystemMessage(Component.translatable("message.dmz_ragnarok.npc.maxprogression.header")
                .withStyle(ChatFormatting.GOLD));

        player.sendSystemMessage(line(r.techniquesApplied, Component.translatable(
                "message.dmz_ragnarok.npc.maxprogression.techniques", r.techniqueCount, DmzMaxProgression.TECHNIQUE_EXPERIENCE)));

        Component statsLine = r.statsCapped
                ? Component.translatable("message.dmz_ragnarok.npc.maxprogression.stats_capped",
                        DmzMaxProgression.STAT_TARGET, r.effectiveStatValue)
                : Component.translatable("message.dmz_ragnarok.npc.maxprogression.stats", r.effectiveStatValue);
        player.sendSystemMessage(line(r.statsApplied, statsLine));

        player.sendSystemMessage(line(r.formsApplied,
                Component.translatable("message.dmz_ragnarok.npc.maxprogression.forms")));

        player.sendSystemMessage(line(r.skillsApplied,
                Component.translatable("message.dmz_ragnarok.npc.maxprogression.skills", r.skillCount)));
    }

    private static Component line(boolean applied, Component body) {
        return body.copy().withStyle(applied ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.dmz_ragnarok.npc.maxprogression.desc").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.dmz_ragnarok.npc.maxprogression.admin").withStyle(ChatFormatting.DARK_RED));
    }
}
