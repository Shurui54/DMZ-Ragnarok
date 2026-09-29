package net.shurui.shuruisutilities.client.gui.saga;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.shuruisutilities.client.gui.DmzDropdown;
import net.shurui.shuruisutilities.client.gui.DmzTextureButton;
import net.shurui.shuruisutilities.client.gui.DmzTextures;
import net.shurui.shuruisutilities.client.gui.RowButton;
import net.shurui.shuruisutilities.client.gui.ScaledScreen;
import net.shurui.shuruisutilities.client.gui.theme.GuiSounds;
import net.shurui.shuruisutilities.client.gui.theme.GuiText;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.client.gui.theme.LayoutValidator;
import net.shurui.shuruisutilities.client.gui.theme.ThemeRender;
import net.shurui.shuruisutilities.client.gui.theme.ThemedEditBox;

import java.util.ArrayList;
import java.util.List;

// shared base for the editor screens: DMZ-green scaled panel, logo header, a dropdown host (open list on top,
// mouse in virtual coords), and helpers for buttons/fields/labels. subclasses build widgets in init().
// ("saga" name is inherited from the reference addon; this base is generic.) Rethemed to the shared "Shurui's"
// GUI theme: spliced panel/button/dropdown/scrollbar art, a fixed logo header with an unavoidable
// content-below-the-logo clamp, scrollbar-column reservation, and DMZ UI sounds.
public abstract class SagaBaseScreen extends ScaledScreen {

    protected record Lbl(String text, int x, int y, int color, boolean centered) {
    }

    // clickable tab-bar cell (virtual coords) that selects section index
    private record TabRegion(int x, int y, int w, int h, int index, java.util.function.IntConsumer onSelect) {
    }

    // a scrollable list region (registered via scrollList): geometry for wheel/drag + a scroll-offset setter
    private record ScrollHandle(int left, int right, int top, int listH, int rowH, int cap, int count,
                                int scroll, java.util.function.IntConsumer setter) {
    }

    // a themed tab-header cell background to blit during render (spliced dropdown.png art, active/inactive)
    private record TabBg(int x, int y, int w, int h, boolean active) {
    }

    // a caption fitted (shrunk/ellipsized) into a bounding box; used for tab labels so they never spill the cell
    private record Caption(String text, int x, int y, int w, int h, int color) {
    }

    // a themed scrollbar (track + thumb) to blit during render, so lists use the commissioned art
    // handle: the scrollHandles index this bar belongs to, so the renderer can ask whether THIS bar's thumb is
    // the one being dragged and draw its held state. The two lists are not index-aligned (a handle is always
    // registered, a bar only when the list overflows), so the link has to be carried rather than inferred.
    private record ThemedBar(int x, int top, int trackH, int thumbY, int thumbH, int handle) {
    }

    protected final List<DmzDropdown> dropdowns = new ArrayList<>();
    protected final List<Lbl> labels = new ArrayList<>();
    protected final List<int[]> rects = new ArrayList<>();
    private final List<Caption> captions = new ArrayList<>();
    private final List<TabRegion> tabRegions = new ArrayList<>();
    private final List<TabBg> tabBgs = new ArrayList<>();
    private final List<ScrollHandle> scrollHandles = new ArrayList<>();
    private final List<ThemedBar> themedBars = new ArrayList<>();
    // optional gray subtitle under the header (centred). used by GENERIC screens (a list, a hub). a screen that
    // edits a NAMED entity uses headerName instead, so the name reads top-left and never collides with the tabs.
    protected String headerSubtitle = null;
    // optional TOP-LEFT entity name (a hologram/group/region/crate/warp/portal/dimension name being edited). drawn
    // left-aligned in the panel's top-left corner (GuiTheme.NAME_LEFT_X/NAME_TOP_Y), gold, fitted so it stops short
    // of the centred logo. this is the suite-wide "named entity -> top left" rule; generic labels use headerSubtitle
    // (or nothing). only one of the two should be set per screen.
    protected String headerName = null;
    // scrollHandles index whose thumb is being dragged, or -1
    private int draggingScroll = -1;
    protected DmzDropdown openDropdown;
    protected final Screen parent;
    // true once this screen instance has played its open sound; set on the FIRST init and never cleared, so the
    // sound fires exactly once per open rather than on every resize/rebuildWidgets
    private boolean openSoundPlayed;
    // dev-only layout validation runs once per rebuild, on the first render after init() (when every widget exists).
    // reset in init() so a rebuildWidgets re-validates the new layout. gated by LayoutValidator.enabled().
    private boolean layoutValidated;

