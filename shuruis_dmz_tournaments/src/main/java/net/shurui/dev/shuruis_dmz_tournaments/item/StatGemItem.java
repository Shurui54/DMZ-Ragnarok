package net.shurui.dev.shuruis_dmz_tournaments.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.shurui.dev.shuruis_dmz_tournaments.dmz.DmzHooks;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Consumable gem: right-click opens a picker to choose ONE of the six DMZ core stats for {@link #amount}. The
 * choice returns as a {@code StatGemChoicePacket}, which re-validates holder, gem and stat before applying and
 * consuming. Needs a created DMZ character.
 */
public class StatGemItem extends Item {
    private final int amount;

    public StatGemItem(int amount, Properties properties) {
        super(properties);
        this.amount = amount;
    }

    public int getAmount() {
        return amount;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            // gem is never consumed here: right-click only opens the picker. The choice returns as
            // StatGemChoicePacket and the server re-validates everything. This pre-check just stops the picker
            // opening with no DMZ character; the key check is authoritative server-side.
            if (!DmzHooks.hasCreatedCharacter(player)) {
                player.displayClientMessage(
                        Component.translatable("item.dmz_ragnarok.stat_gem.no_character")
                                .withStyle(ChatFormatting.RED),
                        true);
                return InteractionResultHolder.fail(stack);
            }
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    net.shurui.dev.shuruis_dmz_tournaments.client.gui.StatGemScreen.open(hand));
            return InteractionResultHolder.success(stack);
        }
        // server side: nothing at use time; apply + consume happen when the picker's choice arrives
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.dmz_ragnarok.stat_gem.tooltip", amount)
                .withStyle(ChatFormatting.GRAY));
    }
}
