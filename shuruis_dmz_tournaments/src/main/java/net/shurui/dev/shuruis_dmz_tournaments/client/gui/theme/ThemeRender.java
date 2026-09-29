package net.shurui.dev.shuruis_dmz_tournaments.client.gui.theme;


import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Convenience drawers that bind the {@link GuiTheme} tokens to {@link NineSlice}, so the base screens and
 * shared widgets each call a single method ({@code panel}, {@code button}, {@code dropdown}, {@code tooltip},
 * {@code header}) instead of repeating the source-rect + inset arithmetic. Changing the look of an element
 * across the whole addon means editing exactly one method here (or, for a pure art swap, just the PNG the
 * token in {@link GuiTheme} points at).
 *
 * <p>Client-only.
 */
public final class ThemeRender {

    private ThemeRender() {
    }

    /**
     * The commissioned panel background. The corner (dragonball stud and rails) is spliced; the straight borders
     * and body are stretched, exact because the body is a top-to-bottom gradient of flat rows.
     */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        NineSlice.draw(g, GuiTheme.PANEL, x, y, w, h,
                GuiTheme.PANEL_CONTENT_X, GuiTheme.PANEL_CONTENT_Y, GuiTheme.PANEL_CONTENT_W, GuiTheme.PANEL_CONTENT_H,
                GuiTheme.PANEL_TEX_W, GuiTheme.PANEL_TEX_H, GuiTheme.PANEL_INSET, GuiTheme.PANEL_SRC_INSET);
    }

    /** One button state spliced to {@code (x,y,w,h)}: the art's own end caps each side, flat interior between,
     *  body stretched on the vertical axis. Every NORMAL (non-icon) button. DISABLED draws the resting strip
     *  under a grey wash, because the art ships no disabled variant. */
    public static void button(GuiGraphics g, int x, int y, int w, int h, GuiTheme.ButtonState state) {
        NineSlice.drawButton(g, GuiTheme.BUTTONS, x, y, w, h,
                GuiTheme.BUTTON_STATE_X, state.v, GuiTheme.BUTTON_STATE_W, GuiTheme.BUTTON_STATE_H,
                GuiTheme.BUTTONS_TEX_W, GuiTheme.BUTTONS_TEX_H,
                GuiTheme.BUTTON_SRC_CAP_W, "button");
        if (state == GuiTheme.ButtonState.DISABLED) {
            g.fill(x, y, x + w, y + h, GuiTheme.BUTTON_DISABLED_WASH);
        }
    }

    /** One button state as a ROUND pill for an icon button (X delete, up/down arrows): the SAME art as
     *  {@link #button}, right cap a horizontal mirror of the left so both ends match. Uses the narrow
     *  {@link GuiTheme#ICON_BUTTON_SRC_CAP_W} so a small square keeps interior between the ends. */
    public static void buttonRounded(GuiGraphics g, int x, int y, int w, int h, GuiTheme.ButtonState state) {
        NineSlice.drawButtonRounded(g, GuiTheme.BUTTONS, x, y, w, h,
                GuiTheme.BUTTON_STATE_X, state.v, GuiTheme.BUTTON_STATE_W, GuiTheme.BUTTON_STATE_H,
                GuiTheme.BUTTONS_TEX_W, GuiTheme.BUTTONS_TEX_H,
                GuiTheme.ICON_BUTTON_SRC_CAP_W, "icon_button");
        if (state == GuiTheme.ButtonState.DISABLED) {
            g.fill(x, y, x + w, y + h, GuiTheme.BUTTON_DISABLED_WASH);
        }
    }

    /** The dropdown / field body fill, spliced (bevel caps, flat interior). Used for dropdown headers and
     *  text-field backdrops. */
    public static void dropdown(GuiGraphics g, int x, int y, int w, int h) {
        NineSlice.drawTiled(g, GuiTheme.DROPDOWN, x, y, w, h,
                0, 0, GuiTheme.DROPDOWN_TEX_W, GuiTheme.DROPDOWN_TEX_H,
                GuiTheme.DROPDOWN_TEX_W, GuiTheme.DROPDOWN_TEX_H,
                GuiTheme.DROPDOWN_INSET, GuiTheme.DROPDOWN_SRC_INSET, "dropdown");
    }

    /**
     * The OPEN dropdown list body, drawn as ONE spliced element for the whole list, not once per row. Same box as
     * the closed header under a darkening wash, so the list reads as a panel opening BEHIND the header. Row
     * selection and hover are a subtle overlay drawn on top by
     * {@link net.shurui.dev.shuruis_dmz_tournaments.client.gui.DmzDropdown}.
     */
    public static void dropdownList(GuiGraphics g, int x, int y, int w, int h) {
        dropdown(g, x, y, w, h);
        g.fill(x, y, x + w, y + h, GuiTheme.DROPDOWN_LIST_WASH);
    }

    /**
     * A tab-header cell background, spliced from the same {@code dropdown.png} body as {@link #dropdown}. Active
     * and inactive tabs share the art; the active one gets a brightness wash
     * ({@link GuiTheme#COLOR_TAB_ACTIVE_WASH}) plus a gold underline the caller draws.
     */
    public static void tab(GuiGraphics g, int x, int y, int w, int h, boolean active) {
        dropdown(g, x, y, w, h);
        if (active) {
            g.fill(x, y, x + w, y + h, GuiTheme.COLOR_TAB_ACTIVE_WASH);
        }
    }

    /** The tooltip / callout body fill, spliced (derived from the dropdown art for now). */
    public static void tooltip(GuiGraphics g, int x, int y, int w, int h) {
        NineSlice.drawTiled(g, GuiTheme.TOOLTIP, x, y, w, h,
                0, 0, GuiTheme.TOOLTIP_TEX_W, GuiTheme.TOOLTIP_TEX_H,
                GuiTheme.TOOLTIP_TEX_W, GuiTheme.TOOLTIP_TEX_H,
                GuiTheme.TOOLTIP_INSET, GuiTheme.DROPDOWN_SRC_INSET, "tooltip");
    }

    /** The text-field body fill, spliced (derived from the dropdown art for now). */
    public static void field(GuiGraphics g, int x, int y, int w, int h) {
        NineSlice.drawTiled(g, GuiTheme.FIELD, x, y, w, h,
                0, 0, GuiTheme.FIELD_TEX_W, GuiTheme.FIELD_TEX_H,
                GuiTheme.FIELD_TEX_W, GuiTheme.FIELD_TEX_H,
                GuiTheme.FIELD_INSET, GuiTheme.DROPDOWN_SRC_INSET, "field");
    }

    /** Backwards-compatible entry point for callers with no drag state to hand over; draws the idle thumb. */
    public static void scrollbar(GuiGraphics g, int x, int trackTop, int trackH, int thumbY, int thumbH) {
        scrollbar(g, x, trackTop, trackH, thumbY, thumbH, false);
    }

    /**
     * Draw the commissioned scrollbar: the track, then the thumb on top.
     *
     * <p>{@code held} picks the thumb's two authored states. True while the thumb is DRAGGED, not merely hovered,
     * so the darker state means "you have hold of this".
     */
    public static void scrollbar(GuiGraphics g, int x, int trackTop, int trackH, int thumbY, int thumbH,
                                 boolean held) {
        NineSlice.drawScrollPiece(g, GuiTheme.SCROLLBAR, x, trackTop, GuiTheme.SCROLLBAR_WIDTH, trackH,
                GuiTheme.SCROLLBAR_CONTENT_X, GuiTheme.SCROLLBAR_CONTENT_Y,
                GuiTheme.SCROLLBAR_CONTENT_W, GuiTheme.SCROLLBAR_CONTENT_H,
                GuiTheme.SCROLLBAR_TEX_W, GuiTheme.SCROLLBAR_TEX_H,
                GuiTheme.SCROLLBAR_CAP, GuiTheme.SCROLLBAR_SRC_CAP, 0, 0, 0);

        int thumbW = GuiTheme.SCROLLBAR_THUMB_WIDTH;
        int thumbX = x + (GuiTheme.SCROLLBAR_WIDTH - thumbW) / 2;
        int stateX = held ? GuiTheme.SCROLLBAR_THUMB_HELD_X : GuiTheme.SCROLLBAR_THUMB_IDLE_X;
        NineSlice.drawScrollPiece(g, GuiTheme.SCROLLBAR_ALT, thumbX, thumbY, thumbW, thumbH,
                stateX, 0, GuiTheme.SCROLLBAR_THUMB_STATE_W, GuiTheme.SCROLLBAR_THUMB_STATE_H,
                GuiTheme.SCROLLBAR_THUMB_TEX_W, GuiTheme.SCROLLBAR_THUMB_TEX_H,
                GuiTheme.SCROLLBAR_THUMB_CAP, GuiTheme.SCROLLBAR_THUMB_SRC_CAP,
                GuiTheme.SCROLLBAR_THUMB_RIB_SRC_Y, GuiTheme.SCROLLBAR_THUMB_RIB_SRC_H,
                GuiTheme.SCROLLBAR_THUMB_RIB_DST_H);
    }

    /**
     * DEAD: the old circular icon-button disc from {@link GuiTheme#CIRCLE_BUTTON}. NO LONGER CALLED; icon buttons
     * now draw {@link #buttonRounded}. Retained unreferenced only so a stale importer keeps compiling.
     */
    public static void circleButton(GuiGraphics g, int x, int y, int w, int h, GuiTheme.ButtonState state) {
        int v = state == GuiTheme.ButtonState.HOVER ? GuiTheme.CIRCLE_BUTTON_HOVER_V : GuiTheme.CIRCLE_BUTTON_NORMAL_V;
        g.blit(GuiTheme.CIRCLE_BUTTON, x, y, w, h,
                0f, (float) v, GuiTheme.CIRCLE_BUTTON_TEX_W, GuiTheme.CIRCLE_BUTTON_STATE_H,
                GuiTheme.CIRCLE_BUTTON_TEX_W, GuiTheme.CIRCLE_BUTTON_TEX_H);
    }

    /**
     * Draw the "DMZ Ragnarok" logo centred in the header at the theme's ONE fixed size, called by every screen.
     *
     * <p>Zero distortion needs a UNIFORM scale on both axes: the destination is
     * {@code (LOGO_TEX_W/LOGO_DOWNSCALE) x (LOGO_TEX_H/LOGO_DOWNSCALE)} and the source is exactly
     * {@code dest * LOGO_DOWNSCALE}, i.e. {@code 27*4 = 108} wide by {@code 16*4 = 64} tall. The sheet's width is a
     * multiple of {@link GuiTheme#LOGO_DOWNSCALE} so that source region is the whole sheet and both axes scale by
     * exactly {@code 1/LOGO_DOWNSCALE}; a width that did not divide evenly would round X differently from Y and
     * squash the art. The centring arithmetic below trims a future leftover margin symmetrically.</p>
     */
    public static void header(GuiGraphics g, int panelLeft, int panelTop, int panelWidth) {
        int lw = GuiTheme.LOGO_DRAW_WIDTH;
        int lh = GuiTheme.LOGO_DRAW_HEIGHT;
        int srcW = lw * GuiTheme.LOGO_DOWNSCALE;                 // 108, an exact multiple of the dest width
        int srcH = lh * GuiTheme.LOGO_DOWNSCALE;                 // 64
        float srcX = (GuiTheme.LOGO_TEX_W - srcW) / 2f;          // 0 on the current sheet; centres any leftover margin
        int lx = panelLeft + (panelWidth - lw) / 2;
        int ly = panelTop + GuiTheme.LOGO_TOP;
        g.blit(GuiTheme.LOGO, lx, ly, lw, lh, srcX, 0f, srcW, srcH,
                GuiTheme.LOGO_TEX_W, GuiTheme.LOGO_TEX_H);
    }

    /**
     * Horizontally/vertically centred fitted caption for a button. The fit width is the button's DRAWN width less
     * {@link GuiTheme#BUTTON_TEXT_INSET} PER SIDE (not the generic {@link GuiTheme#TEXT_PADDING_X}): the button
     * art's angled end cap cuts the top corner in by ~11px, so a caption fitted to the full text-padding inner
     * rode onto that slant and read as spilling (the "Copy still overflows" defect). {@code w} is
     * {@code getWidth()}, which equals the drawn button width for both normal and icon buttons, so this reserves
     * against the drawn width.
     */
    public static void buttonLabel(GuiGraphics g, Font font, String text, int x, int y, int w, int h, int color) {
        int innerW = Math.max(1, w - 2 * GuiTheme.BUTTON_TEXT_INSET);
        GuiText.drawFittedCentered(g, font, text, x + w / 2, y, innerW, h, color);
    }

    /**
     * Centred fitted caption for an ICON button (round-spliced X/arrow/equals). The round splice has no angled
     * slant to clear, so its single-glyph caption uses only the generic {@link GuiTheme#TEXT_PADDING_X}, not the
     * larger {@link GuiTheme#BUTTON_TEXT_INSET}: on the small (14-18px) pill that larger inset would leave almost
     * no room and needlessly shrink the glyph.
     */
    public static void iconButtonLabel(GuiGraphics g, Font font, String text, int x, int y, int w, int h, int color) {
        GuiText.drawFittedCentered(g, font, text, x + w / 2, y, GuiText.innerWidth(w), h, color);
    }
}
