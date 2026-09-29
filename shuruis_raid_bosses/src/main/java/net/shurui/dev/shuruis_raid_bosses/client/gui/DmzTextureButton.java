package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.shuruis_raid_bosses.client.gui.theme.GuiSounds;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_raid_bosses.client.gui.theme.ThemeRender;

/**
 * A button in the shared "Shurui" theme: a nine-sliced sprite in the normal/hover/disabled states of
 * {@link GuiTheme.ButtonState} with a centred caption fitted to the inner width (shrunk then ellipsized so
 * long labels, e.g. Spanish, never spill).
 *
 * <p>The constructor still takes the legacy DMZ atlas coords so old call sites compile, but the visual is
 * driven entirely by the theme now.
 */
public class DmzTextureButton extends AbstractButton {

    private final ResourceLocation texture;
    private final int u, v, spriteW, spriteH, atlas;
    private final Runnable onPress;
    /**
     * When set, always renders as a ROUND-spliced icon pill regardless of width. Width is still honoured as a
     * fallback so a narrow control stays round even if a screen forgot to flag it.
     */
    private boolean icon;
    /** When set, a press plays DMZ's CONFIRM sound (Save/Add/Select) instead of the ordinary click. Off by
     *  default; opt in with {@link #commits()}. */
    private boolean commits;

    public DmzTextureButton(int x, int y, int width, int height, Component label,
                            ResourceLocation texture, int u, int v, int spriteW, int spriteH, int atlas,
                            Runnable onPress) {
        super(x, y, width, height, label);
        this.texture = texture;
        this.u = u;
        this.v = v;
        this.spriteW = spriteW;
        this.spriteH = spriteH;
        this.atlas = atlas;
        this.onPress = onPress;
    }

    /** Mark this button as a round-spliced icon pill (up/down move arrows, X delete). Chainable. */
    public DmzTextureButton asIcon() {
        this.icon = true;
        return this;
    }

    /** True when this draws the round pill: flagged an icon, or narrow enough for the width heuristic. */
    private boolean isIconButton() {
        return icon || getWidth() <= GuiTheme.ICON_BUTTON_MAX_W;
    }

    /** Mark this button as a commit (Save/Add/Select): its press plays DMZ's confirm sound. Chainable. */
    public DmzTextureButton commits() {
        this.commits = true;
        return this;
    }

    @Override
    public void onPress() {
        // Exactly ONE DMZ sound per action. The double-sound defect was a press that OPENS a screen: this
        // played the press sound AND the new screen's init() played its navigation sound. So run the action
        // first, then play the press/confirm sound only if the action did NOT itself bump GuiSounds' navigate
        // counter.
        int navBefore = GuiSounds.navigateCount();
        onPress.run();
        if (GuiSounds.navigateCount() != navBefore) {
            return; // the action opened a screen; its navigation sound is the single sound for this action.
        }
        if (commits) {
            GuiSounds.confirm();
        } else {
            GuiSounds.button();
        }
    }

    /**
     * Suppress vanilla's button click sound. {@code AbstractWidget.mouseClicked} calls both {@code playDownSound}
     * (vanilla's UI_BUTTON_CLICK) and {@code onClick -> onPress} (our DMZ sound), producing TWO sounds per press.
     * No-op here leaves the single DMZ sound played in {@link #onPress}. Keyboard activation goes through here too.
     */
    @Override
    public void playDownSound(net.minecraft.client.sounds.SoundManager soundManager) {
        // Intentionally empty: our DMZ sound is played in onPress instead, so only one sound plays per press.
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GuiTheme.ButtonState state = !this.active ? GuiTheme.ButtonState.DISABLED
                : (isHoveredOrFocused() ? GuiTheme.ButtonState.HOVER : GuiTheme.ButtonState.NORMAL);

        // Icon buttons (move arrows, X delete) draw the SAME buttons.png art spliced ROUND: the angled right
        // cap replaced by a mirror of the rounded left, so both ends round. Selected by asIcon() or, as a
        // fallback, by being narrow.
        if (isIconButton()) {
            ThemeRender.buttonRounded(graphics, getX(), getY(), getWidth(), getHeight(), state);
            int iconColor = this.active ? GuiTheme.COLOR_TITLE : GuiTheme.COLOR_DISABLED;
            ThemeRender.iconButtonLabel(graphics, net.minecraft.client.Minecraft.getInstance().font,
                    getMessage() == null ? "" : getMessage().getString(),
                    getX(), getY(), getWidth(), getHeight(), iconColor);
            return;
        }

        // Draw and caption at the theme's ONE BUTTON_HEIGHT, centred in whatever hit-box the screen built. Trims
        // a clear band off tall buttons so stacked rows never touch; the click box (getHeight) is unchanged.
        int drawH = Math.min(getHeight(), GuiTheme.BUTTON_HEIGHT);
        int drawY = getY() + (getHeight() - drawH) / 2;
        ThemeRender.button(graphics, getX(), drawY, getWidth(), drawH, state);

        int color = this.active ? GuiTheme.COLOR_TITLE : GuiTheme.COLOR_DISABLED;
        ThemeRender.buttonLabel(graphics, net.minecraft.client.Minecraft.getInstance().font,
                getMessage() == null ? "" : getMessage().getString(),
                getX(), drawY, getWidth(), drawH, color);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
