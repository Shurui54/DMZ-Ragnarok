package net.shurui.shuruisutilities.racing.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/**
 * The track wand: an admin tool that draws a race track in the world (nodes, width, checkpoints, pads and so on).
 * The item itself carries NO logic (a side-safe marker); the editing runs in the Ragnarok Key via a
 * {@code PlayerInteractEvent} handler (R2), like the deletion wand. {@link #canAttackBlock} is false so left-click
 * selects a node instead of breaking a block.
 *
 * <p>Its bound track id lives in the stack NBT (R2); R0 registers the item and its inert tooltip only. Gated in
 * {@code ContentGate} so it is inert on a keyless server.
 */
public class TrackWandItem extends Item
{
    /** Stack-NBT keys: the bound track id and the current edit mode ordinal. */
    public static final String NBT_TRACK_ID = "TrackId";
    public static final String NBT_MODE = "Mode";

    public TrackWandItem(Properties properties)
    {
        super(properties);
    }

    /** The track this wand edits, or empty if it is not bound. */
    public static String getTrackId(ItemStack stack)
    {
        if (stack == null || !stack.hasTag() || !stack.getTag().contains(NBT_TRACK_ID))
            return "";
        return stack.getTag().getString(NBT_TRACK_ID);
    }

    public static void setTrackId(ItemStack stack, String trackId)
    {
        if (stack != null)
            stack.getOrCreateTag().putString(NBT_TRACK_ID, trackId == null ? "" : trackId);
    }

    /** The wand's current edit mode ordinal (defaults to 0 = NODE). */
    public static int getModeOrdinal(ItemStack stack)
    {
        if (stack == null || !stack.hasTag())
            return 0;
        return stack.getTag().getInt(NBT_MODE);
    }

    public static void setModeOrdinal(ItemStack stack, int ordinal)
    {
        if (stack != null)
            stack.getOrCreateTag().putInt(NBT_MODE, ordinal);
    }

    /** Whether this stack is a track wand bound to a track. */
    public static boolean isBoundWand(ItemStack stack)
    {
        return stack != null && !stack.isEmpty()
                && stack.getItem() instanceof TrackWandItem
                && !getTrackId(stack).isEmpty();
    }

    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player)
    {
        // Left-click selects the nearest node (handled in the key's editor), so never break a block.
        return false;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand)
    {
        ItemStack stack = player.getItemInHand(hand);
        // Right-click air (not sneaking) with a bound wand opens the editor GUI, client-side. Sneak + right-click air
        // still cycles the in-world edit mode (server-side, in the key), so leave that to the interaction handler.
        if (hand == InteractionHand.MAIN_HAND && level.isClientSide && isBoundWand(stack) && !player.isShiftKeyDown())
        {
            final String id = getTrackId(stack);
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> net.shurui.shuruisutilities.racing.client.RaceScreens.openTrackEditor(id));
            return InteractionResultHolder.success(stack);
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public boolean isFoil(ItemStack stack)
    {
        return true;
    }

    @Override
    public boolean canBeDepleted()
    {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag)
    {
        tooltip.add(Component.translatable("item.dmz_ragnarok.race_track_wand.tip1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.dmz_ragnarok.race_track_wand.tip2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.dmz_ragnarok.race_track_wand.admin").withStyle(ChatFormatting.DARK_GRAY));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
