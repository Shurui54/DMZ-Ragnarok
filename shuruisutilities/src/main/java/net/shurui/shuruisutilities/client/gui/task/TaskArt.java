package net.shurui.shuruisutilities.client.gui.task;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * The commissioned task-board art, and the only place its atlas coordinates are written down.
 *
 * <p>Two sheets, both 512x512 and both authored with a lot of empty space around the pieces, so every sprite
 * below is a measured bounding box rather than a tidy grid cell. Do not "round" these to nicer numbers: the
 * gaps between pieces are not uniform (the three coloured buttons alone start at y=11, y=12 and y=12), and a
 * one-pixel drift shows up as a sliver of the neighbouring sprite.</p>
 *
 * <p>The panel is drawn as ONE stretched blit rather than nine-sliced, which is the opposite of what
 * {@code ThemeRender.panel} does for the ordinary screens. That is deliberate: this panel's four corners carry
 * dragon balls that overhang the border, so there is no straight edge to repeat and no corner that can be
 * drawn 1:1 without the ball landing in the wrong place. Because the whole sheet scales together, the board
 * screen is sized at the ART's aspect ratio (421:415) so the balls stay round.</p>
 *
 * <p>Client-only.</p>
 */
public final class TaskArt
{
    private TaskArt() {}

    /** Namespace is the ORIGINAL owning addon's, not dmz_ragnarok's mod id. The art ships under sdu's tree. */
    public static final ResourceLocation PANEL =
            new ResourceLocation("dmz_ragnarok", "textures/gui/task/panel.png");
    public static final ResourceLocation WIDGETS =
            new ResourceLocation("dmz_ragnarok", "textures/gui/task/widgets.png");

    /** Both sheets are 512x512. */
    private static final int TEX = 512;

    /** The panel, dragon-ball corners included. Everything else on the board is positioned relative to this. */
    private static final int PANEL_U = 45;
    private static final int PANEL_V = 48;
    public static final int PANEL_W = 421;
    public static final int PANEL_H = 415;

    /**
     * The board tab. Its OWN texture, not a region of {@code widgets.png}, and that is the whole point.
     *
     * <p>{@code widgets.png} does carry a red plaque at (9,6)/(9,39), and it is NOT this control. That one has a
     * thick flat yellow border and four tiny orange dots wedged inside its corners. The tab the commission
     * actually specifies, the one in {@code Templatepng.png}, has a thin gold border with a red outline and four
     * full dragon balls that PROTRUDE past the plaque edge, stars and all. They look alike at a glance and are
     * completely different up close, which is how the wrong one survived several rounds of "the tabs have no
     * dragon balls": the sprite was being drawn perfectly, it was simply the wrong sprite.
     *
     * <p>Extracted from the reference at its authored 81x27 and drawn 1:1. Do not scale it: the balls are small
     * and nearest-neighbour sampling drops whole rows when you do.</p>
     */
    public static final ResourceLocation TAB =
            new ResourceLocation("dmz_ragnarok", "textures/gui/task/tab.png");
    public static final int TAB_W = 81;
    public static final int TAB_H = 27;

    /** The three action pills. Yellow sits one pixel higher on the sheet than the other two. */
    private static final int BTN_SW = 55;
    private static final int BTN_SH = 24;
    private static final int BTN_YELLOW_U = 294, BTN_YELLOW_V = 11;
    private static final int BTN_GREEN_U = 357, BTN_GREEN_V = 12;
    private static final int BTN_RED_U = 420, BTN_RED_V = 12;

    /**
     * Reroll glyph, a gold-framed arrow.
     *
     * <p>The sheet also carries a zeni coin at (396,65) and a readout pill at (423,66), which are deliberately
     * NOT used. The board shows its balance through the shared
     * {@link net.shurui.shuruisutilities.client.hud.ZeniReadout} instead, so it matches the scouter HUD exactly
     * rather than being a second, slightly different zeni display.</p>
     */
    private static final int REROLL_U = 357, REROLL_V = 68, REROLL_S = 18;

    /** The 1, 2 and 3 star dragon balls that number the three rows. Evenly spaced 58px apart on the sheet. */
    private static final int BALL_U = 9;
    private static final int BALL_V0 = 81;
    private static final int BALL_V_PITCH = 58;
    private static final int BALL_S = 15;

