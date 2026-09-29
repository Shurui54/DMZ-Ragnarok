package net.shurui.shuruisutilities.client.gui.theme;


import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Text drawing that always fits its element. Every label, button caption and field value in the theme goes
 * through here so text never crosses a border, which is an explicit requirement (and where Spanish, ~20%
 * longer than English, overflows). The strategy per draw:
 *
 * <ol>
 *   <li>Measure the string. If it fits the element's inner width at 1.0 scale, draw it plainly.</li>
 *   <li>Otherwise shrink it via a scaled pose stack down to {@link GuiTheme#MIN_TEXT_SCALE}.</li>
 *   <li>If it still overflows at the minimum scale, ellipsize (trim + "...") so it stops at the border.</li>
 * </ol>
 *
 * <p>Vertical centring uses the font's real line height ({@link Font#lineHeight}) rather than a magic
 * number, and the inner width already has {@link GuiTheme#TEXT_PADDING_X} removed from both sides, so text
 * keeps a consistent gap from the element edge.
 *
 * <p>Client-only.
 */
public final class GuiText {

    private GuiText() {
    }

    /** Inner width available for text inside an element of the given outer width (padding both sides). */
    public static int innerWidth(int elementWidth) {
        return Math.max(1, elementWidth - 2 * GuiTheme.TEXT_PADDING_X);
    }

    /**
     * The Y at which the TOP of a glyph row should be passed to {@link GuiGraphics#drawString} so the line is
     * OPTICALLY centred in an element of the given height.
     *
     * <p>{@link GuiGraphics#drawString} places the glyph CELL's top at {@code y}; the cell's raster rows map to
     * {@code y+0 .. y+7} and {@link Font#lineHeight} is 9 ({@code y+0 .. y+8}). Centring that whole 9px cell,
     * {@code (elementHeight - lineHeight) / 2}, centres the RESERVED line box, not the visible ink: capitals ink
     * only rows 0..6 of the cell (measured from {@code ascii.png}), so a caption with no descender ("Edit",
     * "Menu", "Save") has 2 empty reserved rows below it (the descender-space at cell rows 7..8) and its ink is
     * pushed visibly HIGH. That is the "text sits too high" defect. This method therefore adds
     * {@link GuiTheme#TEXT_OPTICAL_CENTER_NUDGE} (1px, half the empty descender-reserve) so the visible cap band
     * is what ends up centred, while a real descender still fits inside the element.
     *
     * <p>Worked example, 14px button (standard {@link GuiTheme#BUTTON_HEIGHT}), vanilla font:
     * {@code y = elementY + (14 - 9) / 2 + 1 = elementY + 3}. The capital band (cell rows 0..6) then occupies
     * element rows {@code 3..9}: gap ABOVE = 3px (rows 0,1,2), gap BELOW = 4px (rows 10,11,12,13) - within one
     * pixel of each other, i.e. optically centred for a no-descender caption. A descender (cell rows 2..7) bottoms
     * out at element row {@code 3+7 = 10}, a clear 3px above the button's bottom edge (row 13): no clipping. For
     * the 11px field/dropdown row {@code y = elementY + (11-9)/2 + 1 = elementY + 2}: caps at rows 2..8 (gap 2
     * above, 2 below, perfectly centred), descender bottom at row 9, 1px clear. The nudge holds balanced-within-1px
     * with descenders fitting at every height the suite uses (11/14/15/16/18).
     */
    public static int centeredTextY(Font font, int elementY, int elementHeight) {
        return elementY + (elementHeight - font.lineHeight) / 2 + GuiTheme.TEXT_OPTICAL_CENTER_NUDGE;
    }

    /**
     * Draw left-aligned text fitted into {@code [x + padding, x + w - padding]}, vertically centred in the
     * element's {@code h}. Shrinks then ellipsizes as described in the class docs.
     */
    public static void drawFitted(GuiGraphics g, Font font, String text, int x, int y, int w, int h, int color) {
        int inner = innerWidth(w);
        int tx = x + GuiTheme.TEXT_PADDING_X;
        int ty = centeredTextY(font, y, h);
        drawScaledInto(g, font, text, tx, ty, inner, color, false);
    }

    /**
     * Draw text horizontally centred on {@code cx}, vertically centred in the element's {@code h}, fitted
     * into {@code innerW} (already the element's inner width). Used for button captions and tab labels.
     */
    public static void drawFittedCentered(GuiGraphics g, Font font, String text, int cx, int y, int innerW,
                                          int h, int color) {
        int ty = centeredTextY(font, y, h);
        drawScaledInto(g, font, text, cx, ty, innerW, color, true);
    }

    /**
     * Core routine. {@code baselineY} is the top of the glyph row; scaling happens around the text's own
     * anchor so it stays vertically centred. When {@code centered}, {@code anchorX} is the centre, else it
     * is the left edge.
     */
    private static void drawScaledInto(GuiGraphics g, Font font, String text, int anchorX, int baselineY,
                                       int innerW, int color, boolean centered) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int width = font.width(text);
        if (width <= innerW) {
            if (centered) {
                g.drawString(font, text, anchorX - width / 2, baselineY, color, false);
            } else {
                g.drawString(font, text, anchorX, baselineY, color, false);
            }
            return;
        }

        // Would shrinking to the minimum readable scale make it fit?
        float fitScale = innerW / (float) width;
        if (fitScale >= GuiTheme.MIN_TEXT_SCALE) {
            drawAtScale(g, font, text, anchorX, baselineY, color, fitScale, width, centered);
            return;
        }

        // Even at min scale it overflows: ellipsize the string to fit innerW at MIN_TEXT_SCALE.
        float scale = GuiTheme.MIN_TEXT_SCALE;
        int budget = Math.round(innerW / scale);
        String clipped = ellipsize(font, text, budget);
        int clippedW = font.width(clipped);
        drawAtScale(g, font, clipped, anchorX, baselineY, color, scale, clippedW, centered);
    }

    private static void drawAtScale(GuiGraphics g, Font font, String text, int anchorX, int baselineY,
                                    int color, float scale, int textWidth, boolean centered) {
        PoseStack pose = g.pose();
        pose.pushPose();
        // Scale around the anchor so the visual position matches the unscaled case, keeping vertical centring.
        float drawX = centered ? anchorX - textWidth * scale / 2f : anchorX;
        // Nudge the baseline so the shrunk line stays centred on the same row it would occupy at 1.0.
        float drawY = baselineY + font.lineHeight * (1f - scale) / 2f;
        pose.translate(drawX, drawY, 0);
        pose.scale(scale, scale, 1f);
        g.drawString(font, text, 0, 0, color, false);
        pose.popPose();
    }

    /** Trim {@code text} and append "..." so its rendered width is at most {@code budget} px. */
    public static String ellipsize(Font font, String text, int budget) {
        String dots = "...";
        if (font.width(text) <= budget) {
            return text;
        }
        int dotsW = font.width(dots);
        if (budget <= dotsW) {
            return dots;
        }
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end)) + dotsW > budget) {
            end--;
        }
        return text.substring(0, end) + dots;
    }
}
