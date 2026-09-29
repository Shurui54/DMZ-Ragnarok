package net.shurui.dev.sdu.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.client.gui.theme.ThemeRender;

/**
 * Button in the "Shurui's DMZ Essentials" theme: a nine-sliced sprite switching between normal/hover/disabled
 * ({@link GuiTheme.ButtonState}), caption fitted to the inner width (shrunk then ellipsized so long labels,
 * e.g. Spanish, never spill).
 *
 * <p>The constructor still accepts the legacy DMZ atlas coordinates so existing call sites compile, but the
 * visual is driven entirely by the theme now.
 */
public class DmzTextureButton extends AbstractButton {

    private final ResourceLocation texture;
    private final int u, v, spriteW, spriteH, atlas;
    private final Runnable onPress;
    /**
     * When set, ALWAYS renders as a ROUND-spliced icon pill regardless of width. The width-only rule
     * ({@code width <= ICON_BUTTON_MAX_W}) missed row-delete "X" buttons built wider than that, so an explicit
     * flag lets a screen opt in by intent. Width is still honoured as a fallback for a narrow unflagged control.
     */
    private boolean icon;
    /**
     * When set, a press plays DMZ's CONFIRM sound (Save/Add/Select/an applying Back) instead of the ordinary
     * click. Off by default; opt in with {@link #commits()}. A per-button flag, not a fragile label-string check.
     */
    private boolean commits;
    /**
     * When set, plays NO press sound: the action opens a screen ASYNCHRONOUSLY (an open-request packet; the
     * server's reply opens the editor a tick or more later, and that screen's init() plays the one navigation
     * sound). A cross-tick pair can't be coalesced, so the initiator is silenced. Opt in with {@link #opensScreen()}.
     */
    private boolean silentPress;

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

    /** Mark as a round-spliced icon pill (move arrows, X delete). Chainable. See {@link #icon}. */
    public DmzTextureButton asIcon() {
        this.icon = true;
        return this;
    }

    /** Draw the round pill: flagged an icon, or narrow enough for the width heuristic (fallback). */
    private boolean isIconButton() {
        return icon || getWidth() <= GuiTheme.ICON_BUTTON_MAX_W;
    }

    /** Mark this button as a commit (Save/Add/Select): its press plays DMZ's confirm sound. Chainable. */
    public DmzTextureButton commits() {
        this.commits = true;
        return this;
    }

    /** Mark as an ASYNC screen opener: plays no press sound, the arriving screen's navigate() is the one sound. Chainable. See {@link #silentPress}. */
    public DmzTextureButton opensScreen() {
        this.silentPress = true;
        return this;
    }

    @Override
    public void onPress() {
        // Exactly ONE DMZ sound per user action. A sync screen open plays its navigate() during onPress.run()
        // this same tick, and GuiSounds keeps only the tick's first UI sound, so it wins and the press/confirm
        // below is dropped. Actions that stay on this screen play no navigate, so the press/confirm is the only
        // sound. Async opens (open-request packet answered later) can't be coalesced across ticks, so they
        // self-silence via opensScreen(). Client-only; GuiSounds degrades to silence if the sound is absent or muted.
        onPress.run();
        if (silentPress) {
            return; // opens a screen asynchronously: the arriving screen's navigate() is this action's one sound.
        }
        if (commits) {
            net.shurui.dev.sdu.client.gui.theme.GuiSounds.confirm();
        } else {
            net.shurui.dev.sdu.client.gui.theme.GuiSounds.button();
        }
    }

    /**
     * Suppress vanilla's button click sound. This was the "gui sounds play a couple" defect:
     * {@code AbstractWidget.mouseClicked} calls both {@code playDownSound} (vanilla {@code UI_BUTTON_CLICK}) and
     * {@code onClick -> onPress} (our DMZ sound), so one press produced TWO sounds. No-op here leaves exactly the
     * DMZ sound from {@link #onPress}. Also fixes the keyboard-activation path in {@code AbstractButton.keyPressed}.
     */
    @Override
    public void playDownSound(net.minecraft.client.sounds.SoundManager soundManager) {
        // Intentionally empty: our DMZ sound is played in onPress instead, so only one sound plays per press.
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GuiTheme.ButtonState state = !this.active ? GuiTheme.ButtonState.DISABLED
                : (isHoveredOrFocused() ? GuiTheme.ButtonState.HOVER : GuiTheme.ButtonState.NORMAL);

        // Small icon buttons draw the SAME buttons.png art, just spliced ROUND: the authored angled right cap is
        // replaced by a horizontal mirror of the rounded left cap so both ends are rounded
        // (NineSlice.drawButtonRounded), so an icon button reads as the same family as the Edit/Copy buttons
        // beside it. Selected by asIcon() (or, as a fallback, by being narrow). The glyph is drawn centred below.
        if (isIconButton()) {
            // round pill at the full button rect, then caption the glyph centred in the same box
            ThemeRender.buttonRounded(graphics, getX(), getY(), getWidth(), getHeight(), state);
            int iconColor = this.active ? GuiTheme.COLOR_TITLE : GuiTheme.COLOR_DISABLED;
            ThemeRender.iconButtonLabel(graphics, net.minecraft.client.Minecraft.getInstance().font,
                    getMessage() == null ? "" : getMessage().getString(),
                    getX(), getY(), getWidth(), getHeight(), iconColor);
            return;
        }

        // Draw and caption at the theme's ONE BUTTON_HEIGHT, vertically CENTRED in whatever hit-box the screen
        // built. Screens passed a spread of heights (14/15/16/18); rendering all at BUTTON_HEIGHT is the
        // uniformity fix and trims a band off tall buttons so stacked per-row buttons never touch. The hit box
        // (getHeight) is unchanged, so click behaviour is identical. GuiText centres the caption without clipping.
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
