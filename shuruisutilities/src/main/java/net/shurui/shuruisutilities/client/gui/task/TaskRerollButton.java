package net.shurui.shuruisutilities.client.gui.task;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import net.shurui.shuruisutilities.client.gui.theme.GuiSounds;

/**
 * The reroll control: the commissioned gold-framed arrow glyph, with no caption.
 *
 * <p>The price is a TOOLTIP rather than text beside the glyph, because the mock-up gives this control a square
 * icon slot with no room for a number, and the price differs per board. A player who wants to know what a
 * reroll costs hovers it; the cost is still refused server-side if they cannot afford it.</p>
 */
public class TaskRerollButton extends AbstractButton
{
    private final Runnable onPress;

    public TaskRerollButton(int x, int y, int size, Component tooltip, Runnable onPress)
    {
        super(x, y, size, size, Component.empty());
        this.onPress = onPress;
        setTooltip(Tooltip.create(tooltip));
    }

    @Override
    public void onPress()
    {
        int navBefore = GuiSounds.navigateCount();
        onPress.run();
        if (GuiSounds.navigateCount() == navBefore)
        {
            GuiSounds.button();
        }
    }

    @Override
    public void playDownSound(net.minecraft.client.sounds.SoundManager handler)
    {
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        TaskArt.reroll(g, getX(), getY(), getWidth(), isHovered());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output)
    {
        defaultButtonNarrationText(output);
    }
}
