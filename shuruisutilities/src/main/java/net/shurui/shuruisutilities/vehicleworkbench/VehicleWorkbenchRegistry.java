package net.shurui.shuruisutilities.vehicleworkbench;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * The vehicle workbench's recipe type ({@code dmz_ragnarok:vehicle_crafting}), its serializer and its menu type. The
 * workbench block itself is the space console ({@code space_console_a}) in {@code SpaceConsoleBlocks}.
 */
public final class VehicleWorkbenchRegistry
{
    private VehicleWorkbenchRegistry() {}

    public static final ResourceLocation TYPE_ID = new ResourceLocation(ShuruisUtilities.MODID, "vehicle_crafting");
    public static final ResourceLocation UPGRADE_TYPE_ID = new ResourceLocation(ShuruisUtilities.MODID, "vehicle_upgrade");

    public static final DeferredRegister<RecipeType<?>> TYPES =
            DeferredRegister.create(ForgeRegistries.RECIPE_TYPES, ShuruisUtilities.MODID);
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, ShuruisUtilities.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, ShuruisUtilities.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    public static final RegistryObject<RecipeType<VehicleRecipe>> TYPE =
            TYPES.register(TYPE_ID.getPath(), () -> RecipeType.simple(TYPE_ID));
    public static final RegistryObject<RecipeSerializer<VehicleRecipe>> SERIALIZER =
            SERIALIZERS.register(TYPE_ID.getPath(), VehicleRecipe.Serializer::new);
    public static final RegistryObject<MenuType<VehicleWorkbenchMenu>> MENU = MENUS.register("vehicle_workbench",
            () -> IForgeMenuType.create((id, inv, buf) -> new VehicleWorkbenchMenu(id, inv)));

    // The compact console (space_console_b) is the upgrade bench: kits and merges, its own rule type and menu.
    public static final RegistryObject<RecipeType<VehicleUpgradeRecipe>> UPGRADE_TYPE =
            TYPES.register(UPGRADE_TYPE_ID.getPath(), () -> RecipeType.simple(UPGRADE_TYPE_ID));
    public static final RegistryObject<RecipeSerializer<VehicleUpgradeRecipe>> UPGRADE_SERIALIZER =
            SERIALIZERS.register(UPGRADE_TYPE_ID.getPath(), VehicleUpgradeRecipe.Serializer::new);
    public static final RegistryObject<MenuType<VehicleUpgradeMenu>> UPGRADE_MENU = MENUS.register("vehicle_upgrade",
            () -> IForgeMenuType.create((id, inv, buf) -> new VehicleUpgradeMenu(id, inv)));

    // One upgrade kit per vehicle family, built at the big console. Which vehicles each kit fits is data
    // (data/dmz_ragnarok/recipes/vehicle_upgrade/*.json), so a pack can retarget them without code.
    public static final List<RegistryObject<Item>> KITS = new ArrayList<>();
    public static final RegistryObject<Item> HOVERBIKE_KIT = kit("hoverbike_upgrade");
    public static final RegistryObject<Item> CAR_KIT = kit("car_upgrade");
    public static final RegistryObject<Item> AIRCRAFT_KIT = kit("aircraft_upgrade");

    private static RegistryObject<Item> kit(String name)
    {
        RegistryObject<Item> ro = ITEMS.register(name, () -> new VehicleUpgradeKitItem(new Item.Properties().stacksTo(64)));
        KITS.add(ro);
        return ro;
    }

    /**
     * Every blueprint in display order. The client receives the server's recipes on join, so both sides build the
     * same list and a menu button id can name a blueprint by its index here.
     */
    public static List<VehicleRecipe> sorted(RecipeManager manager)
    {
        List<VehicleRecipe> out = new ArrayList<>(manager.getAllRecipesFor(TYPE.get()));
        out.sort(Comparator.comparingInt(VehicleRecipe::order).thenComparing(r -> r.getId().toString()));
        return out;
    }
}
