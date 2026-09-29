package net.shurui.shuruisutilities.god;

import java.util.List;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * The Angel's staff: soulbound to whoever currently holds the Angel title.
 *
 * <h2>Bound to a person, not just marked</h2>
 * The holder's uuid is written into the stack when it is issued. Every restriction below reads THAT, not the title,
 * so a staff that somehow leaves its owner is still identifiable and still refuses to work for anyone else.
 *
 * <h2>What "soulbound" means here</h2>
 * It survives death (kept through the drop), cannot be handed to another player, and is reclaimed when the title
 * moves on. Its container restriction lives in {@link AngelStaffGuard}: it may go in a backpack or a player vault,
 * because those are the player's own storage, but never into a chest or any other world container, which would be a
 * way to leave a unique bound item lying around for someone else to find.
 */
public class AngelStaffItem extends Item
{
    /** Owner uuid stamped on the stack when it is issued. */
    private static final String KEY_OWNER = "rg_angel_owner";

    public AngelStaffItem(Properties properties)
    {
        super(properties);
    }

    /** Build a staff bound to this player. */
    public static ItemStack issueTo(ServerPlayer player)
    {
        ItemStack stack = new ItemStack(AngelItems.ANGEL_STAFF.get());
        stack.getOrCreateTag().putUUID(KEY_OWNER, player.getUUID());
        return stack;
    }

    /** The uuid this staff is bound to, or null when it somehow carries none. */
    public static UUID ownerOf(ItemStack stack)
    {
        if (stack.isEmpty() || !(stack.getItem() instanceof AngelStaffItem))
            return null;
        CompoundTag tag = stack.getTag();
        return tag != null && tag.hasUUID(KEY_OWNER) ? tag.getUUID(KEY_OWNER) : null;
    }

    /** True when this stack is a staff bound to this player. */
    public static boolean isOwnedBy(ItemStack stack, Player player)
    {
        UUID owner = ownerOf(stack);
        return owner != null && player != null && owner.equals(player.getUUID());
    }

    /** True for any angel staff, bound or not. */
    public static boolean isStaff(ItemStack stack)
    {
        return !stack.isEmpty() && stack.getItem() instanceof AngelStaffItem;
    }

    /** No enchant sheen: these are ki, not enchanted gear, and the glint reads as the wrong thing entirely. */
    @Override
    public boolean isFoil(ItemStack stack)
    {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag)
    {
        tooltip.add(Component.literal("Soulbound to the Angel").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("Returns when the title passes on").withStyle(ChatFormatting.DARK_GRAY));
    }
}
