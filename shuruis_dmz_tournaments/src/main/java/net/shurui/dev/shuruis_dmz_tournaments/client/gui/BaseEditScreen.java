package net.shurui.dev.shuruis_dmz_tournaments.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_dmz_tournaments.client.gui.theme.GuiSounds;
import net.shurui.dev.shuruis_dmz_tournaments.client.gui.theme.GuiText;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_dmz_tournaments.client.gui.theme.LayoutValidator;
import net.shurui.dev.shuruis_dmz_tournaments.client.gui.theme.ThemeRender;
import net.shurui.dev.shuruis_dmz_tournaments.client.gui.theme.ThemedEditBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared base for the editor screens, wearing the ported "Shurui's DMZ" GUI theme (the same design system
 * as the sibling {@code sdu} mod): a spliced commissioned panel, a fixed-size logo header, a dropdown host
 * (open list rendered on top, mouse routed in virtual coords), themed buttons/fields/dropdowns/tabs and a
 * commissioned scrollbar. Subclasses build their widgets in {@link #init()} (after {@code super.init()}).
 */
public abstract class BaseEditScreen extends ScaledScreen {

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

    /** A themed scrollbar (track + thumb) to blit during render, enqueued by {@link #scrollList} so it uses
     *  the commissioned art instead of a flat {@code rect} fill. */
    private record ThemedBar(int x, int top, int trackH, int thumbY, int thumbH) {
    }

    /** A caption fitted (shrunk/ellipsized) into a bounding box; used for tab labels so they never overflow. */
    private record Caption(String text, int x, int y, int w, int h, int color) {
    }

    /** A themed tab-header cell background to blit during render (spliced dropdown.png art, active/inactive). */
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
    /**
     * Optional name of the NAMED entity this screen edits (a tournament, a reward category). When set it is drawn
     * in the panel's TOP-LEFT corner (see {@link GuiTheme#NAME_TOP_LEFT_X}), not centred under the logo where it
     * collided with the tabs. A generic screen leaves this null and draws no header label. Supports colour codes.
     */
    protected String topLeftName = null;
    /** Index into {@link #scrollHandles} whose scrollbar thumb is being dragged, or -1. */
    private int draggingScroll = -1;
    protected DmzDropdown openDropdown;
    protected final Screen parent;
    /** One-shot guard so the dev layout validator logs at most once per (re)build, not every frame. */
    private boolean validatedThisBuild;
    /**
     * True once this screen instance has played its open sound. Minecraft re-runs {@link #init()} on every window
     * resize (and this addon on {@code rebuildWidgets()}), so keying the sound off {@code init()} naively would
     * replay it. Set on the FIRST init only and deliberately NOT reset, so the sound fires once per open.
     */
    private boolean openSoundPlayed;

    /** A hover-help region (virtual coords) with wrapped help text drawn as a tooltip. */
    private record Tip(int x, int y, int w, int h, String text) {
    }

    private final List<Tip> tips = new ArrayList<>();

    // Ported from shuruisutilities' SagaBaseScreen.beginScrollBand and completed for real forms: the reference band
    // only CLIPPED the batched label/rect/dropdown lists, so it could never scroll a page of widgets. This version
    // also OFFSETS and clips the real widgets (buttons, edit boxes) added while the band is open, adds a wheel + a
    // draggable thumb, and draws the SAME commissioned scrollbar art {@link #scrollList} uses.
    //
    // Everything between {@link #beginScrollBand} and {@link #endScrollBand} is scrollable CONTENT: drawn shifted up
    // by {@link #bandScroll} and hidden when it falls outside [{@link #bandTop}, {@link #bandBottom}]. The footer
    // (added AFTER endScrollBand) is never part of the band, so Save/Back stay pinned. NB: dropdowns are only
    // CLIPPED, not pixel-offset, which is safe because bandScroll is 0 whenever a content dropdown exists. Keep it
    // that way: do not put a searchable dropdown in a section tall enough to overflow.
    protected int bandTop = Integer.MIN_VALUE;
    protected int bandBottom = Integer.MAX_VALUE;
    /** True only between {@link #beginScrollBand} and {@link #endScrollBand}: gates content capture + width reserve. */
    private boolean bandOpen;
    /** Current pixel scroll offset (0 = top), clamped to [0, {@link #bandMaxScroll}]. */
    private int bandScroll;
    /** Max pixel scroll, computed in {@link #endScrollBand} from the laid-out content height. */
    private int bandMaxScroll;
    /** The lowest {@code y + height} of any content added while the band was open (its natural, unscrolled Y). */
    private int bandContentBottom;
    /** Drawn track height and thumb height of the band scrollbar; computed once in {@link #endScrollBand}. */
    private int bandTrackH, bandThumbH;
    /** Callback that writes a new scroll offset back into the owning screen (typically {@code v -> { scroll = v; rebuildWidgets(); }}). */
    private java.util.function.IntConsumer bandSetter;
    /** True while the band's scrollbar thumb is being dragged. Like {@link #draggingScroll} it survives rebuilds. */
    private boolean draggingBand;
    // Index into each batched list at which content (added after beginScrollBand) starts. Items before these are
    // header chrome (tabs, top-left name row) and are never scrolled or clipped.
    private int contentLabelStart = Integer.MAX_VALUE;
    private int contentRectStart = Integer.MAX_VALUE;
    private int contentDropdownStart = Integer.MAX_VALUE;
    private int contentTipStart = Integer.MAX_VALUE;

    /** A real widget (button / edit box) captured as band content, with the natural (unscrolled) top Y it was
     *  laid out at. Rendered offset by {@link #bandScroll} and hidden when it falls outside the band. */
    private record BandWidget(net.minecraft.client.gui.components.AbstractWidget w, int baseY) {
    }

    private final List<BandWidget> bandWidgets = new ArrayList<>();

    protected BaseEditScreen(Component title, int w, int h, Screen parent) {
        super(title, w, h);
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        // screen-open sound, once per open (flag never cleared, so resize/rebuild does not replay it)
        if (!openSoundPlayed) {
            openSoundPlayed = true;
            GuiSounds.navigate();
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
        bandWidgets.clear();
        openDropdown = null;
        validatedThisBuild = false;
        // Reset the scroll band. beginScrollBand re-supplies bandScroll later in this same init pass, so clearing
        // here is just the default for screens that declare no band.
        bandTop = Integer.MIN_VALUE;
        bandBottom = Integer.MAX_VALUE;
        bandOpen = false;
        bandScroll = 0;
        bandMaxScroll = 0;
        bandContentBottom = 0;
        bandTrackH = 0;
        bandThumbH = 0;
        bandSetter = null;
        contentLabelStart = Integer.MAX_VALUE;
        contentRectStart = Integer.MAX_VALUE;
        contentDropdownStart = Integer.MAX_VALUE;
        contentTipStart = Integer.MAX_VALUE;
        // draggingScroll / draggingBand are intentionally NOT reset here: a scrollbar drag calls rebuildWidgets()
        // (which re-runs init) on every step, and the drag must survive that until mouseReleased.
    }

    /**
     * Mark everything added from here on as scrollable content, inside [{@code top}, {@code bottom}] (virtual Y).
     * {@code scroll} is the owning screen's current pixel offset; {@code setter} writes a new offset back (usually
     * {@code v -> { this.scroll = v; rebuildWidgets(); }}). Call after the header/tab bar and BEFORE the flowing
     * content; pair with {@link #endScrollBand}, then add the footer (which stays outside the band). For a tabbed
     * screen keep the offset PER TAB and reset it to 0 on a tab change, so switching never lands in empty space.
     */
    protected void beginScrollBand(int top, int bottom, int scroll, java.util.function.IntConsumer setter) {
        bandTop = top;
        bandBottom = bottom;
        bandOpen = true;
        bandScroll = Math.max(0, scroll);
        bandSetter = setter;
        bandContentBottom = top;
        contentLabelStart = labels.size();
        contentRectStart = rects.size();
        contentDropdownStart = dropdowns.size();
        contentTipStart = tips.size();
        bandWidgets.clear();
    }

    /**
     * Close the content band opened by {@link #beginScrollBand}: measure the laid-out content, clamp the scroll to
     * the reachable range and, when the content overflows the band, enqueue the commissioned scrollbar art. After
     * this returns, further widgets (the footer) are OUTSIDE the band and render/scroll normally.
     */
    protected void endScrollBand() {
        bandOpen = false;
        bandTrackH = Math.max(1, bandBottom - bandTop);
        int contentH = Math.max(0, bandContentBottom - bandTop);
        bandMaxScroll = Math.max(0, contentH - bandTrackH);
        // Clamp a stale-high offset (content shrank) for THIS render only; the owning screen re-clamps on the next
        // wheel/drag, so endScrollBand never re-enters rebuildWidgets.
        if (bandScroll > bandMaxScroll) {
            bandScroll = bandMaxScroll;
        }
        if (bandMaxScroll > 0) {
            bandThumbH = Math.max(8, (int) ((long) bandTrackH * bandTrackH / Math.max(1, contentH)));
            int thumbY = GuiTheme.scrollThumbY(bandTop, bandTrackH, bandThumbH, bandScroll, bandMaxScroll);
            themedBars.add(new ThemedBar(standardScrollbarX(), bandTop, bandTrackH, thumbY, bandThumbH));
        }
    }

    /** True when a content row of height {@code h} whose drawn top is {@code drawTop} sits fully inside the band. */
    private boolean bandContains(int drawTop, int h) {
        return drawTop >= bandTop && drawTop + h <= bandBottom;
    }

    /**
     * Left edge of a full-width content row, mirroring the scrollbar's reserved column on the other side. A row
     * built between this and {@link #rowControlRight()} is CENTRED on the panel and clear of the scrollbar, so it
     * neither drifts left (a literal x did, once the canvas widened) nor slides sideways when the bar appears.
     */
    protected int rowBandLeft() {
        return uiWidth - rowControlRight();
    }

    /** Width of that centred, scrollbar-clear row band. */
    protected int rowBandWidth() {
        return Math.max(1, rowControlRight() - rowBandLeft());
    }

    /**
     * How many {@code rowH}-tall rows fit between {@code listTop} and the footer band. Use this instead of a
     * hardcoded row cap: a fixed cap (written against a pre-unification screen height) now stops the list part way
     * down the panel while the scrollbar insists there is more. Deriving the cap lets a list fill the space it is
     * given. Always at least 1, so a list starting unusually low still renders a row.
     */
    protected int rowsThatFit(int listTop, int rowH) {
        return rowsThatFit(listTop, rowH, 0);
    }

    /**
     * As {@link #rowsThatFit(int, int)}, but holding {@code reserveBelow} px clear under the list. Pass this
     * whenever the screen draws anything BELOW its list (an add-row, a name field, a total); without it the list
     * grows straight over that content and the widgets underneath end up unreachable.
     */
    protected int rowsThatFit(int listTop, int rowH, int reserveBelow) {
        if (rowH <= 0) {
            return 1;
        }
        return Math.max(1, (GuiTheme.contentBottom(uiHeight) - Math.max(0, reserveBelow) - listTop) / rowH);
    }

    protected int standardScrollbarX() {
        return uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET - GuiTheme.SCROLLBAR_WIDTH;
    }

    /**
     * Reserved-width rule for band content: while a band is open, trim a widget starting at {@code x} so its right
     * edge stops at {@link #rowControlRight} and never enters the scrollbar column. Applied centrally in
     * {@link #field}/{@link #dropdown} (and by {@code FieldEditScreen.bf}), so no per-screen width changes; a no-op
     * when no band is open.
     */
    protected int reserveScrollbar(int x, int w) {
        if (!bandOpen) {
            return w;
        }
        int maxRight = rowControlRight();
        if (x + w > maxRight) {
            return Math.max(1, maxRight - x);
        }
        return w;
    }

    /** Record the lowest content extent while the band is open, so {@link #endScrollBand} can size the scroll range. */
    private void noteContentBottom(int y, int h) {
        if (bandOpen) {
            bandContentBottom = Math.max(bandContentBottom, y + h);
        }
    }

    protected void rect(int x, int y, int w, int h, int color) {
        int cy = clampBelowLogo(x, y, w);
        rects.add(new int[]{x, cy, w, h, color});
        noteContentBottom(cy, h);
    }

    /**
     * Register (and draw the scrollbar for) a vertically scrollable list. Call from {@link #init()} after laying
     * out the visible row window. {@code left..right} is the row area (the wheel hit-box; the bar is drawn just
     * inside {@code right}). {@code count} is the total items, {@code cap} how many fit, {@code scroll} the current
     * top index, {@code setter} receives the new offset (typically {@code v -> { this.scroll = v; rebuildWidgets(); }}).
     */
    protected void scrollList(int left, int right, int top, int rowH, int cap, int count,
                              int scroll, java.util.function.IntConsumer setter) {
        int listH = cap * rowH;
        if (count > cap) {
            // scrollbar art just inside the list's right edge, clamped to the panel's inner area so it never
            // crosses the border
            int barX = scrollbarColumnX(right);
            int maxScroll = count - cap;
            int thumbH = Math.max(8, listH * cap / count);
            // clamp the thumb inside the track (never overshooting the end caps); dragScrollTo reads back the same
            // geometry so it can't desync from the drawn thumb
            int thumbY = GuiTheme.scrollThumbY(top, listH, thumbH, scroll, maxScroll);
            themedBars.add(new ThemedBar(barX, top, listH, thumbY, thumbH));
        }
        scrollHandles.add(new ScrollHandle(left, right, top, listH, rowH, cap, count, scroll, setter));
    }

    /**
     * Left X at which a scrollbar whose desired right edge is {@code desiredRight} should be drawn, clamped so
     * the whole {@link GuiTheme#SCROLLBAR_WIDTH}-wide bar stays inside the panel's inner content area (never
     * crossing the right border, {@link GuiTheme#SCROLLBAR_PANEL_INSET} from the panel edge).
     */
    protected int scrollbarColumnX(int desiredRight) {
        int maxRight = uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET;
        int right = Math.min(desiredRight, maxRight);
        return right - GuiTheme.SCROLLBAR_WIDTH;
    }

    /**
     * The X a TRAILING row control (a list row's Delete/arrow button) may reach its RIGHT edge to, so it never sits
     * under the scrollbar column: one {@link GuiTheme#UNIT} left of where the bar starts. Every list uses this so
     * trailing controls stop at the same place whether or not the bar is visible.
     */
    protected int rowControlRight() {
        return uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET - GuiTheme.SCROLLBAR_WIDTH - GuiTheme.UNIT;
    }

    private void dragScrollTo(ScrollHandle h, double vy) {
        int maxScroll = h.count - h.cap;
        if (maxScroll <= 0) {
            return;
        }
        int thumbH = Math.max(8, h.listH * h.cap / h.count);
        // Invert the SAME clamped geometry the thumb is drawn with (GuiTheme.scrollThumbY): the thumb centre
        // travels within a band inset by SCROLLBAR_THUMB_INSET at each end, so the drag can't desync.
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
        int cy = clampBelowLogo(x, y, w);
        DmzTextureButton b = addRenderableWidget(new DmzTextureButton(x, cy, w, h, label, DmzTextures.MENU_BIG,
                DmzTextures.BUTTON_U, DmzTextures.BUTTON_V, DmzTextures.BUTTON_W, DmzTextures.BUTTON_H,
                DmzTextures.ATLAS, onPress));
        if (bandOpen) {
            bandWidgets.add(new BandWidget(b, cy));
            noteContentBottom(cy, h);
        }
        return b;
    }

    /** A committing button (Save / Add / New / Select): identical to {@link #btn} but its press plays DMZ's
     *  confirm sound instead of the ordinary click. */
    protected DmzTextureButton commitBtn(int x, int y, int w, int h, Component label, Runnable onPress) {
        return btn(x, y, w, h, label, onPress).commits();
    }

    /** Compact themed text input. Returns the {@link EditBox} supertype so the screens (which store the result
     *  as {@code EditBox}) compile untouched; the concrete widget is a {@link ThemedEditBox}. */
    protected EditBox field(int x, int y, int w, String value) {
        int cy = clampBelowLogo(x, y, w);
        EditBox b = new ThemedEditBox(this.font, x, cy, reserveScrollbar(x, w), 10, Component.empty());
        b.setMaxLength(512);
        b.setValue(value == null ? "" : value);
        addRenderableWidget(b);
        if (bandOpen) {
            bandWidgets.add(new BandWidget(b, cy));
            noteContentBottom(cy, 10);
        }
        return b;
    }

    protected DmzDropdown dropdown(int x, int y, int w, List<Component> opts, int idx) {
        int cy = clampBelowLogo(x, y, w);
        DmzDropdown d = new DmzDropdown(x, cy, reserveScrollbar(x, w), GuiTheme.FIELD_HEIGHT, opts, idx);
        dropdowns.add(d);
        noteContentBottom(cy, GuiTheme.FIELD_HEIGHT);
        return d;
    }

    protected void label(String text, int x, int y) {
        int w = this.font == null || text == null ? 1 : this.font.width(text);
        int cy = clampBelowLogo(x, y, w);
        labels.add(new Lbl(text, x, cy, GuiTheme.COLOR_LABEL, false));
        noteContentBottom(cy, this.font == null ? 9 : this.font.lineHeight);
    }

    /** A label centred horizontally on {@code cx} (used for titles/subtitles and tab captions). */
    protected void labelCentered(String text, int cx, int y, int color) {
        labels.add(new Lbl(text, cx, y, color, true));
    }

    /**
     * Clamp a per-screen first-content Y up to {@link GuiTheme#CONTENT_TOP} so content never begins inside the
     * header band: returns the larger of {@code desiredY} and the header floor.
     */
    protected int contentTop(int desiredY) {
        return Math.max(GuiTheme.CONTENT_TOP, desiredY);
    }

    /**
     * Logo-overlap guard applied inside every shared placement helper ({@link #label}, {@link #rect},
     * {@link #field}, {@link #dropdown}, {@link #btn}, {@link #tooltip}), so a screen cannot draw on top of the
     * header logo even hand-placing a raw Y in the header band. Deliberately narrow: an element is pushed to
     * {@link GuiTheme#CONTENT_TOP} ONLY when its Y is above {@link GuiTheme#LOGO_BOTTOM} AND its horizontal span
     * crosses the centred logo's band. Content beside the logo, and Y at or below the logo, is never touched.
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
            // Tab backdrop is the dropdown sheet, SPLICED. Active vs inactive is a brightness wash over the same
            // splice, plus a gold underline on top. The caption is fitted so long section names shrink/ellipsize
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
                    GuiSounds.confirm();            // toggling a choice is a commit
                    onDropdownSelect(openDropdown, row);
                    return true;
                }
                DmzDropdown chosen = openDropdown;
                chosen.select(row);
                openDropdown = null;
                GuiSounds.confirm();                // picking a value commits it
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
            // A content dropdown scrolled out of the band is not clickable (it is not drawn either).
            if (i >= contentDropdownStart && !bandContains(d.getY(), GuiTheme.FIELD_HEIGHT)) {
                continue;
            }
            if (d.headerContains(vx, vy)) {
                d.resetSearch();
                openDropdown = d;
                GuiSounds.navigate();               // opening a dropdown list
                return true;
            }
        }
        for (TabRegion t : tabRegions) {
            if (vx >= t.x && vx < t.x + t.w && vy >= t.y && vy < t.y + t.h) {
                GuiSounds.navigate();               // tab switch
                t.onSelect.accept(t.index);
                return true;
            }
        }
        // Grab the content band's scrollbar thumb?
        if (bandMaxScroll > 0) {
            int barX = standardScrollbarX();
            int barRight = barX + GuiTheme.SCROLLBAR_WIDTH;
            if (vx >= barX - 1 && vx < barRight && vy >= bandTop && vy < bandTop + bandTrackH) {
                draggingBand = true;
                bandDragTo(vy);
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
        if (draggingBand) {
            bandDragTo(toVirtualY(my));
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
        if (draggingBand) {
            draggingBand = false;
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
        // Content band: wheel one row per notch while the cursor is over the band.
        if (bandMaxScroll > 0 && vy >= bandTop && vy < bandBottom && vx >= 0 && vx < uiWidth) {
            int ns = Math.max(0, Math.min(bandMaxScroll, bandScroll - (int) Math.signum(delta) * GuiTheme.ROW_HEIGHT));
            if (ns != bandScroll && bandSetter != null) {
                bandSetter.accept(ns);
            }
            return true;
        }
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
        return super.mouseScrolled(mx, my, delta);
    }

    /** Drag the band thumb: invert the SAME clamped geometry {@link #endScrollBand} drew it with. */
    private void bandDragTo(double vy) {
        if (bandMaxScroll <= 0) {
            return;
        }
        double top = bandTop + GuiTheme.SCROLLBAR_THUMB_INSET;
        double bottom = bandTop + bandTrackH - GuiTheme.SCROLLBAR_THUMB_INSET;
        double travel = (bottom - top) - bandThumbH;
        double t = travel <= 0 ? 0 : (vy - top - bandThumbH / 2.0) / travel;
        int ns = (int) Math.round(Math.max(0.0, Math.min(1.0, t)) * bandMaxScroll);
        if (ns != bandScroll && bandSetter != null) {
            bandSetter.accept(ns);
        }
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
        // umbrella logo centred at the top, one fixed size on every screen
        ThemeRender.header(g, 0, 0, uiWidth);
        // Named-entity title top-left, never centred under the logo where it collided with the tabs. Generic
        // screens leave topLeftName null. Fitted so it stops clear of the centred logo's left edge.
        if (topLeftName != null && !topLeftName.isEmpty()) {
            int centre = uiWidth / 2;
            int logoLeft = centre - GuiTheme.LOGO_HALF_WIDTH - GuiTheme.UNIT;
            int nameW = Math.max(1, logoLeft - GuiTheme.NAME_TOP_LEFT_X);
            GuiText.drawFitted(g, this.font,
                    net.shurui.dev.shuruis_dmz_tournaments.util.ColorCodes.translate(topLeftName),
                    GuiTheme.NAME_TOP_LEFT_X, GuiTheme.NAME_TOP_LEFT_Y, nameW + 2 * GuiTheme.TEXT_PADDING_X,
                    GuiTheme.LOGO_DRAW_HEIGHT, GuiTheme.NAME_TOP_LEFT_COLOR);
        }

        // tab-header backgrounds, drawn first so the gold underline rects and fitted captions sit on top
        for (TabBg t : tabBgs) {
            ThemeRender.tab(g, t.x(), t.y(), t.w(), t.h(), t.active());
        }

        // Offset + clip the band's real widgets BEFORE anything else, so labelRightBoundary reads their scrolled
        // positions and a widget scrolled out of the band is hidden (and so not clickable).
        for (BandWidget bw : bandWidgets) {
            int drawTop = bw.baseY() - bandScroll;
            bw.w().setY(drawTop);
            bw.w().visible = bandContains(drawTop, bw.w().getHeight());
        }
        for (int i = 0; i < rects.size(); i++) {
            int[] r = rects.get(i);
            int ry = r[1];
            if (i >= contentRectStart) {
                ry -= bandScroll;
                if (!bandContains(ry, r[3])) {
                    continue;
                }
            }
            g.fill(r[0], ry, r[0] + r[2], ry + r[3], r[4]);
        }
        for (int i = 0; i < labels.size(); i++) {
            Lbl l = labels.get(i);
            int ly = l.y();
            if (i >= contentLabelStart) {
                ly -= bandScroll;
                if (!bandContains(ly, this.font.lineHeight)) {
                    continue;
                }
            }
            if (l.centered()) {
                g.drawCenteredString(this.font, l.text, l.x, ly, l.color);
            } else {
                // Fit a left-aligned label into the gap before the leftmost trailing widget on its text line, so a
                // row label never runs under the Edit/Copy/Del buttons. The boundary reads already-scrolled
                // positions, so the drawn (scrolled) label Y is passed here.
                int right = labelRightBoundary(l.x, ly);
                int avail = right - l.x;
                if (avail < font.width(l.text)) {
                    String clipped = GuiText.ellipsize(font, l.text, Math.max(0, avail));
                    g.drawString(this.font, clipped, l.x, ly, l.color, false);
                } else {
                    g.drawString(this.font, l.text, l.x, ly, l.color, false);
                }
            }
        }
        for (Caption c : captions) {
            GuiText.drawFittedCentered(g, this.font, c.text(), c.x() + c.w() / 2, c.y(),
                    GuiText.innerWidth(c.w()), c.h(), c.color());
        }
        for (int i = 0; i < dropdowns.size(); i++) {
            DmzDropdown d = dropdowns.get(i);
            // Content dropdowns are clipped when scrolled out, never pixel-offset (see the band note): one only
            // exists at bandScroll == 0, so its drawn Y equals its natural Y.
            if (i >= contentDropdownStart && !bandContains(d.getY(), GuiTheme.FIELD_HEIGHT)) {
                continue;
            }
            d.renderHeader(g, this.font, vmx, vmy, d == openDropdown);
        }
        // scrollbar art for registered lists (track + thumb), enqueued by scrollList
        for (ThemedBar b : themedBars) {
            ThemeRender.scrollbar(g, b.x(), b.top(), b.trackH(), b.thumbY(), b.thumbH());
        }

        super.render(g, vmx, vmy, partialTick);

        // Dev-only layout check (off unless -Dshuruis_dmz_tournaments.gui.validate=true). Runs once per build.
        if (LayoutValidator.enabled() && !validatedThisBuild) {
            validatedThisBuild = true;
            // validate against the panel's INNER rect (outer minus PANEL_INSET) so a widget straying onto the
            // border band is flagged, including a footer button overlapping the bottom border
            LayoutValidator.validateInner(getClass(), children(), 0, 0, uiWidth, uiHeight);
        }

        // The open dropdown list and any popup overlay must sit above everything else, including batched glyphs.
        // Elevating them on Z (like vanilla tooltips at z=400) keeps them on top.
        pose.pushPose();
        pose.translate(0, 0, GuiTheme.Z_OVERLAY);
        if (openDropdown != null) {
            openDropdown.renderList(g, this.font, vmx, vmy);
        }
        renderTopOverlay(g, vmx, vmy);
        pose.popPose();

        String hover = hoveredTip(vmx, vmy);
        pose.popPose();

        // Draw the tooltip in real screen space (unscaled) so it stays readable at the content scale.
        if (hover != null) {
            g.renderTooltip(this.font, this.font.split(Component.literal(hover), 170), mouseX, mouseY);
        }
    }

    /**
     * Right boundary a left-aligned label at {@code (lx, ly)} may extend to before colliding with a trailing
     * control on its row: the leftmost x of any active on-row widget starting to its right whose vertical extent
     * overlaps the label's text line, minus a small gap; else the panel's inner-right edge. Keeps list-row labels
     * out from under the trailing Edit/Copy/Del buttons with no per-screen reservation.
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

    /** Register a hover-help region (virtual coords). */
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
            int ty = t.y;
            if (i >= contentTipStart) {
                ty -= bandScroll;
                if (!bandContains(ty, t.h)) {
                    continue; // its row is scrolled out of the band
                }
            }
            if (vmx >= t.x && vmx < t.x + t.w && vmy >= ty && vmy < ty + t.h) {
                found = t.text; // last match wins (later rows drawn on top)
            }
        }
        return found;
    }

    /** Hook for subclasses to draw a popup (e.g. a colour picker) on top, inside the scaled canvas. */
    protected void renderTopOverlay(GuiGraphics g, int vmx, int vmy) {
    }
}
