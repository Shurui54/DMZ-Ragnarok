package net.shurui.shuruisutilities.vehicleworkbench.client;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import net.shurui.dev.sdu.client.gui.DmzTextureButton;
import net.shurui.dev.sdu.client.gui.theme.GuiSounds;
import net.shurui.dev.sdu.client.gui.theme.GuiText;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.client.gui.theme.ThemeRender;
import net.shurui.shuruisutilities.vehicleworkbench.VehicleRecipe;
import net.shurui.shuruisutilities.vehicleworkbench.VehicleWorkbenchMenu;
import net.shurui.shuruisutilities.vehicleworkbench.VehicleWorkbenchRegistry;

/**
 * The vehicle workbench screen, on the suite's GuiTheme: blueprint list on the left, the selected vehicle's parts
 * (have / need) on the right, the player's inventory below. Build sends the blueprint index; the server checks and
 * pays (see {@link VehicleWorkbenchMenu}). Every count drawn here is display only.
 */
public class VehicleWorkbenchScreen extends AbstractContainerScreen<VehicleWorkbenchMenu>
{
    // Layout in screen-local units. Boxes sit under the header band and above the inventory label.
    private static final int W = 276;
    private static final int H = VehicleWorkbenchMenu.INV_Y + 58 + 16 + 8;
    private static final int BOX_Y = GuiTheme.CONTENT_TOP;
    private static final int BOX_H = 112;

    private static final int LIST_X = 8;
    private static final int LIST_W = 104;
    private static final int ROW_H = 18;
    private static final int ROWS = (BOX_H - 4) / ROW_H;
    private static final int BAR_X = LIST_X + LIST_W + 2;

    private static final int DETAIL_X = BAR_X + GuiTheme.SCROLLBAR_WIDTH + 6;
    private static final int DETAIL_W = W - 8 - DETAIL_X;
    private static final int RESULT_Y = BOX_Y + 4;
    private static final int GRID_Y = RESULT_Y + 20;
    private static final int CELL_W = (DETAIL_W - 8) / 2;
    /** Part rows overlap their 18px slot art by one pixel so four rows clear the Build button. */
    private static final int PART_H = 17;
    private static final int BUILD_Y = BOX_Y + BOX_H - GuiTheme.BUTTON_HEIGHT - 3;

    /** Remembered across openings in one session, so a player building several of the same vehicle stays on it. */
    private static int lastSelected = 0;

    private List<VehicleRecipe> recipes = List.of();
    private int selected;
    private int scroll;
    private boolean draggingBar;
    private DmzTextureButton buildButton;

    public VehicleWorkbenchScreen(VehicleWorkbenchMenu menu, Inventory inv, Component title)
    {
        super(menu, inv, title);
        this.imageWidth = W;
        this.imageHeight = H;
    }

    @Override
    protected void init()
    {
        super.init();
        if (minecraft != null && minecraft.level != null)
            recipes = VehicleWorkbenchRegistry.sorted(minecraft.level.getRecipeManager());
        selected = recipes.isEmpty() ? -1 : Mth.clamp(lastSelected, 0, recipes.size() - 1);
        scroll = Mth.clamp(selected - ROWS + 1, 0, maxScroll());

        buildButton = new DmzTextureButton(leftPos + DETAIL_X + 4, topPos + BUILD_Y, DETAIL_W - 8,
                GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.vehicle_workbench.build"),
                GuiTheme.BUTTONS, 0, 0, 0, 0, 0, this::build);
        buildButton.commits();
        addRenderableWidget(buildButton);
        GuiSounds.navigate();
    }

    // ---------------------------------------------------------------- state

    public List<VehicleRecipe> recipes()
    {
        return recipes;
    }

    public int selected()
    {
        return selected;
    }

    /** Select a blueprint by index (also used by the dev visual test). */
    public void select(int index)
    {
        if (index < 0 || index >= recipes.size())
            return;
        selected = index;
        lastSelected = index;
        if (selected < scroll)
            scroll = selected;
        else if (selected >= scroll + ROWS)
            scroll = selected - ROWS + 1;
    }