    // optional content scroll band, off by default. once a band is set, content items added at index >= the
    // content-start markers that fall outside [bandTop, bandBottom] aren't drawn/hit-tested, so it clips cleanly.
    protected int bandTop = Integer.MIN_VALUE;
    protected int bandBottom = Integer.MAX_VALUE;
    private int contentLabelStart = Integer.MAX_VALUE;
    private int contentRectStart = Integer.MAX_VALUE;
    private int contentDropdownStart = Integer.MAX_VALUE;
    private int contentTipStart = Integer.MAX_VALUE;

    // mark everything added from here as scrollable content + set the visible band
    protected void beginScrollBand(int top, int bottom) {
        bandTop = top;
        bandBottom = bottom;
        contentLabelStart = labels.size();
        contentRectStart = rects.size();
        contentDropdownStart = dropdowns.size();
        contentTipStart = tips.size();
    }

    // virtual-y within the visible band (always true when no band is set)
    protected boolean inBand(int y) {
        return y >= bandTop && y <= bandBottom;
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

    // hover-help region (virtual coords) with wrapped tooltip text
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
        // screen-open sound, exactly once per open (init re-runs on resize and rebuildWidgets)
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
        headerName = null;
        headerSubtitle = null;
        bandTop = Integer.MIN_VALUE;
        bandBottom = Integer.MAX_VALUE;
        contentLabelStart = Integer.MAX_VALUE;
        contentRectStart = Integer.MAX_VALUE;
        contentDropdownStart = Integer.MAX_VALUE;
        contentTipStart = Integer.MAX_VALUE;
        layoutValidated = false;
        // don't reset draggingScroll: a scrollbar drag re-runs init() every step and must survive until release
    }

    protected void rect(int x, int y, int w, int h, int color) {
        rects.add(new int[]{x, clampBelowLogo(x, y, w), w, h, color});
    }

