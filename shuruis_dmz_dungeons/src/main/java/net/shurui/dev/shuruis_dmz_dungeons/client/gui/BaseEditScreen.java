package net.shurui.dev.shuruis_dmz_dungeons.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.shurui.dev.shuruis_dmz_dungeons.client.gui.theme.GuiSounds;
import net.shurui.dev.shuruis_dmz_dungeons.client.gui.theme.GuiText;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_dmz_dungeons.client.gui.theme.ThemeRender;
import net.shurui.dev.shuruis_dmz_dungeons.client.gui.theme.ThemedEditBox;

import java.util.ArrayList;
import java.util.List;

// shared base for the editor screens on the "Shurui's DMZ Essentials" theme: a spliced panel, the logo header at
// one fixed size, a dropdown host (open list on top, mouse routed in virtual coords), and helpers for buttons,
// fields, labels, rects, scrollable lists, tooltips and the tab bar. subclasses build widgets in init() after super.
public abstract class BaseEditScreen extends ScaledScreen {

    protected record Lbl(String text, int x, int y, int color, boolean centered) {
    }

    // a clickable tab-bar cell (virtual coords) that selects section `index` on press
    private record TabRegion(int x, int y, int w, int h, int index, java.util.function.IntConsumer onSelect) {
    }

    // a themed tab-header cell background to blit during render (spliced dropdown.png, active/inactive)
    private record TabBg(int x, int y, int w, int h, boolean active) {
    }

    // a caption fitted (shrunk/ellipsized) into a bounding box; used for tab labels so text never overflows
    private record Caption(String text, int x, int y, int w, int h, int color) {
    }

    // a themed scrollbar (track + thumb) to blit during render, enqueued by scrollList / finishScrollBand so it
    // uses the commissioned art instead of a flat rect fill
    private record ThemedBar(int x, int top, int trackH, int thumbY, int thumbH) {
    }

    // a scrollable list region (registered via scrollList): geometry for wheel/drag input plus a setter to
    // change the scroll offset
    private record ScrollHandle(int left, int right, int top, int listH, int rowH, int cap, int count,
                                int scroll, java.util.function.IntConsumer setter) {
    }

    protected final List<DmzDropdown> dropdowns = new ArrayList<>();
    protected final List<Lbl> labels = new ArrayList<>();
    protected final List<int[]> rects = new ArrayList<>();
    private final List<Caption> captions = new ArrayList<>();
    private final List<TabRegion> tabRegions = new ArrayList<>();
    private final List<TabBg> tabBgs = new ArrayList<>();
    private final List<ScrollHandle> scrollHandles = new ArrayList<>();
    private final List<ThemedBar> themedBars = new ArrayList<>();
    // optional gray subtitle under the title (kept for subclasses; not drawn in the logo header anymore)
    protected String headerSubtitle = null;
    // scrollHandles index whose thumb is being dragged, or -1
    private int draggingScroll = -1;
    protected DmzDropdown openDropdown;
    protected final Screen parent;
    // true once this screen instance has played its open sound. Set on the FIRST init only and never reset, so a
    // resize or rebuildWidgets() does not replay it.
    private boolean openSoundPlayed;

    // optional content scroll band, off by default (whole panel drawn). beginScrollBand(...) marks the
    // content that follows: items outside [bandTop, bandBottom] aren't drawn or hit-tested, so a scrolled
    // section clips cleanly. finishScrollBand(...) computes maxScroll, clamps and draws the thumb.
    protected int scroll;
    protected int maxScroll;
    protected int bandTop = Integer.MIN_VALUE;
    protected int bandBottom = Integer.MAX_VALUE;
    private int contentLabelStart = Integer.MAX_VALUE;
    private int contentRectStart = Integer.MAX_VALUE;
    private int contentDropdownStart = Integer.MAX_VALUE;
    private int contentTipStart = Integer.MAX_VALUE;
    private int contentCaptionStart = Integer.MAX_VALUE;

