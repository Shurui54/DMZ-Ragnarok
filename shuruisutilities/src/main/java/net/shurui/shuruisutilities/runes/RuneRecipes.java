package net.shurui.shuruisutilities.runes;

import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/** Recipe serializer for the rune tier upgrade. Attached to the mod bus in {@code ShuruisUtilities}. */
public final class RuneRecipes
{
    private RuneRecipes() {}

    public static final DeferredRegister<RecipeSerializer<?>> REGISTER =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, ShuruisUtilities.MODID);

    public static final RegistryObject<RecipeSerializer<RuneUpgradeRecipe>> UPGRADE =
            REGISTER.register("rune_upgrade", () -> new SimpleCraftingRecipeSerializer<>(RuneUpgradeRecipe::new));
}
