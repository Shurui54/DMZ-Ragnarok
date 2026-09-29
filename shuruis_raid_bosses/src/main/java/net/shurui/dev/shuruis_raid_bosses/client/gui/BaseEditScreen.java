package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.shurui.dev.shuruis_raid_bosses.client.gui.theme.GuiSounds;
import net.shurui.dev.shuruis_raid_bosses.client.gui.theme.GuiText;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_raid_bosses.client.gui.theme.LayoutValidator;
import net.shurui.dev.shuruis_raid_bosses.client.gui.theme.ThemeRender;
import net.shurui.dev.shuruis_raid_bosses.client.gui.theme.ThemedEditBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared base for the editor screens, ported to the suite-wide "Shurui" theme: a spliced panel with the
 * umbrella logo header, themed buttons/fields/dropdowns, spliced tab bar, and commissioned scrollbar art.
 * Subclasses build their widgets in {@link #init()} (after {@code super.init()}); everything routes through
 * the shared placement helpers so the theme's rules (logo clamp, scrollbar reservation, text fit) apply
 * without any per-screen edit.
 */
public abstract class BaseEditScreen extends ScaledScreen {

    /** Wheel-scroll step for the content band (matches FieldEditScreen's ROW_H so a notch moves ~2 rows). */
    private static final int ROW_H_SCROLL = GuiTheme.ROW_HEIGHT;

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
    /** Optional gray subtitle drawn under the gold title (kept for API compat; no longer drawn, matching sdu). */
    protected String headerSubtitle = null;
    /**
     * Optional name of the NAMED entity this screen edits (a raid, boss stage, enemy wave, ki move, reward),
     * drawn in the TOP LEFT of the panel per the suite title convention. Left null on screens that only carry a
     * generic descriptive label, which draw no name. May contain {@code &} colour codes (translated on draw).
     */
    protected String panelName = null;
    /** Index into {@link #scrollHandles} whose scrollbar thumb is being dragged, or -1. */
    private int draggingScroll = -1;
    protected DmzDropdown openDropdown;
    protected final Screen parent;
    /** One-shot guard so the dev layout validator logs at most once per (re)build, not every frame. */
    private boolean validatedThisBuild;
    /** True once this screen instance has played its open sound; set on the first init only and never cleared,
     *  so the open sound fires exactly once per screen open, not per resize or rebuild. */
    private boolean openSoundPlayed;

    protected int bandTop = Integer.MIN_VALUE;
    protected int bandBottom = Integer.MAX_VALUE;
    private int contentLabelStart = Integer.MAX_VALUE;
    private int contentRectStart = Integer.MAX_VALUE;
    private int contentDropdownStart = Integer.MAX_VALUE;
    private int contentTipStart = Integer.MAX_VALUE;
    private int contentCaptionStart = Integer.MAX_VALUE;
    /** Vertical scroll of the content band and its max (computed by {@link #finishScrollBand}). Zero (and
     *  no scrollbar) when the content fits inside the band. */
    protected int scroll;
    protected int maxScroll;

    /** A hover-help region (virtual coords) with wrapped help text drawn as a tooltip. */
    private record Tip(int x, int y, int w, int h, String text) {
    }

    private final List<Tip> tips = new ArrayList<>();

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

    protected boolean labelHidden(int i, int y) {
        return i >= contentLabelStart && !inBand(y);
    }

    protected boolean rectHidden(int i, int y) {
        return i >= contentRectStart && !inBand(y);
    }

    protected boolean dropdownHidden(int i, int y) {
        return i >= contentDropdownStart && !inBand(y);
    }

    private boolean captionHidden(int i, int y) {
        return i >= contentCaptionStart && !inBand(y);
    }

    protected BaseEditScreen(Component title, int w, int h, Screen parent) {
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
            // Commissioned scrollbar art, drawn just inside the list's right edge at native width, clamped to the
            // panel's inner content area so it never crosses the border.
            int barX = scrollbarColumnX(right);
            int maxScroll = count - cap;
            int thumbH = Math.max(8, listH * cap / count);
            int thumbY = GuiTheme.scrollThumbY(top, listH, thumbH, scroll, maxScroll);
            themedBars.add(new ThemedBar(barX, top, listH, thumbY, thumbH));
        }
        scrollHandles.add(new ScrollHandle(left, right, top, listH, rowH, cap, count, scroll, setter));
    }

    private void dragScrollTo(ScrollHandle h, double vy) {
        int maxScroll = h.count - h.cap;
        if (maxScroll <= 0) {
            return;
        }
        int thumbH = Math.max(8, h.listH * h.cap / h.count);
        // Invert the SAME clamped geometry the thumb is drawn with (GuiTheme.scrollThumbY): the drag band is inset
        // by SCROLLBAR_THUMB_INSET from each track end, so the drag can't desync from the drawn thumb.
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

    /** A committing button (Save / Add / New / Select): identical to {@link #btn} but its press plays DMZ's
     *  confirm sound instead of the ordinary click. */
    protected DmzTextureButton commitBtn(int x, int y, int w, int h, Component label, Runnable onPress) {
        return btn(x, y, w, h, label, onPress).commits();
    }

    /**
     * The Y (top edge) for this screen's footer action row (Save/Back/Menu/Close/Add), derived from the runtime
     * canvas height so a {@link GuiTheme#BUTTON_HEIGHT}-tall footer button's bottom lands clear of the panel's
     * bottom border (see {@link GuiTheme#footerY}). Screens use this instead of a hand-picked {@code UI_H - N} so no
     * footer ever sits on the border. Footer buttons must be built at {@link #footerBtnHeight()} tall for the
     * arithmetic to hold.
     */
    protected int footerY() {
        return GuiTheme.footerY(uiHeight);
    }

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
     * leaves the rest of it empty while the scrollbar insists there is more. Deriving the cap means a list fills the
     * space it is given, and keeps doing so if the shared canvas is ever resized again.
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

    /** The one height footer buttons are built at, so the drawn bottom matches {@link #footerY}. */
    protected int footerBtnHeight() {
        return GuiTheme.BUTTON_HEIGHT;
    }

    /**
     * Add a small ICON button (a row delete "X" or an up/down move arrow) at the STANDARD icon size, right-aligned
     * so its right edge lands on {@code right} (typically {@link #rowControlRight()}). Flags the button as an icon
     * so it always draws the round pill, and vertically centres it in the {@code rowH}-tall row.
     */
    protected DmzTextureButton iconBtnRight(int right, int rowY, int rowH, Component label, Runnable onPress) {
        int size = iconButtonSize(rowH);
        int x = right - size;
        int y = rowY + (rowH - size) / 2;
        return btn(x, y, size, size, label, onPress).asIcon();
    }

    /**
     * Add a small ICON button whose LEFT edge is at {@code left} (the move arrows placed as a fixed inner pair).
     * Same standard size, round pill and row centring as {@link #iconBtnRight}.
     */
    protected DmzTextureButton iconBtnAt(int left, int rowY, int rowH, Component label, Runnable onPress) {
        int size = iconButtonSize(rowH);
        int y = rowY + (rowH - size) / 2;
        return btn(left, y, size, size, label, onPress).asIcon();
    }

    /** The standard on-screen size of a small icon button; exposed so screens can size hover regions to match. */
    protected int iconSize() {
        return GuiTheme.ICON_BUTTON_SIZE;
    }

    /**
     * The on-screen size of an icon button on a {@code rowH}-tall row: the standard {@link GuiTheme#ICON_BUTTON_SIZE}
     * shrunk to fit a short row but NEVER below {@link GuiTheme#ICON_BUTTON_MIN_SIZE} (below that floor the two
     * mirrored rounded caps meet with no interior). The gap trimmed off the size leaves a clear band of panel
     * between a stacked column of icon buttons.
     */
    private int iconButtonSize(int rowH) {
        return Math.max(GuiTheme.ICON_BUTTON_MIN_SIZE,
                Math.min(rowH - GuiTheme.ROW_BUTTON_GAP, GuiTheme.ICON_BUTTON_SIZE));
    }

    /** Compact text input, themed. Preserves the EditBox return type so callers storing it keep compiling. */
    protected EditBox field(int x, int y, int w, String value) {
        // ThemedEditBox reskins only the background; every caret/selection/scroll/keyboard behaviour is vanilla's.
        EditBox b = new ThemedEditBox(this.font, x, clampBelowLogo(x, y, w), reserveScrollbar(x, w), 10,
                Component.empty());
        b.setMaxLength(512);
        b.setValue(value == null ? "" : value);
        return addRenderableWidget(b);
    }

    protected DmzDropdown dropdown(int x, int y, int w, List<Component> opts, int idx) {
        DmzDropdown d = new DmzDropdown(x, clampBelowLogo(x, y, w), reserveScrollbar(x, w), 11, opts, idx);
        dropdowns.add(d);
        return d;
    }

    protected void label(String text, int x, int y) {
        int w = this.font == null || text == null ? 1 : this.font.width(text);
        labels.add(new Lbl(text, x, clampBelowLogo(x, y, w), GuiTheme.COLOR_LABEL, false));
    }

    /** A label centred horizontally on {@code cx} (used for titles/subtitles and tab captions). */
    protected void labelCentered(String text, int cx, int y, int color) {
        labels.add(new Lbl(text, cx, y, color, true));
    }

    /**
     * Emit {@code text} as one or more {@link #label} lines word-wrapped to fit inside the panel, so long
     * help/description text never runs off the right edge. A leading {@code §x} colour code is re-applied
     * to every wrapped line. Lines are spaced 10px apart; returns the Y just below the last line.
     */
    protected int labelWrapped(String text, int x, int y) {
        int maxW = uiWidth - x - 8;
        String prefix = "";
        String body = text;
        if (body.length() >= 2 && body.charAt(0) == '§') {
            prefix = body.substring(0, 2);
            body = body.substring(2);
        }
        StringBuilder line = new StringBuilder();
        int cy = y;
        for (String word : body.split(" ")) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && this.font.width(prefix + trial) > maxW) {
                label(prefix + line, x, cy);
                cy += 10;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(trial);
            }
        }
        if (line.length() > 0) {
            label(prefix + line, x, cy);
            cy += 10;
        }
        return cy;
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
            // enqueued so scroll clipping still works. Active vs inactive is a brightness wash plus a gold
            // underline; the caption is fitted into the cell so long section names shrink/ellipsize.
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

    /**
     * Read back pending edits into the model. No-op here; {@link FieldEditScreen} overrides it to flush
     * its text/dropdown registries. Called before a scroll rebuild so unsaved EditBox text survives.
     */
    protected void applyFields() {
    }

    /**
     * Clamp a per-screen first-content Y up to {@link GuiTheme#CONTENT_TOP} so content never begins inside the
     * header band. Screens pass the Y they want their first row at; this returns the larger of that and the
     * header floor, so a screen can never start content above the logo.
     */
    protected int contentTop(int desiredY) {
        return Math.max(GuiTheme.CONTENT_TOP, desiredY);
    }

    /**
     * Logo-overlap guard applied INSIDE every shared placement helper ({@link #label}, {@link #rect},
     * {@link #field}, {@link #dropdown}, {@link #btn}, {@link #tooltip}), so a screen physically cannot draw an
     * element on top of the header logo even if it hand-places a raw Y in the header band.
     *
     * <p>It is deliberately NARROW so it only moves what actually collides: an element is pushed down to
     * {@link GuiTheme#CONTENT_TOP} ONLY when its Y is above {@link GuiTheme#LOGO_BOTTOM} AND its horizontal span
     * crosses the centred logo's band. Content BESIDE the logo (a header count label, a side action button) does
     * not cross that band and is left exactly where the screen put it, and footer/body content is never touched.
     */
    protected int clampBelowLogo(int x, int y, int w) {
        if (y >= GuiTheme.LOGO_BOTTOM) {
            return y;
        }
        int centre = uiWidth / 2;
        int bandLeft = centre - GuiTheme.LOGO_HALF_WIDTH;
        int bandRight = centre + GuiTheme.LOGO_HALF_WIDTH;
        boolean crossesLogo = x < bandRight && (x + Math.max(1, w)) > bandLeft;
        return crossesLogo ? GuiTheme.CONTENT_TOP : y;
    }

    /**
     * Reserved-width rule: when this screen carries a scroll band (so a scrollbar may appear down the right edge),
     * trim a widget starting at {@code x} so its right edge stops at {@code scrollbar column left - UNIT} and never
     * enters the scrollbar's column. When no band is set the width is returned unchanged, so non-scrolling screens
     * keep their existing full-width fields.
     */
    protected int reserveScrollbar(int x, int w) {
        boolean bandDeclared = bandBottom != Integer.MAX_VALUE;
        if (!bandDeclared) {
            return w;
        }
        int columnLeft = uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET - GuiTheme.SCROLLBAR_WIDTH;
        int maxRight = columnLeft - GuiTheme.UNIT;
        if (x + w > maxRight) {
            return Math.max(1, maxRight - x);
        }
        return w;
    }

    /**
     * Left X at which a scrollbar whose desired right edge is {@code desiredRight} should be drawn, clamped so
     * the whole {@link GuiTheme#SCROLLBAR_WIDTH}-wide bar stays inside the panel's inner content area (never
     * crossing the right border, never sitting left of the panel inset). Shared by {@link #scrollList} and
     * {@link #finishScrollBand} so every screen's scrollbar is constrained the same way.
     */
    protected int scrollbarColumnX(int desiredRight) {
        int maxRight = uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET;
        int right = Math.min(desiredRight, maxRight);
        return right - GuiTheme.SCROLLBAR_WIDTH;
    }

    /** X of the scroll-band scrollbar's right edge (the panel's inner right by default). */
    protected int bandRight() {
        return uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET;
    }

    /** True while any content band on this screen shows a scrollbar (overflowing). */
    protected boolean hasBandScrollbar() {
        return maxScroll > 0;
    }

    /**
     * The X of the rightmost column a TRAILING row control (a list row's delete/arrow icon button) may occupy its
     * RIGHT edge at, so it never sits under the scrollbar. Identical reservation to the field/dropdown width rule.
     */
    protected int rowControlRight() {
        return uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET - GuiTheme.SCROLLBAR_WIDTH - GuiTheme.UNIT;
    }

    /** Resolve a translation key to text in the player's language (with optional %s args), safe against a
     *  literal {@code %} in help text (it uses Component.translatable, not String.format). */
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

    /**
     * Hide the content widgets (text boxes, buttons) that scrolled outside the visible band. A small top
     * tolerance keeps a widget that a row nudges up by a pixel or two for visual centring from being clamped
     * away entirely; genuinely scrolled-off widgets sit at least a full row above the band.
     */
    protected void clampContentWidgets(int top, int bottom) {
        int topTol = top - 2;
        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget w) {
                boolean visible = w.getY() >= topTol && w.getY() <= bottom;
                w.visible = visible;
                w.active = visible;
            }
        }
    }

    /**
     * Close a scroll band opened with {@link #beginScrollBand}: compute the max scroll from how far the
     * content ({@code rowY}) overran the band bottom, clamp the current {@link #scroll}, hide off-band
     * widgets, and draw a right-edge scrollbar (commissioned art) when there is anything to scroll.
     */
    protected void finishScrollBand(int contentTop, int contentBottom, int rowY) {
        maxScroll = Math.max(0, (rowY + scroll) - contentBottom);
        scroll = Mth.clamp(scroll, 0, maxScroll);
        clampContentWidgets(contentTop, contentBottom);
        if (maxScroll > 0) {
            int barX = scrollbarColumnX(bandRight());
            int bandH = contentBottom - contentTop;
            int total = bandH + maxScroll;               // full virtual content height
            int thumbH = Math.max(8, (int) ((long) bandH * bandH / total));
            int thumbY = GuiTheme.scrollThumbY(contentTop, bandH, thumbH, scroll, maxScroll);
            themedBars.add(new ThemedBar(barX, contentTop, bandH, thumbY, thumbH));
        }
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
            if (!dropdownHidden(i, d.getY()) && d.headerContains(vx, vy)) {
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
        // Content-band wheel scroll (a tab/body taller than the panel). Apply pending edits BEFORE the
        // rebuild so unsaved EditBox text isn't discarded, then re-lay-out at the new scroll offset.
        if (maxScroll > 0 && inBand((int) Math.round(vy))) {
            int ns = Mth.clamp(scroll - (int) Math.signum(delta) * ROW_H_SCROLL * 2, 0, maxScroll);
            if (ns != scroll) {
                applyFields();
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
        // Standard header: the umbrella logo centred at the top, at one fixed size on every screen. The old gold
        // title / gray subtitle text is replaced by the logo; headerSubtitle is still accepted by subclasses (kept
        // for API compat) but no longer drawn here, matching the sdu template.
        ThemeRender.header(g, 0, 0, uiWidth);

        // Top-left name of the named entity being edited (raid / boss stage / enemy wave / ki move / reward),
        // per the suite title convention. Drawn left of the centred logo and fitted so it never runs under it.
        if (panelName != null && !panelName.isEmpty()) {
            String shown = net.shurui.dev.shuruis_raid_bosses.util.ColorCodes.translate(panelName);
            int nameMaxW = Math.max(1, (uiWidth / 2 - GuiTheme.LOGO_HALF_WIDTH) - GuiTheme.NAME_X - GuiTheme.UNIT);
            String fitted = font.width(shown) > nameMaxW ? GuiText.ellipsize(font, shown, nameMaxW) : shown;
            g.drawString(this.font, fitted, GuiTheme.NAME_X, GuiTheme.NAME_Y, GuiTheme.NAME_COLOR, false);
        }

        // Themed tab-header backgrounds (spliced dropdown.png; active tab gets a brightness wash). Drawn first so
        // the gold underline rects and the fitted tab captions sit on top.
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
                // Reserve the width of any trailing control on this row so a row label never runs UNDER the
                // Edit/Copy/Del buttons; fitted into the gap between its start and the leftmost widget to its right.
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
        // Commissioned scrollbar art for registered lists / content bands (track + thumb).
        for (ThemedBar b : themedBars) {
            ThemeRender.scrollbar(g, b.x(), b.top(), b.trackH(), b.thumbY(), b.thumbH());
        }

        super.render(g, vmx, vmy, partialTick);

        // Dev-only layout check (off unless -Dsrb.gui.validate=true). Runs once per build.
        if (LayoutValidator.enabled() && !validatedThisBuild) {
            validatedThisBuild = true;
            // Validate against the panel's INNER rect (outer minus PANEL_INSET on every side) so any widget that
            // strays onto the border art band is flagged, including a footer button that overlaps the bottom border.
            LayoutValidator.validateInner(getClass(), children(), 0, 0, uiWidth, uiHeight);
        }

        // The open dropdown list and any popup overlay must sit above everything else, including the batched
        // glyphs of labels/widgets. Elevating them on the Z axis keeps them on top so their text stays readable.
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
     * Right boundary a left-aligned label at {@code (lx, ly)} may extend to before it would collide with a
     * trailing control on its row. Returns the leftmost x of any active on-row widget (button/field/dropdown)
     * that starts to the right of the label and whose vertical extent overlaps the label's text line, minus a
     * small gap; the panel's inner-right edge otherwise.
     */
    private int labelRightBoundary(int lx, int ly) {
        int lineTop = ly;
        int lineBot = ly + this.font.lineHeight;
        int gap = GuiTheme.UNIT;
        int right = uiWidth - GuiTheme.CONTENT_PADDING;
        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget w) {
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
     *  describes. */
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
