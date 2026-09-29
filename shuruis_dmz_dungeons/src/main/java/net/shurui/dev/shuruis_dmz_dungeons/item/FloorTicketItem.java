package net.shurui.dev.shuruis_dmz_dungeons.item;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

// the floor-unlock ticket. ONE item type whose TARGET FLOOR is carried in its stack NBT (like a DMZ Z-Soul, not a
// separate item per value).
//
// right-clicking does NOT travel: it UNLOCKS that floor for the holder. A ticket-locked portal turns players away until
// they redeem the matching ticket, then lets them through like any other portal, so the boss gate and cooldown on that
// portal still apply. The old behaviour warped straight in and skipped them.
//
// target set by /rg dungeon ticket give <floor> (op). Redeeming is PRIVATE and lives in the Ragnarok Key
// (DungeonRewardHooks.useFloorTicket); it consumes only when it actually grants something.
public class FloorTicketItem extends Item {

    // int on the stack's tag: the 1-based floor this ticket warps to. absent / <= 0 -> unconfigured.
    public static final String TARGET_KEY = "sdd_ticket_floor";

    public FloorTicketItem(Properties properties) {
        super(properties);
    }

    // the target floor stamped on a stack, or 0 if unset.
    public static int getTarget(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null ? Math.max(0, tag.getInt(TARGET_KEY)) : 0;
    }

    // a ticket stack configured for the given floor.
    //
    // Also names it. This item doubles as SU's raid ticket, whose base name is "Ragnarok Ticket", so a floor ticket left
    // with the base name would read as raid currency and be offered to a raid shop by mistake. The hover name is the
    // only per-stack way to tell them apart at a glance, since both are the same item id and differ only by the
    // TARGET_KEY tag. Naming the FLOOR one rather than the raid one is deliberate: a raid ticket must keep completely
    // empty NBT so an operator's shop can match it exactly, and a custom name is NBT.
    public static ItemStack forFloor(net.minecraft.world.item.Item item, int floor, int count) {
        ItemStack stack = new ItemStack(item, count);
        int target = Math.max(1, floor);
        stack.getOrCreateTag().putInt(TARGET_KEY, target);
        stack.setHoverName(net.minecraft.network.chat.Component.translatable(
                "item.dmz_ragnarok.floor_ticket.named", target));
        return stack;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // the unlock is server-authoritative; succeed on the client so the swing plays and the interaction reaches
        // the server.
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }

        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return InteractionResultHolder.fail(stack);
        }

        // PRIVATE (S22b): redeeming lives in the Ragnarok Key. Keyless the hook answers with the "needs the key" line
        // and the ticket is not consumed, exactly as before.
        return net.shurui.dev.shuruis_dmz_dungeons.api.key.DungeonRewardHooks.get().useFloorTicket(serverPlayer, stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        int target = getTarget(stack);
        if (target > 0) {
            tooltip.add(Component.translatable("tooltip.dmz_ragnarok.dungeons.floor_ticket", target)
                    .withStyle(ChatFormatting.AQUA));
        } else {
            tooltip.add(Component.translatable("tooltip.dmz_ragnarok.dungeons.floor_ticket_unset")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return getTarget(stack) > 0 || super.isFoil(stack);
    }
}
