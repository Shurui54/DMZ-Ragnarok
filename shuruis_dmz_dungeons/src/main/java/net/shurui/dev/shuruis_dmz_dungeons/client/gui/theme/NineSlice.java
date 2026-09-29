package net.shurui.dev.shuruis_dmz_dungeons.client.gui.theme;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nine-slice / three-slice blit helpers for the theme.
 *
 * <p><b>Source pixels and destination pixels are two different things here, and every method takes both.</b> The
 * commissioned art is drawn large (a 351px panel, a 264x38 button, a 23px scrollbar) while a screen asks for a
 * panel ~300 virtual px wide and a button ~22 tall. An earlier pass squashed the ART down to the old sprite
 * sizes, throwing away ~two thirds of every piece before it was drawn, which is why the menus read as blurry.
 * The art is now stored as drawn and the size it appears at is decided here:
 *
 * <ul>
 *   <li>{@code dstCap}: how many VIRTUAL px a corner/end cap occupies on screen. This is layout, unchanged from
 *       the old sprites, so re-slicing moves nothing;</li>
 *   <li>{@code srcCap}: how many SOURCE px that same cap is in the art.</li>
 * </ul>
 *
 * <p>Because Minecraft renders the GUI at the player's gui scale (commonly 3), a 6-virtual-px cap is ~18 real px,
 * so a 17px source corner lands at close to one source pixel per screen pixel. Nothing here resamples the PNG.
 *
 * <p>Interiors are STRETCHED, not tiled: every interior in this art set is uniform along the axis it grows on
 * (the panel body a vertical gradient with flat rows, the button interior a gradient with identical columns, the
 * dropdown and scrollbar bodies flat), so a stretch is identity with no tile seam. The one exception is the
 * scrollbar THUMB, whose body is a run of evenly spaced ribs: {@link #drawScrollPiece} tiles a single rib pitch.
 *
 * <p>When a destination is smaller than its two caps, the caps are clamped so they never overlap or invert, and
 * the source caps are pulled in by the same proportion. A one-time warning names the element so a screen that
 * squeezes something below its splice minimum is reported rather than silently distorted.
 *
 * <p>Client-only.
 */
public final class NineSlice {

    private static final Logger LOGGER = LoggerFactory.getLogger("shuruis_dmz_dungeons-gui-theme");

    private NineSlice() {
    }

    /** A cap resolved against both rectangles: {@code dst} virtual px on screen sampling {@code src} source px. */
    private record Cap(int dst, int src) {
    }

    /** Convenience: a symmetric nine-slice with the same cap on both axes. */
    public static void drawTiled(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                                 int srcX, int srcY, int srcW, int srcH, int sheetW, int sheetH,
                                 int dstCap, int srcCap, String name) {
        drawTiled(g, tex, x, y, w, h, srcX, srcY, srcW, srcH, sheetW, sheetH,
                dstCap, dstCap, srcCap, srcCap, name);
    }

    /**
     * Nine-slice with SEPARATE horizontal and vertical caps. Corners take their cap on both axes; edges take one
     * axis and stretch the other; the centre stretches both. Lets a caller give a wide horizontal cap (to contain
     * a button's end orb) and a thin vertical one.
     */
    public static void drawTiled(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                                 int srcX, int srcY, int srcW, int srcH, int sheetW, int sheetH,
                                 int dstCapX, int dstCapY, int srcCapX, int srcCapY, String name) {
        Cap cx = cap(dstCapX, srcCapX, w, srcW, name, "x");
        Cap cy = cap(dstCapY, srcCapY, h, srcH, name, "y");

        int midW = w - 2 * cx.dst();               // destination centre width
        int midH = h - 2 * cy.dst();               // destination centre height
        int srcMidW = srcW - 2 * cx.src();         // source centre width
        int srcMidH = srcH - 2 * cy.src();         // source centre height

        int sxL = srcX;
        int sxM = srcX + cx.src();
        int sxR = srcX + srcW - cx.src();
        int syT = srcY;
        int syM = srcY + cy.src();
        int syB = srcY + srcH - cy.src();

        int xL = x;
        int xM = x + cx.dst();
        int xR = x + w - cx.dst();
        int yT = y;
        int yM = y + cy.dst();
        int yB = y + h - cy.dst();

        // corners
        blit(g, tex, xL, yT, cx.dst(), cy.dst(), sxL, syT, cx.src(), cy.src(), sheetW, sheetH);
        blit(g, tex, xR, yT, cx.dst(), cy.dst(), sxR, syT, cx.src(), cy.src(), sheetW, sheetH);
        blit(g, tex, xL, yB, cx.dst(), cy.dst(), sxL, syB, cx.src(), cy.src(), sheetW, sheetH);
        blit(g, tex, xR, yB, cx.dst(), cy.dst(), sxR, syB, cx.src(), cy.src(), sheetW, sheetH);
        // top and bottom edges: cap height, stretched to width
        blit(g, tex, xM, yT, midW, cy.dst(), sxM, syT, srcMidW, cy.src(), sheetW, sheetH);
        blit(g, tex, xM, yB, midW, cy.dst(), sxM, syB, srcMidW, cy.src(), sheetW, sheetH);
        // left and right edges: cap width, stretched to height
        blit(g, tex, xL, yM, cx.dst(), midH, sxL, syM, cx.src(), srcMidH, sheetW, sheetH);
        blit(g, tex, xR, yM, cx.dst(), midH, sxR, syM, cx.src(), srcMidH, sheetW, sheetH);
        // centre
        blit(g, tex, xM, yM, midW, midH, sxM, syM, srcMidW, srcMidH, sheetW, sheetH);
    }

    /**
     * Nine-slice for the panel background. Identical geometry to {@link #drawTiled}; kept as its own name because
     * the panel's cap is the corner dragonball stud plus its rails, and {@code ThemeRender.panel} should land
     * somewhere that says so.
     */
    public static void draw(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                            int srcX, int srcY, int srcW, int srcH, int sheetW, int sheetH,
                            int dstInset, int srcInset) {
        drawTiled(g, tex, x, y, w, h, srcX, srcY, srcW, srcH, sheetW, sheetH, dstInset, srcInset, "panel");
    }

    /**
     * Button drawer: SPLICE horizontally, STRETCH vertically.
     *
     * <p>The button art has an authored end cap each side (a gold ring around a dragonball boss). Each cap draws
     * from its OWN source columns into its own destination columns, so neither end is a stretched copy of the
     * other. The flat interior between them is one stretched blit, exact because that interior is a vertical
     * gradient with identical columns.
     *
     * <p>The whole button stretches on Y, frame rows included, because the art is a top-to-bottom bevel.
     *
     * <p>The cap's WIDTH on screen is derived from how far the button is squashed vertically, so it scales by the
     * same factor on both axes and the orb stays circular. A fixed destination cap could not: the previous constant
     * 12 was right at ~24px tall but made the orb half again too wide at the theme's 14px button (the "buttons look
     * squished vertically" report).
     */
    public static void drawButton(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                                  int srcX, int srcY, int srcW, int srcH, int sheetW, int sheetH,
                                  int srcCapW, String name) {
        Cap c = cap(uniformCap(srcCapW, h, srcH), srcCapW, w, srcW, name, "x");
        int midW = w - 2 * c.dst();
        int srcMidW = srcW - 2 * c.src();
        blit(g, tex, x, y, c.dst(), h, srcX, srcY, c.src(), srcH, sheetW, sheetH);
        blit(g, tex, x + w - c.dst(), y, c.dst(), h,
                srcX + srcW - c.src(), srcY, c.src(), srcH, sheetW, sheetH);
        blit(g, tex, x + c.dst(), y, midW, h, srcX + c.src(), srcY, srcMidW, srcH, sheetW, sheetH);
    }

    /**
     * ROUND button drawer, used ONLY for small icon buttons (the row delete "X" and up/down arrows). Draws the
     * SAME art as {@link #drawButton} so an icon button reads as the same family as the Edit/Copy buttons beside
     * it, but builds the RIGHT cap as a horizontal MIRROR of the LEFT cap so both ends match.
     *
     * <p>The mirror is done one destination column at a time, each sampling the matching source column from the
     * far side of the cap, so it is a source remap, never a second scaling pass. {@code dstCapW} should be the
     * narrow icon cap, or a small square button would be nothing but two caps meeting.
     */
    public static void drawButtonRounded(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                                         int srcX, int srcY, int srcW, int srcH, int sheetW, int sheetH,
                                         int srcCapW, String name) {
        Cap c = cap(uniformCap(srcCapW, h, srcH), srcCapW, w, srcW, name, "x");
        int midW = w - 2 * c.dst();
        int srcMidW = srcW - 2 * c.src();
        // Left cap: the art's own rounded end.
        blit(g, tex, x, y, c.dst(), h, srcX, srcY, c.src(), srcH, sheetW, sheetH);
        // Right cap: the left cap's columns, mirrored. Destination column j (counted rightwards) takes the slice
        // of the source cap that is j slices in from its RIGHT edge, so the cap reads back to front.
        int sliceW = Math.max(1, c.src() / Math.max(1, c.dst()));
        for (int j = 0; j < c.dst(); j++) {
            int su = srcX + c.src() - (j + 1) * c.src() / Math.max(1, c.dst());
            blit(g, tex, x + w - c.dst() + j, y, 1, h, Math.max(srcX, su), srcY, sliceW, srcH, sheetW, sheetH);
        }
        // Interior between the two caps.
        blit(g, tex, x + c.dst(), y, midW, h, srcX + c.src(), srcY, srcMidW, srcH, sheetW, sheetH);
    }

    /**
     * Scrollbar piece drawer: an end cap top and bottom with a body between. Used for both track and thumb, which
     * differ only in their body. {@code dstBodyStep} chooses the treatment:
     * <ul>
     *   <li><b>0 or less: STRETCH.</b> For the TRACK, whose body is a single flat colour, so stretching is identity.</li>
     *   <li><b>positive: TILE.</b> For the THUMB, whose body is evenly spaced ribs. One rib pitch ({@code srcBodyH}
     *       source rows) repeats every {@code dstBodyStep} destination px, so a tall thumb grows MORE ribs, not
     *       longer ones. A partial rib at the end samples the matching fraction of the pitch, cut off cleanly.</li>
     * </ul>
     *
     * <p>When the destination is shorter than two caps, only the caps are drawn (halved), never a stretched body.
     */
    public static void drawScrollPiece(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                                       int srcX, int srcY, int srcW, int srcH, int sheetW, int sheetH,
                                       int dstCap, int srcCap, int srcBodyY, int srcBodyH, int dstBodyStep) {
        Cap c = cap(dstCap, srcCap, h, srcH, null, "y");
        if (h <= 2 * c.dst() || c.dst() <= 0) {
            int d = Math.max(0, Math.min(c.dst(), h / 2));
            if (d > 0) {
                blit(g, tex, x, y, w, d, srcX, srcY, srcW, c.src(), sheetW, sheetH);
                blit(g, tex, x, y + h - d, w, d, srcX, srcY + srcH - c.src(), srcW, c.src(), sheetW, sheetH);
            }
            return;
        }
        int midH = h - 2 * c.dst();
        blit(g, tex, x, y, w, c.dst(), srcX, srcY, srcW, c.src(), sheetW, sheetH);
        if (dstBodyStep <= 0 || srcBodyH <= 0) {
            blit(g, tex, x, y + c.dst(), w, midH,
                    srcX, srcY + c.src(), srcW, srcH - 2 * c.src(), sheetW, sheetH);
        } else {
            int drawn = 0;
            while (drawn < midH) {
                int step = Math.min(dstBodyStep, midH - drawn);
                int srcStep = Math.max(1, srcBodyH * step / dstBodyStep);
                blit(g, tex, x, y + c.dst() + drawn, w, step, srcX, srcBodyY, srcW, srcStep, sheetW, sheetH);
                drawn += step;
            }
        }
        blit(g, tex, x, y + h - c.dst(), w, c.dst(),
                srcX, srcY + srcH - c.src(), srcW, c.src(), sheetW, sheetH);
    }

    /** Draw a whole sprite region at the given size (used for fixed-size art like the 36x36 slot). */
    public static void drawFixed(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                                 int srcX, int srcY, int srcW, int srcH, int sheetW, int sheetH) {
        blit(g, tex, x, y, w, h, srcX, srcY, srcW, srcH, sheetW, sheetH);
    }

    /**
     * The destination cap width that scales a cap by the SAME factor its element is scaled vertically. An end cap
     * is the only part of a button with a shape of its own (a ring around a dragonball boss), so the only part
     * that can be caught out of proportion; interiors are uniform along the axis they stretch on. Deriving width
     * from the height ratio keeps the boss round at any button size.
     */
    private static int uniformCap(int srcCap, int dstH, int srcH) {
        if (srcH <= 0) {
            return srcCap;
        }
        return Math.max(1, Math.round(srcCap * (float) dstH / srcH));
    }

    /** True once per (element, axis) that a target was smaller than its two caps, to log the clamp only once. */
    private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Resolve a cap against both rectangles. The destination cap is clamped so two caps always fit, and the source
     * cap is pulled in by the SAME proportion so a squeezed element shrinks evenly instead of crushing a full-size
     * cap into half the room. The source cap is separately clamped to half the source rect. When the destination
     * forced a clamp, it is logged once against the element name.
     */
    private static Cap cap(int dstCap, int srcCap, int dst, int src, String name, String axis) {
        int wantDst = Math.max(0, dstCap);
        int wantSrc = Math.max(0, Math.min(srcCap, src / 2));
        int okDst = Math.max(0, Math.min(wantDst, dst / 2));
        if (okDst < wantDst && name != null) {
            String key = name + "#" + axis;
            if (WARNED.add(key)) {
                LOGGER.warn("[gui-theme] {} drawn {}px on {}-axis, below its {}px splice minimum "
                        + "(2x{}px cap); caps clamped to avoid distortion.", name, dst, axis, 2 * wantDst, wantDst);
            }
        }
        int okSrc = wantDst == 0 ? 0 : Math.max(okDst == 0 ? 0 : 1, wantSrc * okDst / wantDst);
        return new Cap(okDst, Math.min(okSrc, src / 2));
    }

    private static void blit(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                             int u, int v, int uw, int vh, int sheetW, int sheetH) {
        if (w <= 0 || h <= 0 || uw <= 0 || vh <= 0) {
            return;
        }
        g.blit(tex, x, y, w, h, (float) u, (float) v, uw, vh, sheetW, sheetH);
    }
}
