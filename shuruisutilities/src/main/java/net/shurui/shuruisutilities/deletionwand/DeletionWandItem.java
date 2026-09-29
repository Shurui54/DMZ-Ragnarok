package net.shurui.shuruisutilities.deletionwand;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * Admin-only "Deletion Wand": a retextured stick that removes whatever it interacts with.
 * <ul>
 *   <li>Left-click an entity: delete it (server-side, via {@link DeletionWandHandler}'s
 *       {@code AttackEntityEvent} listener).</li>
 *   <li>Right-click while looking at an SU hologram: durable-delete that hologram
 *       (handled in {@code DeletionWandHandler}'s {@code RightClickItem} listener).</li>
 * </ul>
 * The item carries NO deletion logic (a purely cosmetic marker, so it stays side-safe); all deletion runs on
 * the server in {@link DeletionWandHandler}. Gated by su.admin.deletionwand.
 */
public class DeletionWandItem extends Item
{
    public DeletionWandItem(Properties properties)
    {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag)
    {
        tooltip.add(Component.literal("Left-click an entity to delete it").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Right-click to delete the hologram you are looking at")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Admin only").withStyle(ChatFormatting.DARK_GRAY));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
