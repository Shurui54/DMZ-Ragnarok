package net.shurui.shuruisutilities.client.gui.cosmetics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import net.shurui.shuruisutilities.client.gui.theme.GuiSounds;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticQuality;

/**
 * One square cosmetic in a grid: the theme's slot art, the quality frame, the emblem, and the two corner marks
 * that say "you are wearing this" and "this one counts to N".
 *
 * <p>A real {@link AbstractButton} rather than a hand-tested rectangle, so the click, the focus ring and the
 * keyboard route come from vanilla and {@code SagaBaseScreen}'s layout validator can see it. That is the same
 * reason {@code RowButton} is one. The SOUND is the suite's, not vanilla's; see {@link #onPress}.
 *
 * <p>Everything visual is delegated to {@link CosmeticStyle}: this class decides WHAT to draw and that one
 * decides how it looks, so a restyle is one file.
 */
public class CosmeticTile extends AbstractButton
{
    private final Runnable onPress;
    private final String catalogId;
    private final String name;
    private final CosmeticQuality quality;

    /** Short corner text, top right: a Super copy's count. Blank draws nothing. */
    private String counter = "";

    /** Whether this exact copy is the one currently worn. */
    private boolean equipped;

    /** Whether this tile is the screen's current selection. */
    private boolean selected;

    /**
     * The "wear nothing" tile. It draws the empty slot and nothing else, and it is the first tile in a picker
     * so taking a slot off never needs a different control somewhere else on the screen.
     */
    private boolean empty;

    public CosmeticTile(int x, int y, int size, String catalogId, String name, CosmeticQuality quality,
            Runnable onPress)
    {
        super(x, y, size, size, Component.literal(name == null ? "" : name));
        this.catalogId = catalogId == null ? "" : catalogId;
        this.name = name == null ? "" : name;
        this.quality = quality == null ? CosmeticQuality.NORMAL : quality;
        this.onPress = onPress;
    }

    public CosmeticTile counter(String text)
    {
        this.counter = text == null ? "" : text;
        return this;
    }

    public CosmeticTile equipped(boolean value)
    {
        this.equipped = value;
        return this;
    }

    public CosmeticTile selected(boolean value)
    {
        this.selected = value;
        return this;
    }

    public CosmeticTile empty(boolean value)
    {
        this.empty = value;
        return this;
    }

    @Override
    public void onPress()
    {
        // The suite's "exactly one sound per action" rule, copied from DmzTextureButton: run the action, then
        // play the press sound ONLY if the action did not itself open a screen (which plays its own navigation
        // sound synchronously inside init). Opening the picker from a slot box is exactly that case.
        int navBefore = GuiSounds.navigateCount();
        if (onPress != null)
            onPress.run();
        if (GuiSounds.navigateCount() == navBefore)
            GuiSounds.button();
    }

    /** Suppress vanilla's click so the DMZ sound played in {@link #onPress} is the only one. */
    @Override
    public void playDownSound(net.minecraft.client.sounds.SoundManager soundManager)
    {
        // Intentionally empty.
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        Font font = Minecraft.getInstance().font;
        int size = getWidth();
        CosmeticStyle.tile(g, getX(), getY(), size, empty ? CosmeticQuality.NORMAL : quality, equipped, selected,
                isHoveredOrFocused());
        if (empty)
        {
            CosmeticStyle.emblem(g, font, "", name, getX(), getY(), size, 0xFF8A8A8A);
            return;
        }
        CosmeticStyle.emblem(g, font, catalogId, name, getX(), getY(), size, CosmeticStyle.color(quality));
        if (!counter.isEmpty())
        {
            // Top right, in the quality colour, over a dark plate so a light emblem cannot swallow it. Lifted on
            // Z because the emblem is now a rendered ITEM, which draws in front and would otherwise win the
            // depth test against anything drawn after it. See CosmeticStyle.decorationZ.
            int w = font.width(counter);
            int cx = getX() + size - 2 - w;
            int cy = getY() + 2;
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, CosmeticStyle.decorationZ(size));
            g.fill(cx - 1, cy - 1, cx + w + 1, cy + font.lineHeight - 1, 0xCC101014);
            g.drawString(font, counter, cx, cy, CosmeticStyle.color(quality), false);
            g.pose().popPose();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output)
    {
        defaultButtonNarrationText(output);
    }
}
