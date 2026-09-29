package net.shurui.dev.shuruis_raid_bosses.client.gui.theme;


import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Text drawing that always fits its element, so text never crosses a border (an explicit requirement, and
 * where Spanish, ~20% longer than English, overflows). Per draw:
 *
 * <ol>
 *   <li>Fits at 1.0 scale: draw plainly.</li>
 *   <li>Otherwise shrink via a scaled pose stack down to {@link GuiTheme#MIN_TEXT_SCALE}.</li>
 *   <li>Still overflows at min scale: ellipsize (trim + "...").</li>
 * </ol>
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
     * Y to pass {@link GuiGraphics#drawString} so the line is OPTICALLY centred in an element of the given
     * height. drawString puts the glyph cell's top at {@code y} and {@link Font#lineHeight} is 9; centring the
     * whole 9px cell centres the reserved box, not the ink (capitals ink only rows 0..6, so a descenderless
     * caption sits high). Adds {@link GuiTheme#TEXT_OPTICAL_CENTER_NUDGE} (1px) to centre the visible cap band.
     */
    public static int centeredTextY(Font font, int elementY, int elementHeight) {
        return elementY + (elementHeight - font.lineHeight) / 2 + GuiTheme.TEXT_OPTICAL_CENTER_NUDGE;
    }

    /** Left-aligned text fitted into {@code [x + padding, x + w - padding]}, vertically centred in {@code h}.
     *  Shrinks then ellipsizes. */
    public static void drawFitted(GuiGraphics g, Font font, String text, int x, int y, int w, int h, int color) {
        int inner = innerWidth(w);
        int tx = x + GuiTheme.TEXT_PADDING_X;
        int ty = centeredTextY(font, y, h);
        drawScaledInto(g, font, text, tx, ty, inner, color, false);
    }

    /** Text centred on {@code cx}, vertically centred in {@code h}, fitted into {@code innerW} (already the
     *  inner width). Button captions and tab labels. */
    public static void drawFittedCentered(GuiGraphics g, Font font, String text, int cx, int y, int innerW,
                                          int h, int color) {
        int ty = centeredTextY(font, y, h);
        drawScaledInto(g, font, text, cx, ty, innerW, color, true);
    }

    /** Core routine. Scaling happens around the anchor so it stays centred. {@code anchorX} is the centre when
     *  {@code centered}, else the left edge. */
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
        // Scale around the anchor so the position matches the unscaled case.
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
