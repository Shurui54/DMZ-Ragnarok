package net.shurui.dev.sdu.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.shurui.dev.sdu.api.key.TokenBuffHooks;
import net.shurui.dev.sdu.buff.TokenBuffStore;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A consumable token that grants a temporary TP-gain buff. Right-click to apply {@link #percent} extra TP gain for 30
 * minutes.
 *
 * <p>Single-slot on purpose: only one TP-boost gem may run at a time. Using another while one is live is refused with
 * the time left and the gem is NOT consumed, so a player cannot stack two gems or waste one. The active boost still
 * multiplies on top of the global {@code /tpboost} window and any shrine TP buff (separate sources, see the Ragnarok
 * Key's {@code TokenBuffFeature.onTpGain}); only gem-on-gem is blocked. The buff and its {@link TpBoostEffect} pip
 * both read the same {@link TokenBuffStore} expiry, so the number and the pip cannot disagree.
 */
public class TpBuffTokenItem extends Item implements TintedGem {

    private static final long DURATION_MILLIS = 30L * 60L * 1000L; // 30 minutes

    /** Buff strength as a fraction (0.10 for 10%). */
    private final double percent;
    /** Per-tier tint (0xFFrrggbb) multiplied onto the shared greyscale base texture. */
    private final int tint;

    public TpBuffTokenItem(Properties properties, double percent, int tint) {
        super(properties);
        this.percent = percent;
        this.tint = tint;
    }

    @Override
    public int gemTint() {
        return tint;
    }

    private int displayPercent() {
        return (int) Math.round(percent * 100);
    }

    /**
     * Always sheened, so a BOOST token is never mistaken for the plain lump-sum gem. SU's rune-glint
     * renderer (see {@code RuneGlintTypes}) recolours the sheen green; vanilla's purple would read as
     * "enchanted".
     */
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // Server-authoritative: only the server touches the buff store and consumes the item. The gem logic is the
        // Ragnarok Key's (feature tokenbuffs); without the key the gem is refused and kept.
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            if (!TokenBuffHooks.available()) {
                serverPlayer.sendSystemMessage(Component.translatable(
                        "message.dmz_ragnarok.npc.token.unavailable").withStyle(ChatFormatting.RED));
                return InteractionResultHolder.fail(stack);
            }
            if (!TokenBuffHooks.get().useGem(serverPlayer, stack, TokenBuffStore.Category.TP, percent, DURATION_MILLIS)) {
                return InteractionResultHolder.fail(stack);
            }
        }
        // Success on both sides so the client swings the arm.
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.dmz_ragnarok.npc.token.tp.gain", displayPercent()).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.dmz_ragnarok.npc.token.duration").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.dmz_ragnarok.npc.token.tp.one_at_a_time").withStyle(ChatFormatting.DARK_GRAY));
    }
}