    /** Ask the server to build the selected blueprint. */
    public void build()
    {
        if (selected < 0 || minecraft == null || minecraft.gameMode == null)
            return;
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, selected);
    }

    private VehicleRecipe current()
    {
        return selected >= 0 && selected < recipes.size() ? recipes.get(selected) : null;
    }

    private int maxScroll()
    {
        return Math.max(0, recipes.size() - ROWS);
    }

    private Container parts()
    {
        return VehicleWorkbenchMenu.parts(minecraft.player);
    }

    @Override
    protected void containerTick()
    {
        super.containerTick();
        VehicleRecipe r = current();
        boolean affordable = r != null && minecraft != null && minecraft.player != null && r.plan(parts()) != null;
        buildButton.active = affordable;
        buildButton.setTooltip(r != null && !affordable
                ? Tooltip.create(Component.translatable("gui.dmz_ragnarok.vehicle_workbench.missing"))
                : null);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mx, int my)
    {
        int x = leftPos;
        int y = topPos;
        ThemeRender.panel(g, x, y, imageWidth, imageHeight);
        ThemeRender.header(g, x, y, imageWidth);

        ThemeRender.field(g, x + LIST_X, y + BOX_Y, LIST_W, BOX_H);
        ThemeRender.tooltip(g, x + DETAIL_X, y + BOX_Y, DETAIL_W, BOX_H);
        drawList(g, mx, my);
        drawScrollbar(g);
        drawDetail(g);

        for (Slot s : menu.slots)
            slotArt(g, x + s.x - 1, y + s.y - 1);
    }

    private void slotArt(GuiGraphics g, int x, int y)
    {
        g.blit(GuiTheme.SLOT, x, y, 18, 18, 0.0F, 0.0F, GuiTheme.SLOT_TEX_W, GuiTheme.SLOT_TEX_H,
                GuiTheme.SLOT_TEX_W, GuiTheme.SLOT_TEX_H);
    }

    private void drawList(GuiGraphics g, int mx, int my)
    {
        int x0 = leftPos + LIST_X + 2;
        int y0 = topPos + BOX_Y + 2;
        int w = LIST_W - 4;
        if (recipes.isEmpty())
        {
            GuiText.drawFitted(g, font, Component.translatable("gui.dmz_ragnarok.vehicle_workbench.empty").getString(),
                    x0, y0, w, ROW_H, GuiTheme.COLOR_MUTED);
            return;
        }
        Container have = parts();
        for (int row = 0; row < ROWS; row++)
        {
            int i = scroll + row;
            if (i >= recipes.size())
                break;
            VehicleRecipe r = recipes.get(i);
            int ry = y0 + row * ROW_H;
            if (i == selected)
                g.fill(x0, ry, x0 + w, ry + ROW_H, GuiTheme.COLOR_TAB_ACTIVE_WASH);
            else if (inside(mx, my, x0, ry, w, ROW_H))
                g.fill(x0, ry, x0 + w, ry + ROW_H, GuiTheme.COLOR_HOVER_WASH);
            ItemStack out = r.result();
            g.renderItem(out, x0 + 1, ry + 1);
            boolean ready = r.plan(have) != null;
            int color = i == selected ? GuiTheme.COLOR_TITLE : (ready ? GuiTheme.COLOR_ROW : GuiTheme.COLOR_MUTED);
            GuiText.drawFitted(g, font, name(out), x0 + 18, ry, w - 18, ROW_H, color);
        }
    }

    private void drawScrollbar(GuiGraphics g)
    {
        int top = topPos + BOX_Y;
        int thumbH = thumbHeight();
        int thumbY = GuiTheme.scrollThumbY(top, BOX_H, thumbH, scroll, maxScroll());
        ThemeRender.scrollbar(g, leftPos + BAR_X, top, BOX_H, thumbY, thumbH, draggingBar);
    }

    private int thumbHeight()
    {
        int total = Math.max(1, recipes.size());
        return Math.max(12, (BOX_H - 2) * Math.min(ROWS, total) / total);
    }

    private void drawDetail(GuiGraphics g)
    {
        int x0 = leftPos + DETAIL_X + 4;
        VehicleRecipe r = current();
        if (r == null)
            return;
        ItemStack out = r.result();
        slotArt(g, x0, topPos + RESULT_Y);
        g.renderItem(out, x0 + 1, topPos + RESULT_Y + 1);
        GuiText.drawFitted(g, font, name(out), x0 + 20, topPos + RESULT_Y, DETAIL_W - 28, 18,
                GuiTheme.COLOR_TITLE);

        Container have = parts();
        List<VehicleRecipe.Part> parts = r.parts();
        for (int i = 0; i < parts.size(); i++)
        {
            VehicleRecipe.Part part = parts.get(i);
            int cx = x0 + (i % 2) * CELL_W;
            int cy = topPos + GRID_Y + (i / 2) * PART_H;
            slotArt(g, cx, cy);
            ItemStack shown = shown(part.ingredient());
            if (!shown.isEmpty())
                g.renderItem(shown, cx + 1, cy + 1);
            int owned = VehicleRecipe.count(have, part.ingredient());
            int color = owned >= part.count() ? GuiTheme.COLOR_LABEL : GuiTheme.COLOR_SHORTFALL;
            GuiText.drawFitted(g, font, Math.min(owned, 999) + "/" + part.count(), cx + 18, cy, CELL_W - 20, 18,
                    color);
        }
    }

    /** The vehicle's name, with its tier when a blueprint builds above tier 1. */
    private static String name(ItemStack stack)
    {
        int tier = net.shurui.shuruisutilities.vehicleworkbench.VehicleTiers.tier(stack);
        String base = stack.getHoverName().getString();
        return tier > 1 ? base + " " + net.shurui.shuruisutilities.vehicleworkbench.VehicleTiers.label(tier).getString()
                : base;
    }

    /** One of the ingredient's items, cycling once a second for a tag ingredient. */
    private static ItemStack shown(Ingredient ingredient)
    {
        ItemStack[] items = ingredient.getItems();
        if (items.length == 0)
            return ItemStack.EMPTY;
        return items[(int) ((System.currentTimeMillis() / 1000L) % items.length)];
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my)
    {
        g.drawString(font, title, GuiTheme.NAME_TOP_LEFT_X + 2, GuiTheme.NAME_TOP_LEFT_Y, GuiTheme.COLOR_TITLE, false);
        g.drawString(font, playerInventoryTitle, VehicleWorkbenchMenu.INV_X, VehicleWorkbenchMenu.INV_Y - 10,
                GuiTheme.COLOR_LABEL, false);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial)
    {
        renderBackground(g);
        super.render(g, mx, my, partial);
        renderTooltip(g, mx, my);
        ItemStack hovered = hoveredStack(mx, my);
        if (!hovered.isEmpty())
            g.renderTooltip(font, hovered, mx, my);
    }

    /** The blueprint row or part cell under the cursor, for its item tooltip. */
    private ItemStack hoveredStack(int mx, int my)
    {
        int row = rowAt(mx, my);
        if (row >= 0)
            return recipes.get(row).result();
        VehicleRecipe r = current();
        if (r == null)
            return ItemStack.EMPTY;
        int x0 = leftPos + DETAIL_X + 4;
        if (inside(mx, my, x0, topPos + RESULT_Y, 18, 18))
            return r.result();
        for (int i = 0; i < r.parts().size(); i++)
        {
            int cx = x0 + (i % 2) * CELL_W;
            int cy = topPos + GRID_Y + (i / 2) * PART_H;
            if (inside(mx, my, cx, cy, 18, 18))
                return shown(r.parts().get(i).ingredient());
        }
        return ItemStack.EMPTY;
    }

    // ---------------------------------------------------------------- input

    private int rowAt(double mx, double my)
    {
        int x0 = leftPos + LIST_X + 2;
        int y0 = topPos + BOX_Y + 2;
        if (!inside(mx, my, x0, y0, LIST_W - 4, ROWS * ROW_H))
            return -1;
        int i = scroll + (int) ((my - y0) / ROW_H);
        return i < recipes.size() ? i : -1;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h)
    {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button)
    {
        if (button == 0)
        {
            int row = rowAt(mx, my);
            if (row >= 0)
            {
                if (row != selected)
                    GuiSounds.button();
                select(row);
                return true;
            }
            if (inside(mx, my, leftPos + BAR_X, topPos + BOX_Y, GuiTheme.SCROLLBAR_WIDTH, BOX_H) && maxScroll() > 0)
            {
                draggingBar = true;
                dragTo(my);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy)
    {
        if (draggingBar)
        {
            dragTo(my);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button)
    {
        draggingBar = false;
        return super.mouseReleased(mx, my, button);
    }

    private void dragTo(double my)
    {
        int travel = Math.max(1, BOX_H - thumbHeight());
        double t = (my - (topPos + BOX_Y) - thumbHeight() / 2.0) / travel;
        scroll = Mth.clamp((int) Math.round(t * maxScroll()), 0, maxScroll());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta)
    {
        if (inside(mx, my, leftPos + LIST_X, topPos + BOX_Y, LIST_W + GuiTheme.SCROLLBAR_WIDTH + 2, BOX_H))
        {
            scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }
}
