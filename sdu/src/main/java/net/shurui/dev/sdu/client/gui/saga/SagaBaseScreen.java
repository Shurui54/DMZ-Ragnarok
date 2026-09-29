package net.shurui.dev.sdu.client.gui.saga;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.DmzDropdown;
import net.shurui.dev.sdu.client.gui.DmzTextureButton;
import net.shurui.dev.sdu.client.gui.DmzTextures;
import net.shurui.dev.sdu.client.gui.ScaledScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.client.gui.theme.GuiText;
import net.shurui.dev.sdu.client.gui.theme.LayoutValidator;
import net.shurui.dev.sdu.client.gui.theme.ThemeRender;
import net.shurui.dev.sdu.client.gui.theme.ThemedEditBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared base for the saga-editor screens: a DMZ-green scaled panel, gold title, a dropdown host
 * (open list rendered on top, mouse routed in virtual coords), and small helpers for buttons,
 * fields and labels. Subclasses build their widgets in {@link #init()} (after {@code super.init()}).
 */
public abstract class SagaBaseScreen extends ScaledScreen {

    protected record Lbl(String text, int x, int y, int color, boolean centered) {
    }

    /** A clickable tab-bar cell (virtual coords) that selects section {@code index} when pressed. */
    private record TabRegion(int x, int y, int w, int h, int index, java.util.function.IntConsumer onSelect) {
    }

    /**
     * A scrollable list region registered by a subclass in {@link #init()} via {@link #scrollList}.
     * Holds the geometry needed to route wheel/drag input and a callback to change the scroll offset.
     */
    private record ScrollHandle(int left, int right, int top, int listH, int rowH, int cap, int count,
                                int scroll, java.util.function.IntConsumer setter) {
    }

    /** A themed scrollbar (track + thumb) to blit during render, enqueued by {@link #scrollList} /
     *  {@link #finishScrollBand} so it uses the commissioned art instead of a flat {@code rect} fill. */
    // handle: the scrollHandles index this bar belongs to, so the renderer can ask whether THIS bar's thumb is
    // the one being dragged and draw its held state. The two lists are not index-aligned (a handle is always
    // registered, a bar only when the list overflows), so the link has to be carried rather than inferred.
    // NO_HANDLE marks a bar with no draggable thumb at all (the scroll BAND, which only the wheel moves). It is
    // deliberately not -1: that is the value draggingScroll rests at, so a -1 handle would read as "being
    // dragged" the whole time nothing was.
    private static final int NO_HANDLE = Integer.MIN_VALUE;

    private record ThemedBar(int x, int top, int trackH, int thumbY, int thumbH, int handle) {
    }

    /** A caption fitted (shrunk/ellipsized) into a bounding box; used for tab labels and any element caption
     *  that must never overflow its cell. Drawn horizontally centred in {@code [x, x+w]}. */
    private record Caption(String text, int x, int y, int w, int h, int color) {
    }

    /** A themed tab-header cell background to blit during render (spliced dropdown.png art, active/inactive),
     *  enqueued by {@link #tabs} so tabs use the commissioned sheet instead of flat {@code rect} fills. */
    private record TabBg(int x, int y, int w, int h, boolean active) {
    }

    protected final List<DmzDropdown> dropdowns = new ArrayList<>();
    protected final List<Lbl> labels = new ArrayList<>();
    protected final List<int[]> rects = new ArrayList<>();
    private final List<Caption> captions = new ArrayList<>();
    private final List<TabRegion> tabRegions = new ArrayList<>();
    private final List<TabBg> tabBgs = new ArrayList<>();
    private final List<ScrollHandle> scrollHandles = new ArrayList<>();
    private final List<ThemedBar> themedBars = new ArrayList<>();
    /** Optional gray subtitle drawn under the gold title (e.g. the category being edited). */
    protected String headerSubtitle = null;
    /** Index into {@link #scrollHandles} whose scrollbar thumb is being dragged, or -1. */
    private int draggingScroll = -1;
    protected DmzDropdown openDropdown;
    protected final Screen parent;
    /** One-shot guard so the dev layout validator logs at most once per (re)build, not every frame. */
    private boolean validatedThisBuild;
    /**
     * True once this screen instance has played its open sound. Minecraft re-runs {@link #init()} on every window
     * resize (and this addon re-runs it on {@code rebuildWidgets()}), so keying the open sound off {@code init()}
     * naively would replay it. This flag is set on the FIRST init only and is deliberately NOT reset there, so the
     * sound fires exactly once per screen open, not per resize or rebuild.
     */
    private boolean openSoundPlayed;

    protected int bandTop = Integer.MIN_VALUE;
    protected int bandBottom = Integer.MAX_VALUE;
    private int contentLabelStart = Integer.MAX_VALUE;
    private int contentRectStart = Integer.MAX_VALUE;
    private int contentDropdownStart = Integer.MAX_VALUE;
    private int contentTipStart = Integer.MAX_VALUE;
    private int contentCaptionStart = Integer.MAX_VALUE;
    /** Vertical scroll of the content band and its max (computed by {@link #finishScrollBand}). Shared by
     *  every saga screen that wraps a tall section in a band. Zero (and no scrollbar) when content fits. */
    protected int scroll;
    protected int maxScroll;

    /** Mark everything added from now on as scrollable "content" and set the visible band. */
    protected void beginScrollBand(int top, int bottom) {
        bandTop = top;
        bandBottom = bottom;
        contentLabelStart = labels.size();
        contentRectStart = rects.size();
        contentDropdownStart = dropdowns.size();
        contentTipStart = tips.size();
        contentCaptionStart = captions.size();
    }

    /** True if virtual-y {@code y} is within the visible scroll band (always true when no band is set). */
    protected boolean inBand(int y) {
        return y >= bandTop && y <= bandBottom;
    }

    /**
     * Close a scroll band opened with {@link #beginScrollBand}: compute {@link #maxScroll} from how far the
     * running {@code contentBottom} cursor overran the band bottom, clamp {@link #scroll}, hide the content
     * widgets that scrolled out of view, and draw a scrollbar thumb on the right edge when there's overflow.
     *
     * <p>Callers lay their band content between {@code beginScrollBand} and this, starting the row cursor at
     * {@code contentTop - scroll}, then pass the same {@code contentTop} here plus the total content height
     * expressed as the cursor's final virtual Y. To make the thumb math work, {@code contentBottomCursor}
     * must be the running cursor value AFTER laying out the section (i.e. {@code rowY}/{@code y}); this method
     * derives content height as {@code (contentBottomCursor + scroll) - contentTop}.
     *
     * @param contentTop          top edge of the visible band (virtual coords)
     * @param contentBottom       bottom edge of the visible band (virtual coords)
     * @param contentBottomCursor the running layout cursor after the section was built (start was
     *                            {@code contentTop - scroll})
     */
    protected void finishScrollBand(int contentTop, int contentBottom, int contentBottomCursor) {
        maxScroll = Math.max(0, (contentBottomCursor + scroll) - contentBottom);
        if (scroll > maxScroll) {
            scroll = maxScroll;
        }
        if (scroll < 0) {
            scroll = 0;
        }
        clampContentWidgets(contentTop, contentBottom);
        if (maxScroll > 0) {
            // Commissioned scrollbar art (track + thumb) down the right edge of the band; the wheel moves it.
            // Thumb math mirrors scrollList: height proportional to visible fraction, position to scroll.
            // Constrain the bar's right edge to the panel's inner content area (SCROLLBAR_PANEL_INSET from the
            // panel edge) so the track can never sit on or cross the panel border. The band's top/bottom already
            // sit below the header / above the footer, so the vertical extent stays inside the outline too.
            int barX = scrollbarColumnX(bandRight());
            int bandH = contentBottom - contentTop;
            int thumbH = Math.max(8, (int) ((long) bandH * bandH / (bandH + maxScroll)));
            // Same clamp as scrollList: the thumb stays fully inside the track at both extremes.
            int thumbY = GuiTheme.scrollThumbY(contentTop, bandH, thumbH, scroll, maxScroll);
            themedBars.add(new ThemedBar(barX, contentTop, bandH, thumbY, thumbH, NO_HANDLE));
        }
    }

    /**
     * X of the scroll-band scrollbar's right edge. Defaults to the panel's inner right; a screen with a
     * reserved column (e.g. a preview) overrides it so the thumb sits at the content column's edge.
     */
    protected int bandRight() {
        return uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET;
    }

    /**
     * Left X at which a scrollbar whose desired right edge is {@code desiredRight} should be drawn, clamped so
     * the whole {@link GuiTheme#SCROLLBAR_WIDTH}-wide bar stays inside the panel's inner content area: it never
     * crosses the right border ({@link GuiTheme#SCROLLBAR_PANEL_INSET} from the panel edge) and never sits left
     * of that inset either. Shared by {@link #scrollList} and {@link #finishScrollBand} so every screen's
     * scrollbar is constrained the same way.
     */
    protected int scrollbarColumnX(int desiredRight) {
        int maxRight = uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET;
        int right = Math.min(desiredRight, maxRight);
        return right - GuiTheme.SCROLLBAR_WIDTH;
    }

    /** True while any content band on this screen shows a scrollbar (overflowing). Screens reserve the
     *  scrollbar column in their field/dropdown widths only when this is set (see the reserved-width rule). */
    protected boolean hasBandScrollbar() {
        return maxScroll > 0;
    }

    /** Hide the content widgets (text boxes, buttons) that scrolled outside the visible band. */
    protected void clampContentWidgets(int top, int bottom) {
        for (net.minecraft.client.gui.components.events.GuiEventListener child : children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget w) {
                boolean visible = w.getY() >= top && w.getY() <= bottom;
                w.visible = visible;
                w.active = visible;
            }
        }
    }

    /**
     * Hook so a band-using subclass can flush in-progress EditBox text into its model before the band
     * rebuilds on a wheel scroll (otherwise typed-but-uncommitted text is lost). Default: no-op.
     */
    protected void applyBeforeBandScroll() {
    }

    /** Pixels the content band moves per wheel notch. Roughly two rows; overridable by row-based screens. */
    protected int bandStep() {
        return 24;
    }

    private boolean labelHidden(int i, int y) {
        return i >= contentLabelStart && !inBand(y);
    }

    private boolean rectHidden(int i, int y) {
        return i >= contentRectStart && !inBand(y);
    }

    private boolean dropdownHidden(int i, int y) {
        return i >= contentDropdownStart && !inBand(y);
    }

    private boolean captionHidden(int i, int y) {
        return i >= contentCaptionStart && !inBand(y);
    }

    /** A hover-help region (virtual coords) with wrapped help text drawn as a tooltip. */
    private record Tip(int x, int y, int w, int h, String text) {
    }

    private final List<Tip> tips = new ArrayList<>();

    protected SagaBaseScreen(Component title, int w, int h, Screen parent) {
        super(title, w, h);
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        // Screen-open sound, exactly once per open. init() re-runs on resize and rebuildWidgets(); the flag is set
        // here on the first pass and never cleared, so a resize or rebuild does not replay it.
        if (!openSoundPlayed) {
            openSoundPlayed = true;
            net.shurui.dev.sdu.client.gui.theme.GuiSounds.navigate();
        }
        dropdowns.clear();
        labels.clear();
        rects.clear();
        captions.clear();
        tabRegions.clear();
        tabBgs.clear();
        scrollHandles.clear();
        themedBars.clear();
        tips.clear();
        openDropdown = null;
        validatedThisBuild = false;
        bandTop = Integer.MIN_VALUE;
        bandBottom = Integer.MAX_VALUE;
        contentLabelStart = Integer.MAX_VALUE;
        contentRectStart = Integer.MAX_VALUE;
        contentDropdownStart = Integer.MAX_VALUE;
        contentTipStart = Integer.MAX_VALUE;
        contentCaptionStart = Integer.MAX_VALUE;
        // draggingScroll is intentionally NOT reset here: a scrollbar drag calls rebuildWidgets()
        // (which re-runs init) on every step, and the drag must survive that until mouseReleased.
    }

    protected void rect(int x, int y, int w, int h, int color) {
        rects.add(new int[]{x, clampBelowLogo(x, y, w), w, h, color});
    }

    /**
     * Register (and draw the scrollbar for) a vertically scrollable list. Call from {@link #init()}
     * after laying out the visible row window. {@code left..right} is the row area (used as the wheel
     * hit-box; the scrollbar is drawn just inside {@code right}). {@code count} is the total number of
     * items, {@code cap} how many fit at once, {@code scroll} the current top index, and {@code setter}
     * receives the new scroll offset (typically {@code v -> { this.scroll = v; rebuildWidgets(); }}).
     */
    protected void scrollList(int left, int right, int top, int rowH, int cap, int count,
                              int scroll, java.util.function.IntConsumer setter) {
        int listH = cap * rowH;
        if (count > cap) {
            // Commissioned scrollbar art. Drawn just inside the list's right edge at its native width, but
            // clamped to the panel's inner content area so it never crosses the border.
            int barX = scrollbarColumnX(right);
            int maxScroll = count - cap;
            int thumbH = Math.max(8, listH * cap / count);
            // Clamp the thumb fully inside the track (never overshooting its end caps); the drag hit-test in
            // dragScrollTo reads back the same clamped geometry so it can't desync from the drawn thumb.
            int thumbY = GuiTheme.scrollThumbY(top, listH, thumbH, scroll, maxScroll);
            themedBars.add(new ThemedBar(barX, top, listH, thumbY, thumbH, scrollHandles.size()));
        }
        scrollHandles.add(new ScrollHandle(left, right, top, listH, rowH, cap, count, scroll, setter));
    }

    private void dragScrollTo(ScrollHandle h, double vy) {
        int maxScroll = h.count - h.cap;
        if (maxScroll <= 0) {
            return;
        }
        int thumbH = Math.max(8, h.listH * h.cap / h.count);
        // Invert the SAME clamped geometry the thumb is drawn with (GuiTheme.scrollThumbY): the thumb centre
        // travels between bandTop + thumbH/2 and bandBottom - thumbH/2, where the band is inset by
        // SCROLLBAR_THUMB_INSET from each track end. Using the identical band here keeps the drag from
        // desyncing from the drawn thumb.
        double bandTop = h.top + GuiTheme.SCROLLBAR_THUMB_INSET;
        double bandBottom = h.top + h.listH - GuiTheme.SCROLLBAR_THUMB_INSET;
        double travel = (bandBottom - bandTop) - thumbH;
        double t = travel <= 0 ? 0 : (vy - bandTop - thumbH / 2.0) / travel;
        int ns = (int) Math.round(Math.max(0.0, Math.min(1.0, t)) * maxScroll);
        if (ns != h.scroll) {
            h.setter.accept(ns);
        }
    }

    protected void back() {
        this.minecraft.setScreen(parent);
    }

    protected DmzTextureButton btn(int x, int y, int w, int h, Component label, Runnable onPress) {
        return addRenderableWidget(new DmzTextureButton(x, clampBelowLogo(x, y, w), w, h, label, DmzTextures.MENU_BIG,
                DmzTextures.BUTTON_U, DmzTextures.BUTTON_V, DmzTextures.BUTTON_W, DmzTextures.BUTTON_H,
                DmzTextures.ATLAS, onPress));
    }

    /** A committing button (Save / Add / New / paste / Select): identical to {@link #btn} but its press plays
     *  DMZ's confirm sound instead of the ordinary click. */
    protected DmzTextureButton commitBtn(int x, int y, int w, int h, Component label, Runnable onPress) {
        return btn(x, y, w, h, label, onPress).commits();
    }

    /**
     * The Y (top edge) for this screen's footer action row (Save/Back/Menu/Close), derived from the runtime canvas
     * height so a {@link GuiTheme#BUTTON_HEIGHT}-tall footer button's bottom lands clear of the panel's bottom
     * border (see {@link GuiTheme#footerY}). Screens use this instead of a hand-picked {@code UI_H - N} so no footer
     * ever sits on the border. Footer buttons must be built at {@link #footerBtnHeight()} tall for the arithmetic
     * to hold.
     */
    protected int footerY() {
        return GuiTheme.footerY(uiHeight);
    }

    /** The one height footer buttons are built at, so the drawn bottom matches {@link #footerY}. */
    /**
     * Left edge of a full-width content row, mirroring the scrollbar's reserved column on the other side.
     *
     * <p>A row built between this and {@link #rowControlRight()} is CENTRED on the panel and still clear of the
     * scrollbar, so it neither drifts left (which is what a literal x did once the canvas was widened) nor slides
     * sideways when a list starts overflowing and the bar appears.
     */
    protected int rowBandLeft() {
        return uiWidth - rowControlRight();
    }

    /** Width of that centred, scrollbar-clear row band. */
    protected int rowBandWidth() {
        return Math.max(1, rowControlRight() - rowBandLeft());
    }

    /**
     * How many {@code rowH}-tall rows fit between {@code listTop} and the start of the footer band.
     *
     * <p>Use this instead of a hardcoded row cap. Every list in the suite was written against whatever height its
     * screen happened to be before they were unified, so a fixed cap now stops the list part way down the panel and
     * leaves the rest of it empty while the scrollbar insists there is more (the Groups list showing 9 of 14 with
     * a third of the panel blank). Deriving the cap means a list fills the space it is given, and keeps doing so if
     * the shared canvas is ever resized again.
     *
     * <p>Always at least 1, so a screen whose list starts unusually low still renders a row rather than nothing.
     */
    protected int rowsThatFit(int listTop, int rowH) {
        return rowsThatFit(listTop, rowH, 0);
    }

    /**
     * As {@link #rowsThatFit(int, int)}, but holding {@code reserveBelow} px clear underneath the list.
     *
     * <p>Pass this whenever the screen draws anything BELOW its list: an add-row, a name field, a total. Without it
     * the list is told it may run all the way to the footer and grows straight over that content, which is a worse
     * fault than the short list this replaces, because the widgets underneath end up unreachable rather than merely
     * badly placed.
     */
    protected int rowsThatFit(int listTop, int rowH, int reserveBelow) {
        if (rowH <= 0) {
            return 1;
        }
        return Math.max(1, (GuiTheme.contentBottom(uiHeight) - Math.max(0, reserveBelow) - listTop) / rowH);
    }

    protected int footerBtnHeight() {
        return GuiTheme.BUTTON_HEIGHT;
    }

    /** One on/off flag toggle for {@link #flagRow}: its label, current state, the toggle action, and hover help. */
    protected record Flag(String label, boolean on, Runnable toggle, String tooltip) {
    }

    /**
     * Lay out a row of on/off flag toggles (the quest editor's {@code [x] Party [ ] Secret [ ] Parallel
     * [ ] Branch} strip, the side-quest editor's three-flag strip) evenly across {@code [left, rowControlRight()]}
     * so the WHOLE row stays inside the reserved content column and the last flag never runs under the scrollbar
     * (the "flag row clips the scrollbar" defect). Previously each screen hand-placed the flags at fixed x/width
     * and the trailing one overran into the scrollbar column; this shares the same {@link #rowControlRight()}
     * reservation the fields, dropdowns and trailing row controls already use, and divides the available width
     * equally between the flags with a {@link GuiTheme#FLAG_ROW_GAP} between each. The labels shrink-to-fit inside
     * their pills (DmzTextureButton captions are fitted then ellipsized), so a narrower cell rewraps the text
     * rather than overflowing. Each flag's toggle keeps the screen's own apply()+rebuild behaviour, and its hover
     * help is registered over the same cell.
     *
     * @param left  left edge of the row (virtual coords)
     * @param y     top of the row (virtual coords)
     * @param rowH  row height (each flag button is this tall)
     * @param flags the flags to place, left to right
     */
    protected void flagRow(int left, int y, int rowH, Flag... flags) {
        int n = flags.length;
        if (n <= 0) {
            return;
        }
        // Right edge is the shared reserved column, exactly as trailing row controls use, so the row can never
        // reach into the scrollbar. Width is divided equally, minus one FLAG_ROW_GAP between adjacent cells.
        int right = rowControlRight();
        int totalGap = GuiTheme.FLAG_ROW_GAP * (n - 1);
        int cellW = Math.max(1, (right - left - totalGap) / n);
        for (int i = 0; i < n; i++) {
            Flag f = flags[i];
            int x = left + i * (cellW + GuiTheme.FLAG_ROW_GAP);
            // Last cell absorbs any integer-division remainder so the row's right edge lands exactly on `right`.
            int w = (i == n - 1) ? Math.max(1, right - x) : cellW;
            btn(x, y, w, rowH, flagLabel(f.label(), f.on()), f.toggle());
            if (f.tooltip() != null) {
                tooltip(x, y, w, rowH, f.tooltip());
            }
        }
    }

    /** The shared flag caption: a green {@code [x]} when on, a gray {@code [ ]} when off, then the name. Kept in
     *  one place so the quest and side-quest flag rows format identically. */
    protected static Component flagLabel(String name, boolean on) {
        return Component.literal((on ? "§a[x] " : "§7[ ] ") + name);
    }

    /**
     * The X of the rightmost column a TRAILING row control (a list row's delete/arrow icon button) may occupy its
     * RIGHT edge at, so it never sits under the scrollbar. This is the identical reservation the field/dropdown
     * width rule uses ({@link #reserveScrollbar}): the scrollbar column is
     * {@code [uiWidth - SCROLLBAR_PANEL_INSET - SCROLLBAR_WIDTH, uiWidth - SCROLLBAR_PANEL_INSET)}, and a row
     * control may reach up to one grid {@link GuiTheme#UNIT} left of that column's start. Every list uses this so
     * buttons and fields stop at the same place (the "X buttons sit under the scrollbar" defect), and it applies
     * whether the screen shows the bar or not (reserving the column unconditionally keeps rows aligned and never
     * lets a control drift under a bar that appears once the list overflows).
     */
    protected int rowControlRight() {
        return uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET - GuiTheme.SCROLLBAR_WIDTH - GuiTheme.UNIT;
    }

    /** The standard on-screen size of a small icon button ({@link GuiTheme#ICON_BUTTON_SIZE}); exposed so
     *  screens can size hover-help regions to their icon buttons without importing {@link GuiTheme}. */
    protected int iconSize() {
        return GuiTheme.ICON_BUTTON_SIZE;
    }

    /**
     * Add a small circular ICON button (a row delete "X" or an up/down move arrow) at the STANDARD icon size
     * ({@link GuiTheme#ICON_BUTTON_SIZE}), right-aligned so its right edge lands on {@code right} (typically
     * {@link #rowControlRight()} for the trailing delete, or a fixed inner column for the arrows). Flags the
     * button as an icon so it always draws the {@code circle_button.png} disc regardless of width, and vertically
     * centres it in the {@code rowH}-tall row. Shared by every list so all delete/arrow controls are one size and
     * one shape. Returns the button so a caller can disable it (greyed arrow at the list ends).
     */
    protected DmzTextureButton iconBtnRight(int right, int rowY, int rowH, Component label, Runnable onPress) {
        int size = iconButtonSize(rowH);
        int x = right - size;
        int y = rowY + (rowH - size) / 2;
        return btn(x, y, size, size, label, onPress).asIcon();
    }

    /**
     * The on-screen size of an icon button on a {@code rowH}-tall row. It is the standard
     * {@link GuiTheme#ICON_BUTTON_SIZE} (so every X/arrow is one uniform size), shrunk to fit a short row but
     * NEVER below {@link GuiTheme#ICON_BUTTON_MIN_SIZE}: below that floor the two mirrored rounded caps meet with
     * no interior and the glyph has nowhere to sit, so on a very short row the pill is allowed to grow one or two
     * px past the row band (centred, so it overhangs equally top and bottom) rather than collapse to a sliver.
     * This is the "increase its size to a sensible uniform minimum" rule.
     */
    private int iconButtonSize(int rowH) {
        // clamp(rowH - gap, MIN, STANDARD): aim for the row height LESS one ROW_BUTTON_GAP so a stacked column of
        // icon buttons (the per-row delete X) leaves a visible band of panel between each pill instead of forming
        // one flush column (the "no space between the X buttons" defect). The gap is trimmed off the SIZE and the
        // pill is centred in the row, so it appears as an equal band above and below every icon button, and the
        // same shrink opens a matching gap between a horizontally adjacent pair (the up/down arrows placed one
        // ICON_BUTTON_SIZE apart). Capped at the one standard size and floored at ICON_BUTTON_MIN_SIZE (below which
        // the round pill has no interior), so a short row grows the pill rather than collapsing it to a sliver.
        return Math.max(GuiTheme.ICON_BUTTON_MIN_SIZE,
                Math.min(rowH - GuiTheme.ROW_BUTTON_GAP, GuiTheme.ICON_BUTTON_SIZE));
    }

    /**
     * Add a small circular ICON button whose LEFT edge is at {@code left} (used for the up/down move arrows, which
     * are placed as a fixed inner pair rather than right-aligned). Same standard size, disc treatment and row
     * centring as {@link #iconBtnRight}.
     */
    protected DmzTextureButton iconBtnAt(int left, int rowY, int rowH, Component label, Runnable onPress) {
        int size = iconButtonSize(rowH);
        int y = rowY + (rowH - size) / 2;
        return btn(left, y, size, size, label, onPress).asIcon();
    }

    /**
     * Clamp a per-screen first-content Y up to {@link GuiTheme#CONTENT_TOP} so content never begins inside the
     * header band (the "logo overlaps the first field" defect). Screens pass the Y they want their first row at;
     * this returns the larger of that and the header floor, so a screen can never start content above the logo.
     */
    protected int contentTop(int desiredY) {
        return Math.max(GuiTheme.CONTENT_TOP, desiredY);
    }

    /**
     * Unavoidable logo-overlap guard applied INSIDE every shared placement helper ({@link #label}, {@link #rect},
     * {@link #field}, {@link #dropdown}, {@link #btn}, {@link #tooltip}), so a screen physically cannot draw an
     * element on top of the header logo even if it hand-places a raw Y in the header band (the reviewer's
     * {@code FormGroupEditScreen} "Group" field still sitting under the logo, because {@link #contentTop} was
     * opt-in and that screen never called it).
     *
     * <p>It is deliberately NARROW so it only moves what actually collides: an element is pushed down to
     * {@link GuiTheme#CONTENT_TOP} (just below the logo) ONLY when its Y is above {@link GuiTheme#LOGO_BOTTOM} AND
     * its horizontal span {@code [x, x+w)} crosses the centred logo's band
     * ({@code [centre - LOGO_HALF_WIDTH, centre + LOGO_HALF_WIDTH]}). Content BESIDE the logo (a header count
     * label at the left, a side action button at the right) does not cross that band and is left exactly where the
     * screen put it, and footer/body content (Y at or below the logo) is never touched. The logo keeps its size
     * and position; only colliding content moves down, matching the reviewer's "there is more than enough room to
     * move it down".
     *
     * @param x element left (virtual coords)
     * @param y element top    (virtual coords)
     * @param w element width  (virtual px); pass a small positive value for point-anchored labels
     * @return {@code y}, or {@link GuiTheme#CONTENT_TOP} when the element would otherwise overlap the logo
     */
    protected int clampBelowLogo(int x, int y, int w) {
        if (y >= GuiTheme.LOGO_BOTTOM) {
            return y; // at or below the logo already: nothing to do.
        }
        int centre = uiWidth / 2;
        int bandLeft = centre - GuiTheme.LOGO_HALF_WIDTH;
        int bandRight = centre + GuiTheme.LOGO_HALF_WIDTH;
        boolean crossesLogo = x < bandRight && (x + Math.max(1, w)) > bandLeft;
        return crossesLogo ? GuiTheme.CONTENT_TOP : y;
    }

    /** Compact text input (~60% of the old height) so more fits without shrinking the whole panel. */
    protected EditBox field(int x, int y, int w, String value) {
        return field(x, y, w, value, 512);
    }

    /**
     * Same as {@link #field(int, int, int, String)} but with an explicit character cap. Most fields are fine at
     * the 512 default, but some values (e.g. a DMZ hair code, which can run to several thousand characters) must
     * not be truncated - the default limit silently cut long codes down to 512, corrupting them.
     */
    protected EditBox field(int x, int y, int w, String value, int maxLength) {
        // ThemedEditBox is a drop-in EditBox subclass that only reskins the background; every caret,
        // selection, scrolling and keyboard behaviour is delegated to vanilla. Returning the EditBox
        // supertype keeps all 35 screens (which store the result as EditBox) compiling untouched.
        EditBox b = new ThemedEditBox(this.font, x, clampBelowLogo(x, y, w), reserveScrollbar(x, w), 10, Component.empty());
        b.setMaxLength(maxLength);
        b.setValue(value == null ? "" : value);
        return addRenderableWidget(b);
    }

    protected DmzDropdown dropdown(int x, int y, int w, List<Component> opts, int idx) {
        DmzDropdown d = new DmzDropdown(x, clampBelowLogo(x, y, w), reserveScrollbar(x, w), 11, opts, idx);
        dropdowns.add(d);
        return d;
    }

    /**
     * Reserved-width rule (defect: fields/dropdowns ran under the scrollbar). When this screen carries a
     * scroll band (so a scrollbar may appear down the right edge), trim a widget starting at {@code x} so its
     * right edge stops at {@code scrollbar column left - UNIT} and never enters the scrollbar's column. The
     * reserved band is {@link GuiTheme#SCROLLBAR_RESERVE} (bar width + one grid gap) inside the panel content
     * inset, so a field can never be drawn under, or flush against, the scrollbar. Applied centrally in
     * {@link #field} and {@link #dropdown} so no per-screen width has to change. When no band is set the width
     * is returned unchanged, so non-scrolling screens keep their existing full-width fields.
     */
    protected int reserveScrollbar(int x, int w) {
        boolean bandDeclared = bandBottom != Integer.MAX_VALUE;
        if (!bandDeclared) {
            return w;
        }
        // Right edge the scrollbar column occupies, measured from the panel edge: the bar sits at
        // [uiWidth - SCROLLBAR_PANEL_INSET - SCROLLBAR_WIDTH, uiWidth - SCROLLBAR_PANEL_INSET). A widget may
        // reach up to one grid UNIT left of that column start.
        int columnLeft = uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET - GuiTheme.SCROLLBAR_WIDTH;
        int maxRight = columnLeft - GuiTheme.UNIT;
        if (x + w > maxRight) {
            return Math.max(1, maxRight - x);
        }
        return w;
    }

    protected void label(String text, int x, int y) {
        // A left-anchored label crosses the logo band only over its own text width; clamp it below the logo when
        // it would otherwise render under the wordmark (the header count/first-field labels that sat under it).
        int w = this.font == null || text == null ? 1 : this.font.width(text);
        labels.add(new Lbl(text, x, clampBelowLogo(x, y, w), 0xFFCFE8B0, false));
    }

    /**
     * Resolve a translation key to text in the player's language (with optional %s args). Uses
     * {@code Component.translatable(...).getString()} rather than String.format, so a literal {@code %}
     * in help text (e.g. "gain %") is safe and doesn't need escaping. Our own UI text goes through this.
     */
    protected static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    /** Resolve an array of translation keys (e.g. tab/section labels) to the player's language. */
    protected static String[] trAll(String[] keys) {
        String[] out = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            out[i] = Component.translatable(keys[i]).getString();
        }
        return out;
    }

    /** A label centred horizontally on {@code cx} (used for titles/subtitles and tab captions). */
    protected void labelCentered(String text, int cx, int y, int color) {
        labels.add(new Lbl(text, cx, y, color, true));
    }

    /**
     * Draw a horizontal tab bar of {@code sections} inside {@code x,y,w}, highlighting {@code active} in
     * gold and invoking {@code onSelect} with the tab index on click. Tabs wrap onto extra rows when they
     * don't all fit; returns the Y just below the bar so callers can start their content there.
     */
    protected int tabs(int x, int y, int w, String[] sections, int active, java.util.function.IntConsumer onSelect) {
        int n = sections.length;
        int gap = GuiTheme.UNIT;
        int h = GuiTheme.BUTTON_HEIGHT;
        int rowGap = GuiTheme.UNIT;
        int perRow = Math.max(1, Math.min(n, (w + gap) / (44 + gap)));
        int rows = (n + perRow - 1) / perRow;
        int cellW = (w - gap * (perRow - 1)) / perRow;
        for (int i = 0; i < n; i++) {
            int row = i / perRow;
            int col = i % perRow;
            int cx = x + col * (cellW + gap);
            int cy = y + row * (h + rowGap);
            boolean on = i == active;
            // Tab backdrop is the commissioned dropdown sheet, SPLICED (1:1 bevel caps, tiled flat interior),
            // enqueued in the band-aware tabBgs list so scroll clipping still works. Active vs inactive is a
            // texture choice: the active tab uses the darker dropdown_alt.png body so it reads distinct from
            // the flat dropdown.png inactive tabs, and it additionally gets a gold underline. The caption is
            // fitted into the cell (theme text-fit) so long, e.g. Spanish, section names shrink/ellipsize
            // instead of spilling the cell.
            tabBgs.add(new TabBg(cx, cy, cellW, h, on));
            if (on) {
                rect(cx, cy + h - 2, cellW, 2, GuiTheme.COLOR_TITLE);
            }
            captions.add(new Caption(sections[i], cx, cy, cellW, h, on ? GuiTheme.COLOR_TITLE : GuiTheme.COLOR_ROW));
            tabRegions.add(new TabRegion(cx, cy, cellW, h, i, onSelect));
        }
        return y + rows * (h + rowGap);
    }

    protected static List<Component> options(String[] values) {
        List<Component> list = new ArrayList<>();
        for (String v : values) {
            list.add(Component.literal(v));
        }
        return list;
    }

    /** Called when a dropdown row is chosen; override to react (e.g. rebuild type-specific fields). */
    protected void onDropdownSelect(DmzDropdown dropdown, int row) {
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        double vx = toVirtualX(mx), vy = toVirtualY(my);
        if (openDropdown != null) {
            if (openDropdown.scrollbarAt(vx, vy)) {
                openDropdown.beginDrag(vy);
                return true;
            }
            int row = openDropdown.rowAt(vx, vy);
            if (row >= 0) {
                if (openDropdown.isMultiSelect()) {
                    openDropdown.toggle(row);       // stay open so several can be checked
                    net.shurui.dev.sdu.client.gui.theme.GuiSounds.confirm(); // toggling a choice is a commit
                    onDropdownSelect(openDropdown, row);
                    return true;
                }
                DmzDropdown chosen = openDropdown;
                chosen.select(row);
                openDropdown = null;
                // Picking a value commits it (confirm sound), UNLESS this dropdown opens a screen asynchronously
                // (e.g. the hub's editor list sends an open-request packet). For those the single sound is the
                // arriving screen's own navigate(); a cross-tick pair cannot be coalesced, so we stay silent here.
                if (!chosen.isOpensScreen()) {
                    net.shurui.dev.sdu.client.gui.theme.GuiSounds.confirm();
                }
                onDropdownSelect(chosen, row);
                return true;
            }
            boolean onHeader = openDropdown.headerContains(vx, vy);
            openDropdown = null;
            if (onHeader) {
                return true;
            }
        }
        for (int i = 0; i < dropdowns.size(); i++) {
            DmzDropdown d = dropdowns.get(i);
            if (!dropdownHidden(i, d.getY()) && d.headerContains(vx, vy)) {
                d.resetSearch();
                openDropdown = d;
                net.shurui.dev.sdu.client.gui.theme.GuiSounds.navigate(); // opening a dropdown list
                return true;
            }
        }
        for (TabRegion t : tabRegions) {
            if (vx >= t.x && vx < t.x + t.w && vy >= t.y && vy < t.y + t.h) {
                net.shurui.dev.sdu.client.gui.theme.GuiSounds.navigate(); // tab switch
                t.onSelect.accept(t.index);
                return true;
            }
        }
        // Grab a list scrollbar thumb?
        for (int i = 0; i < scrollHandles.size(); i++) {
            ScrollHandle h = scrollHandles.get(i);
            int barX = scrollbarColumnX(h.right);
            int barRight = barX + GuiTheme.SCROLLBAR_WIDTH;
            if (h.count > h.cap && vx >= barX - 1 && vx < barRight && vy >= h.top && vy < h.top + h.listH) {
                draggingScroll = i;
                dragScrollTo(h, vy);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (openDropdown != null && openDropdown.isDragging()) {
            openDropdown.dragTo(toVirtualY(my));
            return true;
        }
        if (draggingScroll >= 0 && draggingScroll < scrollHandles.size()) {
            dragScrollTo(scrollHandles.get(draggingScroll), toVirtualY(my));
            return true;
        }
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (openDropdown != null && openDropdown.isDragging()) {
            openDropdown.endDrag();
            return true;
        }
        if (draggingScroll >= 0) {
            draggingScroll = -1;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (openDropdown != null && openDropdown.isSearchable()) {
            openDropdown.charTyped(c);
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (openDropdown != null) {
            if (keyCode == 256 /* ESC */) {
                openDropdown = null;
                return true;
            }
            if (openDropdown.keyPressed(keyCode)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (openDropdown != null) {
            openDropdown.scroll(delta);
            return true;
        }
        double vx = toVirtualX(mx), vy = toVirtualY(my);
        for (ScrollHandle h : scrollHandles) {
            if (h.count > h.cap && vx >= h.left && vx < h.right && vy >= h.top && vy < h.top + h.listH) {
                int max = h.count - h.cap;
                int ns = Math.max(0, Math.min(max, h.scroll - (int) Math.signum(delta)));
                if (ns != h.scroll) {
                    h.setter.accept(ns);
                }
                return true;
            }
        }
        // Fallback: scroll the content band (only when nothing above claimed the wheel). No open dropdown,
        // no list scrollbar under the cursor, and the cursor is inside a band that actually overflows.
        if (openDropdown == null && maxScroll > 0 && inBand((int) Math.round(vy))) {
            applyBeforeBandScroll(); // keep in-progress EditBox text before the rebuild
            int ns = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(delta) * bandStep()));
            if (ns != scroll) {
                scroll = ns;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(originX(), originY(), 0);
        pose.scale((float) guiScale, (float) guiScale, 1.0f);

        int vmx = (int) Math.round(toVirtualX(mouseX));
        int vmy = (int) Math.round(toVirtualY(mouseY));

        ThemeRender.panel(g, 0, 0, uiWidth, uiHeight);
        // Standard header: the umbrella "Shurui's DMZ Essentials" logo centred at the top, at one fixed size
        // on every screen. The per-screen caption line that used to sit under the logo (rendering e.g.
        // "Editor Menu") was removed per the reviewer's struck-through annotation; headerSubtitle is still
        // accepted by subclasses (kept for a possible future placement) but no longer drawn here.
        ThemeRender.header(g, 0, 0, uiWidth);

        // Themed tab-header backgrounds (spliced dropdown.png; active tab gets a brightness wash). Drawn first
        // so the gold underline rects and the fitted tab captions sit on top. Tabs are added in the header
        // (before any beginScrollBand), so they are never part of a content band and need no clip check.
        for (TabBg t : tabBgs) {
            ThemeRender.tab(g, t.x(), t.y(), t.w(), t.h(), t.active());
        }

        for (int i = 0; i < rects.size(); i++) {
            int[] r = rects.get(i);
            if (rectHidden(i, r[1])) {
                continue;
            }
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], r[4]);
        }
        for (int i = 0; i < labels.size(); i++) {
            Lbl l = labels.get(i);
            if (labelHidden(i, l.y())) {
                continue;
            }
            if (l.centered()) {
                g.drawCenteredString(this.font, l.text, l.x, l.y, l.color);
            } else {
                // Reserve the width of any trailing control on this row: a left-aligned label is fitted into
                // the gap between its start and the leftmost widget (button/field/dropdown) that shares its
                // text line and sits to its right, so a row label never runs UNDER the Edit/Copy/Del buttons.
                // General rule applied to every list here, so no per-screen edit is needed.
                int right = labelRightBoundary(l.x, l.y);
                int avail = right - l.x;
                if (avail < font.width(l.text)) {
                    String clipped = GuiText.ellipsize(font, l.text, Math.max(0, avail));
                    g.drawString(this.font, clipped, l.x, l.y, l.color, false);
                } else {
                    g.drawString(this.font, l.text, l.x, l.y, l.color, false);
                }
            }
        }
        for (int i = 0; i < captions.size(); i++) {
            Caption c = captions.get(i);
            if (captionHidden(i, c.y())) {
                continue;
            }
            GuiText.drawFittedCentered(g, this.font, c.text(), c.x() + c.w() / 2, c.y(),
                    GuiText.innerWidth(c.w()), c.h(), c.color());
        }
        for (int i = 0; i < dropdowns.size(); i++) {
            DmzDropdown d = dropdowns.get(i);
            if (dropdownHidden(i, d.getY())) {
                continue;
            }
            d.renderHeader(g, this.font, vmx, vmy, d == openDropdown);
        }
        // Commissioned scrollbar art for registered lists / content bands (track + thumb), enqueued by
        // scrollList / finishScrollBand. Drawn as blits here rather than as flat rect fills.
        for (ThemedBar b : themedBars) {
            ThemeRender.scrollbar(g, b.x(), b.top(), b.trackH(), b.thumbY(), b.thumbH(),
                    draggingScroll == b.handle());
        }

        super.render(g, vmx, vmy, partialTick);

        // Dev-only layout check (off unless -Dsdu.gui.validate=true). Runs once per build: flag widgets that
        // overflow the content area or overlap each other, so the phase-2 sweep has actionable log lines.
        if (LayoutValidator.enabled() && !validatedThisBuild) {
            validatedThisBuild = true;
            // Validate against the panel's INNER rect (outer minus PANEL_INSET on every side) so any widget that
            // strays onto the border art band is flagged, including a footer button that overlaps the bottom border.
            LayoutValidator.validateInner(getClass(), children(), 0, 0, uiWidth, uiHeight);
        }

        // The open dropdown list and any popup overlay must sit above everything else, including the
        // batched glyphs of labels/widgets. Elevating them on the Z axis (like vanilla tooltips at z=400)
        // makes the GUI depth test keep them on top so their text stays readable.
        pose.pushPose();
        pose.translate(0, 0, 400);
        if (openDropdown != null) {
            openDropdown.renderList(g, this.font, vmx, vmy);
        }
        renderTopOverlay(g, vmx, vmy);
        pose.popPose();

        String hover = hoveredTip(vmx, vmy);
        pose.popPose();

        // Draw the tooltip in real screen space (unscaled) so it stays readable at 60% content scale.
        if (hover != null) {
            g.renderTooltip(this.font, this.font.split(Component.literal(hover), 170), mouseX, mouseY);
        }
    }

    /**
     * Right boundary a left-aligned label at {@code (lx, ly)} may extend to before it would collide with a
     * trailing control on its row. Returns the leftmost x of any active on-row widget (button/field/dropdown)
     * that starts to the right of the label and whose vertical extent overlaps the label's text line, minus a
     * small gap; the panel's inner-right edge otherwise. This is what keeps list-row labels out from under the
     * Edit/Copy/Del buttons without any screen having to reserve the width itself.
     */
    private int labelRightBoundary(int lx, int ly) {
        int lineTop = ly;
        int lineBot = ly + this.font.lineHeight;
        int gap = GuiTheme.UNIT;
        int right = uiWidth - GuiTheme.CONTENT_PADDING;
        for (net.minecraft.client.gui.components.events.GuiEventListener child : children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget w) {
                if (!w.visible || w.getX() <= lx) {
                    continue;
                }
                if (w.getY() < lineBot && w.getY() + w.getHeight() > lineTop) {
                    right = Math.min(right, w.getX() - gap);
                }
            }
        }
        for (DmzDropdown d : dropdowns) {
            if (d == openDropdown) {
                continue;
            }
            int dx = d.getX();
            if (dx <= lx) {
                continue;
            }
            int dy = d.getY();
            if (dy < lineBot && dy + GuiTheme.FIELD_HEIGHT > lineTop) {
                right = Math.min(right, dx - gap);
            }
        }
        return Math.max(lx, right);
    }

    /** Register a hover-help region (virtual coords). Clamped below the logo the same way as the widget it
     *  describes, so a moved first-row control keeps its hover region aligned with it. */
    protected void tooltip(int x, int y, int w, int h, String text) {
        tips.add(new Tip(x, clampBelowLogo(x, y, w), w, h, text));
    }

    private String hoveredTip(int vmx, int vmy) {
        if (openDropdown != null) {
            return null; // don't cover an open dropdown list
        }
        String found = null;
        for (int i = 0; i < tips.size(); i++) {
            Tip t = tips.get(i);
            if (i >= contentTipStart && !inBand(t.y())) {
                continue; // scrolled out of view
            }
            if (vmx >= t.x && vmx < t.x + t.w && vmy >= t.y && vmy < t.y + t.h) {
                found = t.text; // last match wins (later rows drawn on top)
            }
        }
        return found;
    }

    /** Hook for subclasses to draw a popup (e.g. a colour picker) on top, inside the scaled canvas. */
    protected void renderTopOverlay(GuiGraphics g, int vmx, int vmy) {
    }
}
