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
 * connected server opened their gate (the synced ClientGate answer, never a client-side key check).
 *
 * <p>JEI stays optional: it is compileOnly and a soft dependency in mods.toml, and nothing in the suite names this
 * class. JEI finds it through the {@link JeiPlugin} annotation, so it is only ever classloaded when JEI is installed
 * (the optional-dependency pattern). Only what JEI LISTS changes; the items stay registered and the server still decides
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
