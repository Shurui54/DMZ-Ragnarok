package net.shurui.dev.shuruis_raid_bosses.client.gui.theme;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nine-slice / three-slice blit helpers for the theme.
 *
 * <p><b>Source pixels and destination pixels are two different things here, and every method takes both.</b>
 * The art is drawn at a working size (a 351px panel, a 264x38 button, a 23px scrollbar) while a screen asks
 * for a panel ~300 virtual px wide and a button ~22 tall. An earlier pass squashed the ART down to the old
 * sprite sizes, throwing away two thirds of every piece before drawing (why the menus read blurry). The art
 * is now stored as drawn and the SIZE it appears at is decided here:
 *
 * <ul>
 *   <li>{@code dstCap}: VIRTUAL px a corner/end cap occupies on screen. Layout, unchanged from the old
 *       sprites, so re-slicing moves nothing;</li>
 *   <li>{@code srcCap}: SOURCE px that same cap is in the art.</li>
 * </ul>
 *
 * <p>A cap samples all its source detail into its destination rect. At gui scale 3 a 6 virtual px cap is ~18
 * real px, so a 17px source corner lands near 1:1. Nothing here resamples the PNG.
 *
 * <p>Interiors are STRETCHED, not tiled: every interior is uniform along the axis it grows on (panel body a
 * vertical gradient with flat rows, button interior identical columns, dropdown/scrollbar bodies flat), so
 * stretching is identity with no seam. The one exception is the scrollbar THUMB, whose body is a run of ribs:
 * {@link #drawScrollPiece} tiles a single rib pitch so a tall thumb grows more ribs, not longer ones.
 *
 * <p>When a destination is smaller than its two caps, caps are clamped so they never overlap, the source caps
 * pulled in by the same proportion, and a one-time warning naming the element is logged.
 *
 * <p>Client-only.
 */
public final class NineSlice {

    private static final Logger LOGGER = LoggerFactory.getLogger("shuruis_raid_bosses-gui-theme");

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
     * Nine-slice with SEPARATE horizontal and vertical caps: corners cap both axes, edges cap one and stretch
     * the other, centre stretches both. Separate caps let a caller give a wide horizontal cap (a button's end
     * orb) and a thin vertical one.
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
     * Nine-slice for the panel background. Same geometry as {@link #drawTiled}, kept as its own name so
     * {@code ThemeRender.panel} lands somewhere that says the cap is the corner dragonball stud plus its rails.
     */
    public static void draw(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                            int srcX, int srcY, int srcW, int srcH, int sheetW, int sheetH,
                            int dstInset, int srcInset) {
        drawTiled(g, tex, x, y, w, h, srcX, srcY, srcW, srcH, sheetW, sheetH, dstInset, srcInset, "panel");
    }

    /**
     * Button drawer: SPLICE horizontally, STRETCH vertically. Each authored end cap (gold ring round a
     * dragonball boss) draws from its OWN source columns, so neither end is a stretched copy of the other; the
     * flat interior between them is one stretched blit (exact, identical columns). Y stretches including the
     * frame rows, since the art is a top-to-bottom bevel drawn at 38px.
     *
     * <p>The cap WIDTH is not passed in: it is derived from the vertical squash so the cap scales by the same
     * factor on both axes and the end orb stays circular. A fixed cap could not (a button is laid out at
     * whatever height its screen gives): the old constant 12 was right at ~24px tall but half again too wide at
     * the theme's 14px button (the "buttons look squished vertically" report).
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
     * ROUND button drawer, ONLY for the small icon buttons (row delete "X", up/down arrows). Same art as
     * {@link #drawButton} so it reads as the same family, but the RIGHT cap is a horizontal MIRROR of the LEFT
     * so both ends match. The mirror is done one destination column at a time (a source remap, not a second
     * scaling pass). Pass the narrow icon cap, not the wide standard one, or a small square button is nothing
     * but two caps meeting.
     */
    public static void drawButtonRounded(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                                         int srcX, int srcY, int srcW, int srcH, int sheetW, int sheetH,
                                         int srcCapW, String name) {
        Cap c = cap(uniformCap(srcCapW, h, srcH), srcCapW, w, srcW, name, "x");
        int midW = w - 2 * c.dst();
        int srcMidW = srcW - 2 * c.src();
        // Left cap: the art's own rounded end.
        blit(g, tex, x, y, c.dst(), h, srcX, srcY, c.src(), srcH, sheetW, sheetH);
        // Right cap: mirror of the left. Destination column j takes the slice j in from the source cap's RIGHT edge.
        int sliceW = Math.max(1, c.src() / Math.max(1, c.dst()));
        for (int j = 0; j < c.dst(); j++) {
            int su = srcX + c.src() - (j + 1) * c.src() / Math.max(1, c.dst());
            blit(g, tex, x + w - c.dst() + j, y, 1, h, Math.max(srcX, su), srcY, sliceW, srcH, sheetW, sheetH);
        }
        // Interior between the two caps.
        blit(g, tex, x + c.dst(), y, midW, h, srcX + c.src(), srcY, srcMidW, srcH, sheetW, sheetH);
    }

    /**
     * Scrollbar piece drawer: an end cap top and bottom with a body between. Used for track and thumb;
     * {@code dstBodyStep} chooses the body treatment:
     * <ul>
     *   <li><b>0 or less: STRETCH.</b> The TRACK body is flat, so stretching is identity, no seam.</li>
     *   <li><b>positive: TILE.</b> The THUMB body is a run of ribs: one rib pitch ({@code srcBodyH} source rows)
     *       repeats every {@code dstBodyStep} dst px, so a tall thumb grows more ribs. A partial rib at the end
     *       samples the matching fraction of the pitch, cut off cleanly not squashed.</li>
     * </ul>
     *
     * <p>Shorter than two caps: only the caps are drawn (halved), never a stretched body.
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
     * Destination cap width that scales a cap by the SAME factor its element is scaled vertically. The end cap
     * is the only part of a button with a shape of its own (the ring around the boss), so the only part that
     * can go out of proportion; deriving width from the height ratio keeps the boss round at any button size.
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
     * Resolve a cap against both rectangles. The destination cap is clamped so two caps fit, and the source cap
     * is pulled in by the SAME proportion so a squeezed element shrinks evenly. The source cap is also clamped
     * to half the source rect so a caller cannot ask for more art than exists. A forced clamp is logged once
     * against the element name.
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
