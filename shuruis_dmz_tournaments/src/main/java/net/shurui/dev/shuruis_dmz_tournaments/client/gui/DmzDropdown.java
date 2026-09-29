package net.shurui.dev.shuruis_dmz_tournaments.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_dmz_tournaments.client.gui.theme.GuiText;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_dmz_tournaments.client.gui.theme.ThemeRender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A lightweight dropdown selector styled to match the DMZ menus. Not a vanilla widget: the owning
 * {@link ScaledScreen} renders the collapsed header inline, renders the expanded list as a top-most
 * overlay, and routes clicks/scroll in virtual coordinates. This keeps the open list above other
 * widgets without fighting vanilla's render order.
 *
 * <p>Optionally {@linkplain #searchable() searchable}: while open, the collapsed header doubles as a
 * text field and typing filters the visible options (case-insensitive substring match). Because the
 * search box reuses the header's footprint it costs no extra layout space. The list also has a
 * click-and-drag scrollbar thumb.
 */
public class DmzDropdown {

    private static final int ROW_H = GuiTheme.DROPDOWN_ROW_HEIGHT;
    private static final int MAX_VISIBLE = 6;
    /** Width of the commissioned scrollbar art, drawn 1:1 (never widened). */
    private static final int SCROLLBAR_W = GuiTheme.SCROLLBAR_WIDTH;

    private final int x, y, w, h;
    private final List<Component> options;
    private int index;
    private int scroll;

    /** Whether the header becomes a search field while open. */
    private boolean searchable;
    /** Current search text (only meaningful when {@link #searchable}). */
    private String query = "";
    /** Option indices that match {@link #query}; the visible/scrollable set. */
    private final List<Integer> filtered = new ArrayList<>();
    /** True while the user drags the scrollbar thumb. */
    private boolean draggingThumb;

    /** When true the list is a checklist: rows toggle membership in {@link #selected} and stay open. */
    private boolean multiSelect;
    private final java.util.Set<Integer> selected = new java.util.LinkedHashSet<>();

    public DmzDropdown(int x, int y, int w, int h, List<Component> options, int index) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.options = options;
        this.index = Math.max(0, Math.min(index, Math.max(0, options.size() - 1)));
        refilter();
    }

    /** Enable the in-header search box. Fluent so it can be chained onto construction/registration. */
    public DmzDropdown searchable() {
        this.searchable = true;
        refilter();
        return this;
    }

    public boolean isSearchable() {
        return searchable;
    }

    /** Turn this into a multi-select checklist. */
    public DmzDropdown multiSelect() {
        this.multiSelect = true;
        return this;
    }

    public boolean isMultiSelect() {
        return multiSelect;
    }

    /** Toggle whether an option is selected (multi-select mode). */
    public void toggle(int optionIndex) {
        if (optionIndex < 0 || optionIndex >= options.size()) {
            return;
        }
        if (!selected.remove(optionIndex)) {
            selected.add(optionIndex);
        }
    }

    public void setSelected(java.util.Collection<Integer> indices) {
        selected.clear();
        for (Integer i : indices) {
            if (i != null && i >= 0 && i < options.size()) {
                selected.add(i);
            }
        }
    }

    public java.util.List<Integer> getSelected() {
        return new java.util.ArrayList<>(selected);
    }

    public int getIndex() {
        return index;
    }

    public int getY() {
        return y;
    }

    public int getX() {
        return x;
    }

    public boolean headerContains(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void refilter() {
        filtered.clear();
        if (!searchable || query.isEmpty()) {
            for (int i = 0; i < options.size(); i++) {
                filtered.add(i);
            }
        } else {
            String q = query.toLowerCase(Locale.ROOT);
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i).getString().toLowerCase(Locale.ROOT).contains(q)) {
                    filtered.add(i);
                }
            }
        }
        clampScroll();
    }

    private void clampScroll() {
        int max = Math.max(0, filtered.size() - visibleRows());
        scroll = Math.max(0, Math.min(scroll, max));
    }

    /** Clear any typed search and reset the scroll; call when the list is (re)opened. */
    public void resetSearch() {
        query = "";
        scroll = 0;
        refilter();
    }

    private int visibleRows() {
        return Math.min(MAX_VISIBLE, Math.max(1, filtered.size()));
    }

    private int listTop() {
        return y + h;
    }

    private int listHeight() {
        return visibleRows() * ROW_H;
    }

    private boolean overflow() {
        return filtered.size() > visibleRows();
    }

    /** Actual option index under the cursor while open, or -1. */
    public int rowAt(double mx, double my) {
        if (mx < x || mx >= x + w) {
            return -1;
        }
        int top = listTop();
        if (my < top || my >= top + listHeight()) {
            return -1;
        }
        int visRow = scroll + (int) ((my - top) / ROW_H);
        return (visRow >= 0 && visRow < filtered.size()) ? filtered.get(visRow) : -1;
    }

    public void select(int optionIndex) {
        if (optionIndex >= 0 && optionIndex < options.size()) {
            this.index = optionIndex;
        }
    }

    public void scroll(double delta) {
        int max = Math.max(0, filtered.size() - visibleRows());
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(delta)));
    }

    private int thumbHeight() {
        int listH = listHeight();
        return Math.max(8, listH * visibleRows() / Math.max(1, filtered.size()));
    }

    /** True if the point is on the scrollbar track of the open list. */
    public boolean scrollbarAt(double mx, double my) {
        if (!overflow()) {
            return false;
        }
        int barX = x + w - SCROLLBAR_W;
        int top = listTop();
        return mx >= barX - 1 && mx < x + w && my >= top && my < top + listHeight();
    }

    public void beginDrag(double my) {
        draggingThumb = true;
        dragTo(my);
    }

    public void endDrag() {
        draggingThumb = false;
    }

    public boolean isDragging() {
        return draggingThumb;
    }

    /** Move the thumb so its centre tracks the cursor, snapping scroll to the nearest row offset. */
    public void dragTo(double my) {
        int maxScroll = Math.max(0, filtered.size() - visibleRows());
        if (maxScroll == 0) {
            scroll = 0;
            return;
        }
        int listH = listHeight();
        int thumbH = thumbHeight();
        // Invert the SAME clamped geometry the thumb is drawn with (GuiTheme.scrollThumbY): the drag band is
        // inset by SCROLLBAR_THUMB_INSET at each end, so the drag can't desync from the drawn thumb.
        double bandTop = listTop() + GuiTheme.SCROLLBAR_THUMB_INSET;
        double bandBottom = listTop() + listH - GuiTheme.SCROLLBAR_THUMB_INSET;
        double travel = (bandBottom - bandTop) - thumbH;
        double t = travel <= 0 ? 0 : (my - bandTop - thumbH / 2.0) / travel;
        scroll = (int) Math.round(Math.max(0.0, Math.min(1.0, t)) * maxScroll);
    }

    /** Append a typed character to the search query (no-op unless searchable). */
    public void charTyped(char c) {
        if (!searchable || c < ' ') {
            return;
        }
        query += c;
        scroll = 0;
        refilter();
    }

    /** Handle backspace for the search query; returns true if the key was consumed. */
    public boolean keyPressed(int keyCode) {
        if (!searchable) {
            return false;
        }
        if (keyCode == 259 /* GLFW_KEY_BACKSPACE */) {
            if (!query.isEmpty()) {
                query = query.substring(0, query.length() - 1);
                scroll = 0;
                refilter();
            }
            return true;
        }
        return false;
    }

    /** Draw the collapsed selector (backdrop + current value or search box + arrow). */
    public void renderHeader(GuiGraphics g, Font font, int mouseX, int mouseY, boolean open) {
        // Themed header (nine-sliced fill) with a hover/open wash. The arrow gutter on the right is reserved
        // so the value text stops before it and never runs under the arrow.
        ThemeRender.dropdown(g, x, y, w, h);
        if (headerContains(mouseX, mouseY) || open) {
            g.fill(x, y, x + w, y + h, GuiTheme.COLOR_HOVER_WASH);
        }
        int arrowGutter = 10;
        // Shift text right by DROPDOWN_TEXT_INSET to clear the bevel; cut the fitted width by the same so it
        // still stops before the arrow gutter.
        int inset = GuiTheme.DROPDOWN_TEXT_INSET;
        int tX = x + inset;
        int tW = Math.max(1, w - inset - arrowGutter);
        int textX = tX + GuiTheme.TEXT_PADDING_X;
        int textH = h;
        if (open && searchable) {
            boolean caret = (System.currentTimeMillis() / 500) % 2 == 0;
            if (query.isEmpty()) {
                GuiText.drawFitted(g, font,
                        Component.translatable("gui.dmz_ragnarok.tournaments.dropdown.search").getString(),
                        tX, y, tW, textH, 0xFF8A8A8A);
                if (caret) {
                    g.drawString(font, "_", textX, GuiText.centeredTextY(font, y, h), 0xFFFFFFFF, false);
                }
            } else {
                GuiText.drawFitted(g, font, query + (caret ? "_" : ""), tX, y, tW, textH, 0xFFFFFFFF);
            }
        } else if (multiSelect) {
            GuiText.drawFitted(g, font, multiSummary(font), tX, y, tW, textH, GuiTheme.COLOR_VALUE);
        } else {
            String label = options.isEmpty()
                    ? Component.translatable("gui.dmz_ragnarok.tournaments.dropdown.empty").getString()
                    : options.get(index).getString();
            GuiText.drawFitted(g, font, label, tX, y, tW, textH, GuiTheme.COLOR_VALUE);
        }
        g.drawString(font, open ? "▲" : "▼", x + w - arrowGutter, GuiText.centeredTextY(font, y, h),
                GuiTheme.COLOR_LABEL, false);
    }

    /** A short summary of the checked options for the collapsed multi-select header. */
    private String multiSummary(Font font) {
        if (selected.isEmpty()) {
            return "§7" + net.minecraft.client.resources.language.I18n.get("gui.dmz_ragnarok.tournaments.common.none");
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i : selected) {
            if (i < 0 || i >= options.size()) {
                continue;
            }
            String opt = options.get(i).getString();
            String next = sb.length() == 0 ? opt : sb + ", " + opt;
            if (font.width(next) > w - 20 && shown > 0) {
                sb.append(" +").append(selected.size() - shown);
                return sb.toString();
            }
            sb.setLength(0);
            sb.append(next);
            shown++;
        }
        return sb.toString();
    }

    /** Draw the expanded list; call after everything else so it sits on top. */
    public void renderList(GuiGraphics g, Font font, int mouseX, int mouseY) {
        int top = listTop();
        int rows = visibleRows();
        int listH = rows * ROW_H;
        // Open list body is the commissioned dropdown (alt) art, spliced to size. No solid-colour fill under
        // or over it.
        ThemeRender.dropdownList(g, x, top, w, listH);

        // same left inset as the header, so open-list rows never hug the border
        int inset = GuiTheme.DROPDOWN_TEXT_INSET;

        if (filtered.isEmpty()) {
            GuiText.drawFitted(g, font,
                    Component.translatable("gui.dmz_ragnarok.tournaments.dropdown.no_matches").getString(),
                    x + inset, top, w - inset, ROW_H, 0xFF808080);
            return;
        }

        int textRight = overflow() ? x + w - SCROLLBAR_W - 1 : x + w;
        for (int i = 0; i < rows; i++) {
            int visRow = scroll + i;
            if (visRow >= filtered.size()) {
                break;
            }
            int optIndex = filtered.get(visRow);
            int ry = top + i * ROW_H;
            // Selection/hover are subtle OVERLAYS on the single spliced body (not redrawn per row): selected
            // row a soft grey wash, hovered row a faint green wash.
            boolean selectedRow = !multiSelect && optIndex == index;
            if (selectedRow) {
                g.fill(x, ry, textRight, ry + ROW_H, 0x40FFFFFF);
            }
            boolean hovered = mouseX >= x && mouseX < textRight && mouseY >= ry && mouseY < ry + ROW_H;
            if (hovered) {
                g.fill(x, ry, textRight, ry + ROW_H, 0x556AB07A);
            }
            if (multiSelect) {
                boolean sel = selected.contains(optIndex);
                g.drawString(font, sel ? "§a[x]" : "§7[ ]", x + inset + 4,
                        GuiText.centeredTextY(font, ry, ROW_H), 0xFFFFFFFF, false);
                int textLeft = x + inset + 28;
                int optW = textRight - textLeft;
                GuiText.drawFitted(g, font, options.get(optIndex).getString(), textLeft - GuiTheme.TEXT_PADDING_X,
                        ry, optW + 2 * GuiTheme.TEXT_PADDING_X, ROW_H, sel ? GuiTheme.COLOR_VALUE : GuiTheme.COLOR_ROW);
            } else {
                int color = optIndex == index ? GuiTheme.COLOR_VALUE : GuiTheme.COLOR_ROW;
                GuiText.drawFitted(g, font, options.get(optIndex).getString(), x + inset, ry,
                        textRight - x - inset, ROW_H, color);
            }
        }

        // Commissioned scrollbar art (track + thumb) on the right when the list overflows, at its native 12px
        // width just inside the list's right edge.
        if (overflow()) {
            int barX = x + w - SCROLLBAR_W;
            int maxScroll = filtered.size() - rows;
            int thumbH = thumbHeight();
            // Clamp the thumb fully inside the track (matches BaseEditScreen); dragTo inverts the same band.
            int thumbY = GuiTheme.scrollThumbY(top, listH, thumbH, scroll, maxScroll);
            ThemeRender.scrollbar(g, barX, top, listH, thumbY, thumbH);
        }
    }
}
