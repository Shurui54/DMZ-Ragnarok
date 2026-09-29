package net.shurui.dev.shuruis_dmz_tournaments.inventory;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.shurui.dev.shuruis_dmz_tournaments.dmz.CuriosBridge;

/**
 * Stashes a contestant's items for a no-items tournament: main inventory, offhand and equipped Curios are
 * saved and cleared, worn armor is left on. Held in the player's persistent NBT so it survives logout and
 * restart, and returned intact when the player leaves the tournament or logs out.
 */
public final class InventoryVault {
    private static final String KEY = "shuruisTournamentStash";

    private InventoryVault() {}

    public static boolean hasStash(ServerPlayer player) {
        return player.getPersistentData().contains(KEY);
    }

    /** Save and clear main inventory + offhand + curios (armor untouched). No-op if already stashed. */
    public static void store(ServerPlayer player) {
        CompoundTag root = player.getPersistentData();
        if (root.contains(KEY)) return;
        Inventory inv = player.getInventory();
        CompoundTag stash = new CompoundTag();

        stash.put("main", takeAll(inv.items));
        stash.put("offhand", takeAll(inv.offhand));

        IItemHandlerModifiable curios = CuriosBridge.getEquipped(player);
        if (curios != null) {
            ListTag list = new ListTag();
            for (int i = 0; i < curios.getSlots(); i++) {
                ItemStack stack = curios.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    list.add(slotTag(i, stack));
                    curios.setStackInSlot(i, ItemStack.EMPTY);
                }
            }
            stash.put("curios", list);
        }

        root.put(KEY, stash);
        player.inventoryMenu.broadcastChanges();
    }

    /** Return everything to the player and drop the stash. No-op if nothing was stashed. */
    public static void restore(ServerPlayer player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(KEY)) return;
        CompoundTag stash = root.getCompound(KEY);
        Inventory inv = player.getInventory();

        putAll(stash.getList("main", Tag.TAG_COMPOUND), inv.items);
        putAll(stash.getList("offhand", Tag.TAG_COMPOUND), inv.offhand);

        IItemHandlerModifiable curios = CuriosBridge.getEquipped(player);
        if (curios != null) {
            for (Tag t : stash.getList("curios", Tag.TAG_COMPOUND)) {
                CompoundTag tag = (CompoundTag) t;
                int slot = tag.getInt("Slot");
                if (slot >= 0 && slot < curios.getSlots()) {
                    curios.setStackInSlot(slot, ItemStack.of(tag));
                }
            }
        }

        root.remove(KEY);
        player.inventoryMenu.broadcastChanges();
    }

    private static ListTag takeAll(NonNullList<ItemStack> slots) {
        ListTag list = new ListTag();
        for (int i = 0; i < slots.size(); i++) {
            ItemStack stack = slots.get(i);
            if (!stack.isEmpty()) {
                list.add(slotTag(i, stack));
                slots.set(i, ItemStack.EMPTY);
            }
        }
        return list;
    }

    private static void putAll(ListTag list, NonNullList<ItemStack> slots) {
        for (Tag t : list) {
            CompoundTag tag = (CompoundTag) t;
            int slot = tag.getInt("Slot");
            if (slot >= 0 && slot < slots.size()) {
                slots.set(slot, ItemStack.of(tag));
            }
        }
    }

    private static CompoundTag slotTag(int slot, ItemStack stack) {
        CompoundTag tag = stack.save(new CompoundTag());
        tag.putInt("Slot", slot);
        return tag;
    }
}
