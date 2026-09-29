package net.shurui.dev.shuruis_dmz_tournaments.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.shuruis_dmz_tournaments.client.gui.theme.GuiSounds;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_dmz_tournaments.client.gui.theme.ThemeRender;

/**
 * A button in the ported "Shurui's DMZ" theme: a nine-sliced sprite that switches normal / hover /
 * disabled states from {@link GuiTheme.ButtonState}, with a centred caption fitted to the inner width
 * (shrunk then ellipsized so long, e.g. Spanish, labels never spill).
 *
 * <p>The constructor still takes the legacy DMZ atlas coordinates so existing call sites compile, but
 * the visual is driven by the theme now, so every button shares one look.
 */
public class DmzTextureButton extends AbstractButton {

    private final ResourceLocation texture;
    private final int u, v, spriteW, spriteH, atlas;
    private final Runnable onPress;
    /**
     * When set, always renders as a ROUND-spliced icon pill regardless of width. The width-only rule
     * ({@code width <= ICON_BUTTON_MAX_W}) missed row delete "X" buttons built wider than that, so the
     * flag lets a screen opt in by intent. Width still counts as a fallback for a control a screen
     * forgot to flag.
     */
    private boolean icon;
    /**
     * When set, a press plays DMZ's CONFIRM sound (Save / Add / Select / a Back that applies edits)
     * instead of the ordinary click. Off by default; opt in with {@link #commits()}. Per-button flag so
     * the distinction lives with the control, not a fragile label-string check.
     */
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

    /** Mark this button a round-spliced icon pill (move arrows, X delete). Chainable. See {@link #icon}. */
    public DmzTextureButton asIcon() {
        this.icon = true;
        return this;
    }

    /** Round pill when flagged an icon, or narrow enough for the width heuristic (kept as a fallback). */
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
        // Exactly ONE DMZ sound per action. The double-sound defect: a press that OPENS a fresh screen
        // played this handler's sound AND that screen's init() navigation sound. So run the action first,
        // then play the press/confirm sound only if the action did NOT bump GuiSounds' monotonic navigate
        // counter (a fresh screen's init() bumps it synchronously inside onPress.run()). If it advanced,
        // the navigation sound is the one correct sound and this button stays silent. Client-only; each
        // GuiSounds call degrades to silence if the DMZ sound is absent.
        int navBefore = GuiSounds.navigateCount();
        onPress.run();
        if (GuiSounds.navigateCount() != navBefore) {
            return; // the action opened a screen; its navigation sound covers this action
        }
        if (commits) {
            GuiSounds.confirm();
        } else {
            GuiSounds.button();
        }
    }

    /**
     * Suppress vanilla's button click sound. {@code AbstractWidget.mouseClicked} calls {@code playDownSound}
     * (vanilla {@code UI_BUTTON_CLICK}) AND {@code onClick -> onPress} (our DMZ sound), so one press made TWO
     * sounds. No-op here leaves exactly the DMZ sound from {@link #onPress}. The keyboard path in
     * {@code AbstractButton.keyPressed} also calls this before {@code onPress}, so this fixes that too.
     */
    @Override
    public void playDownSound(net.minecraft.client.sounds.SoundManager soundManager) {
        // empty on purpose: the DMZ sound plays in onPress, so one sound per press
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GuiTheme.ButtonState state = !this.active ? GuiTheme.ButtonState.DISABLED
                : (isHoveredOrFocused() ? GuiTheme.ButtonState.HOVER : GuiTheme.ButtonState.NORMAL);

        // Small icon buttons draw the SAME buttons.png art, spliced ROUND: the authored angled right cap is
        // replaced by a horizontal mirror of the rounded left cap so both ends round (NineSlice.drawButtonRounded),
        // keeping them in the same family as the Edit/Copy buttons. Selected by asIcon() or, as a fallback, by
        // being narrow. The glyph is drawn centred on the pill below.
        if (isIconButton()) {
            ThemeRender.buttonRounded(graphics, getX(), getY(), getWidth(), getHeight(), state);
            int iconColor = this.active ? GuiTheme.COLOR_TITLE : GuiTheme.COLOR_DISABLED;
            ThemeRender.iconButtonLabel(graphics, net.minecraft.client.Minecraft.getInstance().font,
                    getMessage() == null ? "" : getMessage().getString(),
                    getX(), getY(), getWidth(), getHeight(), iconColor);
            return;
        }

        // Draw every button at the theme's one BUTTON_HEIGHT, vertically CENTRED in whatever hit-box the screen
        // built. Screens historically passed a spread (11/14/16/18/20); one height is the uniformity fix and
        // trims a band off tall buttons so stacked per-row buttons never touch. Hit box (getHeight) is unchanged,
        // so clicks behave the same and no screen has to change. GuiText centres the caption within BUTTON_HEIGHT.
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
