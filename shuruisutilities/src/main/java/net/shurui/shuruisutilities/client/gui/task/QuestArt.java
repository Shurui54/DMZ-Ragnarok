package net.shurui.shuruisutilities.client.gui.task;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * The commissioned quest banner: a navy bar with a gold underline and a dragon-ball-and-exclamation badge on its
 * left end, tapering to a point on the right. Used for DMZ's quest notifications and the quest tracker so they
 * stop being flat coloured rectangles.
 *
 * <h2>The art is its own file at its own size, and is drawn 1:1</h2>
 *
 * The banner used to be a region cut out of a 512x512 sheet at (165, 164), which is where the cut-off, mangled
 * render came from. The art is now a standalone {@value #TEX_W}x{@value #TEX_H} image, so there is no sub-region
 * to get wrong, and {@link #banner(GuiGraphics, int, int)} draws it at exactly its authored size. Drawing 1:1 is
 * the whole point: every stretching bug in this element came from fitting the art into a box that was not its
 * shape, so the default path now cannot distort it at all.
 *
 * <h2>Why stretching is a LAST resort here</h2>
 *
 * This art is not a simple cap-plus-uniform-bar. Measured column by column, the only run of identical columns is
 * {@code x=264..351}, 88 pixels near the right end; the badge, the body detail and the right taper are all
 * pictures. So {@link #bannerStretched} slices around THAT measured window rather than assuming everything past
 * the badge repeats, which is what the old two-piece split assumed and got wrong. Re-measure this window if the
 * art is redrawn; do not guess it.
 *
 * <p>Client-only.</p>
 */
public final class QuestArt
{
    private QuestArt() {}

    /** Namespace is the ORIGINAL owning addon's; the art ships under sdu's tree. */
    public static final ResourceLocation BANNER =
            new ResourceLocation("dmz_ragnarok", "textures/gui/task/quest_info.png");

    /** The image's real pixel size. The whole file is the banner, so these are also its draw size. */
    private static final int TEX_W = 354;
    private static final int TEX_H = 58;

    /** The banner's authored size, for callers sizing a box to it 1:1. */
    public static final int SHEET_W = TEX_W;
    public static final int SHEET_H = TEX_H;

    /**
     * Where the badge ends and text may begin, measured from the art (the first column that repeats the one
     * before it). Text must start right of this or it is drawn over the dragon ball.
     */
    public static final int CAP_W = 96;

    /**
     * The only horizontal window that is safe to stretch, measured column by column on the real image. Outside it
     * the art is picture (badge, body detail, and the taper at the right end), which smears if stretched.
     */
    private static final int STRETCH_U = 264;
    private static final int STRETCH_W = 88;

    /** Draw the banner at its authored size, which is the only path that cannot distort it. */
    public static void banner(GuiGraphics g, int x, int y)
    {
        banner(g, x, y, 1.0F);
    }

    /**
     * Draw the banner at a UNIFORM scale: both axes multiply by the same factor, so the aspect ratio is preserved
     * and nothing smears. This is completely different from {@link #bannerStretched}, which changes the aspect on
     * purpose and so may only touch the one measured uniform column window. A uniform scale is safe on the whole
     * image because every pixel keeps its neighbours' proportions; that is why the badge, the body detail and the
     * right taper all survive it. The FONT is never scaled with this: callers keep text at normal size and lay it
     * out against the scaled box using {@link #scaledCapW}.
     *
     * @param scale multiplier on both width and height. 1.0 is the authored size; a value at or below 0 draws nothing.
     */
    public static void banner(GuiGraphics g, int x, int y, float scale)
    {
        if (scale <= 0.0F)
            return;
        int w = Math.round(TEX_W * scale);
        int h = Math.round(TEX_H * scale);
        g.blit(BANNER, x, y, w, h, 0.0F, 0.0F, TEX_W, TEX_H, TEX_W, TEX_H);
    }

    /** The banner's drawn width at a given uniform scale, so a caller sizes its box without redoing the multiply. */
    public static int scaledW(float scale)
    {
        return Math.round(TEX_W * scale);
    }

    /** The banner's drawn height at a given uniform scale. */
    public static int scaledH(float scale)
    {
        return Math.round(TEX_H * scale);
    }

    /** Where the badge ends at a given uniform scale, so text starts right of the scaled dragon ball, not the 1:1 one. */
    public static int scaledCapW(float scale)
    {
        return Math.round(CAP_W * scale);
    }

    /**
     * Draw the banner at a WIDER width than it was authored at, stretching only the measured uniform window so
     * the badge and the right taper keep their shape. Height is always the authored height: this art has detail
     * on both ends, so scaling it vertically would smear them however the width is sliced.
     *
     * <p>A width at or below the authored width falls back to the plain 1:1 draw rather than squeezing, because
     * squeezing is what produced the mangled banner this class was rewritten to fix. Prefer {@link #banner} and
     * only reach for this when a caller genuinely needs a longer bar.</p>
     */
    public static void bannerStretched(GuiGraphics g, int x, int y, int w)
    {
        if (w <= TEX_W)
        {
            banner(g, x, y);
            return;
        }
        int left = STRETCH_U;                       // badge + body detail, drawn as-is
        int right = TEX_W - (STRETCH_U + STRETCH_W); // the taper, drawn as-is
        int middle = w - left - right;               // everything the extra width goes into
        g.blit(BANNER, x, y, left, TEX_H, 0.0F, 0.0F, left, TEX_H, TEX_W, TEX_H);
        g.blit(BANNER, x + left, y, middle, TEX_H,
                (float) STRETCH_U, 0.0F, STRETCH_W, TEX_H, TEX_W, TEX_H);
        g.blit(BANNER, x + left + middle, y, right, TEX_H,
                (float) (STRETCH_U + STRETCH_W), 0.0F, right, TEX_H, TEX_W, TEX_H);
    }
}