    // register + draw the scrollbar for a vertical list. left..right is the row area (wheel hit-box; bar drawn
    // inside right, clamped to the panel's inner content area). count = total, cap = visible, scroll = top index.
    protected void scrollList(int left, int right, int top, int rowH, int cap, int count,
                              int scroll, java.util.function.IntConsumer setter) {
        int listH = cap * rowH;
        if (count > cap) {
            int barX = standardScrollbarX();
            int maxScroll = count - cap;
            int thumbH = Math.max(8, listH * cap / count);
            // clamp the thumb fully inside the track (never overshooting its end caps); the drag hit-test reads
            // back the same clamped geometry so it can't desync from the drawn thumb
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
        // invert the SAME clamped geometry the thumb is drawn with (GuiTheme.scrollThumbY): the thumb centre
        // travels between bandTop + thumbH/2 and bandBottom - thumbH/2, where the band is inset by
        // SCROLLBAR_THUMB_INSET from each track end
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

    // The panel chrome drawn behind a screen's content, before the header logo and every widget. Overridable so a
    // screen with its OWN commissioned panel (the task board) can swap the art without duplicating the rest of
    // render(). The default is the shared nine-sliced theme panel every other screen uses, so overriding nothing
    // leaves a screen exactly as it was.
    protected void renderPanel(GuiGraphics g) {
        ThemeRender.panel(g, 0, 0, uiWidth, uiHeight);
    }

    // The header logo. Overridable for the same reason as renderPanel: a screen built to a commissioned mock-up
    // may draw its logo at that mock-up's size rather than the suite's standard one. Default is unchanged, so
    // every screen that does not override this is exactly as it was.
    protected void renderHeader(GuiGraphics g) {
        ThemeRender.header(g, 0, 0, uiWidth);
    }

    protected DmzTextureButton btn(int x, int y, int w, int h, Component label, Runnable onPress) {
        return addRenderableWidget(new DmzTextureButton(x, clampBelowLogo(x, y, w), w, h, label, DmzTextures.MENU_BIG,
                DmzTextures.BUTTON_U, DmzTextures.BUTTON_V, DmzTextures.BUTTON_W, DmzTextures.BUTTON_H,
                DmzTextures.ATLAS, onPress));
    }

    // a committing button (Save / Add / New / Select): identical to btn but its press plays DMZ's confirm sound
    protected DmzTextureButton commitBtn(int x, int y, int w, int h, Component label, Runnable onPress) {
        return btn(x, y, w, h, label, onPress).commits();
    }

    // The Y (top edge) for this screen's footer action row (Save/Back/Menu/Close), derived from the runtime canvas
    // height so a BUTTON_HEIGHT-tall footer button's bottom lands clear of the panel's bottom border (see
    // GuiTheme.footerY). Screens use this instead of a hand-picked UI_H - N so no footer sits on the border. Footer
    // buttons must be built at footerBtnHeight() tall for the arithmetic to hold.
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

    // The one height footer buttons are built at, so the drawn bottom matches footerY().
    protected int footerBtnHeight() {
        return GuiTheme.BUTTON_HEIGHT;
    }

    // transparent full-width clickable list row; use instead of manual hit-testing so clicks always land
    protected RowButton rowBtn(int x, int y, int w, int h, Component label, Runnable onPress) {
        return addRenderableWidget(new RowButton(x, clampBelowLogo(x, y, w), w, h, label, onPress));
    }

    // X of a scroll-band scrollbar's right edge; a screen with a reserved column can override this
    protected int bandRight() {
        return uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET;
    }

    // left X at which a scrollbar whose desired right edge is desiredRight should be drawn, clamped so the whole
    // SCROLLBAR_WIDTH-wide bar stays inside the panel's inner content area (never crossing the border on either
    // side). shared by scrollList so every screen's scrollbar is constrained the same way.
    protected int scrollbarColumnX(int desiredRight) {
        int maxRight = uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET;
        int right = Math.min(desiredRight, maxRight);
        return right - GuiTheme.SCROLLBAR_WIDTH;
    }

    // THE canonical scrollbar column X for every list. Historically screens passed differing row-area right edges
    // (UI_W-12 on list screens, UI_W-8 elsewhere), which drew the bar a few px further left on some screens than
    // the rowControlRight() the trailing controls reserved to, so a control could sit 2px on the bar. Every list's
    // bar is now drawn at this ONE reserved column (panel inset from the right edge), which is exactly the column
    // rowControlRight() stops trailing controls short of, so bar and controls line up on every screen. The
    // per-screen row-area `right` passed to scrollList is still used for the wheel/drag hit-box, just not the bar X.
    protected int standardScrollbarX() {
        return uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET - GuiTheme.SCROLLBAR_WIDTH;
    }

    // rightmost X a trailing row control (a list row's delete/arrow) may occupy, so it never sits under the
    // scrollbar column. same reservation as reserveScrollbar; applied whether or not the bar currently shows.
    protected int rowControlRight() {
        return uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET - GuiTheme.SCROLLBAR_WIDTH - GuiTheme.UNIT;
    }

    // clamp a per-screen first-content Y up to CONTENT_TOP so content never begins inside the header band
    protected int contentTop(int desiredY) {
        return Math.max(GuiTheme.CONTENT_TOP, desiredY);
    }

    // unavoidable logo-overlap guard applied INSIDE every shared placement helper (label/rect/field/dropdown/btn/
    // tooltip), so a screen physically cannot draw an element on top of the header logo even if it hand-places a
    // raw Y in the header band. deliberately NARROW: an element is pushed down to CONTENT_TOP only when its Y is
    // above LOGO_BOTTOM AND its horizontal span crosses the centred logo's band. content beside the logo (a left
    // header label, a right side button) does not cross that band and is left exactly where the screen put it.
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

    // compact text input (~60% height) so more fits without shrinking the panel. ThemedEditBox reskins only the
    // background; every caret/selection/scroll/keyboard behaviour is delegated to vanilla, and the EditBox
    // supertype is returned so all screens (which store the result as EditBox) keep compiling.
    protected EditBox field(int x, int y, int w, String value) {
        EditBox b = new ThemedEditBox(this.font, x, clampBelowLogo(x, y, w), reserveScrollbar(x, w), 10, Component.empty());
        b.setMaxLength(512);
        b.setValue(value == null ? "" : value);
        return addRenderableWidget(b);
    }

    protected DmzDropdown dropdown(int x, int y, int w, List<Component> opts, int idx) {
        DmzDropdown d = new DmzDropdown(x, clampBelowLogo(x, y, w), reserveScrollbar(x, w), 11, opts, idx);
        dropdowns.add(d);
        return d;
    }

    // reserved-width rule: when this screen carries a scroll band (so a scrollbar may appear down the right edge),
    // trim a widget starting at x so its right edge stops one grid UNIT left of the scrollbar column and never
    // enters it. applied centrally in field/dropdown so no per-screen width has to change; a no-op when no band.
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

    protected void label(String text, int x, int y) {
        // a left-anchored label crosses the logo band only over its own text width; clamp it below the logo when
        // it would otherwise render under the wordmark
        int w = this.font == null || text == null ? 1 : this.font.width(text);
        labels.add(new Lbl(text, x, clampBelowLogo(x, y, w), 0xFFCFE8B0, false));
    }

    // left-aligned label in an explicit color
    protected void label(String text, int x, int y, int color) {
        int w = this.font == null || text == null ? 1 : this.font.width(text);
        labels.add(new Lbl(text, x, clampBelowLogo(x, y, w), color, false));
    }

    // resolve a translation key (optional args) via Component.translatable, not String.format, so a literal %
    // in help text ("gain %") is safe
    protected static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    // resolve an array of translation keys
    protected static String[] trAll(String[] keys) {
        String[] out = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            out[i] = Component.translatable(keys[i]).getString();
        }
        return out;
    }

    // label centered on cx
    protected void labelCentered(String text, int cx, int y, int color) {
        labels.add(new Lbl(text, cx, y, color, true));
    }

    // draw a tab bar of sections in x,y,w, highlighting active, calling onSelect(index) on click. tabs wrap onto
    // extra rows when they don't fit; returns the Y just below the bar. backdrops are the spliced dropdown.png
    // sheet, captions are theme-fitted so long (e.g. Spanish) names shrink/ellipsize instead of spilling.
    protected int tabs(int x, int y, int w, String[] sections, int active, java.util.function.IntConsumer onSelect) {
        int n = sections.length;
        int gap = GuiTheme.UNIT;
        int h = GuiTheme.BUTTON_HEIGHT;
        int rowGap = GuiTheme.UNIT;
        int pad = 8;   // horizontal room for text inside each cell
        // size columns to the widest caption (bounded to bar width) so labels aren't crammed into fixed cells
        int widest = 0;
        for (String s : sections) {
            widest = Math.max(widest, this.font.width(s));
        }
        int minCell = Math.min(w, widest + pad);
        int perRow = Math.max(1, Math.min(n, (w + gap) / (minCell + gap)));
        int rows = (n + perRow - 1) / perRow;
        int cellW = (w - gap * (perRow - 1)) / perRow;
        for (int i = 0; i < n; i++) {
            int row = i / perRow;
            int col = i % perRow;
            int cx = x + col * (cellW + gap);
            int cy = y + row * (h + rowGap);
            boolean on = i == active;
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

    // called when a dropdown row is chosen; override to react
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
        // grab a list scrollbar thumb?
        for (int i = 0; i < scrollHandles.size(); i++) {
            ScrollHandle h = scrollHandles.get(i);
            int barX = standardScrollbarX();
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
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Dev-only layout scan, once per rebuild. Runs here (not at the end of init) because subclasses build their
        // widgets AFTER calling super.init(), so the widget list is only complete by the first render. Checks the
        // registered AbstractWidgets against the panel's inner content rectangle for overflow + overlap. Open
        // dropdown lists float on top and are excluded (they are drawn separately, not registered as widgets).
        if (LayoutValidator.enabled() && !layoutValidated) {
            layoutValidated = true;
            // Validate against the panel's INNER rect (outer minus PANEL_INSET on every side) so any widget that
            // strays onto the border art band is flagged, including a footer button that overlaps the bottom border.
            LayoutValidator.validateInner(getClass(), children(), 0, 0, uiWidth, uiHeight);
        }
        renderBackground(g);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(originX(), originY(), 0);
        pose.scale((float) guiScale, (float) guiScale, 1.0f);

        int vmx = (int) Math.round(toVirtualX(mouseX));
        int vmy = (int) Math.round(toVirtualY(mouseY));

        renderPanel(g);
        // standard header: the umbrella logo centred at the top, at one fixed size on every screen. The optional
        // gray subtitle (a screen's live count/context) is drawn under the logo band, clamped clear of it.
        renderHeader(g);
        if (headerSubtitle != null) {
            g.drawCenteredString(this.font, headerSubtitle, uiWidth / 2, GuiTheme.LOGO_BOTTOM + 1, GuiTheme.COLOR_MUTED);
        }
        // Top-left entity name (the "named entity -> top left" rule). Left-aligned in the panel's top-left corner,
        // gold, fitted so a long name stops short of the centred logo instead of running under the wordmark.
        if (headerName != null && !headerName.isEmpty()) {
            int centre = uiWidth / 2;
            int nameRight = centre - GuiTheme.LOGO_HALF_WIDTH - GuiTheme.NAME_LOGO_GAP;
            int avail = Math.max(1, nameRight - GuiTheme.NAME_LEFT_X);
            String shown = font.width(headerName) > avail ? GuiText.ellipsize(font, headerName, avail) : headerName;
            g.drawString(this.font, shown, GuiTheme.NAME_LEFT_X, GuiTheme.NAME_TOP_Y, GuiTheme.COLOR_TITLE, false);
        }

        // themed tab-header backgrounds (spliced dropdown.png; active tab gets a brightness wash + gold underline).
        // tabs are added in the header (before any beginScrollBand) so they are never part of a content band.
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
                // reserve the width of any trailing control on this row: a left-aligned label is fitted into the
                // gap between its start and the leftmost widget that shares its text line and sits to its right, so
                // a row label never runs UNDER the Edit/Copy/Del buttons and never spills the panel.
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
        // commissioned scrollbar art (track + thumb) for registered lists, enqueued by scrollList
        for (ThemedBar b : themedBars) {
            ThemeRender.scrollbar(g, b.x(), b.top(), b.trackH(), b.thumbY(), b.thumbH(),
                    draggingScroll == b.handle());
        }

        super.render(g, vmx, vmy, partialTick);

        // the open dropdown list + popups must sit above everything (incl. batched label glyphs). elevate on Z
        // (like vanilla tooltips at z=400) so the depth test keeps them on top.
        pose.pushPose();
        pose.translate(0, 0, GuiTheme.Z_OVERLAY);
        if (openDropdown != null) {
            openDropdown.renderList(g, this.font, vmx, vmy);
        }
        renderTopOverlay(g, vmx, vmy);
        pose.popPose();

        String hover = hoveredTip(vmx, vmy);
        pose.popPose();

        // draw the tooltip unscaled (real screen space) so it stays readable at content scale
        if (hover != null) {
            g.renderTooltip(this.font, this.font.split(Component.literal(hover), 170), mouseX, mouseY);
        }
    }

    // right boundary a left-aligned label at (lx, ly) may extend to before it would collide with a trailing
    // control on its row. returns the leftmost x of any active on-row widget that starts to the right of the
    // label and whose vertical extent overlaps the label's text line, minus a small gap; the panel inner-right
    // edge otherwise. keeps list-row labels out from under the Edit/Copy/Del buttons with no per-screen reserve.
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

    // register a hover-help region (virtual coords), clamped below the logo the same way as its widget
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

    // hook for subclasses to draw a popup on top, inside the scaled canvas
    protected void renderTopOverlay(GuiGraphics g, int vmx, int vmy) {
    }

    // expose the DmzDropdown x so labelRightBoundary can read it (added getter on DmzDropdown mirrors sdu)
}
