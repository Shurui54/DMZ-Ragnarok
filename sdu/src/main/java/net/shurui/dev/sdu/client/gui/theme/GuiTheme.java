package net.shurui.dev.sdu.client.gui.theme;

import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.DmzNpc;

/**
 * Single source of truth for the "Shurui's DMZ Essentials" GUI design system (phase 1: the sdu addon).
 *
 * <p>Every layout, colour and texture decision the base screens and shared widgets make lives here as a
 * named constant. Screens and widgets read from this class instead of hardcoding numbers, so re-skinning
 * the whole suite later is a matter of editing this one file (and swapping the texture PNGs it points at).
 * This class IS the written style guide; the field comments are the specification.
 *
 * <p>All coordinates below are in the base screens' virtual canvas units (see {@code ScaledScreen}), which
 * the renderer uniformly scales to fit the window. One virtual unit therefore maps to roughly one logical
 * GUI pixel of the DMZ menu art.
 *
 * <p>Client-only: this class is only ever loaded from {@code client/} render code and must never be
 * classloaded on a dedicated server.
 */
public final class GuiTheme {

    private GuiTheme() {
    }

    // All commissioned art lives under assets/sdu/textures/gui/. The whole point of routing through these
    // constants is that dropping in bespoke replacement art later is a texture-file change with NO code edit:
    // keep the same pixel dimensions (or update the *_TEX_W/H below to match) and the nine-slice insets do
    // the rest. Each texture also carries the sheet size it was authored at, because GuiGraphics#blit needs
    // the source sheet dimensions.

    /**
     * Panel background, 351x332, drawn edge to edge (no transparent margin).
     *
     * <p>These are SOURCE pixels, and they are much larger than the destination the panel is drawn at. That is
     * deliberate: see {@link NineSlice} for why the art is stored at the size it was drawn and scaled here
     * rather than being resampled down into the file.
     */
    public static final ResourceLocation PANEL = tex("panel");
    public static final int PANEL_TEX_W = 351, PANEL_TEX_H = 332;
    /** The whole sheet is panel; there is no margin to trim. */
    public static final int PANEL_CONTENT_X = 0, PANEL_CONTENT_Y = 0;
    public static final int PANEL_CONTENT_W = PANEL_TEX_W, PANEL_CONTENT_H = PANEL_TEX_H;
    /**
     * The panel corner in SOURCE px. Measured, not guessed: the border rails are rows/columns 0..2, the corner
     * dragonball stud runs out to x=14 and its surrounding rail to x=16, so 17 captures the whole corner with
     * the flat body beginning immediately after. Paired with the destination {@link #PANEL_INSET}.
     */
    public static final int PANEL_SRC_INSET = 17;

    /**
     * Button state sheet, 264x76: two 264x38 states stacked, NORMAL on top and HOVER below (see
     * {@link ButtonState}).
     *
     * <p>The art has only these two. The old sheet carried a third, desaturated, "disabled" strip; that state is
     * now the normal strip under a grey wash ({@link #BUTTON_DISABLED_WASH}) so a disabled button still reads as
     * inactive without the artist having to draw and maintain a third variant of every future button.
     */
    public static final ResourceLocation BUTTONS = tex("buttons");
    public static final int BUTTONS_TEX_W = 264, BUTTONS_TEX_H = 76;
    /** Each button state occupies this rect (x,w,h fixed; y varies per state). */
    public static final int BUTTON_STATE_X = 0, BUTTON_STATE_W = 264, BUTTON_STATE_H = 38;
    /**
     * Grey wash laid over the NORMAL button strip to render a DISABLED button, since the art ships no disabled
     * variant. Kept light enough that the caption stays legible; the caption's own disabled colour carries most
     * of the cue.
     */
    public static final int BUTTON_DISABLED_WASH = 0x77202020;
    /**
     * Left/right end-cap width of the button art, in source pixels. The art has ANGLED (slanted) end caps:
     * measured by alpha scan, the left slant occupies source x1..4 and the right slant x113..124 across the
     * full 14px height. A cap of 12 fully contains each slant so it is drawn 1:1 (never sheared). The flat
     * interior between the caps (source x5..112) is horizontally uniform (max channel diff 1) so it TILES at
     * native scale to any width with no banding. This is why the button is spliced, not stretched.
     */
    public static final int BUTTON_CAP_W = 12;

    /**
     * The button end cap in SOURCE px. Measured on the art: the left cap (gold frame + ring + dragonball boss)
     * ends at x=17 with the flat interior starting at x=18, and the right cap mirrors it from x=245. 19 captures
     * either end whole. Paired with the destination {@link #BUTTON_CAP_W}.
     */
    public static final int BUTTON_SRC_CAP_W = 19;

    /**
     * Dropdown / field / tooltip / tab body sheet, 145x87: a flat box inside a symmetric 5px bevel (one bright
     * outline column and a four step gradient, the same bevel the scrollbar track uses).
     */
    public static final ResourceLocation DROPDOWN = tex("dropdown");
    public static final int DROPDOWN_TEX_W = 145, DROPDOWN_TEX_H = 87;
    /** The bevel in SOURCE px, paired with the destination {@link #DROPDOWN_INSET}. */
    public static final int DROPDOWN_SRC_INSET = 5;
    /**
     * The OPEN dropdown list body uses the SAME art as the closed header.
     *
     * <p>It used to have its own sheet, a blue band fading into flat purple, which spliced asymmetrically (the
     * "purple area at the bottom but not the top" report) and no longer belongs to this palette. The new art set
     * has one box, so the list is that box under a darkening wash ({@link #DROPDOWN_LIST_WASH}), which reads as
     * a panel opening BEHIND the header rather than as a second, differently coloured widget.
     */
    public static final ResourceLocation DROPDOWN_ALT = DROPDOWN;
    /** Darkening wash that separates the open list body from the header drawn on top of it. */
    public static final int DROPDOWN_LIST_WASH = 0x55000000;

    /** Tooltip / callout body. Reuses the dropdown fill until bespoke tooltip art is commissioned; swapping
     *  in a dedicated tooltip.png here is the only change needed when that art arrives. */
    public static final ResourceLocation TOOLTIP = DROPDOWN;
    public static final int TOOLTIP_TEX_W = DROPDOWN_TEX_W, TOOLTIP_TEX_H = DROPDOWN_TEX_H;

