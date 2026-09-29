package net.shurui.dev.sdu.compat.tinkers;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

// Tinkers' Construct melee-tool detection for weapon blocking. Only reached when tconstruct is loaded
// (WeaponBlocking guards the call). Tinkers tools do not extend SwordItem or TieredItem, so the plain item
// tests miss them; Tinkers tags every melee tool as tconstruct:modifiable/melee/weapon, which we match with
// the plain Minecraft tag API. That reads a data-driven tag and never classloads a Tinkers class, so it is
// safe by construction even though it lives behind the ModList guard by convention.
public final class TinkersWeaponCompat {

    private static final TagKey<Item> TINKERS_MELEE_WEAPON =
            TagKey.create(Registries.ITEM, new ResourceLocation("tconstruct", "modifiable/melee/weapon"));

    private TinkersWeaponCompat() {
    }

    public static boolean isMeleeWeapon(ItemStack stack) {
        return stack.is(TINKERS_MELEE_WEAPON);
    }
}
