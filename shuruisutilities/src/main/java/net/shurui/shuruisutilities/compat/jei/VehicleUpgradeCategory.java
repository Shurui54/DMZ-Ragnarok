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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.spaceconsole.SpaceConsoleBlocks;
import net.shurui.shuruisutilities.vehicleworkbench.VehicleTiers;
import net.shurui.shuruisutilities.vehicleworkbench.VehicleUpgradeRecipe;
import net.shurui.shuruisutilities.vehicleworkbench.VehicleUpgrades;

/**
 * JEI page for the compact console: one entry per vehicle and tier step, either a merge (the copies it takes) or a kit
 * upgrade (the vehicle plus its kits). The bench applies a rule rather than fixed recipes, so these entries are
 * generated from the tiered vehicle tag, the kit rules and the tier limits.
 */
public class VehicleUpgradeCategory implements IRecipeCategory<VehicleUpgradeCategory.Entry>
{
    /** One upgrade step as JEI shows it. */
    public record Entry(List<ItemStack> vehicles, ItemStack kits, ItemStack result, int fromTier) {}

    public static final RecipeType<Entry> TYPE = new RecipeType<>(
            new ResourceLocation("dmz_ragnarok", "vehicle_upgrade"), Entry.class);

    private static final int WIDTH = 150;
    private static final int HEIGHT = 42;
    private static final int ARROW_X = 96;
    private static final int OUTPUT_X = 128;

    private final IDrawable background;
    private final IDrawable icon;
    private final IDrawable slot;
    private final IDrawable arrow;

    public VehicleUpgradeCategory(IGuiHelper gui)
    {
        this.background = gui.createBlankDrawable(WIDTH, HEIGHT);
        this.icon = gui.createDrawableItemStack(new ItemStack(SpaceConsoleBlocks.SPACE_CONSOLE_B.get()));
        this.slot = gui.getSlotDrawable();
        this.arrow = gui.getRecipeArrow();
    }

    /** Every step from tier 1 up to the max tier, for every tiered vehicle. */
    public static List<Entry> entries(RecipeManager recipes)
    {
        List<Entry> out = new ArrayList<>();
        int max = VehicleTiers.maxTier();
        var tag = ForgeRegistries.ITEMS.tags();
        if (tag == null)
            return out;
        for (Item item : tag.getTag(VehicleTiers.TIERED))
        {
            ItemStack base = new ItemStack(item);
            VehicleUpgradeRecipe rule = VehicleUpgrades.ruleFor(recipes, base);
            for (int t = 1; t < max; t++)
            {
                ItemStack from = VehicleTiers.withTier(base.copy(), t);
                ItemStack to = VehicleTiers.withTier(base.copy(), t + 1);
                List<ItemStack> copies = new ArrayList<>();
                for (int i = 0; i < VehicleTiers.mergeCopies(t); i++)
                    copies.add(from.copy());
                out.add(new Entry(copies, ItemStack.EMPTY, to, t));
                if (rule != null)
                {
                    ItemStack[] kits = rule.kit().getItems();
                    if (kits.length > 0)
                        out.add(new Entry(List.of(from.copy()), kits[0].copyWithCount(Math.min(64, rule.kitsFor(t))), to, t));
                }
            }
        }
        return out;
    }

    @Override
    public RecipeType<Entry> getRecipeType()
    {
        return TYPE;
    }

    @Override
    public Component getTitle()
    {
        return Component.translatable("jei.dmz_ragnarok.vehicle_upgrade");
    }

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
    public void setRecipe(IRecipeLayoutBuilder builder, Entry entry, IFocusGroup focuses)
    {
        for (int i = 0; i < entry.vehicles().size(); i++)
            builder.addSlot(RecipeIngredientRole.INPUT, 1 + i * 18, 1).setBackground(slot, -1, -1)
                    .addItemStack(entry.vehicles().get(i));
        if (!entry.kits().isEmpty())
            builder.addSlot(RecipeIngredientRole.INPUT, 1, 23).setBackground(slot, -1, -1).addItemStack(entry.kits());
        builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X, 12).setBackground(slot, -1, -1)
                .addItemStack(entry.result());
    }

    @Override
    public void draw(Entry entry, IRecipeSlotsView slots, GuiGraphics g, double mx, double my)
    {
        arrow.draw(g, ARROW_X, 11);
        // "I -> II" over the arrow, so a page of identical icons still reads at a glance
        String step = VehicleTiers.roman(entry.fromTier()) + " > " + VehicleTiers.roman(entry.fromTier() + 1);
        var font = Minecraft.getInstance().font;
        g.drawString(font, step, ARROW_X + 12 - font.width(step) / 2, 1, 0xFF404040, false);
        if (!entry.kits().isEmpty())
            g.drawString(font, VehicleTiers.label(entry.fromTier() + 1).getString(), 22, 28, 0xFF404040, false);
    }
}
