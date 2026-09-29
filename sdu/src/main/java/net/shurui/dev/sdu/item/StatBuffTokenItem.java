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
 * A consumable token that grants a temporary stat-purchase discount buff. Right-click to apply {@link #percent}
 * cheaper stat purchases for 2.5 minutes. The actual discount is applied in a later wave (a stat-cost mixin that reads
 * {@link TokenBuffStore#strongestActive}); this item only writes the buff into the store.
 *
 * <p>Single-slot on purpose, mirroring {@link TpBuffTokenItem}: only one stat-discount gem may run at a time. Using
 * another while one is live is refused with the time left and the gem is NOT consumed, so a player cannot stack two
 * gems or waste one. TP gems and stat gems are gated INDEPENDENTLY: a running TP boost does not block a stat gem and a
 * running stat discount does not block a TP gem, because the owner's "when one is active" means one gem of the same
 * kind. To make any buff block any buff, change the {@code Category.STAT} check here to also test
 * {@code Category.TP} (and the same in {@link TpBuffTokenItem}). Any older stacked entries from before this change
 * expire naturally, and only the strongest of them counts while they do (see
 * {@link TokenBuffStore#strongestActive}); only new uses are blocked.
 */
public class StatBuffTokenItem extends Item implements TintedGem {

    // 2.5 minutes. Deliberately much shorter than the TP gem's 30: the stat discount is the "cooldown" window in which
    // no second stat gem of any tier may be used (see use()), so it doubles as a short one-at-a-time lockout, not a
    // long-running economy buff. The TP gem keeps its 30, gated on the independent TP category.
    private static final long DURATION_MILLIS = 150L * 1000L; // 2.5 minutes (150 s / 3000 ticks)

    /** Discount strength as a fraction (0.10 for 10%). */
    private final double percent;
    /** Per-tier tint (0xFFrrggbb) multiplied onto the shared greyscale base texture. */
    private final int tint;

    public StatBuffTokenItem(Properties properties, double percent, int tint) {
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
            if (!TokenBuffHooks.get().useGem(serverPlayer, stack, TokenBuffStore.Category.STAT, percent, DURATION_MILLIS)) {
                return InteractionResultHolder.fail(stack);
            }
        }
        // Success on both sides so the client swings the arm.
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.dmz_ragnarok.npc.token.stat.cheaper", displayPercent()).withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.translatable("tooltip.dmz_ragnarok.npc.token.stat.duration").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.dmz_ragnarok.npc.token.stat.one_at_a_time").withStyle(ChatFormatting.DARK_GRAY));
    }
}
