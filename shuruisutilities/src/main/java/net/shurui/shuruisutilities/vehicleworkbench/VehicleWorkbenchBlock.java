package net.shurui.shuruisutilities.vehicleworkbench;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.shurui.shuruisutilities.spaceconsole.SpaceConsoleBlock;

/**
 * A space console that does a job: the full console ({@code space_console_a}) opens the vehicle workbench, the compact
 * one ({@code space_console_b}) the upgrade bench. Same model, facing and shape as the decorative consoles.
 *
 * <p>No block entity, like a crafting table: the menu is built per player and holds nothing, so breaking the console
 * can never destroy parts.
 */
public class VehicleWorkbenchBlock extends SpaceConsoleBlock
{
    private static final Component WORKBENCH = Component.translatable("container.dmz_ragnarok.vehicle_workbench");
    private static final Component UPGRADE = Component.translatable("container.dmz_ragnarok.vehicle_upgrade");

    /** True for the upgrade bench, false for the crafting workbench. */
    private final boolean upgrade;

    public VehicleWorkbenchBlock(Properties properties, VoxelShape northShape, boolean upgrade)
    {
        super(properties, northShape);
        this.upgrade = upgrade;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit)
    {
        if (level.isClientSide)
            return InteractionResult.SUCCESS;
        player.openMenu(provider(level, pos));
        return InteractionResult.CONSUME;
    }

    private MenuProvider provider(Level level, BlockPos pos)
    {
        ContainerLevelAccess access = ContainerLevelAccess.create(level, pos);
        if (upgrade)
            return new SimpleMenuProvider((id, inv, p) -> new VehicleUpgradeMenu(id, inv, access), UPGRADE);
        return new SimpleMenuProvider((id, inv, p) -> new VehicleWorkbenchMenu(id, inv, access), WORKBENCH);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable BlockGetter level, List<Component> tooltip, TooltipFlag flag)
    {
        tooltip.add(Component.translatable(upgrade ? "tooltip.dmz_ragnarok.vehicle_upgrade_bench"
                : "tooltip.dmz_ragnarok.vehicle_workbench").withStyle(ChatFormatting.GRAY));
    }
}
