package net.shurui.shuruisutilities.client.gui.task;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import net.shurui.shuruisutilities.client.gui.theme.GuiSounds;
import net.shurui.shuruisutilities.client.gui.theme.ThemeRender;

/**
 * One of the three board tabs (Daily / Weekly / Monthly), drawn on the commissioned plaque.
 *
 * <p>The plaque has a selected and an unselected frame in the art, so unlike the action pills this control does
 * not need a shader tint to show its state. Hover still brightens, to tell a clickable plaque apart from the
 * selected one sitting under the pointer.</p>
 */
public class TaskTabButton extends AbstractButton
{
    /** Latches so the diagnostic in {@link #renderWidget} prints once per client run, not once per frame. */
    private static final java.util.concurrent.atomic.AtomicBoolean SU_DIAG =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final boolean selected;
    private final Runnable onPress;

    public TaskTabButton(int x, int y, int w, int h, Component label, boolean selected, Runnable onPress)
    {
        super(x, y, w, h, label);
        this.selected = selected;
        this.onPress = onPress;
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
        // DIAGNOSTIC, one line per client run. Three rounds of "the tabs have no dragon balls" have all checked
        // out statically: the sprite box contains the balls, the blit is the scaling overload, and the label
        // draws no background over it. So the remaining question is whether this even runs, and at what size.
        // Silence in the log means this button is not on screen at all and the tabs being looked at are drawn by
        // something else; a line with a tiny height means the plaque is being squashed below where the balls can
        // survive. Remove once the answer is in.
        if (SU_DIAG.compareAndSet(false, true))
        {
            net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.info(
                    "[TaskTabs] drawing plaque at {}x{} (x={}, y={}) selected={}",
                    getWidth(), getHeight(), getX(), getY(), selected);
        }
        // Selected wins over hover: the current board must stay obvious while the pointer wanders across the row.
        if (selected)
        {
            TaskArt.tab(g, getX(), getY(), getWidth(), getHeight(), true);
        }
        else if (isHovered())
        {
            TaskArt.tabHovered(g, getX(), getY());
        }
        else
        {
            TaskArt.tab(g, getX(), getY(), getWidth(), getHeight(), false);
        }
        // Gold on the selected plaque, muted on the rest, so the current board is readable at a glance even
        // though hover borrows the same brighter plaque frame.
        int colour = selected ? 0xFFFFD24A : 0xFFBFAF8F;
        ThemeRender.buttonLabel(g, Minecraft.getInstance().font, getMessage().getString(),
                getX(), getY(), getWidth(), getHeight(), colour);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output)
    {
        defaultButtonNarrationText(output);
    }
}