    /** Which pill an action uses. The art has ONE frame per colour, so hover is a shader brighten, not a sprite. */
    public enum Pill
    {
        GREEN(BTN_GREEN_U, BTN_GREEN_V),
        RED(BTN_RED_U, BTN_RED_V),
        YELLOW(BTN_YELLOW_U, BTN_YELLOW_V);

        final int u;
        final int v;

        Pill(int u, int v)
        {
            this.u = u;
            this.v = v;
        }
    }

    public static void panel(GuiGraphics g, int x, int y, int w, int h)
    {
        blit(g, PANEL, x, y, w, h, PANEL_U, PANEL_V, PANEL_W, PANEL_H, 1.0f);
    }

    /**
     * One tab, drawn at the art's native size from {@code (x, y)}. {@code w}/{@code h} are ignored on purpose:
     * this art has exactly one correct size and honouring a caller's wrong one is how the dragon balls got
     * destroyed before.
     *
     * <p>The reference supplies a SINGLE tab frame, so selected and hover are brightness on the one frame
     * rather than separate art. Unselected is dimmed rather than selected being blown out, which keeps the
     * plaque's own colours honest and still makes the current board obvious.</p>
     */
    public static void tab(GuiGraphics g, int x, int y, int w, int h, boolean selected)
    {
        float tint = selected ? 1.0f : 0.62f;
        blitSized(g, TAB, x, y, TAB_W, TAB_H, 0, 0, TAB_W, TAB_H, TAB_W, TAB_H, tint);
    }

    /** Hover brightening for a tab that is not the selected one. */
    public static void tabHovered(GuiGraphics g, int x, int y)
    {
        blitSized(g, TAB, x, y, TAB_W, TAB_H, 0, 0, TAB_W, TAB_H, TAB_W, TAB_H, 0.85f);
    }

    /**
     * One action pill. {@code hovered} brightens and {@code enabled} dims, both through the shader colour
     * rather than through extra art, because the commission only supplies a single frame per colour.
     */
    public static void pill(GuiGraphics g, Pill pill, int x, int y, int w, int h, boolean hovered, boolean enabled)
    {
        float tint = !enabled ? 0.45f : (hovered ? 1.18f : 1.0f);
        blit(g, WIDGETS, x, y, w, h, pill.u, pill.v, BTN_SW, BTN_SH, tint);
    }

    public static void reroll(GuiGraphics g, int x, int y, int size, boolean hovered)
    {
        blit(g, WIDGETS, x, y, size, size, REROLL_U, REROLL_V, REROLL_S, REROLL_S, hovered ? 1.2f : 1.0f);
    }

    /** Row bullet: the 1, 2 or 3 star ball. {@code index} is the slot, clamped so a fourth row cannot read off-sheet. */
    public static void ball(GuiGraphics g, int index, int x, int y, int size)
    {
        int i = Math.max(0, Math.min(2, index));
        blit(g, WIDGETS, x, y, size, size, BALL_U, BALL_V0 + i * BALL_V_PITCH, BALL_S, BALL_S, 1.0f);
    }

    /**
     * Scaled sub-image blit with an optional brightness multiply. The blit uploads immediately rather than
     * batching, so the shader colour applies to this draw alone provided it is put back straight after.
     */
    private static void blit(GuiGraphics g, ResourceLocation sheet, int x, int y, int w, int h,
                             int u, int v, int sw, int sh, float tint)
    {
        // The two 512x512 sheets. Anything with its own texture size goes through blitSized instead.
        blitSized(g, sheet, x, y, w, h, u, v, sw, sh, TEX, TEX, tint);
    }

    /** As {@link #blit}, but for a texture that is NOT one of the 512x512 sheets (the tab has its own file). */
    private static void blitSized(GuiGraphics g, ResourceLocation sheet, int x, int y, int w, int h,
                                  int u, int v, int sw, int sh, int texW, int texH, float tint)
    {
        boolean tinted = tint != 1.0f;
        if (tinted)
        {
            RenderSystem.setShaderColor(tint, tint, tint, 1.0f);
        }
        g.blit(sheet, x, y, w, h, (float) u, (float) v, sw, sh, texW, texH);
        if (tinted)
        {
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }
}
