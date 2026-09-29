package net.shurui.shuruisutilities.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * A small, self-contained widget that draws a single {@link ItemStack} outside a vanilla inventory slot: a themed
 * inset cell, the item icon, its stack-count/durability decorations, and (on request) the vanilla hover tooltip.
 *
 * <p>Nothing in the shared GUI toolkit renders a bare ItemStack; the only precedent was
 * {@code compat/customnpcs/client/ZeniPriceScreen} calling {@code renderItem} directly. This packages that up as a
 * reusable piece so more than one screen (the auction house, and later a trade menu) can drop item icons into their
 * rows. It is deliberately general and holds no screen state.
 *
 * <p>All coordinates are the base screens' VIRTUAL canvas units. Draw and hit-test from inside
 * {@link net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen}'s scaled/translated pose (e.g. from
 * {@code renderTopOverlay}) so the icon lands exactly on its row. The cell is {@link #SIZE} px square with the
 * 16px icon centred one pixel in, matching a vanilla slot's icon inset.
 */
public final class ItemDisplay
{
    /** On-screen (virtual) size of the square cell. The 16px icon sits one px in on each axis. */
    public static final int SIZE = 18;

    private final int x;
    private final int y;
    private ItemStack stack;

    public ItemDisplay(int x, int y, ItemStack stack)
    {
        this.x = x;
        this.y = y;
        this.stack = stack == null ? ItemStack.EMPTY : stack;
    }

    public void setStack(ItemStack stack)
    {
        this.stack = stack == null ? ItemStack.EMPTY : stack;
    }

    public ItemStack stack()
    {
        return stack;
    }

    public int x()
    {
        return x;
    }

    public int y()
    {
        return y;
    }

    /** True when the given virtual mouse point is over the cell. */
    public boolean isHovered(double vmx, double vmy)
    {
        return vmx >= x && vmx < x + SIZE && vmy >= y && vmy < y + SIZE;
    }

    /**
     * Draw the cell background, the item icon and its decorations (count, durability). Call from inside the scaled
     * pose. A no-op empty stack still draws the empty cell so a listing row keeps a consistent slot outline.
     */
    public void render(GuiGraphics g, Font font)
    {
        // themed inset cell so the icon reads as sitting in a slot, using the theme's inset colour (never a literal).
        g.fill(x, y, x + SIZE, y + SIZE, GuiTheme.COLOR_INSET);
        if (stack.isEmpty())
        {
            return;
        }
        int ix = x + 1;
        int iy = y + 1;
        g.renderItem(stack, ix, iy);
        g.renderItemDecorations(font, stack, ix, iy);
    }

    /**
     * Draw the vanilla hover tooltip for this stack at the given virtual mouse point. Call from inside the scaled
     * pose (so the tooltip tracks the scaled UI). No-op for an empty stack.
     */
    public void renderTooltip(GuiGraphics g, Font font, int vmx, int vmy)
    {
        if (stack.isEmpty())
        {
            return;
        }
        g.renderTooltip(font, stack, vmx, vmy);
    }
}
