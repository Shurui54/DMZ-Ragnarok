package net.shurui.shuruisutilities.dragonballbag;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

// registration for the single dragon ball bag item. The DeferredRegister is attached to the mod bus in
// ShuruisUtilities' constructor alongside the other SU item registers; the item is shown in the equipment
// creative tab (see ContentTabs). stacksTo(1) because each bag is a distinct container with its own NBT.
public final class DragonBallBagItems
{
    private DragonBallBagItems()
    {
    }

    public static final DeferredRegister<Item> REGISTER =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    public static final RegistryObject<Item> BAG =
            REGISTER.register("dragonball_bag", () -> new DragonBallBagItem(new Item.Properties().stacksTo(1)));
}