    // mark everything added from here on as scrollable content and set the visible band
    protected void beginScrollBand(int top, int bottom) {
        bandTop = top;
        bandBottom = bottom;
        contentLabelStart = labels.size();
        contentRectStart = rects.size();
        contentDropdownStart = dropdowns.size();
        contentTipStart = tips.size();
        contentCaptionStart = captions.size();
    }

    // is virtual-y within the visible band? (always true when no band is set)
    protected boolean inBand(int y) {
        return y >= bandTop && y <= bandBottom;
    }

    private boolean labelHidden(int i, int y) {
        return i >= contentLabelStart && !inBand(y);
    }

    private boolean rectHidden(int i, int y) {
        return i >= contentRectStart && !inBand(y);
    }

    private boolean captionHidden(int i, int y) {
        return i >= contentCaptionStart && !inBand(y);
    }

    protected boolean dropdownHidden(int i, int y) {
        return i >= contentDropdownStart && !inBand(y);
    }

    // hide the text boxes/buttons that scrolled outside the band
    protected void clampContentWidgets(int top, int bottom) {
        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget w) {
                boolean visible = w.getY() >= top && w.getY() <= bottom;
                w.visible = visible;
                w.active = visible;
            }
        }
    }

    // close the band: clamp scroll to maxScroll, hide off-band widgets, draw the themed thumb if it overflows.
    // call after building the section rows, but BEFORE adding pinned buttons that live outside the band.
    protected void finishScrollBand(int contentTop, int contentBottom) {
        scroll = Mth.clamp(scroll, 0, maxScroll);
        clampContentWidgets(contentTop, contentBottom);
        if (maxScroll > 0) {
            int bandH = contentBottom - contentTop;
            // constrain the bar inside the panel content area so the track never sits on the border
            int barX = scrollbarColumnX(bandRight());
            int thumbH = Math.max(8, (int) ((long) bandH * bandH / (bandH + maxScroll)));
            int thumbY = GuiTheme.scrollThumbY(contentTop, bandH, thumbH, scroll, maxScroll);
            themedBars.add(new ThemedBar(barX, contentTop, bandH, thumbY, thumbH));
        }
    }

    // X of the scroll-band scrollbar's right edge. defaults to the panel's inner right.
    protected int bandRight() {
        return uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET;
    }

    // left X at which a scrollbar whose desired right edge is `desiredRight` should be drawn, clamped so the
    // whole SCROLLBAR_WIDTH-wide bar stays inside the panel's inner content area (never crossing the border).
    protected int scrollbarColumnX(int desiredRight) {
        int maxRight = uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET;
        int right = Math.min(desiredRight, maxRight);
        return right - GuiTheme.SCROLLBAR_WIDTH;
    }

    // true while any content band on this screen shows a scrollbar (overflowing). fields/dropdowns reserve the
    // scrollbar column only then (see reserveScrollbar).
    protected boolean hasBandScrollbar() {
        return maxScroll > 0;
    }

    // a hover-help region (virtual coords) whose wrapped text is drawn as a tooltip
    private record Tip(int x, int y, int w, int h, String text) {
    }

    private final List<Tip> tips = new ArrayList<>();

    protected BaseEditScreen(Component title, int w, int h, Screen parent) {
        super(title, w, h);
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        // screen-open sound, exactly once per open (see openSoundPlayed).
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
        bandTop = Integer.MIN_VALUE;
        bandBottom = Integer.MAX_VALUE;
        contentLabelStart = Integer.MAX_VALUE;
        contentRectStart = Integer.MAX_VALUE;
        contentDropdownStart = Integer.MAX_VALUE;
        contentTipStart = Integer.MAX_VALUE;
        contentCaptionStart = Integer.MAX_VALUE;
        maxScroll = 0;
        // do NOT reset scroll here: a wheel/section rebuild re-runs init and has to keep the current offset.
        // subclasses zero it themselves when the tab changes.
        // do NOT reset draggingScroll here either: a scrollbar drag calls rebuildWidgets (re-init) every
        // step, and the drag has to survive that until mouseReleased.
    }

    protected void rect(int x, int y, int w, int h, int color) {
        rects.add(new int[]{x, clampBelowLogo(x, y, w), w, h, color});
    }

    // register (and draw the themed scrollbar for) a vertically scrollable list. call from init() after laying out
    // the visible rows. left..right is the wheel hit-box. count = total items, cap = how many fit, scroll = current
    // top index, setter takes the new offset (usually v -> { this.scroll = v; rebuildWidgets(); }).
    protected void scrollList(int left, int right, int top, int rowH, int cap, int count,
                              int scroll, java.util.function.IntConsumer setter) {
        int listH = cap * rowH;
        if (count > cap) {
            // commissioned scrollbar art just inside the list's right edge, clamped inside the panel border
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
        // invert the SAME clamped geometry the thumb is drawn with (GuiTheme.scrollThumbY): the drag band is
        // inset by SCROLLBAR_THUMB_INSET from each track end, so the drag can't desync from the drawn thumb.
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

    // a committing button (Save / Add / Select): identical to btn but its press plays DMZ's confirm sound
    protected DmzTextureButton commitBtn(int x, int y, int w, int h, Component label, Runnable onPress) {
        return btn(x, y, w, h, label, onPress).commits();
    }

    // The Y (top edge) for this screen's footer action row, derived from the runtime canvas height so a
    // BUTTON_HEIGHT-tall footer button's bottom lands clear of the panel's bottom border (see GuiTheme.footerY).
    // Footer buttons must be built at footerBtnHeight() tall for the arithmetic to hold.
    protected int footerY() {
        return GuiTheme.footerY(uiHeight);
    }

    /**
     * Left edge of a full-width content row, mirroring the scrollbar's reserved column on the other side. A row
     * built between this and {@link #rowControlRight()} is CENTRED on the panel and clear of the scrollbar, so it
     * neither drifts left when the canvas widens nor slides when a list starts overflowing and the bar appears.
     */
    protected int rowBandLeft() {
        return uiWidth - rowControlRight();
    }

    /** Width of that centred, scrollbar-clear row band. */
    protected int rowBandWidth() {
        return Math.max(1, rowControlRight() - rowBandLeft());
    }

    /**
     * How many {@code rowH}-tall rows fit between {@code listTop} and the start of the footer band. Use this
     * instead of a hardcoded row cap: lists were written against each screen's old height, so a fixed cap now stops
     * the list part way down and leaves the rest blank while the scrollbar insists there is more (the Groups list
     * showing 9 of 14). Deriving the cap fills the space given. Always at least 1.
     */
    protected int rowsThatFit(int listTop, int rowH) {
        return rowsThatFit(listTop, rowH, 0);
    }

    /**
     * As {@link #rowsThatFit(int, int)}, but holding {@code reserveBelow} px clear underneath the list. Pass this
     * whenever the screen draws anything BELOW its list (an add-row, a name field, a total). Without it the list
     * runs to the footer and grows over that content, leaving the widgets underneath unreachable.
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

    // the X at which a trailing row control (a list row's delete/arrow icon button) may put its RIGHT edge so it
    // never sits under the scrollbar. identical reservation the field/dropdown width rule uses.
    protected int rowControlRight() {
        return uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET - GuiTheme.SCROLLBAR_WIDTH - GuiTheme.UNIT;
    }

    // clamp a per-screen first-content Y up to CONTENT_TOP so content never begins inside the logo header band
    protected int contentTop(int desiredY) {
        return Math.max(GuiTheme.CONTENT_TOP, desiredY);
    }

    // logo-overlap guard applied INSIDE every shared placement helper (label, rect, field, dropdown, btn,
    // tooltip), so a screen cannot draw an element on top of the header logo even with a hand-placed Y. NARROW: an
    // element is pushed down to CONTENT_TOP only when its Y is above LOGO_BOTTOM AND its span crosses the centred
    // logo's band. content beside the logo, and footer/body content, is never moved.
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

    // compact themed text input so more rows fit without shrinking the panel. ThemedEditBox reskins only the
    // background; caret/selection/scroll/keyboard are delegated to vanilla. returns EditBox so existing callers
    // compile. reserves the scrollbar column when a band is present.
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

    // reserved-width rule (defect: fields/dropdowns ran under the scrollbar). when this screen carries a scroll
    // band (so a scrollbar may appear), trim a widget starting at x so its right edge stops at the scrollbar
    // column left minus one grid UNIT. when no band is set the width is returned unchanged.
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
        int w = this.font == null || text == null ? 1 : this.font.width(text);
        labels.add(new Lbl(text, x, clampBelowLogo(x, y, w), GuiTheme.COLOR_LABEL, false));
    }

    // label centred horizontally on cx (titles/subtitles, tab captions)
    protected void labelCentered(String text, int cx, int y, int color) {
        labels.add(new Lbl(text, cx, y, color, true));
    }

    // draw a horizontal tab bar inside x,y,w, active tab in gold, onSelect(index) on click. tabs wrap onto extra
    // rows when they don't fit. backdrops are spliced dropdown.png (active tab gets a brightness wash); captions
    // are fitted so long names shrink/ellipsize. returns the Y just below the bar.
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

    // fired when a dropdown row is chosen; override to react (e.g. rebuild type-specific fields)
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
                    openDropdown.toggle(row);       // stay open so you can check several
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
        // grabbing a list scrollbar thumb?
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
        // content-band scroll is the fallback: no dropdown open, no list handle caught the wheel, band
        // overflows, cursor inside the band.
        if (maxScroll > 0 && inBand((int) Math.round(vy))) {
            int ns = Mth.clamp(scroll - (int) Math.signum(delta) * ROW_STEP, 0, maxScroll);
            if (ns != scroll) {
                beforeScrollRebuild(); // flush unsaved edits before init() rebuilds the widgets
                scroll = ns;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    // pixels scrolled per wheel notch for the content band
    protected int ROW_STEP = 24;

    // hook: runs just before a content-scroll rebuildWidgets() so a subclass can flush edits
    protected void beforeScrollRebuild() {
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

        // spliced panel + the umbrella logo centred at one fixed size on every screen (the shared header).
        ThemeRender.panel(g, 0, 0, uiWidth, uiHeight);
        ThemeRender.header(g, 0, 0, uiWidth);

        // themed tab-header backgrounds (spliced dropdown.png; active tab gets a brightness wash + gold
        // underline). tabs are added in the header before any beginScrollBand, so they need no clip check.
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
                // reserve the width of any trailing control on this row so a left-aligned label never runs UNDER
                // the Edit/Copy/Del buttons; fit (ellipsize) it into the gap before that widget.
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
        // commissioned scrollbar art for registered lists / content bands (track + thumb).
        for (ThemedBar b : themedBars) {
            ThemeRender.scrollbar(g, b.x(), b.top(), b.trackH(), b.thumbY(), b.thumbH());
        }

        super.render(g, vmx, vmy, partialTick);

        // open dropdown list + any popup must sit above everything, including the batched label/widget glyphs.
        // bump to Z_OVERLAY (same as vanilla tooltips) so the depth test keeps them on top and text stays readable.
        pose.pushPose();
        pose.translate(0, 0, GuiTheme.Z_OVERLAY);
        if (openDropdown != null) {
            openDropdown.renderList(g, this.font, vmx, vmy);
        }
        renderTopOverlay(g, vmx, vmy);
        pose.popPose();

        String hover = hoveredTip(vmx, vmy);
        pose.popPose();

        // draw the tooltip in real (unscaled) screen space so it stays readable at content scale
        if (hover != null) {
            g.renderTooltip(this.font, this.font.split(Component.literal(hover), 170), mouseX, mouseY);
        }
    }

    // right boundary a left-aligned label at (lx,ly) may extend to before it collides with a trailing control on
    // its row. keeps list-row labels out from under the Edit/Copy/Del buttons without any screen reserving width.
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

    // register a hover-help region (virtual coords), clamped below the logo the same way as the widget it describes
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

    // hook for subclasses to draw a popup (e.g. the colour picker) on top, inside the scaled canvas
    protected void renderTopOverlay(GuiGraphics g, int vmx, int vmy) {
    }
}
