package net.shurui.shuruisutilities.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

// chest-style container screen skinned with DMZ's panel, for SU's item-grid GUIs (crate/trade/perm editor).
// nine-slice panel background + a dark cell behind each slot. sized from the menu's slot count (rows =
// (slots-36)/9), matching vanilla chest geometry.
public class DmzChestScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T>
{
    public DmzChestScreen(T menu, Inventory playerInv, Component title)
    {
        super(menu, playerInv, title);
        int rows = Math.max(1, (menu.slots.size() - 36) / 9);
        this.imageWidth = 176;
        this.imageHeight = 114 + rows * 18;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY)
    {
        DmzTextures.panel(g, leftPos, topPos, imageWidth, imageHeight);
        for (Slot s : menu.slots)
        {
            int x = leftPos + s.x;
            int y = topPos + s.y;
            g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF12281B); // cell border
            g.fill(x, y, x + 16, y + 16, 0xFF20402C);         // cell face
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY)
    {
        g.drawString(font, title, titleLabelX, titleLabelY, DmzTextures.GOLD, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, DmzTextures.GRAY, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }
}
