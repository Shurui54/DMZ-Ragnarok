package net.shurui.shuruisutilities.runes;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * Nine runes of one tier become one of the next, keeping the stat and the grade.
 *
 * <p>A dynamic recipe rather than a JSON one because a rune's TIER lives in its stack NBT, not in its id. A shaped or
 * shapeless recipe can neither read the tier off the nine inputs nor stamp the raised tier onto the result, so the
 * matching and the output are both decided here.
 *
 * <p>It is deliberately strict about sameness: all nine must be the same item, which pins stat AND grade together,
 * and all nine must carry the same tier. Allowing a mixed pile would mean choosing which of the inputs the result
 * takes after, and any choice there is a way to launder a bad rune into a good one's grade.
 *
 * <p>Mythical inputs do not match at all. There is nothing above them, so the recipe simply does not exist for a
 * Mythical rune rather than quietly consuming nine of the best runes in the game to hand back one of the same.
 */
public class RuneUpgradeRecipe extends CustomRecipe
{
    /** The whole grid, so nine runes and nothing else. */
    private static final int REQUIRED = 9;

    public RuneUpgradeRecipe(net.minecraft.resources.ResourceLocation id, CraftingBookCategory category)
    {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer container, Level level)
    {
        return !assemble(container).isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingContainer container, RegistryAccess registries)
    {
        return assemble(container);
    }

    /**
     * The raised rune, or empty when the grid is not nine matching upgradeable runes.
     *
     * <p>One method behind both {@code matches} and {@code assemble} on purpose: two implementations of "is this a
     * valid grid" is how a recipe ends up claiming a grid it then cannot build, which shows up as a crafting slot
     * that flickers or a result that vanishes on pickup.
     */
    private static ItemStack assemble(CraftingContainer container)
    {
        ItemStack first = ItemStack.EMPTY;
        RuneTier tier = null;
        int found = 0;

        for (int i = 0; i < container.getContainerSize(); i++)
        {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty())
                continue;
            found++;
            if (found > REQUIRED)
                return ItemStack.EMPTY;
            if (!(stack.getItem() instanceof RuneItem rune) || rune.isDormant())
                return ItemStack.EMPTY;
            RuneTier stackTier = RuneItem.tier(stack);
            if (first.isEmpty())
            {
                first = stack;
                tier = stackTier;
                continue;
            }
            // Same item pins the stat and the grade; same tier pins the rest. Anything mixed is not this recipe.
            if (stack.getItem() != first.getItem() || stackTier != tier)
                return ItemStack.EMPTY;
        }

        if (found != REQUIRED || tier == null)
            return ItemStack.EMPTY;
        RuneTier next = promote(tier);
        if (next == null)
            return ItemStack.EMPTY;

        ItemStack out = new ItemStack(first.getItem());
        RuneItem.setTier(out, next);
        return out;
    }

    /** The tier one step up, or null when already at the top. */
    public static RuneTier promote(RuneTier tier)
    {
        RuneTier[] all = RuneTier.values();
        int i = tier.ordinal();
        return i >= all.length - 1 ? null : all[i + 1];
    }

    @Override
    public boolean canCraftInDimensions(int width, int height)
    {
        return width * height >= REQUIRED;
    }

    @Override
    public RecipeSerializer<?> getSerializer()
    {
        return RuneRecipes.UPGRADE.get();
    }
}
