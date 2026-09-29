package net.shurui.shuruisutilities.timemachine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Places the life-size time machine multiblock. A custom item rather than a plain {@code BlockItem} because a single
 * right-click has to fit-check and set the whole footprint at once (see {@link TimeMachineStructure}).
 *
 * <p>The anchor (centre-bottom cell) is the cell against the clicked face, so the structure sits where a normal block
 * would and rises/spreads from there; the model is centred over the anchor. It faces the player (like a furnace). If
 * any cell of the footprint is blocked or outside the world, placement is refused with a message rather than clipping
 * into terrain, so a machine is only ever placed whole.
 */
public final class TimeMachineItem extends Item
{
    public TimeMachineItem(Properties properties)
    {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context)
    {
        Level level = context.getLevel();
        BlockPos anchor = context.getClickedPos().relative(context.getClickedFace());
        Player player = context.getPlayer();

        if (!TimeMachineStructure.canPlace(level, anchor))
        {
            if (player != null && !level.isClientSide)
            {
                player.displayClientMessage(
                        Component.translatable("message.dmz_ragnarok.core.time_machine_no_room"), true);
            }
            return InteractionResult.FAIL;
        }

        if (!level.isClientSide)
        {
            // face the player, like a furnace: the footprint is symmetric so this only turns the rendered model.
            Direction facing = context.getHorizontalDirection().getOpposite();
            TimeMachineStructure.place(level, anchor, facing);
            if (player == null || !player.getAbilities().instabuild)
            {
                ItemStack stack = context.getItemInHand();
                stack.shrink(1);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
