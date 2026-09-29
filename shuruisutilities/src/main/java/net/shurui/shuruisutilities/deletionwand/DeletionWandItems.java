package net.shurui.shuruisutilities.deletionwand;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registry for the admin {@link DeletionWandItem} ({@code deletion_wand}). A single, un-stackable, EPIC-rarity
 * item with no creative tab (spawned via {@code /give}). Its {@code item/generated} model + texture come from
 * the asset pipeline; the lang key {@code item.shuruisutilities.deletion_wand} is provided there too.
 * Registered to the mod event bus from {@link ShuruisUtilities}'s constructor.
 */
public final class DeletionWandItems
{
    private DeletionWandItems() {}

    public static final DeferredRegister<Item> REGISTER =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    public static final RegistryObject<Item> DELETION_WAND = REGISTER.register("deletion_wand",
            () -> new DeletionWandItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));
}
