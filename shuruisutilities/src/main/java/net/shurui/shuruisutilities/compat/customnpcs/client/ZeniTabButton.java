package net.shurui.shuruisutilities.compat.customnpcs.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

// a standalone copy of CustomNPCs' GuiMenuTopButton look (customnpcs:textures/gui/menutopbutton.png), which is the
// tab style the owner asked for ("like the tabs when making a customnpc"). we do NOT extend CNPC's widget: its
// GuiButtonNop needs an IGuiInterface owner and its click path calls gui.buttonEvent(this), and IGuiInterface is 12
// methods (buttonEvent, save, hasSubGui, getSubGui, getParent, elementClicked, subGuiClosed, getWrapper, initGui,
// getWidth, getHeight) several of which return noppes types, so satisfying it from a vanilla Screen is not cheap.
// we replicate only the blit math, which is the on-screen result. the ResourceLocation namespace is just a string,
// so this classloads nothing from noppes, and the whole compat package is only registered when CNPC is present.
public final class ZeniTabButton extends Button
{
    static final ResourceLocation TEXTURE = new ResourceLocation("customnpcs", "textures/gui/menutopbutton.png");

    private boolean active;

    ZeniTabButton(int x, int y, Font font, Component label, OnPress onPress)
    {
        super(x, y, tabWidth(font, label.getString()), 20, label, onPress, DEFAULT_NARRATION);
    }

    // CNPC sizes the tab to the label plus 12px of chrome; reused by the player side, which draws tabs by hand.
    static int tabWidth(Font font, String label)
    {
        return font.width(label) + 12;
    }

    void setActive(boolean active)
    {
        this.active = active;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial)
    {
        Font font = Minecraft.getInstance().font;
        int h = 20 - (active ? 0 : 2);
        boolean hover = mouseX >= getX() && mouseX < getX() + getWidth() && mouseY >= getY() && mouseY < getY() + h;
        draw(g, font, getX(), getY(), getWidth(), getMessage(), active, hover);
    }

    // the exact GuiMenuTopButton drawing: two halves of one texture row (row 0 active, 1 idle, 2 hover), label
    // centred, 0xFFFFA0 when active or hovered else 0xE0E0E0. inactive tabs are 2px shorter so the active one reads
    // as raised. shared so the player shop (which cannot host a real widget through ScreenEvent) matches the editor.
    static void draw(GuiGraphics g, Font font, int x, int y, int width, Component label, boolean active, boolean hover)
    {
        int h = 20 - (active ? 0 : 2);
        int k = active ? 0 : (hover ? 2 : 1);
        g.blit(TEXTURE, x, y, 0, k * 20, width / 2, h);
        g.blit(TEXTURE, x + width / 2, y, 200 - width / 2, k * 20, width / 2, h);
        int color = (active || hover) ? 0xFFFFA0 : 0xE0E0E0;
        g.drawCenteredString(font, label, x + width / 2, y + (h - 8) / 2, color);
    }
}
