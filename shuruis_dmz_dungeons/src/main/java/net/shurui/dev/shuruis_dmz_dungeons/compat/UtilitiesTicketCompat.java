package net.shurui.dev.shuruis_dmz_dungeons.compat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.dev.shuruis_dmz_dungeons.item.FloorTicketItem;
import net.shurui.dev.shuruis_dmz_dungeons.registry.ModItems;

// makes SU's existing ss_ticket item double as the dungeon FLOOR ticket, rather than shipping a second item. Imports
// NO shuruisutilities type: resolves ss_ticket by its registry id and only reads/writes the dungeon's own NBT key
// (FloorTicketItem.TARGET_KEY), so it never classloads an SU class. Degrades cleanly: SU absent -> floorTicketItem()
// falls back to the dungeon's own floor_ticket and no handler is registered.
//
// ss_ticket, not bike_voucher (the hoverbike ticket, excluded). Raid tickets are untouched: a raid ticket is an
// ss_ticket with NO floor tag, and this handler acts ONLY on a floor-tagged stack.
public final class UtilitiesTicketCompat {

    public static final String SU_MODID = "dmz_ragnarok";

    // SU's raid-win ticket, reused as the dungeon floor ticket. NOT bike_voucher (the hoverbike ticket).
    private static final ResourceLocation SS_TICKET = new ResourceLocation(SU_MODID, "ss_ticket");

    private UtilitiesTicketCompat() {
    }

    // the item /rg dungeon ticket give stamps: SU's ss_ticket when it resolves, else the dungeon's own floor_ticket.
    // resolves by registry id only, so safe anywhere.
    public static Item floorTicketItem() {
        Item su = suTicket();
        return su != null ? su : ModItems.FLOOR_TICKET.get();
    }

    // resolve SU's ss_ticket, or null when the item is missing. Throwable-safe: mod presence is not item presence
    // (a stale registry could lack ss_ticket).
    public static Item suTicket() {
        try {
            Item item = ForgeRegistries.ITEMS.getValue(SS_TICKET);
            return (item != null && item != Items.AIR) ? item : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // true when this player has REDEEMED a ticket for this floor (what a ticket-locked portal asks). Checked off the
    // recorded unlock, not inventory: possession made the ticket a keycard you keep forever and could lend round a
    // party. Server-side only.
    public static boolean hasUnlockedFloor(net.minecraft.world.entity.player.Player player, int floor) {
        if (player == null || floor <= 0) {
            return false;
        }
        net.minecraft.server.MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        return net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonTicketUnlocks.get(server)
                .isUnlocked(player.getUUID(), floor);
    }

    // registered from the mod constructor ONLY when SU is present. Handles ss_ticket's right-click, since it is a
    // plain item with no use() of its own.
    public static void init() {
        MinecraftForge.EVENT_BUS.register(new UtilitiesTicketCompat());
        Shuruis_dmz_dungeons.LOGGER.info(
                "[{}] Shurui's Utilities present; ss_ticket doubles as the dungeon floor ticket when it carries a "
                        + "floor target.", Shuruis_dmz_dungeons.MODID);
    }

    // redeem on right-click, ONLY for an ss_ticket carrying a floor target; an untagged one (a raid ticket) is left
    // alone. Routes through the same key redeem as FloorTicketItem so both ticket items mean the same thing. Nobody is moved.
    @SubscribeEvent
    public void onRightClick(PlayerInteractEvent.RightClickItem event) {
        ItemStack stack = event.getItemStack();
        Item su = suTicket();
        if (su == null || stack.getItem() != su) {
            return;
        }
        int target = FloorTicketItem.getTarget(stack);
        if (target <= 0) {
            return; // a plain raid ticket: not ours to touch.
        }
        // consume the interaction so nothing else (SU/vanilla) also processes this right-click.
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getLevel().isClientSide) {
            return; // teleport is server-authoritative; the client just plays the swing.
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // PRIVATE (S22b): redeeming lives in the Ragnarok Key. Keyless the hook answers with the "needs the key" line.
        net.shurui.dev.shuruis_dmz_dungeons.api.key.DungeonRewardHooks.get().redeemSuTicket(player, stack, target);
    }

    // tooltip parity: show the floor a stamped ss_ticket unlocks. an untagged one gets no extra line.
    @SubscribeEvent
    public void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        Item su = suTicket();
        if (su == null || stack.getItem() != su) {
            return;
        }
        int target = FloorTicketItem.getTarget(stack);
        if (target > 0) {
            event.getToolTip().add(Component.translatable("tooltip.dmz_ragnarok.dungeons.floor_ticket",
                    target).withStyle(ChatFormatting.AQUA));
        }
    }
}
