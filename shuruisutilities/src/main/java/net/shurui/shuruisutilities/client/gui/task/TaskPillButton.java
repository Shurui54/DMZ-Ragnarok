package net.shurui.shuruisutilities.client.gui.task;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import net.shurui.shuruisutilities.client.gui.theme.GuiSounds;
import net.shurui.shuruisutilities.client.gui.theme.ThemeRender;

/**
 * An action button on the task board, drawn from the commissioned coloured pills instead of the shared theme
 * button. Green accepts, red abandons, yellow claims, which is the colour language the mock-up uses and the
 * reason these are not just themed buttons with coloured captions.
 *
 * <p>Sound handling copies {@link net.shurui.shuruisutilities.client.gui.DmzTextureButton}: run the action
 * first, then play the press sound only if the action did not itself open a screen, so one click never makes
 * two sounds.</p>
 */
public class TaskPillButton extends AbstractButton
{
    private final TaskArt.Pill pill;
    private final Runnable onPress;

    public TaskPillButton(int x, int y, int w, int h, Component label, TaskArt.Pill pill, Runnable onPress)
    {
        super(x, y, w, h, label);
        this.pill = pill;
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

    /** Suppress vanilla's click so the only sound is the DMZ one played in {@link #onPress()}. */
    @Override
    public void playDownSound(net.minecraft.client.sounds.SoundManager handler)
    {
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        TaskArt.pill(g, pill, getX(), getY(), getWidth(), getHeight(), isHovered(), this.active);
        // The pill art carries its own darker lower half, so a white caption reads on all three colours.
        ThemeRender.buttonLabel(g, Minecraft.getInstance().font, getMessage().getString(),
                getX(), getY(), getWidth(), getHeight(), 0xFFFFFFFF);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output)
    {
        defaultButtonNarrationText(output);
    }
}
