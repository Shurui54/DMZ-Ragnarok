package net.shurui.shuruisutilities.vehicleworkbench;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.crafting.CraftingHelper;

/**
 * One vehicle blueprint for the space console workbench: a parts list (ingredient plus count, no grid shape) and the
 * vehicle it builds.
 *
 * <p>The "container" a blueprint is matched against is the player's own inventory, not a crafting grid: the
 * workbench pulls parts straight out of the inventory, so {@link #matches} counts across every slot it is given.
 */
public class VehicleRecipe implements Recipe<Container>
{
    /** One line of the parts list. */
    public record Part(Ingredient ingredient, int count) {}

    private final ResourceLocation id;
    private final List<Part> parts;
    private final ItemStack result;
    /** Display order in the workbench and JEI; ties fall back to the recipe id. */
    private final int order;

    public VehicleRecipe(ResourceLocation id, List<Part> parts, ItemStack result, int order)
    {
        this.id = id;
        this.parts = List.copyOf(parts);
        this.result = result;
        this.order = order;
    }

    public List<Part> parts()
    {
        return parts;
    }

    public ItemStack result()
    {
        return result.copy();
    }

    public int order()
    {
        return order;
    }

    /**
     * Plan which slots pay for this blueprint, without touching them: {@code take[slot]} is how many to remove from
     * each slot, or null when the container cannot afford it. Parts are paid in order from what earlier parts left,
     * so one stack can never be counted twice.
     */
    public int[] plan(Container inv)
    {
        int size = inv.getContainerSize();
        int[] left = new int[size];
        for (int i = 0; i < size; i++)
            left[i] = inv.getItem(i).getCount();
        int[] take = new int[size];
        for (Part part : parts)
        {
            int need = part.count();
            for (int i = 0; i < size && need > 0; i++)
            {
                if (left[i] <= 0)
                    continue;
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty() || !part.ingredient().test(stack))
                    continue;
                int n = Math.min(left[i], need);
                left[i] -= n;
                take[i] += n;
                need -= n;
            }
            if (need > 0)
                return null;
        }
        return take;
    }

    /** How many of a part's items the container holds, for the have/need readout. */
    public static int count(Container inv, Ingredient ingredient)
    {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++)
        {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && ingredient.test(stack))
                n += stack.getCount();
        }
        return n;
    }

    @Override
    public boolean matches(Container inv, Level level)
    {
        return plan(inv) != null;
    }

    @Override
    public ItemStack assemble(Container inv, RegistryAccess access)
    {
        return result.copy();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height)
    {
        return true;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess access)
    {
        return result;
    }

    @Override
    public NonNullList<Ingredient> getIngredients()
    {
        NonNullList<Ingredient> out = NonNullList.create();
        for (Part part : parts)
            out.add(part.ingredient());
        return out;
    }

    @Override
    public ResourceLocation getId()
    {
        return id;
    }

    // Kept out of the vanilla recipe book: it has no category for this type and would log a warning per recipe.
    @Override
    public boolean isSpecial()
    {
        return true;
    }

    @Override
    public RecipeSerializer<?> getSerializer()
    {
        return VehicleWorkbenchRegistry.SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType()
    {
        return VehicleWorkbenchRegistry.TYPE.get();
    }

    /**
     * JSON: {@code {"type": "dmz_ragnarok:vehicle_crafting", "order": 10, "parts": [{"item": "...", "count": 2},
     * {"tag": "...", "count": 4}], "result": {"item": "..."}, "tier": 2}} ("tier" optional, default 1). Forge's top level "conditions" work as usual.
     */
    public static class Serializer implements RecipeSerializer<VehicleRecipe>
    {
        /** The workbench draws parts in a two by four grid; more would run off the panel. */
        public static final int MAX_PARTS = 8;

        @Override
        public VehicleRecipe fromJson(ResourceLocation id, JsonObject json)
        {
            JsonArray array = GsonHelper.getAsJsonArray(json, "parts");
            List<Part> parts = new ArrayList<>();
            for (JsonElement el : array)
            {
                JsonObject obj = GsonHelper.convertToJsonObject(el, "part");
                int count = GsonHelper.getAsInt(obj, "count", 1);
                if (count < 1 || count > 64 * 36)
                    throw new JsonParseException("Vehicle recipe " + id + ": part count " + count + " out of range");
                parts.add(new Part(CraftingHelper.getIngredient(obj, false), count));
            }
            if (parts.isEmpty())
                throw new JsonParseException("Vehicle recipe " + id + " has no parts");
            if (parts.size() > MAX_PARTS)
                throw new JsonParseException("Vehicle recipe " + id + " has more than " + MAX_PARTS + " parts");
            ItemStack result = CraftingHelper.getItemStack(GsonHelper.getAsJsonObject(json, "result"), true);
            // "tier": a blueprint can build a vehicle above tier 1 directly (the doubled-parts tier 2 recipes)
            int tier = GsonHelper.getAsInt(json, "tier", 1);
            if (tier > 1)
                VehicleTiers.withTier(result, tier);
            return new VehicleRecipe(id, parts, result, GsonHelper.getAsInt(json, "order", 0));
        }

        @Override
        public VehicleRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf)
        {
            int n = buf.readVarInt();
            List<Part> parts = new ArrayList<>(n);
            for (int i = 0; i < n; i++)
            {
                Ingredient ing = Ingredient.fromNetwork(buf);
                parts.add(new Part(ing, buf.readVarInt()));
            }
            ItemStack result = buf.readItem();
            int order = buf.readVarInt();
            return new VehicleRecipe(id, parts, result, order);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, VehicleRecipe recipe)
        {
            buf.writeVarInt(recipe.parts.size());
            for (Part part : recipe.parts)
            {
                part.ingredient().toNetwork(buf);
                buf.writeVarInt(part.count());
            }
            buf.writeItem(recipe.result);
            buf.writeVarInt(recipe.order);
        }
    }
}
