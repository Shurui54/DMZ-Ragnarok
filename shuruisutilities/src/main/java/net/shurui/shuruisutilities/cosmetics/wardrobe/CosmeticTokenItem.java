package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.List;
import java.util.UUID;

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

/**
 * The tradeable, item form of a cosmetic copy. One registered item; the copy it stands for is entirely in its NBT
 * ({@link CosmeticTransferGate#TAG_COSMETIC}, {@link CosmeticTransferGate#TAG_INSTANCE} and, for a tooltip only,
 * {@link CosmeticTransferGate#TAG_QUALITY}).
 *
 * <h2>The item is a bearer handle, never the authority</h2>
 * Holding one lets you redeem it, trade it, auction it, mail it or drop it, but only because a server-side ledger
 * row ({@link CosmeticOwnership}, flagged {@code escrowed}) says the copy is presently a live token. The NBT is
 * whatever the last hand to touch it wrote, so nothing here trusts it: {@code CosmeticGrants.redeem} (Ragnarok Key) and
 * {@link CosmeticTransferGate#mayMove} both re-read the ledger, and a forged or copied token that names no
 * outstanding row grants and moves nothing. See {@link CosmeticTransferGate}.
 *
 * <p>Right-clicking redeems, server side, exactly once. A copy of the token (or the same token used twice) finds
 * the row no longer escrowed and is consumed without a second grant, which is the safe direction. Redemption is
 * routed through {@code CosmeticGrants} (reached through {@code CosmeticHooks}; keyless it only says why
 * nothing happened) so the wardrobe screen's redeem button and this both share one path.
 */
public class CosmeticTokenItem extends Item
{
    public CosmeticTokenItem()
    {
        // Copies of the SAME instance stack (redeem shrinks by one); different instances carry different NBT and
        // never stack, so there is no need to force stacksTo(1).
        super(new Item.Properties());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand)
    {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer sp))
            return InteractionResultHolder.success(stack);
        net.shurui.shuruisutilities.api.key.CosmeticHooks.get().redeemToken(sp, stack, hand);
        // Whatever the outcome, the click itself succeeds: the redeem path messages the player and shrinks the
        // stack on success. Returning the (possibly shrunk) held stack keeps the hand in sync.
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public Component getName(ItemStack stack)
    {
        String id = CosmeticTransferGate.cosmeticIdOf(stack);
        CosmeticDef def = id == null ? null : CosmeticCatalog.get(id);
        String name = def != null && def.displayName != null && !def.displayName.isBlank() ? def.displayName
                : (id == null ? "" : id);
        return Component.translatable("item.dmz_ragnarok.cosmetic_token.named", name);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag)
    {
        String id = CosmeticTransferGate.cosmeticIdOf(stack);
        if (id == null)
        {
            tooltip.add(Component.translatable("item.dmz_ragnarok.cosmetic_token.invalid")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        CosmeticQuality quality = CosmeticTransferGate.displayQualityOf(stack);
        tooltip.add(Component.translatable("item.dmz_ragnarok.cosmetic_token.quality",
                Component.translatable("gui.dmz_ragnarok.cosmetics.quality." + quality.key))
                .withStyle(ChatFormatting.GRAY));
        CosmeticDef def = CosmeticCatalog.get(id);
        if (def != null && !def.tradeable)
            // The kind was flipped back to bound after this token was minted: it may still be redeemed, but no
            // route will move it any more. Say so, rather than let a listing silently fail.
            tooltip.add(Component.translatable("item.dmz_ragnarok.cosmetic_token.redeem_only")
                    .withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("item.dmz_ragnarok.cosmetic_token.redeem_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Build a token stack for one copy. The single place a token's NBT shape is written. */
    public static ItemStack build(String catalogId, UUID instanceId, CosmeticQuality quality)
    {
        ItemStack stack = new ItemStack(CosmeticContentItems.COSMETIC_TOKEN.get());
        var tag = stack.getOrCreateTag();
        tag.putString(CosmeticTransferGate.TAG_COSMETIC, catalogId);
        tag.putUUID(CosmeticTransferGate.TAG_INSTANCE, instanceId);
        tag.putString(CosmeticTransferGate.TAG_QUALITY,
                (quality == null ? CosmeticQuality.NORMAL : quality).key);
        return stack;
    }
}
