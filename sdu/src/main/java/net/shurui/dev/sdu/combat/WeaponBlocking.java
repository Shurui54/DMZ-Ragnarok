package net.shurui.dev.sdu.combat;

import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.fml.ModList;
import net.shurui.dev.sdu.compat.tinkers.TinkersWeaponCompat;

// Central weapon test for "may I raise a DMZ guard while holding this?". DMZ already supports weapon
// blocking everywhere except the client input: the server flag (UpdateStatC2S) is set with no item check and
// the damage reduction (CombatEvent) only reads Status.isBlocking(), so a weapon block gets the exact same
// reduction as an unarmed one (no new balance number needed). The only thing that stopped it was DMZ's
// client gate demanding both hands empty; WeaponBlockInputMixin makes that gate treat a recognised weapon as
// an empty hand, and this method decides what counts as a weapon.
//
// Detection is vanilla-first, then Forge tool actions for modded swords that do not extend SwordItem, then
// Tinkers melee tools through the gated compat, then any TieredItem as a catch-all (all tiered tools carry a
// melee attack-damage modifier). Shields, bows, food and plain items are NOT weapons, so vanilla shield
// blocking, bows and eating stay untouched.
public final class WeaponBlocking {

    private static final boolean TINKERS_LOADED = ModList.get().isLoaded("tconstruct");

    private WeaponBlocking() {
    }

    public static boolean isBlockableWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        // vanilla and DMZ melee weapons (DMZ's WeaponItem, ZSword and Power Pole all extend SwordItem)
        if (item instanceof SwordItem || item instanceof AxeItem || item instanceof TridentItem) {
            return true;
        }
        // modded swords that only declare the Forge sword tool actions
        if (stack.canPerformAction(ToolActions.SWORD_DIG) || stack.canPerformAction(ToolActions.SWORD_SWEEP)) {
            return true;
        }
        // Tinkers' Construct melee tools, only consulted when tconstruct is present
        if (TINKERS_LOADED && TinkersWeaponCompat.isMeleeWeapon(stack)) {
            return true;
        }
        // any other tiered tool: all carry a melee attack-damage modifier
        return item instanceof TieredItem;
    }
}
