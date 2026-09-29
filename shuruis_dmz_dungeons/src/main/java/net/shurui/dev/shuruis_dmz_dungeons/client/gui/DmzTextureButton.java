package net.shurui.dev.shuruis_dmz_dungeons.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.shuruis_dmz_dungeons.client.gui.theme.GuiSounds;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_dmz_dungeons.client.gui.theme.ThemeRender;

// A button in the shared "Shurui's DMZ Essentials" theme: a spliced button sprite switching between
// normal / hover / disabled from GuiTheme.ButtonState, with a caption fitted to the inner width (shrunk then
// ellipsized so long, e.g. Spanish, labels never spill). The constructor still accepts the legacy DMZ atlas
// coordinates so existing call sites keep compiling, but the visual is driven entirely by the theme now.
public class DmzTextureButton extends AbstractButton {

    private final ResourceLocation texture;
    private final int u, v, spriteW, spriteH, atlas;
    private final Runnable onPress;

    // When set, ALWAYS renders as a ROUND-spliced icon pill (the shared buttons.png art with the angled right cap
    // replaced by a mirror of the rounded left cap), regardless of width. Width is still honoured as a fallback so
    // a narrow control stays round even without the flag.
    private boolean icon;
    // When set, a press plays DMZ's CONFIRM sound (a commit: Save / Add / Select) instead of the ordinary click.
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

    // Mark this button as a round-spliced icon pill (up/down move arrows, X delete). Chainable.
    public DmzTextureButton asIcon() {
        this.icon = true;
        return this;
    }

    // True when this button should draw the round pill: either flagged an icon, or narrow enough to fall under
    // the width heuristic (kept as a fallback so existing narrow buttons stay round).
    private boolean isIconButton() {
        return icon || getWidth() <= GuiTheme.ICON_BUTTON_MAX_W;
    }

    // Mark this button as a commit (Save/Add/Select): its press plays DMZ's confirm sound. Chainable.
    public DmzTextureButton commits() {
        this.commits = true;
        return this;
    }

    @Override
    public void onPress() {
        // Exactly ONE DMZ sound per user action. If the action opens a fresh screen, that screen's init() plays
        // its own navigation ("open") sound; the navigate counter advancing tells us to stay silent here. Otherwise
        // the press/confirm sound plays. Each GuiSounds call degrades to silence if the DMZ sound is absent.
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

    // Suppress vanilla's button click sound so exactly one sound plays per press (the DMZ sound in onPress). The
    // keyboard-activation path also calls this before onPress, so this fixes the duplicate on keyboard too.
    @Override
    public void playDownSound(net.minecraft.client.sounds.SoundManager soundManager) {
        // Intentionally empty: the DMZ sound plays in onPress instead.
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GuiTheme.ButtonState state = !this.active ? GuiTheme.ButtonState.DISABLED
                : (isHoveredOrFocused() ? GuiTheme.ButtonState.HOVER : GuiTheme.ButtonState.NORMAL);

        int color = this.active ? GuiTheme.COLOR_TITLE : GuiTheme.COLOR_DISABLED;
        if (isIconButton()) {
            // Round-spliced pill (X delete, move arrows): the SAME buttons.png art, both ends rounded, so an icon
            // button reads as the same family as the Edit/Copy buttons beside it. Caption uses the plain text
            // padding (no angled cap to clear).
            ThemeRender.buttonRounded(graphics, getX(), getY(), getWidth(), getHeight(), state);
            ThemeRender.iconButtonLabel(graphics, Minecraft.getInstance().font, getMessage().getString(),
                    getX(), getY(), getWidth(), getHeight(), color);
        } else {
            // Standard button: rounded left cap, authored angled right cap, flat interior tiled. Caption is
            // centred within BUTTON_HEIGHT and fitted so it clears the angled cap.
            ThemeRender.button(graphics, getX(), getY(), getWidth(), getHeight(), state);
            int capH = Math.min(getHeight(), GuiTheme.BUTTON_HEIGHT);
            int capY = getY() + (getHeight() - capH) / 2;
            ThemeRender.buttonLabel(graphics, Minecraft.getInstance().font, getMessage().getString(),
                    getX(), capY, getWidth(), capH, color);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