    /** Text field / tab body. Also derived from the dropdown fill for now; see the class docs on swapping. */
    public static final ResourceLocation FIELD = tex("dropdown");
    public static final int FIELD_TEX_W = DROPDOWN_TEX_W, FIELD_TEX_H = DROPDOWN_TEX_H;

    /** Inventory-slot art (36x36). Fixed size, drawn 1:1 (no slicing). */
    public static final ResourceLocation SLOT = tex("slot");
    public static final int SLOT_TEX_W = 36, SLOT_TEX_H = 36;

    /**
     * Scrollbar TRACK art, 23x222: a flat body inside a symmetric 5px bevel (a bright outline column plus a four
     * step gradient), the same bevel the dropdown box uses. Drawn as a three-slice: an end cap top and bottom
     * with the flat body stretched between them, which is exact because the body is one colour.
     */
    public static final ResourceLocation SCROLLBAR = tex("scrollbar");
    public static final int SCROLLBAR_TEX_W = 23, SCROLLBAR_TEX_H = 222;
    /** The whole sheet is track; there is no transparent margin to trim. */
    public static final int SCROLLBAR_CONTENT_X = 0, SCROLLBAR_CONTENT_Y = 0;
    public static final int SCROLLBAR_CONTENT_W = SCROLLBAR_TEX_W, SCROLLBAR_CONTENT_H = SCROLLBAR_TEX_H;
    /** End cap height on screen, in virtual px. Layout depends on this (see {@link #SCROLLBAR_THUMB_INSET}). */
    public static final int SCROLLBAR_CAP = 3;
    /** The same end cap in SOURCE px: the 5 rows of bevel before the flat body starts. */
    public static final int SCROLLBAR_SRC_CAP = 5;
    /** On-screen width the scrollbar bar is drawn at, in virtual px. Unchanged from the previous art so no
     *  screen's reserved gutter moves; the 23px source is scaled into it. */
    public static final int SCROLLBAR_WIDTH = 12;
    /**
     * How much of the track must stay visible around the thumb, in virtual px: its bright blue outline, and
     * nothing more. The thumb is inset by exactly this on all four sides, so the bar reads as a groove with a
     * block sliding in it. Anything larger leaves a gap of empty track at the ends of the thumb's travel, which
     * is what the previous {@link #SCROLLBAR_CAP}-sized inset did.
     */
    public static final int SCROLLBAR_TRACK_OUTLINE = 1;
    /**
     * Inset, in virtual pixels, of the scrollbar column from the panel's inner content edge. In the user's
     * screenshot the scrollbar ran flush to (and on top of) the panel outline top and bottom and past its right
     * edge. The panel's rounded border curve is {@link #PANEL_INSET} (6) px deep, so a scrollbar drawn any closer
     * than that to an edge sits on the outline. This inset is the panel border depth plus one grid {@link #UNIT}
     * of clear body, so the scrollbar's track always has a visible band of panel between it and the border on
     * every side. Applied to the shared scroll layout so every screen's scrollbar stays inside the content area.
     *
     * <p>Value is {@link #PANEL_INSET} (6) plus one grid {@link #UNIT} (2) = 8. It is spelled as a literal here
     * because both {@code PANEL_INSET} and {@code UNIT} are declared lower in this file and static initializers
     * run top-to-bottom (a forward reference would not compile).
     */
    public static final int SCROLLBAR_PANEL_INSET = 8;
    /**
     * Horizontal space a widget row must RESERVE on its right whenever a scrollbar is present on that screen, so
     * no field or dropdown is ever drawn into the scrollbar's column (the ID/Cat/Title/Desc/Turn-in overlap the
     * user reported). It is the bar width plus one grid {@link #UNIT} of gap; the shared field/row layout stops
     * widths short by this much when the screen shows a scrollbar, the same way row labels stop short of trailing
     * buttons.
     *
     * <p>The trailing {@code + 2} is one grid {@link #UNIT} (spelled literally for the same top-to-bottom
     * static-initialization reason as {@link #SCROLLBAR_PANEL_INSET}).
     */
    public static final int SCROLLBAR_RESERVE = SCROLLBAR_WIDTH + 2;

    /**
     * Vertical inset, in virtual pixels, that the scroll THUMB's travel is held clear of each end of the
     * track. The user reported the thumb overshooting the scrollbar background slightly at the very top and
     * bottom of its travel: a thumb whose top sat exactly at the track top (and bottom exactly at the track
     * bottom) poked past the track art's rounded {@link #SCROLLBAR_CAP} end cap. This inset keeps the thumb
     * fully inside the track's flat interior at both extremes: the thumb's usable travel band is
     * {@code [top + SCROLLBAR_THUMB_INSET, top + trackH - SCROLLBAR_THUMB_INSET]} and its height is clamped so
     * both ends stay within it. It equals {@link #SCROLLBAR_TRACK_OUTLINE}, so at either end of its travel the
     * thumb stops with the track's outline showing and NOTHING else: a larger inset (it used to be the track's
     * whole end cap) left a band of empty track above a thumb scrolled to the top, which read as the bar not
     * having reached the end. Applied through {@link #scrollThumbY} so every scrollbar (both
     * the base saga lists/bands and the dropdown list) uses the identical clamp, and the drag hit-test reads
     * back the same clamped geometry so dragging can never desync from the drawn thumb.
     */
    public static final int SCROLLBAR_THUMB_INSET = SCROLLBAR_TRACK_OUTLINE;

    /**
     * Clamp a scroll thumb fully inside its track. Returns the thumb's top Y so that its top is never above
     * {@code top + SCROLLBAR_THUMB_INSET} and its bottom (top + {@code thumbH}) never below
     * {@code top + trackH - SCROLLBAR_THUMB_INSET}. The thumb height is first capped to the inset band so a
     * very tall thumb on a short track still fits. {@code scroll}/{@code maxScroll} position the thumb linearly
     * within that band. Shared by every scrollbar so the clamp rule is identical everywhere, and so the drag
     * hit-test (which calls this with the same arguments) matches the drawn thumb pixel-for-pixel.
     *
     * @return the clamped thumb top Y (virtual coords)
     */
    public static int scrollThumbY(int top, int trackH, int thumbH, int scroll, int maxScroll) {
        int bandTop = top + SCROLLBAR_THUMB_INSET;
        int bandBottom = top + trackH - SCROLLBAR_THUMB_INSET;
        int travel = Math.max(0, (bandBottom - bandTop) - thumbH);
        int y = bandTop + (maxScroll <= 0 ? 0 : (int) ((long) travel * scroll / maxScroll));
        // Final guard against rounding: never let the thumb cross either inset edge.
        int maxY = bandBottom - thumbH;
        if (y < bandTop) {
            y = bandTop;
        }
        if (y > maxY) {
            y = maxY;
        }
        return y;
    }

