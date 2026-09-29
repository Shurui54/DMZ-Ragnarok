package net.shurui.shuruisutilities.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.shurui.shuruisutilities.client.gui.theme.GuiSounds;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.client.gui.theme.ThemeRender;

// A button in the "Shurui's" GUI theme: a nine-sliced button sprite that switches between the normal / hover /
// disabled states from GuiTheme.ButtonState, with a centred caption fitted to the button's inner width (shrunk
// then ellipsized so long, e.g. Spanish, labels never spill). The constructor still accepts the legacy DMZ atlas
// coordinates so existing call sites keep compiling, but the visual is driven entirely by the theme now, so every
// button in the mod shares one look.
public class DmzTextureButton extends AbstractButton
{
    private final ResourceLocation texture;
    private final int u, v, spriteW, spriteH, atlas;
    private final Runnable onPress;
    // When set, this button ALWAYS renders as a ROUND-spliced icon pill (the shared buttons.png art with the
    // angled right cap replaced by a mirror of the rounded left cap), regardless of its width. Width is still
    // honoured as a fallback so a narrow control stays round even if a screen forgot to flag it.
    private boolean icon;
    // When set, a press plays DMZ's CONFIRM sound (a commit: Save / Add / Select) instead of the ordinary click.
    private boolean commits;

    public DmzTextureButton(int x, int y, int width, int height, Component label, ResourceLocation texture,
            int u, int v, int spriteW, int spriteH, int atlas, Runnable onPress)
    {
        super(x, y, width, height, label);
        this.texture = texture;
        this.u = u;
        this.v = v;
        this.spriteW = spriteW;
        this.spriteH = spriteH;
        this.atlas = atlas;
        this.onPress = onPress;
    }

    // factory using the standard menubig button sprite
    public static DmzTextureButton of(int x, int y, int w, int h, Component label, Runnable onPress)
    {
        return new DmzTextureButton(x, y, w, h, label, DmzTextures.MENU_BIG, DmzTextures.BUTTON_U,
                DmzTextures.BUTTON_V, DmzTextures.BUTTON_W, DmzTextures.BUTTON_H, DmzTextures.ATLAS, onPress);
    }

    // Mark this button as a round-spliced icon pill (up/down move arrows, X delete). Chainable.
    public DmzTextureButton asIcon()
    {
        this.icon = true;
        return this;
    }

    // True when this button should draw the round pill: either flagged an icon, or narrow enough to fall under the
    // width heuristic (kept as a fallback so existing narrow buttons stay round).
    private boolean isIconButton()
    {
        return icon || getWidth() <= GuiTheme.ICON_BUTTON_MAX_W;
    }

    // Mark this button as a commit (Save/Add/Select): its press plays DMZ's confirm sound. Chainable.
    public DmzTextureButton commits()
    {
        this.commits = true;
        return this;
    }

    @Override
    public void onPress()
    {
        // Exactly ONE DMZ sound per user action. Run the action first, then play the press/confirm sound ONLY if
        // the action did NOT itself trigger a navigation ("open") sound (a fresh screen's init() bumps a monotonic
        // navigate counter synchronously inside onPress.run()). If it advanced, that navigation sound is the single
        // correct sound for this action and this button stays silent; otherwise the press/confirm sound plays.
        int navBefore = GuiSounds.navigateCount();
        onPress.run();
        if (GuiSounds.navigateCount() != navBefore)
            return; // the action opened a screen; its navigation sound is the single sound for this action.
        if (commits)
            GuiSounds.confirm();
        else
            GuiSounds.button();
    }

    // Suppress vanilla's button click sound so only our single DMZ sound plays per press. AbstractWidget's
    // mouseClicked (and AbstractButton.keyPressed) call playDownSound before onPress; overriding it to a no-op
    // leaves exactly one sound per interaction, the DMZ sound played in onPress.
    @Override
    public void playDownSound(net.minecraft.client.sounds.SoundManager soundManager)
    {
        // Intentionally empty: our DMZ sound is played in onPress instead.
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
    {
        GuiTheme.ButtonState state = !this.active ? GuiTheme.ButtonState.DISABLED
                : (isHoveredOrFocused() ? GuiTheme.ButtonState.HOVER : GuiTheme.ButtonState.NORMAL);

        // Small icon buttons (the move arrows and X delete) draw the SAME buttons.png art as every other button,
        // just spliced ROUND: the authored angled right cap is replaced by a horizontal mirror of the rounded left
        // cap so both ends are rounded. The icon glyph (arrow or X) is drawn centred on the pill.
        if (isIconButton())
        {
            ThemeRender.buttonRounded(graphics, getX(), getY(), getWidth(), getHeight(), state);
            int iconColor = this.active ? GuiTheme.COLOR_TITLE : GuiTheme.COLOR_DISABLED;
            ThemeRender.iconButtonLabel(graphics, Minecraft.getInstance().font,
                    getMessage() == null ? "" : getMessage().getString(),
                    getX(), getY(), getWidth(), getHeight(), iconColor);
            return;
        }

        // Draw and caption the button at the theme's ONE standard BUTTON_HEIGHT, vertically CENTRED in whatever
        // hit-box the screen built. Rendering every button at the same BUTTON_HEIGHT is the uniformity fix, and it
        // also trims a clear band off tall buttons so stacked per-row buttons never touch. The click/hit box
        // (getHeight) is unchanged, so click behaviour is identical and no screen file has to change.
        int drawH = Math.min(getHeight(), GuiTheme.BUTTON_HEIGHT);
        int drawY = getY() + (getHeight() - drawH) / 2;
        ThemeRender.button(graphics, getX(), drawY, getWidth(), drawH, state);

        int color = this.active ? GuiTheme.COLOR_TITLE : GuiTheme.COLOR_DISABLED;
        ThemeRender.buttonLabel(graphics, Minecraft.getInstance().font,
                getMessage() == null ? "" : getMessage().getString(),
                getX(), drawY, getWidth(), drawH, color);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output)
    {
        defaultButtonNarrationText(output);
    }
}
