package net.shurui.dev.shuruis_dmz_dungeons.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

// consumable gem that grants fixed DMZ Training Points on right-click. The grant is PRIVATE and lives in the Ragnarok
// Key (DungeonRewardHooks.useTpGem); if DMZ is absent or its TP API shifted the gem is NOT consumed.
public class TpGemItem extends Item {

    private final int tpAmount;
    private final boolean foil;

    public TpGemItem(int tpAmount, Properties properties) {
        this(tpAmount, false, properties);
    }

    public TpGemItem(int tpAmount, boolean foil, Properties properties) {
        super(properties);
        this.tpAmount = tpAmount;
        this.foil = foil;
    }

    public int getTpAmount() {
        return tpAmount;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return foil || super.isFoil(stack);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // TP is a server-side stat. return SUCCESS on the client anyway so the hand-swing plays and the
        // interaction isn't rejected before the server sees it.
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }

        // PRIVATE (S22b): granting the TP lives in the Ragnarok Key. Keyless the hook answers with the "needs the key"
        // line and the gem is not consumed, exactly as before.
        return net.shurui.dev.shuruis_dmz_dungeons.api.key.DungeonRewardHooks.get().useTpGem(player, stack, this);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.dmz_ragnarok.dungeons.tp_gem", tpAmount)
                .withStyle(ChatFormatting.AQUA));
    }
}
