package net.shurui.shuruisutilities.god;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/** The Angel's staff item. Registered on the mod bus from the main class, like every other item register here. */
public final class AngelItems
{
    private AngelItems() {}

    public static final DeferredRegister<Item> REGISTER =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    /** Unique per Angel, so it never stacks. */
    public static final RegistryObject<Item> ANGEL_STAFF = REGISTER.register("angel_staff",
            () -> new AngelStaffItem(new Item.Properties().stacksTo(1).fireResistant()));
}
