package net.shurui.shuruisutilities.senzu.bag;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

// registration for the single senzu bean bag item. The DeferredRegister is attached to the mod bus in
// ShuruisUtilities' constructor alongside the other SU item registers. The id "senzubag" is the SAME id that used to
// be a plain placeholder Item in ContentItems: that placeholder line was removed so this is the ONLY registration of
// "senzubag" (no double-registration), which keeps the existing texture, model and lang entry. It is shown in the
// equipment creative tab (see ContentTabs). stacksTo(1) because each bag is a distinct container with its own NBT.
public final class SenzuBagItems
{
    private SenzuBagItems()
    {
    }

    public static final DeferredRegister<Item> REGISTER =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    public static final RegistryObject<Item> BAG =
            REGISTER.register("senzubag", () -> new SenzuBagItem(new Item.Properties().stacksTo(1)));
}
