package net.shurui.shuruisutilities.client.gui;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.permissions.gui.PermGuiMenu;

import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

// custom menu types for SU's chest-style GUIs, replacing the vanilla GENERIC_9x3/9x6 types so we can bind a
// DMZ-styled DmzChestScreen instead. client factory builds each menu with the same slot layout as the server.
public final class SUMenus
{
    private SUMenus() {}

    public static final DeferredRegister<MenuType<?>> REGISTER =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<MenuType<PermGuiMenu>> PERM =
            REGISTER.register("perm", () -> IForgeMenuType.create((id, inv, buf) -> new PermGuiMenu(id, inv)));

    // dragon ball bag: client factory builds an empty local handler that the server fills via container sync.
    public static final RegistryObject<MenuType<net.shurui.shuruisutilities.dragonballbag.DragonBallBagMenu>> DRAGONBALL_BAG =
            REGISTER.register("dragonball_bag", () -> IForgeMenuType.create(
                    (id, inv, buf) -> new net.shurui.shuruisutilities.dragonballbag.DragonBallBagMenu(id, inv)));

    // senzu bean bag: same pattern, an empty local handler the server fills via container sync.
    public static final RegistryObject<MenuType<net.shurui.shuruisutilities.senzu.bag.SenzuBagMenu>> SENZU_BAG =
            REGISTER.register("senzu_bag", () -> IForgeMenuType.create(
                    (id, inv, buf) -> new net.shurui.shuruisutilities.senzu.bag.SenzuBagMenu(id, inv)));
}
