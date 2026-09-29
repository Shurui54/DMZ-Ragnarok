package net.shurui.dev.sdu.item;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.util.OwnerUuids;

// server-authoritative Shurui's Armor: invincible while any piece is worn (LivingAttackEvent HIGHEST), and a
// wearer lock so only whitelisted UUIDs keep a piece equipped. anyone else is ejected (returned/dropped) with
// a message, both on LivingEquipmentChangeEvent and a per-tick safety net covering /item replace, dispensers,
// mod equips and login-with-armor-on.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class ShuruisArmorHandler {

    // three slots, no helmet.
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static final String EJECT_MESSAGE_KEY = "message.dmz_ragnarok.npc.armor.eject";

    private ShuruisArmorHandler() {
    }

    private static boolean isAllowed(Player player) {
        return OwnerUuids.contains(player.getUUID());
    }

    private static boolean isArmorSlot(EquipmentSlot slot) {
        return slot != null && slot.getType() == EquipmentSlot.Type.ARMOR;
    }

    private static boolean isWearingAnyPiece(Player player) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (ShuruisArmorItem.isPiece(player.getItemBySlot(slot))) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingAttack(LivingAttackEvent event) {
        if (event.getEntity() instanceof Player player && isWearingAnyPiece(player)) {
            event.setCanceled(true);
        }
    }

    // eject the instant a non-whitelisted player equips a piece.
    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        EquipmentSlot slot = event.getSlot();
        if (!isArmorSlot(slot)) {
            return;
        }
        if (!ShuruisArmorItem.isPiece(event.getTo())) {
            return;
        }
        if (isAllowed(player)) {
            return;
        }
        ejectFromSlot(player, slot);
    }

    // per-tick safety net (server END): re-scan the slots for equips that don't fire
    // LivingEquipmentChangeEvent cleanly (/item replace, dispensers, other mods) and login-with-armor-on.
    @SubscribeEvent
    public static void onPlayerTick(net.minecraftforge.event.TickEvent.PlayerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (isAllowed(player)) {
            return;
        }
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (ShuruisArmorItem.isPiece(player.getItemBySlot(slot))) {
                ejectFromSlot(player, slot);
            }
        }
    }

    // move the piece out of the slot back to inventory (drop if full), and warn.
    private static void ejectFromSlot(ServerPlayer player, EquipmentSlot slot) {
        ItemStack stack = player.getItemBySlot(slot);
        if (!ShuruisArmorItem.isPiece(stack)) {
            return;
        }
        player.setItemSlot(slot, ItemStack.EMPTY);
        ItemStack moved = stack.copy();
        player.getInventory().add(moved); // mutates moved down to whatever did not fit
        if (!moved.isEmpty()) {
            player.drop(moved, false);
        }
        player.sendSystemMessage(Component.translatable(EJECT_MESSAGE_KEY));
    }
}
