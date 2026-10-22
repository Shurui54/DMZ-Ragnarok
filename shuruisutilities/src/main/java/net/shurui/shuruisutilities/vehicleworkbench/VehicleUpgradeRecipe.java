package net.shurui.shuruisutilities.vehicleworkbench;

import com.google.gson.JsonArray;
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
 * An upgrade kit rule for the compact console: which vehicles a kit upgrades, and how many kits each step takes.
 *
 * <p>JSON: {@code {"type": "dmz_ragnarok:vehicle_upgrade", "vehicles": <ingredient>, "kit": <ingredient>,
 * "kits_per_tier": [1, 2, 4, 8, 16]}}. Entry i is the kit count to go from tier i+1 to i+2; past the end of the list
 * each step doubles the last entry. The bench applies the rule (this is a lookup, not a grid recipe), so
 * {@link #matches} always answers false and nothing vanilla ever crafts it.
 */
public class VehicleUpgradeRecipe implements Recipe<Container>
{
    private final ResourceLocation id;
    private final Ingredient vehicles;
    private final Ingredient kit;
    private final int[] kitsPerTier;

    public VehicleUpgradeRecipe(ResourceLocation id, Ingredient vehicles, Ingredient kit, int[] kitsPerTier)
    {
        this.id = id;
        this.vehicles = vehicles;
        this.kit = kit;
        this.kitsPerTier = kitsPerTier;
    }

    public Ingredient vehicles()
    {
        return vehicles;
    }

    public Ingredient kit()
    {
        return kit;
    }

    /** Kits needed to take a vehicle from {@code tier} to {@code tier + 1}. */
    public int kitsFor(int tier)
    {
        int i = Math.max(0, tier - 1);
        if (i < kitsPerTier.length)
            return kitsPerTier[i];
        long n = kitsPerTier[kitsPerTier.length - 1];
        for (int k = kitsPerTier.length - 1; k < i; k++)
            n = Math.min(64L, n * 2L);
        return (int) n;
    }

    @Override
    public boolean matches(Container inv, Level level)
    {
        return false;
    }

    @Override
    public ItemStack assemble(Container inv, RegistryAccess access)
    {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int w, int h)
    {
        return false;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess access)
    {
        // empty on purpose: ContentGate's recipe strip and the recipe book both skip a recipe with no result
        return ItemStack.EMPTY;
    }

    @Override
    public NonNullList<Ingredient> getIngredients()
    {
        return NonNullList.of(Ingredient.EMPTY, vehicles, kit);
    }

    @Override
    public ResourceLocation getId()
    {
        return id;
    }

    @Override
    public boolean isSpecial()
    {
        return true;
    }

    @Override
    public RecipeSerializer<?> getSerializer()
    {
        return VehicleWorkbenchRegistry.UPGRADE_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType()
    {
        return VehicleWorkbenchRegistry.UPGRADE_TYPE.get();
    }

    public static class Serializer implements RecipeSerializer<VehicleUpgradeRecipe>
    {
        @Override
        public VehicleUpgradeRecipe fromJson(ResourceLocation id, JsonObject json)
        {
            Ingredient vehicles = CraftingHelper.getIngredient(json.get("vehicles"), false);
            Ingredient kit = CraftingHelper.getIngredient(json.get("kit"), false);
            JsonArray arr = GsonHelper.getAsJsonArray(json, "kits_per_tier");
            if (arr.isEmpty())
                throw new JsonParseException("Vehicle upgrade " + id + ": kits_per_tier is empty");
            int[] kits = new int[arr.size()];
            for (int i = 0; i < kits.length; i++)
            {
                kits[i] = GsonHelper.convertToInt(arr.get(i), "kits_per_tier");
                if (kits[i] < 1 || kits[i] > 64)
                    throw new JsonParseException("Vehicle upgrade " + id + ": kit counts must be 1 to 64");
            }
            return new VehicleUpgradeRecipe(id, vehicles, kit, kits);
        }

        @Override
        public VehicleUpgradeRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf)
        {
            Ingredient vehicles = Ingredient.fromNetwork(buf);
            Ingredient kit = Ingredient.fromNetwork(buf);
            return new VehicleUpgradeRecipe(id, vehicles, kit, buf.readVarIntArray());
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, VehicleUpgradeRecipe recipe)
        {
            recipe.vehicles.toNetwork(buf);
            recipe.kit.toNetwork(buf);
            buf.writeVarIntArray(recipe.kitsPerTier);
        }
    }
}
