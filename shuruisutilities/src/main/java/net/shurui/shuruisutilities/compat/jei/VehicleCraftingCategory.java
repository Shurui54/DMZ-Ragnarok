package net.shurui.shuruisutilities.compat.jei;

import java.util.ArrayList;
import java.util.List;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import net.shurui.shuruisutilities.spaceconsole.SpaceConsoleBlocks;
import net.shurui.shuruisutilities.vehicleworkbench.VehicleRecipe;
import net.shurui.shuruisutilities.vehicleworkbench.VehicleWorkbenchRegistry;

/**
 * JEI page for the space console's vehicle blueprints: up to eight parts in a three wide grid (each stack showing the
 * count the blueprint takes), an arrow, and the vehicle. Only classloaded by {@link RagnarokJeiPlugin}, so only when
 * JEI is installed.
 */
public class VehicleCraftingCategory implements IRecipeCategory<VehicleRecipe>
{
    public static final RecipeType<VehicleRecipe> TYPE =
            new RecipeType<>(VehicleWorkbenchRegistry.TYPE_ID, VehicleRecipe.class);

    private static final int WIDTH = 120;
    private static final int HEIGHT = 54;
    private static final int ARROW_X = 62;
    private static final int OUTPUT_X = 98;

    private final IDrawable background;
    private final IDrawable icon;
    private final IDrawable slot;
    private final IDrawable arrow;

    public VehicleCraftingCategory(IGuiHelper gui)
    {
        this.background = gui.createBlankDrawable(WIDTH, HEIGHT);
        this.icon = gui.createDrawableItemStack(new ItemStack(SpaceConsoleBlocks.SPACE_CONSOLE_A.get()));
        this.slot = gui.getSlotDrawable();
        this.arrow = gui.getRecipeArrow();
    }

    @Override
    public RecipeType<VehicleRecipe> getRecipeType()
    {
        return TYPE;
    }

    @Override
    public Component getTitle()
    {
        return Component.translatable("jei.dmz_ragnarok.vehicle_crafting");
    }

    // Older JEI 15 builds still require a background, newer ones read the size below; both are provided.
    @Override
    @SuppressWarnings("removal")
    public IDrawable getBackground()
    {
        return background;
    }

    @Override
    public int getWidth()
    {
        return WIDTH;
    }

    @Override
    public int getHeight()
    {
        return HEIGHT;
    }

    @Override
    public IDrawable getIcon()
    {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, VehicleRecipe recipe, IFocusGroup focuses)
    {
        List<VehicleRecipe.Part> parts = recipe.parts();
        for (int i = 0; i < parts.size(); i++)
        {
            VehicleRecipe.Part part = parts.get(i);
            List<ItemStack> stacks = new ArrayList<>();
            for (ItemStack s : part.ingredient().getItems())
                stacks.add(s.copyWithCount(part.count()));
            builder.addSlot(RecipeIngredientRole.INPUT, 1 + (i % 3) * 18, 1 + (i / 3) * 18)
                    .setBackground(slot, -1, -1)
                    .addItemStacks(stacks);
        }
        builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X, 19)
                .setBackground(slot, -1, -1)
                .addItemStack(recipe.result());
    }

    @Override
    public void draw(VehicleRecipe recipe, IRecipeSlotsView slots, GuiGraphics g, double mx, double my)
    {
        arrow.draw(g, ARROW_X, 18);
    }
}
