package net.shurui.shuruisutilities.vehicleworkbench.client;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.vehicleworkbench.VehicleTiers;

/**
 * Shows a vehicle's tier: appended to the item's name line, plus a hint line that it can be upgraded at the compact
 * console. Reads only the stack's own NBT, never a server gate or config.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class VehicleTierTooltip
{
    private VehicleTierTooltip() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event)
    {
        if (!VehicleTiers.isTiered(event.getItemStack()))
            return;
        int tier = VehicleTiers.tier(event.getItemStack());
        List<Component> lines = event.getToolTip();
        if (!lines.isEmpty())
            lines.set(0, lines.get(0).copy().append(" ").append(VehicleTiers.label(tier)));
        lines.add(Math.min(1, lines.size()), Component.translatable("tooltip.dmz_ragnarok.vehicle_tier_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