    /**
     * Scrollbar THUMB art, 46x29: TWO 23x29 states side by side, idle on the left and held on the right.
     *
     * <p>Two states because a scrollbar with no press feedback reads as decoration. The right state is the same
     * thumb drawn darker, which is what the artist supplied and what a pressed control conventionally does. The
     * held state is shown while the thumb is being DRAGGED, not merely hovered, so the cue means "you have hold
     * of this" rather than "your cursor is near this".
     *
     * <p>There is no separate grip sprite any more. The old art needed one stamped in the middle of an otherwise
     * blank bar; this thumb carries evenly spaced ribs down its whole body, and
     * {@link NineSlice#drawScrollPiece} tiles one rib pitch so a taller thumb grows more ribs instead of longer
     * ones.
     */
    public static final ResourceLocation SCROLLBAR_ALT = tex("scrollbar_alt");
    public static final int SCROLLBAR_THUMB_TEX_W = 46, SCROLLBAR_THUMB_TEX_H = 29;
    /** One state's rect within the sheet; the held state sits exactly one state width to the right. */
    public static final int SCROLLBAR_THUMB_STATE_W = 23, SCROLLBAR_THUMB_STATE_H = 29;
    public static final int SCROLLBAR_THUMB_IDLE_X = 0;
    public static final int SCROLLBAR_THUMB_HELD_X = SCROLLBAR_THUMB_STATE_W;
    /**
     * On-screen thumb width, in virtual px: the track's width less its outline on each side, so the thumb sits
     * INSIDE the groove instead of covering its edge. The thumb art is as wide as the track art, so drawing it
     * at the full width hid the outline entirely and the two pieces read as one slab.
     */
    public static final int SCROLLBAR_THUMB_WIDTH = SCROLLBAR_WIDTH - 2 * SCROLLBAR_TRACK_OUTLINE;
    /** Thumb end cap: 4 source rows of rounded bevel, drawn 2 virtual px tall. */
    public static final int SCROLLBAR_THUMB_CAP = 2;
    public static final int SCROLLBAR_THUMB_SRC_CAP = 4;
    /**
     * One rib pitch of the thumb body: 4 source rows repeated every 2 virtual px. Measured off the art, whose
     * body repeats on a 4 row cycle. Tiled rather than stretched so the ribs keep their spacing at any thumb
     * height.
     */
    public static final int SCROLLBAR_THUMB_RIB_SRC_Y = SCROLLBAR_THUMB_SRC_CAP;
    public static final int SCROLLBAR_THUMB_RIB_SRC_H = 4;
    public static final int SCROLLBAR_THUMB_RIB_DST_H = 2;

    /**
     * Circular icon-button background. A {@value #CIRCLE_BUTTON_TEX_W}x{@value #CIRCLE_BUTTON_TEX_H} sheet of two
     * square states stacked vertically: NORMAL on top, HOVER on the bottom.
     *
     * <p>NO LONGER USED. The X and arrow icon buttons previously drew this separate disc sprite, which read as a
     * different widget from the Edit/Copy buttons beside them (the reviewer's complaint that "the x buttons are
     * not inside of the normal button"). They now draw the SAME {@link #BUTTONS} art as every other button, just
     * spliced ROUND: the authored angled right cap is replaced by a horizontal mirror of the rounded left cap so
     * both ends are rounded (see {@link NineSlice#drawButtonRounded} and {@link GuiTheme#BUTTON_CAP_W}). The PNG
     * file {@code circle_button.png} is left on disk but is dead art; the constant is retained only so the field
     * comment records why it went away. Nothing references {@link ThemeRender#circleButton} anymore.
     */
    public static final ResourceLocation CIRCLE_BUTTON = tex("circle_button");
    public static final int CIRCLE_BUTTON_TEX_W = 20, CIRCLE_BUTTON_TEX_H = 40;
    /** Height of one state cell in {@link #CIRCLE_BUTTON} (the sheet is two cells tall). */
    public static final int CIRCLE_BUTTON_STATE_H = 20;
    /** Source Y of the NORMAL disc (top cell) and the HOVER disc (bottom cell) in {@link #CIRCLE_BUTTON}. */
    public static final int CIRCLE_BUTTON_NORMAL_V = 0;
    public static final int CIRCLE_BUTTON_HOVER_V = CIRCLE_BUTTON_STATE_H;
    /**
     * A themed button whose drawn width is at or below this many virtual pixels is treated as a small ICON button
     * and rendered with the ROUND-spliced {@link #BUTTONS} art (both caps rounded) instead of the standard splice
     * (rounded left cap, authored angled right cap). The suite's icon buttons (move arrows, X delete) are built at
     * the standard {@link #ICON_BUTTON_SIZE}; a standard action button is >=30px, so this cleanly separates the two
     * classes. This lets an icon button pick up the round treatment purely by its size, so no screen file has to
     * change its button construction, and it is why NORMAL buttons keep the angled cap while ONLY icon buttons get
     * the mirrored round cap.
     */
    public static final int ICON_BUTTON_MAX_W = 22;

    /**
     * Rounded end-cap width, in source pixels, used ONLY for the round splice of an icon button (see
     * {@link NineSlice#drawButtonRounded}). It is DELIBERATELY narrower than the standard {@link #BUTTON_CAP_W}
     * (12, which fully contains the wide authored angled right slant): an icon button is a small square, and a
     * 12px cap on each side would need 24px of width before any interior showed, forcing the button huge. An
     * alpha scan of {@code buttons.png} shows the rounded LEFT corner completes by source x4 (the first opaque
     * column reaches x1 by row 10 and the curve is done by x4), so a {@value}px cap captures the whole rounded
     * corner with a 2px margin while still being drawn 1:1 (never scaled). Both the left cap and its mirror (the
     * right cap of an icon button) use this width, so the pill is symmetric and its interior tiles between them.
     */
    public static final int ICON_BUTTON_CAP_W = 6;

