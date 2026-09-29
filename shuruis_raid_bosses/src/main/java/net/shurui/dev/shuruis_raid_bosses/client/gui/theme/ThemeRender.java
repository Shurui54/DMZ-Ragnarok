package net.shurui.dev.shuruis_raid_bosses.client.gui.theme;


import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Convenience drawers that bind the {@link GuiTheme} tokens to {@link NineSlice}, so base screens and shared
 * widgets call one method ({@code panel}, {@code button}, {@code dropdown}, {@code tooltip}, {@code header})
 * instead of repeating the source-rect + inset arithmetic. Change an element's look in one method here (or
 * swap the PNG the {@link GuiTheme} token points at).
 *
 * <p>Client-only.
 */
public final class ThemeRender {

    private ThemeRender() {
    }

    /** Panel background. The corner (dragonball stud + rails) is spliced; the straight borders and gradient
     *  body are stretched (exact). */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        NineSlice.draw(g, GuiTheme.PANEL, x, y, w, h,
                GuiTheme.PANEL_CONTENT_X, GuiTheme.PANEL_CONTENT_Y, GuiTheme.PANEL_CONTENT_W, GuiTheme.PANEL_CONTENT_H,
                GuiTheme.PANEL_TEX_W, GuiTheme.PANEL_TEX_H, GuiTheme.PANEL_INSET, GuiTheme.PANEL_SRC_INSET);
    }

    /** One button state, spliced: the art's own end caps, flat interior between, body stretched vertically.
     *  Every NORMAL (non-icon) button. DISABLED is the resting strip under a grey wash (no disabled art). */
    public static void button(GuiGraphics g, int x, int y, int w, int h, GuiTheme.ButtonState state) {
        NineSlice.drawButton(g, GuiTheme.BUTTONS, x, y, w, h,
                GuiTheme.BUTTON_STATE_X, state.v, GuiTheme.BUTTON_STATE_W, GuiTheme.BUTTON_STATE_H,
                GuiTheme.BUTTONS_TEX_W, GuiTheme.BUTTONS_TEX_H,
                GuiTheme.BUTTON_SRC_CAP_W, "button");
        if (state == GuiTheme.ButtonState.DISABLED) {
            g.fill(x, y, x + w, y + h, GuiTheme.BUTTON_DISABLED_WASH);
        }
    }

    /** Icon button as a ROUND pill (X delete, up/down arrows): same art as {@link #button} but the right cap
     *  mirrors the left so both ends match. Narrow {@link GuiTheme#ICON_BUTTON_SRC_CAP_W} so a small square
     *  still has interior between the ends. */
    public static void buttonRounded(GuiGraphics g, int x, int y, int w, int h, GuiTheme.ButtonState state) {
        NineSlice.drawButtonRounded(g, GuiTheme.BUTTONS, x, y, w, h,
                GuiTheme.BUTTON_STATE_X, state.v, GuiTheme.BUTTON_STATE_W, GuiTheme.BUTTON_STATE_H,
                GuiTheme.BUTTONS_TEX_W, GuiTheme.BUTTONS_TEX_H,
                GuiTheme.ICON_BUTTON_SRC_CAP_W, "icon_button");
        if (state == GuiTheme.ButtonState.DISABLED) {
            g.fill(x, y, x + w, y + h, GuiTheme.BUTTON_DISABLED_WASH);
        }
    }

    /** Dropdown / field body fill, spliced (bevel caps, flat interior). Dropdown headers and text-field backdrops. */
    public static void dropdown(GuiGraphics g, int x, int y, int w, int h) {
        NineSlice.drawTiled(g, GuiTheme.DROPDOWN, x, y, w, h,
                0, 0, GuiTheme.DROPDOWN_TEX_W, GuiTheme.DROPDOWN_TEX_H,
                GuiTheme.DROPDOWN_TEX_W, GuiTheme.DROPDOWN_TEX_H,
                GuiTheme.DROPDOWN_INSET, GuiTheme.DROPDOWN_SRC_INSET, "dropdown");
    }

    /**
     * OPEN dropdown list body, ONE spliced element for the whole list not once per row. Same box as the closed
     * header under a darkening wash, so it reads as a panel opening behind the header. Row selection and hover
     * are an overlay drawn on top by {@link net.shurui.dev.shuruis_raid_bosses.client.gui.DmzDropdown}.
     */
    public static void dropdownList(GuiGraphics g, int x, int y, int w, int h) {
        dropdown(g, x, y, w, h);
        g.fill(x, y, x + w, y + h, GuiTheme.DROPDOWN_LIST_WASH);
    }

    /**
     * Tab-header cell, spliced from the same {@code dropdown.png} as {@link #dropdown}. Active/inactive share
     * the art; the active one gets a brightness wash ({@link GuiTheme#COLOR_TAB_ACTIVE_WASH}) plus a gold
     * underline the caller draws.
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
     * Draw the scrollbar: track, then thumb on top. {@code held} picks the thumb's two states; true while
     * DRAGGED, not hovered, so the darker state means "you have hold of this".
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
     * Draw the "DMZ Ragnarok" logo centred in the header at the theme's one fixed size.
     *
     * <p>UNIFORM scale on both axes so nothing squashes: destination is
     * {@code (LOGO_TEX_W/LOGO_DOWNSCALE) x (LOGO_TEX_H/LOGO_DOWNSCALE)} and the source is exactly
     * {@code dest * LOGO_DOWNSCALE} = {@code 27*4 = 108} by {@code 16*4 = 64}. The sheet width is a multiple of
     * {@link GuiTheme#LOGO_DOWNSCALE} so both axes scale by exactly {@code 1/LOGO_DOWNSCALE}; a width that did
     * not divide evenly would round X differently from Y. The centring arithmetic trims leftover margin evenly.
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
     * Centred fitted caption for a button. Fit width is the DRAWN width less {@link GuiTheme#BUTTON_TEXT_INSET}
     * per side (not the generic {@link GuiTheme#TEXT_PADDING_X}): the angled end cap cuts the top corner in by
     * ~11px, so a caption at the full text-padding inner rode onto the slant (the "Copy still overflows"
     * defect). {@link GuiText#drawFittedCentered} shrinks then ellipsizes against this width.
     */
    public static void buttonLabel(GuiGraphics g, Font font, String text, int x, int y, int w, int h, int color) {
        int innerW = Math.max(1, w - 2 * GuiTheme.BUTTON_TEXT_INSET);
        GuiText.drawFittedCentered(g, font, text, x + w / 2, y, innerW, h, color);
    }

    /**
     * Centred fitted caption for an ICON button (round-spliced X/arrow/equals). The round splice has no angled
     * slant to clear, so its single glyph uses only the generic {@link GuiTheme#TEXT_PADDING_X}, not the larger
     * {@link GuiTheme#BUTTON_TEXT_INSET}: on a 14-18px pill that inset would leave almost no room.
     */
    public static void iconButtonLabel(GuiGraphics g, Font font, String text, int x, int y, int w, int h, int color) {
        GuiText.drawFittedCentered(g, font, text, x + w / 2, y, GuiText.innerWidth(w), h, color);
    }
}
