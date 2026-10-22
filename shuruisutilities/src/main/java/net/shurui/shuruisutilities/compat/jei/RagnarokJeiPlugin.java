package net.shurui.shuruisutilities.compat.jei;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import net.shurui.dev.sdu.api.PrivateItems;

/**
 * Optional JEI integration: hides the items of PRIVATE features (sdu.api.PrivateItems) from JEI's list unless the
 * connected server opened their gate (the synced ClientGate answer, never a client-side key check), and always hides
 * every cosmetic item ({@code CosmeticContentItems}).
 *
 * <p>It also adds the vehicle pages: the workbench's blueprints ({@link VehicleCraftingCategory}, the full console) and
 * the upgrade steps ({@link VehicleUpgradeCategory}, the compact console).
 *
 * <p>JEI stays optional: it is compileOnly and a soft dependency in mods.toml, and nothing in the suite names this
 * class. JEI finds it through the {@link JeiPlugin} annotation, so it is only ever classloaded when JEI is installed
 * (docs/compat-pattern.md). Only what JEI LISTS changes; the items stay registered and the server still decides
 * whether they work.
 *
 * <p>JEI's runtime can start before the login key packet lands (recipes and tags arrive first), so the list is
 * re-applied whenever {@code PrivateListingRefresh} sees the synced answer change: items hidden too early come back
 * on a keyed server, and items of a server that loses the key between sessions go away.
 */
@JeiPlugin
public class RagnarokJeiPlugin implements IModPlugin
{
    private static final ResourceLocation UID = new ResourceLocation("dmz_ragnarok", "private_items");

    /** The live runtime, or null while JEI is not running. Client thread only. */
    private static IJeiRuntime runtime;
    /** What this plugin removed from the current runtime, so exactly those are put back when a gate opens. */
    private static final Set<Item> removed = new HashSet<>();
    private static boolean listening;

    @Override
    public ResourceLocation getPluginUid()
    {
        return UID;
    }

    // The space console's vehicle blueprints get their own JEI page, with the console as its catalyst.
    @Override
    public void registerCategories(mezz.jei.api.registration.IRecipeCategoryRegistration registration)
    {
        registration.addRecipeCategories(new VehicleCraftingCategory(registration.getJeiHelpers().getGuiHelper()),
                new VehicleUpgradeCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(mezz.jei.api.registration.IRecipeRegistration registration)
    {
        net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null)
            return;
        registration.addRecipes(VehicleCraftingCategory.TYPE,
                net.shurui.shuruisutilities.vehicleworkbench.VehicleWorkbenchRegistry.sorted(level.getRecipeManager()));
        registration.addRecipes(VehicleUpgradeCategory.TYPE, VehicleUpgradeCategory.entries(level.getRecipeManager()));
    }

    @Override
    public void registerRecipeCatalysts(mezz.jei.api.registration.IRecipeCatalystRegistration registration)
    {
        registration.addRecipeCatalyst(new ItemStack(
                net.shurui.shuruisutilities.spaceconsole.SpaceConsoleBlocks.SPACE_CONSOLE_A.get()),
                VehicleCraftingCategory.TYPE);
        registration.addRecipeCatalyst(new ItemStack(
                net.shurui.shuruisutilities.spaceconsole.SpaceConsoleBlocks.SPACE_CONSOLE_B.get()),
                VehicleUpgradeCategory.TYPE);
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime)
    {
        runtime = jeiRuntime;
        removed.clear();
        if (!listening)
        {
            listening = true;
            net.shurui.shuruisutilities.client.PrivateListingRefresh.addListener(RagnarokJeiPlugin::apply);
        }
        apply();
    }

    @Override
    public void onRuntimeUnavailable()
    {
        runtime = null;
        removed.clear();
    }

    /** Bring JEI's list in line with the synced answer: remove newly hidden items, restore newly opened ones. */
    private static void apply()
    {
        IJeiRuntime rt = runtime;
        if (rt == null)
            return;
        Set<Item> hidden = new HashSet<>();
        for (ItemStack stack : PrivateItems.hiddenStacks())
            hidden.add(stack.getItem());
        // Cosmetic art items (worn pieces, pet and mount icons, crate props, the cosmetic token) are never listed:
        // they come only from the wardrobe, shop and crates, never from crafting or creative.
        for (net.minecraftforge.registries.RegistryObject<Item> ro
                : net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticContentItems.ITEMS.getEntries())
            if (ro.isPresent())
                hidden.add(ro.get());

        List<ItemStack> restore = new ArrayList<>();
        for (Item item : removed)
            if (!hidden.contains(item))
                restore.add(new ItemStack(item));
        List<ItemStack> remove = new ArrayList<>();
        for (Item item : hidden)
            if (!removed.contains(item))
                remove.add(new ItemStack(item));

        if (!restore.isEmpty())
        {
            rt.getIngredientManager().addIngredientsAtRuntime(VanillaTypes.ITEM_STACK, restore);
            for (ItemStack stack : restore)
                removed.remove(stack.getItem());
        }
        if (!remove.isEmpty())
        {
            rt.getIngredientManager().removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, remove);
            for (ItemStack stack : remove)
                removed.add(stack.getItem());
        }
    }
}