    /**
     * The icon-button cap in SOURCE px, paired with {@link #ICON_BUTTON_CAP_W}. It is the same proportion of the
     * art that the destination cap is of the drawn button, so the rounded corner reaches the same point on a
     * small icon pill as it does on a full width button: {@code BUTTON_SRC_CAP_W * ICON_BUTTON_CAP_W /
     * BUTTON_CAP_W}, which is 9.5, taken up to 10 so the whole curve is inside it.
     */
    public static final int ICON_BUTTON_SRC_CAP_W = 10;

    /**
     * The ONE standard on-screen size (square) of a small ICON button: the row delete "X" and the up/down move
     * arrows. Every list row across the suite builds its trailing delete/arrow controls at this size so the
     * round-spliced icon button reads identically on every screen (the reviewer's "uniformity is important" and
     * "not buttons too small" complaints). The earlier value 14, then clamped down to an 11-12px item row, left
     * the two mirrored caps meeting with almost no interior between them (an unreadable sliver, the "increase its
     * size to a sensible uniform minimum" case). At {@value} the two {@link #ICON_BUTTON_CAP_W} (6) caps leave a
     * clear {@code 18 - 2*6 = 6}px strip of tiled interior between them, the button is one grid {@link #UNIT}
     * shorter than the {@link #BUTTON_HEIGHT} text buttons on the same row so the X/arrow reads as the same family,
     * and it stays under {@link #ICON_BUTTON_MAX_W} so it is routed to the round splice. Square, so the caps mirror
     * symmetrically and the disc is never an oval.
     */
    public static final int ICON_BUTTON_SIZE = 18;

    /**
     * Hard floor, in virtual px, for an icon button's drawn size after any per-row clamp. The round splice needs at
     * least {@code 2 * ICON_BUTTON_CAP_W} (12) px of width before the two mirrored rounded caps meet with no
     * interior; the icon helpers therefore never build an icon button narrower than this, growing a cramped item
     * row slightly rather than drawing a sliver. It is {@code 2 * ICON_BUTTON_CAP_W} + one grid {@link #UNIT} = 14
     * so a thin strip of tiled interior always sits between the two rounded caps, keeping the X/arrow glyph centred
     * on a real body. Spelled literally for the same top-to-bottom static-init reason as {@link #SCROLLBAR_PANEL_INSET}.
     */
    public static final int ICON_BUTTON_MIN_SIZE = 14;

