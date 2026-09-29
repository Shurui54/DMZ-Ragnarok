package net.shurui.shuruisutilities.saibaman;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Admin tool that instantly completes a growing {@link SaibamanCropBlock}: right-click a planted saibaman crop with it
 * and the crop jumps straight to its final, harvestable stage. Its tier is unaffected (the tier is still decided by how
 * long a player charged ki beside the crop, so an instantly finished but untended crop harvests a low tier).
 *
 * <p>Gated to staff: it only works for a player in creative mode or with permission level 2 (a standard op). For anyone
 * else the click passes through as if the tool did nothing, so it is inert in survival hands even if one is obtained.
 * The tool is never consumed.
 */
public class SaibamanGrowItem extends Item
{
    public SaibamanGrowItem(Properties properties)
    {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context)
    {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof SaibamanCropBlock))
        {
            return InteractionResult.PASS;
        }
        Player player = context.getPlayer();
        boolean allowed = player != null && (player.getAbilities().instabuild || player.hasPermissions(2));
        if (!allowed)
        {
            return InteractionResult.PASS;
        }
        // client just reports success so the arm swings; the server owns the outcome.
        if (level.isClientSide)
        {
            return InteractionResult.SUCCESS;
        }
        if (state.getValue(SaibamanCropBlock.AGE) >= SaibamanCropBlock.MAX_AGE)
        {
            // already fully grown: nothing to do, do not spam feedback.
            return InteractionResult.PASS;
        }
        // jump straight to the harvestable stage. UPDATE_ALL so the model repaints and the block's ticker gate is
        // re-evaluated (a mature crop stops ticking). The block entity's accrued charged ticks are left intact, so the
        // harvested pet's tier still reflects however much the crop was tended.
        level.setBlock(pos, state.setValue(SaibamanCropBlock.AGE, SaibamanCropBlock.MAX_AGE), Block.UPDATE_ALL);
        if (player instanceof ServerPlayer serverPlayer)
        {
            serverPlayer.sendSystemMessage(Component.translatable("message.dmz_ragnarok.core.saibaman.grown"));
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag)
    {
        tooltip.add(Component.translatable("item.dmz_ragnarok.saibaman_grow_tool.tip").withStyle(ChatFormatting.GRAY));
    }
}
