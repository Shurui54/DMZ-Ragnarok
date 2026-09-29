package net.shurui.shuruisutilities.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

// transparent full-width list row. real AbstractButton so clicks/sound/focus come for free (no hit-testing).
public class RowButton extends AbstractButton
{
    private final Runnable onPress;
    private int leftColor = 0xFFFFFFFF;
    private int rightColor = 0xFFB0B0B0;
    private Component right;
    private Component icon;
    private int textX = 4;

    public RowButton(int x, int y, int w, int h, Component label, Runnable onPress)
    {
        super(x, y, w, h, label);
        this.onPress = onPress;
    }

    // §-codes in the label override this
    public RowButton color(int argb)
    {
        this.leftColor = argb;
        return this;
    }

    public RowButton right(Component text, int argb)
    {
        this.right = text;
        this.rightColor = argb;
        return this;
    }

    // left-edge icon, shifts the label right
    public RowButton icon(Component icon)
    {
        this.icon = icon;
        this.textX = 24;
        return this;
    }

    @Override
    public void onPress()
    {
        onPress.run();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        if (isHoveredOrFocused())
        {
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0x33FFFFFF);
            g.fill(getX(), getY(), getX() + getWidth(), getY() + 1, 0x556AB07A);
            g.fill(getX(), getY() + getHeight() - 1, getX() + getWidth(), getY() + getHeight(), 0x556AB07A);
        }
        var font = Minecraft.getInstance().font;
        // Optically centre the row text in the row band (same nudge every themed element uses) instead of the raw
        // (h-8)/2 line-box centre.
        int ty = net.shurui.shuruisutilities.client.gui.theme.GuiText.centeredTextY(font, getY(), getHeight());
        if (icon != null)
            g.drawString(font, icon, getX() + 4, ty, 0xFFFFFFFF, false);
        // Fit the left label into the gap up to the trailing value (or the row's right edge), so a long name
        // shrinks/ellipsizes instead of running under the right-aligned value or past the row edge.
        int rightReserve = right != null ? font.width(right.getString()) + 8 : 4;
        int avail = Math.max(1, getWidth() - textX - rightReserve);
        String left = getMessage() == null ? "" : getMessage().getString();
        if (font.width(left) > avail)
            left = net.shurui.shuruisutilities.client.gui.theme.GuiText.ellipsize(font, left, avail);
        g.drawString(font, left, getX() + textX, ty, leftColor, false);
        if (right != null)
            g.drawString(font, right, getX() + getWidth() - 4 - font.width(right), ty, rightColor, false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output)
    {
        defaultButtonNarrationText(output);
    }
}
