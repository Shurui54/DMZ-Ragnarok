package net.shurui.shuruisutilities.vehicleworkbench;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** A vehicle upgrade kit: inert on its own, spent at the compact console to raise a vehicle one tier. */
public class VehicleUpgradeKitItem extends Item
{
    public VehicleUpgradeKitItem(Properties props)
    {
        super(props);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag)
    {
        tooltip.add(Component.translatable(getDescriptionId() + ".desc").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.dmz_ragnarok.vehicle_upgrade_kit").withStyle(ChatFormatting.DARK_GRAY));
    }
}
