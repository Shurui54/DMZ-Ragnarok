package net.shurui.shuruisutilities.ritual;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.server.level.ServerPlayer;

/**
 * The idol in hand. Right clicking it opens the confirmation, which is where the ritual is actually accepted.
 *
 * <p>A {@link BlockItem} so it can still be placed: a player who finds the idol before they are strong enough to use it
 * should be able to set it down somewhere safe rather than carry it around or lose it. Placing is the SHIFT action,
 * because the common case by far is opening the prompt and a player who has just hunted this down should not have to
 * aim away from the ground to read it.
 *
 * <p>The prompt is built on the SERVER and pushed, rather than opened blind on the client, so the numbers it shows are
 * the same ones the ritual will check a moment later. A GUI that promised something the server then refused would be
 * worse than no GUI.
 */
public final class ShenronIdolItem extends BlockItem
{
    /** Which dragon this item's idol depicts once placed. Both items place the SAME block, on different states. */
    private final ShenronIdolBlock.IdolDragon dragon;

    public ShenronIdolItem(Block block, Properties properties)
    {
        this(block, properties, ShenronIdolBlock.IdolDragon.SHENRON);
    }

    public ShenronIdolItem(Block block, Properties properties, ShenronIdolBlock.IdolDragon dragon)
    {
        super(block, properties);
        this.dragon = dragon;
    }

    /**
     * Place the state this item's dragon belongs to.
     *
     * <p>One block with a property rather than two blocks: the ritual, the radar registration and the recreation
     * prompt all key on the one block, and a second block would have to be taught to every one of them.
     */
    @Override
    protected net.minecraft.world.level.block.state.BlockState getPlacementState(
            net.minecraft.world.item.context.BlockPlaceContext context)
    {
        net.minecraft.world.level.block.state.BlockState state = super.getPlacementState(context);
        return state == null ? null : state.setValue(ShenronIdolBlock.DRAGON, dragon);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand)
    {
        ItemStack held = player.getItemInHand(hand);
        if (player.isShiftKeyDown())
            return InteractionResultHolder.pass(held); // fall through to placement
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer)
        {
            // The recreation is the Ragnarok Key's (feature rituals); without it the idol does nothing but sit there.
            if (net.shurui.shuruisutilities.api.key.RitualHooks.available())
                net.shurui.shuruisutilities.api.key.RitualHooks.get().openIdolPrompt(serverPlayer);
            else
                serverPlayer.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("ritual.dmz_ragnarok.idol.silent"), true);
        }
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
    }
}