    /**
     * The umbrella "DMZ Ragnarok" logo, heading every screen. Sized 108x64 so it is an exact
     * {@link #LOGO_DOWNSCALE} multiple of the {@link #LOGO_DRAW_WIDTH}x{@link #LOGO_DRAW_HEIGHT} the header draws
     * it at; both axes therefore scale by the same 1/4 and the mark cannot be squashed. Width follows the art's
     * aspect: the earlier three-line wordmark was 174 wide at the same height, so the two-line mark simply
     * occupies a narrower band at the SAME fixed height.
     *
     * <p>AUTHORING: export the sheet at its full 108x64 detail. The 27x16 draw size is in the virtual canvas
     * units of {@code ScaledScreen}, NOT physical pixels: {@link #CANONICAL_UI_SCALE} and then Minecraft's own
     * GUI scale both multiply it, so the mark covers a few hundred real pixels on screen and the sheet's extra
     * detail is what is actually sampled. Do not pre-flatten the sheet to the draw size.</p>
     */
    public static final ResourceLocation LOGO = tex("logo");
    public static final int LOGO_TEX_W = 108, LOGO_TEX_H = 64;

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(DmzNpc.MODID, "textures/gui/" + name + ".png");
    }

    // Insets are measured from the drawn content edge, in source-texture pixels. They were derived by
    // scanning each PNG for where the rounded corner / bevel border ends and the flat repeating interior
    // begins (see the design report). Change these ONLY if you change the corresponding art.

    /** Panel corner size. The rounded border curve reaches the straight edge 5px in from the content edge
     *  (measured: content starts at source x3, the curve is vertical by x8). A 6px corner captures the curve
     *  with a 1px safety margin; the corner is drawn 1:1, the horizontal edges + interior tile at native
     *  scale, and only the vertical axis is allowed to stretch because the panel body is a smooth designed
     *  top-to-bottom gradient (tiling it would repeat the gradient). See {@link ThemeRender#panel}. */
    public static final int PANEL_INSET = 6;

    /** Dropdown / field / tooltip bevel width. The dropdown art is a framed box with a 4px lighter L/R bevel
     *  over a flat interior, so a 4px inset keeps the bevel crisp while the flat interior tiles to any size. */
    public static final int DROPDOWN_INSET = 4;
    public static final int FIELD_INSET = 4;
    public static final int TOOLTIP_INSET = 4;

    /**
     * The ONE canonical physical-pixels-per-virtual-unit scale for every screen in the suite. Each screen
     * declares its own virtual canvas size (UI_W x UI_H) and lays its widgets out in virtual units; the
     * reviewer's "the gui sizes are changing" complaint was that {@code ScaledScreen} previously derived a
     * DIFFERENT scale per screen by fitting each canvas to the window, so a 14px button (and the logo, and the
     * text) rendered at a different PHYSICAL size on a 240-wide screen than on a 340-wide one.
     *
     * <p>The fix, applied at the source in {@code ScaledScreen}, is to render every screen at this fixed scale
     * so one virtual pixel is always the same number of physical pixels; a button, the logo and a text row are
     * then physically identical on every screen. The scale is only reduced below this (uniformly) when a screen
     * is so large it would not otherwise fit the current window, and never increased above it. 2 keeps the DMZ
     * menu art crisp (an even multiple) while fitting the largest canvas (340x320) inside a 720p window.
     */
    public static final double CANONICAL_UI_SCALE = 2.0;
    /** Fraction of the window a screen may occupy before the canonical scale is reduced to make it fit. */
    public static final double UI_FIT_MARGIN = 0.95;
    /** Hard floor for the auto-reduced scale on very small windows (keeps text legible). */
    public static final double MIN_UI_SCALE = 0.75;

    /**
     * The ONE canvas size every themed screen is built at, in virtual units.
     *
     * <p>Screens used to declare their own {@code UI_W}/{@code UI_H}, and 90 of them between them used 50
     * different sizes, from 220x110 up to 340x320. Navigating the suite therefore resized the window under the
     * player on almost every click, which is what "unify the sizes across all guis" is about. Every themed
     * screen now takes its canvas from here, so the panel is the same object moving between contents rather
     * than a new box each time.
     *
     * <p>300x260 rather than the tallest screen's 340x320: at gui scale 3 a 320 tall canvas is 960 real pixels
     * and overflows a window that is not close to full height, and the handful of screens that were taller can
     * scroll, which the base screen already supports. 300 wide was already the width 71 of the 90 screens used.
     *
     * <p>Two screens deliberately do NOT use this: the task board and the main menu are sized to their own
     * panel art so every sprite on them draws at exact pixels. Resizing those would resample bespoke art, which
     * is the trade that was explicitly removed from them earlier.
     */
    public static final int SCREEN_W = 300;
    public static final int SCREEN_H = 260;

    /** Base spacing unit. All gaps/paddings are multiples of this so the layout stays on a grid. */
    public static final int UNIT = 2;

    /** Padding between the panel's inner border and content on every side. */
    public static final int CONTENT_PADDING = 6;

    /** Gap between stacked action buttons / rows. */
    public static final int GAP = 4;

    /**
     * Vertical gap trimmed off the BOTTOM of every button so stacked per-row buttons never touch. List screens
     * lay one button per row at the row pitch (e.g. a 16px-tall button on a 16px row), which made adjacent rows'
     * buttons flush into one solid column (the user's screenshot). Rather than change the pitch (which would
     * reduce how many rows fit and could push content past the panel), the button SHRINKS by this much on its
     * vertical axis: it is drawn and captioned within {@code height - ROW_BUTTON_GAP}, leaving a clear band of
     * panel between rows. 2 is one {@link #UNIT} (the base grid step), the smallest gap that visibly separates
     * rows while a 16px button still leaves 14px for the caption (the standard button height, which the
     * {@link GuiText} centring already fits comfortably). Applied in {@code DmzTextureButton} so all 35 screens
     * benefit with no per-screen edit; the button's click/hit box is unchanged (full row), so this is a purely
     * visual separation and no click behaviour changes. */
    public static final int ROW_BUTTON_GAP = 2;

    /** Vertical stride of a labelled field row (kept equal to the legacy ROW_H so existing layouts are
     *  unchanged; the base classes reference this instead of a private literal). */
    public static final int ROW_HEIGHT = 12;

    /**
     * One standard button height for the whole suite. Screens historically built action buttons at a spread of
     * 14/15/16/18 px (footer Save/Back/Close were 18, list-row Edit/Copy 15-16, flags 16, add-rows 14). That
     * inconsistency is the reviewer's "uniformity is important" complaint. Rather than churn 35 screens' explicit
     * heights (and risk shifting their footers), {@link net.shurui.dev.sdu.client.gui.DmzTextureButton} now draws
     * every non-icon button captioned within this ONE height, vertically centred in whatever hit-box the screen
     * built, so all text buttons read at a single physical height regardless of the height the call site passed.
     * 14 is the common list-row button height and the height {@link GuiText} centres a descender caption in without
     * clipping (proven in {@link GuiText#centeredTextY}).
     */
    public static final int BUTTON_HEIGHT = 14;
    /** Standard text-field / dropdown header height. */
    public static final int FIELD_HEIGHT = 11;
    /** Standard dropdown list row height. */
    public static final int DROPDOWN_ROW_HEIGHT = 14;

    /**
     * Standard horizontal gap between adjacent buttons on the same row (e.g. Edit and Copy in a list row, or the
     * footer Save/Back pair). One grid {@link #UNIT} times two = 4px: tight enough that a button pair reads as a
     * group, wide enough that the two rounded caps never touch. Exposed so a screen can space a button pair on the
     * grid instead of guessing a literal.
     */
    public static final int BUTTON_GAP_X = 4;
    /**
     * Standard vertical gap between stacked buttons/rows. Equal to {@link #ROW_BUTTON_GAP} (2px, one grid
     * {@link #UNIT}) so the clear band a per-row button already trims off its bottom in
     * {@link net.shurui.dev.sdu.client.gui.DmzTextureButton} matches the gap the layout leaves between rows.
     */
    public static final int BUTTON_GAP_Y = ROW_BUTTON_GAP;
    /**
     * Horizontal gap between adjacent flag toggles laid out across one row by the shared flag-row helper
     * (SagaBaseScreen.flagRow): the quest editor's {@code [x] Party [ ] Secret [ ] Parallel [ ] Branch} strip
     * and the side-quest editor's three-flag strip. One grid {@link #UNIT} (2px) so several flags still fit in
     * the reserved content column once the row is trimmed to stop at rowControlRight(); tighter than
     * {@link #BUTTON_GAP_X} because a flag row packs more buttons into the same width and the labels already
     * shrink-to-fit inside each pill.
     */
    public static final int FLAG_ROW_GAP = UNIT;

    /** Height of the header band that carries the logo, measured from the panel's top inner edge. The editors
     *  start their content at y=28 and list screens at y=34, so the header must clear that; 26 does. */
    public static final int HEADER_HEIGHT = 26;
    /**
     * The lowest Y at which any screen's first content row may begin, so content never collides with the header
     * (the reviewer's "the logo at the top overlaps the first field" on the saga editor). The logo's lower edge
     * sits at {@link #LOGO_TOP} + {@link #LOGO_DRAW_HEIGHT} = 7 + 16 = 23 inside the {@value #HEADER_HEIGHT}px
     * header band, so anything drawn above y=23 overlaps it. This floor is one grid {@link #UNIT} below the header
     * band, giving a clear separator between the header and the first row on EVERY screen without moving or
     * shrinking the logo (its size and 7px offset are settled). Screens clamp their first content Y up to this via
     * {@link net.shurui.dev.sdu.client.gui.saga.SagaBaseScreen#contentTop(int)}.
     *
     * <p>Value is {@link #HEADER_HEIGHT} (26) + {@link #UNIT} (2) = 28, spelled as a literal here for the same
     * top-to-bottom static-initialization reason as {@link #SCROLLBAR_PANEL_INSET} (both operands are declared
     * later in this file).
     */
    public static final int CONTENT_TOP = 28;
    /** Height of the footer band reserved for action buttons at the bottom of a screen. */
    public static final int FOOTER_HEIGHT = 20;
    /**
     * Clear gap, in virtual px, held between a footer button's DRAWN bottom edge and the panel's bottom edge, so
     * the button never touches or overlaps the panel's rounded border art (the user's "save and menu/back buttons
     * on top of the gui background borders" report). The panel border is {@link #PANEL_INSET} (6) px deep, so any
     * button whose bottom sits within that band lands ON the outline. This gap is the border depth plus one grid
     * {@link #UNIT} of clear panel body, the exact vertical counterpart of {@link #SCROLLBAR_PANEL_INSET} on the
     * sides. Value is {@link #PANEL_INSET} (6) + {@link #UNIT} (2) = 8, spelled literally for the same top-to-bottom
     * static-initialization reason as {@link #SCROLLBAR_PANEL_INSET}.
     */
    public static final int FOOTER_BORDER_GAP = 8;
    /**
     * The reserved band at the BOTTOM of a screen that scrollable/flowing content may never enter, the vertical
     * counterpart of the {@link #HEADER_HEIGHT} band at the top. Measured UP FROM THE BORDER: the footer button's
     * bottom stops {@link #FOOTER_BORDER_GAP} above the panel edge (clear of the border), the button is
     * {@link #BUTTON_HEIGHT} tall, and one grid {@link #UNIT} of clear panel sits above it before ordinary content
     * may begin. Value: {@link #FOOTER_BORDER_GAP} (8) + {@link #BUTTON_HEIGHT} (14) + {@link #UNIT} (2) = 24,
     * spelled literally for the same top-to-bottom static-init reason as {@link #SCROLLBAR_PANEL_INSET}. Footer
     * buttons pass exactly {@link #BUTTON_HEIGHT} so the drawn bottom equals {@code footerY + BUTTON_HEIGHT}.
     */
    public static final int FOOTER_BAND = 24;
    /**
     * The Y (top edge) at which a screen's footer action row is placed, given the screen's total {@code uiHeight}:
     * high enough that a {@link #BUTTON_HEIGHT}-tall button's bottom edge lands exactly {@link #FOOTER_BORDER_GAP}
     * above the panel's bottom edge, clear of the border. Every footer uses this instead of a hand-picked
     * {@code uiHeight - N} so the row lands at the same place suite-wide, always inside the panel body. Equals
     * {@code uiHeight - FOOTER_BORDER_GAP - BUTTON_HEIGHT} = {@code uiHeight - 22}. Footer buttons must be built at
     * {@link #BUTTON_HEIGHT} tall for the drawn bottom to match this arithmetic.
     */
    public static int footerY(int uiHeight) {
        return uiHeight - FOOTER_BORDER_GAP - BUTTON_HEIGHT;
    }
    /**
     * The lowest Y a screen's flowing/scrollable content may reach before it would enter the reserved
     * {@link #FOOTER_BAND}. One grid {@link #UNIT} above {@link #footerY} so a clear strip separates the last
     * content row from the footer.
     */
    public static int contentBottom(int uiHeight) {
        return footerY(uiHeight) - UNIT;
    }
    /**
     * Gap, in virtual UI pixels, between the panel's top outline and the top of the logo. The panel's light
     * top outline occupies virtual rows 0..1 (source rows 3..4 of panel.png), so a logo drawn flush at the old
     * y=2 sat directly on that outline with no breathing room (the user's screenshot complaint). This constant
     * pushes the logo down so a clear band of panel body shows above it. Identical on every screen because
     * every screen draws the panel and header at the same virtual origin.
     *
     * <p>Back to 0, matching the task board, which sets its logo at y=2 and is the placement Shurui asked every
     * screen to copy. It was raised to 5 against the OLD panel art, whose thick soft top border genuinely
     * crowded the wordmark; the current art's border is a thin crisp rail, so the extra band now just reads as
     * the logo floating low in the header. The logo's top edge lands at {@link #LOGO_TOP} = 2 and its bottom at
     * 2 + 16 (the fixed logo height) = 18, well clear of the earliest content start (y=28 on the editors) and
     * inside the 26px {@link #HEADER_HEIGHT} band, so nothing below it moves. The logo keeps its fixed 16px
     * height; only its Y changes.
     */
    public static final int LOGO_OUTLINE_GAP = 0;
    /** Y of the logo's top edge inside the header: the panel outline, plus {@link #LOGO_OUTLINE_GAP}. */
    public static final int LOGO_TOP = 2 + LOGO_OUTLINE_GAP;
    /**
     * The logo is drawn at ONE fixed size on every screen: the native 108x64 art at a uniform 1/4 downscale.
     * Both axes use the SAME divisor so the aspect ratio is preserved (no squashing): the divisor is chosen
     * so the logo clears the y=28 editor content start without shrinking per-screen. The old bug the user saw
     * (logo "much smaller and squashed on the races list") was NOT this size differing between screens (it is
     * identical here on every screen) but the whole canvas being scaled by a different factor per screen; that
     * is fixed at the source in {@code ScaledScreen} so one virtual pixel is the same physical size everywhere.
     */
    public static final int LOGO_DOWNSCALE = 4;
    public static final int LOGO_DRAW_WIDTH = LOGO_TEX_W / LOGO_DOWNSCALE;   // 108/4 -> 27
    public static final int LOGO_DRAW_HEIGHT = LOGO_TEX_H / LOGO_DOWNSCALE;  // 64/4 -> 16

    /**
     * The lower edge Y of the logo inside the header, i.e. the first Y at which content directly under the logo
     * is clear of it: {@link #LOGO_TOP} + {@link #LOGO_DRAW_HEIGHT} = 7 + 16 = 23. Used by the logo-band overlap
     * clamp so an element whose horizontal span crosses the centred logo is pushed down to {@link #CONTENT_TOP}
     * (below the logo) while elements beside the logo (a header count label, a side action button) are left where
     * they are. Spelled as a literal for the same top-to-bottom static-initialization reason as
     * {@link #SCROLLBAR_PANEL_INSET} (its operands are declared earlier but this keeps the whole band in one place). */
    public static final int LOGO_BOTTOM = 23;
    /**
     * Horizontal half-width, in virtual px, of the logo's overlap band measured from the panel centre. The logo is
     * drawn centred at {@link #LOGO_DRAW_WIDTH} (27) wide, so its footprint is the centre plus/minus 27/2 rounded up
     * to 14. An element counts as "under the logo" (and is pushed below it) when its horizontal span crosses
     * {@code [centre - LOGO_HALF_WIDTH, centre + LOGO_HALF_WIDTH]}. Any content wholly left or right of that band is
     * beside the logo, not under it, and is never moved. */
    public static final int LOGO_HALF_WIDTH = (LOGO_DRAW_WIDTH + 1) / 2;

    /** Inner horizontal padding subtracted from an element's width before text is fit into it. */
    public static final int TEXT_PADDING_X = 3;
    /**
     * Extra LEFT inset for dropdown text (both the collapsed header value/search box and the open list rows),
     * ON TOP of {@link #TEXT_PADDING_X}. The bevelled dropdown frame reads as a border, and text at only the
     * 3px base padding hugged that border in the user's screenshots. Shifting the text right by this amount
     * gives comfortable padding while the right-side arrow gutter is preserved separately, so text still cannot
     * run under the arrow. Applied via a named constant so every dropdown gets the same inset.
     */
    public static final int DROPDOWN_TEXT_INSET = 4;
    /**
     * Horizontal caption inset, per side, subtracted from a BUTTON's drawn width before a caption is fitted into
     * it (used by {@link ThemeRender#buttonLabel}), REPLACING the generic {@link #TEXT_PADDING_X} for buttons.
     * This is the real reason "Copy" still spilled: the fit used only the 3px text padding, but the button art has
     * an ANGLED end cap whose top-right corner is cut in by ~11 source px (measured on {@code buttons.png}: the
     * NORMAL state's top row reaches only x113 of the x1..124 cell, sloping out to x124 lower down). A caption fitted
     * to the full {@code w - 2*TEXT_PADDING_X} therefore rode onto that slanted corner and its final glyph/descender
     * read as spilling past the button's visible edge, even though it never exceeded the advance width. Reserving
     * this larger inset per side pulls the caption off the steep part of the slant so it stays over the flat/mild
     * interior. It is one grid {@link #UNIT} (2) beyond {@link #TEXT_PADDING_X} (3) = 5, big enough to clear the
     * corner cut at caption height without over-shrinking: a wide button's caption still fits at 1.0 (only the
     * tightest hand-placed buttons, e.g. a 28px "Copy", shrink, and they shrink to sit inside the flat region
     * instead of bleeding into the cap). The fit path in {@link GuiText} then guarantees, against THIS drawn-width
     * inner, that no caption can exceed it. */
    public static final int BUTTON_TEXT_INSET = TEXT_PADDING_X + UNIT;
    /** Smallest pose-stack scale text is allowed to shrink to before it is ellipsized instead. Below this
     *  the vanilla font stops being readable, so we clip rather than shrink further. */
    public static final float MIN_TEXT_SCALE = 0.55f;

    /**
     * Optical-centring nudge, in virtual px, added when a single text line is vertically centred in an element
     * (see {@link GuiText#centeredTextY}). It exists because centring the font's full {@link Font#lineHeight}
     * (9px) cell does NOT optically centre the visible ink.
     *
     * <p>Measured from the vanilla default font's own glyph raster ({@code assets/minecraft/textures/font/ascii.png},
     * 8x8 cells) rather than guessed:
     * <ul>
     *   <li>{@code drawString} places the glyph CELL's top row at {@code y}; the cell's 8 raster rows map to
     *       {@code y+0 .. y+7}, and {@link Font#lineHeight} is 9 (rows {@code y+0 .. y+8}).</li>
     *   <li>CAPITALS (A, E, M, S, C, ...) ink rows 0..6 of the cell: they occupy {@code y+0 .. y+6} (7px tall),
     *       top-aligned in the cell.</li>
     *   <li>DESCENDERS (g, p, q, y, ...) ink rows 2..7: their tail reaches {@code y+7}, the last cell row.</li>
     *   <li>Cell rows 7..8 of the lineHeight box are therefore empty leading/descender-reserve for a caption
     *       with no descender.</li>
     * </ul>
     *
     * <p>So a no-descender caption ("Edit", "Menu", "Save") has its 7px cap band at {@code y+0..y+6} while the
     * 9px line box centred by {@code (H - lineHeight)/2} reserves 2 empty rows (7,8) BELOW it. Centring that box
     * thus biases the visible ink UP by ~1px (the bug the reviewer kept seeing). Shifting the draw down by this
     * one-pixel correction re-centres the cap band: at the standard {@link #BUTTON_HEIGHT} (14) the caps then
     * land in rows 3..9 of the element, leaving a 3px gap above and a 4px gap below (within one px of each other,
     * optically centred), while a descender's tail bottoms out at row 10, a clear 3px above the button's bottom
     * edge so it never clips. The value 1 is exactly half the 2px empty descender-reserve the lineHeight box
     * adds below the caps, rounded to the grid, and it holds balanced at every element height the suite uses
     * (11/14/15/16/18) with descenders always fitting; see {@link GuiText#centeredTextY} for the per-height
     * worked gaps. Named here so the single correction is tunable in one place. */
    public static final int TEXT_OPTICAL_CENTER_NUDGE = 1;

    /** Primary heading / active colour: DMZ gold. */
    public static final int COLOR_TITLE = 0xFFF6E27A;
    /** Standard body / label text. */
    public static final int COLOR_LABEL = 0xFFCFE8B0;
    /** Muted / subtitle / placeholder text. */
    public static final int COLOR_MUTED = 0xFF9A9A9A;
    /** Value text inside fields and selected dropdown rows. */
    public static final int COLOR_VALUE = 0xFFF6E27A;
    /** Ordinary (unselected) list-row text. */
    public static final int COLOR_ROW = 0xFFE0E0E0;
    /** Disabled text. */
    public static final int COLOR_DISABLED = 0xFF808080;
    /** Panel-dark used for insets / borders drawn as fills. */
    public static final int COLOR_INSET = 0xFF10281A;
    /** Scroll thumb. */
    public static final int COLOR_THUMB = 0xFF6AB07A;
    /** Scroll thumb while dragging. */
    public static final int COLOR_THUMB_ACTIVE = 0xFF9BE0AB;
    /** Translucent white hover wash over a texture button/header. */
    public static final int COLOR_HOVER_WASH = 0x33FFFFFF;
    /** Translucent white wash laid over the ACTIVE tab's spliced dropdown body so it reads brighter than the
     *  inactive tabs without needing a second texture. Lighter than a full highlight so the tab art still shows
     *  through; paired with the active tab's gold underline for a clear selected state. */
    public static final int COLOR_TAB_ACTIVE_WASH = 0x40FFFFFF;

    // DragonMineZ's own menu sound events, referenced by ResourceLocation (not by a hard MainSounds field ref)
    // so a missing/renamed sound in a future DMZ degrades to silence rather than crashing (the codebase's
    // "DMZ integration degrades, never crashes" convention). Verified against DMZ's registered sound ids. The
    // GuiSounds helper looks these up once and null-guards the result. Mapping (see GuiSounds):
    //   UI_MENU_SWITCH -> a screen opening, a tab switch, a dropdown open/select (navigation)
    //   PIP_MENU       -> an ordinary button press
    //   CONFIRM_MENU   -> a commit (Save / Add / Select / Back-that-applies)
    /** DMZ sound id played on navigation (screen open, tab switch, dropdown open/select). */
    public static final ResourceLocation SND_UI_MENU_SWITCH = dmzSound("ui_menu_switch");
    /** DMZ sound id played on an ordinary (non-committing) button press. */
    public static final ResourceLocation SND_PIP_MENU = dmzSound("pip_menu");
    /** DMZ sound id played when an action is confirmed/committed (Save, Add, Select). */
    public static final ResourceLocation SND_CONFIRM_MENU = dmzSound("confirm_menu");
    /** Modest playback volume for UI sounds so a click never overpowers gameplay audio. */
    public static final float UI_SOUND_VOLUME = 0.35f;
    /** Playback pitch for UI sounds (1.0 = the sound's authored pitch). */
    public static final float UI_SOUND_PITCH = 1.0f;

    private static ResourceLocation dmzSound(String name) {
        return new ResourceLocation("dragonminez", name);
    }

    /** Z translation for overlay content (open dropdown lists, popups, tooltips) so it sits above the
     *  batched glyphs of ordinary widgets. Matches vanilla's tooltip elevation. */
    public static final int Z_OVERLAY = 400;

    // Top-left entity NAME anchors. A screen that edits a NAMED entity draws that name in the top left of the
    // panel (not centred under the logo, where it collided with the tab row). Three naming conventions grew up
    // separately in the module screens before those copies were collapsed into this one class; each is kept with
    // its own name and its own value so no module screen shifts by a pixel. New screens should prefer the
    // shuruisutilities set (NAME_LEFT_X / NAME_TOP_Y / NAME_LOGO_GAP), which fits the name into the clear band to
    // the left of the centred logo.
    /**
     * Top-left NAME anchor X (shuruisutilities convention): one grid {@link #UNIT} past the panel's rounded border
     * curve ({@link #PANEL_INSET}), so the name sits just inside the top-left corner with a clear band of panel
     * between it and the outline. Value is {@link #PANEL_INSET} (6) + {@link #UNIT} (2) = 8, spelled literally for
     * the same top-to-bottom static-initialization reason as {@link #SCROLLBAR_PANEL_INSET}.
     */
    public static final int NAME_LEFT_X = 8;
    /** Top-left NAME anchor Y (shuruisutilities convention): aligned with {@link #LOGO_TOP} so the name's cap band
     *  sits level with the top of the centred logo. */
    public static final int NAME_TOP_Y = LOGO_TOP;
    /** Clear gap kept between a fitted top-left name's right edge and the centred logo's left edge: one grid
     *  {@link #UNIT}, so a long name is shrunk/ellipsized rather than running under the wordmark. */
    public static final int NAME_LOGO_GAP = UNIT;
    /** Top-left NAME anchor X (raid-boss convention): flush to the content padding column {@link #CONTENT_PADDING}. */
    public static final int NAME_X = CONTENT_PADDING;
    /** Top-left NAME anchor Y (raid-boss convention): aligned with {@link #LOGO_TOP}. */
    public static final int NAME_Y = LOGO_TOP;
    /** Top-left NAME colour (raid-boss convention): DMZ gold, matching {@link #COLOR_TITLE}. */
    public static final int NAME_COLOR = 0xFFF6E27A;
    /** Top-left NAME anchor X (tournament convention): flush to the content padding column {@link #CONTENT_PADDING}. */
    public static final int NAME_TOP_LEFT_X = CONTENT_PADDING;
    /** Top-left NAME anchor Y (tournament convention): a fixed 7px inset from the panel top. */
    public static final int NAME_TOP_LEFT_Y = 7;
    /** Top-left NAME colour (tournament convention): DMZ gold, matching {@link #COLOR_TITLE}. */
    public static final int NAME_TOP_LEFT_COLOR = 0xFFF6E27A;

    /**
     * The two vertical states packed into {@link #BUTTONS}, in top-to-bottom order: the resting button and the
     * brighter lit one used on hover.
     *
     * <p>DISABLED deliberately points at the SAME strip as NORMAL. The art ships two states, not three, so a
     * disabled button is the resting strip under {@link #BUTTON_DISABLED_WASH}; keeping the enum constant means
     * no caller had to change, and it stays the single place to look if a drawn disabled variant ever arrives.
     */
    public enum ButtonState {
        /** Top strip: resting appearance. */
        NORMAL(0),
        /** Bottom strip: brighter, used on hover/focus. */
        HOVER(BUTTON_STATE_H),
        /** The resting strip again; {@link ThemeRender#button} washes it grey. */
        DISABLED(0);

        public final int v;

        ButtonState(int v) {
            this.v = v;
        }
    }
}
