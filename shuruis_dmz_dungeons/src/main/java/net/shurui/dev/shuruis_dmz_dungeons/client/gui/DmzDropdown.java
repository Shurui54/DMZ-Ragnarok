package net.shurui.dev.shuruis_dmz_dungeons.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_dmz_dungeons.client.gui.theme.GuiText;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_dmz_dungeons.client.gui.theme.ThemeRender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// lightweight dropdown styled like the DMZ menus. NOT a vanilla widget: the owning ScaledScreen draws the
// collapsed header inline and the open list as a top-most overlay, routing clicks/scroll in virtual coords, which
// keeps the open list on top without fighting vanilla's render order. optionally searchable() (header doubles as
// a case-insensitive substring filter). list has a click-drag scrollbar thumb.
public class DmzDropdown {

    private static final int ROW_H = GuiTheme.DROPDOWN_ROW_HEIGHT;
    private static final int MAX_VISIBLE = 6;
    // width of the commissioned scrollbar art, drawn 1:1 (never widened)
    private static final int SCROLLBAR_W = GuiTheme.SCROLLBAR_WIDTH;

    private final int x, y, w, h;
    private final List<Component> options;
    private int index;
    private int scroll;

    private boolean searchable;
    private String query = "";
    // option indices matching query, i.e. the currently visible/scrollable set
    private final List<Integer> filtered = new ArrayList<>();
    private boolean draggingThumb;

    // checklist mode: rows toggle membership in `selected` and the list stays open
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

    // turn on the in-header search box; fluent so you can chain it
    public DmzDropdown searchable() {
        this.searchable = true;
        refilter();
        return this;
    }

    public boolean isSearchable() {
        return searchable;
    }

    // make this a multi-select checklist
    public DmzDropdown multiSelect() {
        this.multiSelect = true;
        return this;
    }

    public boolean isMultiSelect() {
        return multiSelect;
    }

    // toggle an option's checked state (multi-select mode)
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

    // virtual-canvas Y of the collapsed header (scroll band uses it to hide off-band dropdowns)
    public int getY() {
        return y;
    }

    // virtual-canvas X of the collapsed header (used by row-label boundary maths)
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

    // clear the typed search and reset scroll; call when the list (re)opens
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

    // real option index under the cursor while open, or -1
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

    // is the point on the open list's scrollbar track?
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

    // drag the thumb so its centre follows the cursor, snapping scroll to the nearest row
    public void dragTo(double my) {
        int maxScroll = Math.max(0, filtered.size() - visibleRows());
        if (maxScroll == 0) {
            scroll = 0;
            return;
        }
        int listH = listHeight();
        int thumbH = thumbHeight();
        // invert the SAME clamped geometry the thumb is drawn with (GuiTheme.scrollThumbY): the drag band is
        // inset by SCROLLBAR_THUMB_INSET from each track end, so the drag can't desync from the drawn thumb.
        double bandTop = listTop() + GuiTheme.SCROLLBAR_THUMB_INSET;
        double bandBottom = listTop() + listH - GuiTheme.SCROLLBAR_THUMB_INSET;
        double travel = (bandBottom - bandTop) - thumbH;
        double t = travel <= 0 ? 0 : (my - bandTop - thumbH / 2.0) / travel;
        scroll = (int) Math.round(Math.max(0.0, Math.min(1.0, t)) * maxScroll);
    }

    // append a typed char to the search query (no-op unless searchable)
    public void charTyped(char c) {
        if (!searchable || c < ' ') {
            return;
        }
        query += c;
        scroll = 0;
        refilter();
    }

    // handle backspace on the search query; returns true if consumed
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

    // draw the collapsed selector: themed backdrop + current value (or search box) + arrow
    public void renderHeader(GuiGraphics g, Font font, int mouseX, int mouseY, boolean open) {
        // themed dropdown header (nine-sliced fill) with a hover/open wash. the arrow gutter on the right is
        // reserved so the value text is fitted into the space left of it and never runs under the arrow.
        ThemeRender.dropdown(g, x, y, w, h);
        if (headerContains(mouseX, mouseY) || open) {
            g.fill(x, y, x + w, y + h, GuiTheme.COLOR_HOVER_WASH);
        }
        int arrowGutter = 10;
        // shift text right by DROPDOWN_TEXT_INSET so it clears the bevel; reduce the fitted width by the same
        // amount so it still stops before the arrow gutter (which is preserved on the right).
        int inset = GuiTheme.DROPDOWN_TEXT_INSET;
        int tX = x + inset;
        int tW = Math.max(1, w - inset - arrowGutter);
        int textX = tX + GuiTheme.TEXT_PADDING_X;
        if (open && searchable) {
            boolean caret = (System.currentTimeMillis() / 500) % 2 == 0;
            if (query.isEmpty()) {
                GuiText.drawFitted(g, font, Component.translatable("gui.dmz_ragnarok.dungeons.dropdown.search").getString(),
                        tX, y, tW, h, 0xFF8A8A8A);
                if (caret) {
                    g.drawString(font, "_", textX, GuiText.centeredTextY(font, y, h), 0xFFFFFFFF, false);
                }
            } else {
                GuiText.drawFitted(g, font, query + (caret ? "_" : ""), tX, y, tW, h, 0xFFFFFFFF);
            }
        } else if (multiSelect) {
            GuiText.drawFitted(g, font, multiSummary(font), tX, y, tW, h, GuiTheme.COLOR_VALUE);
        } else {
            String label = options.isEmpty()
                    ? Component.translatable("gui.dmz_ragnarok.dungeons.dropdown.empty").getString()
                    : options.get(index).getString();
            GuiText.drawFitted(g, font, label, tX, y, tW, h, GuiTheme.COLOR_VALUE);
        }
        g.drawString(font, open ? "▲" : "▼", x + w - arrowGutter, GuiText.centeredTextY(font, y, h),
                GuiTheme.COLOR_LABEL, false);
    }

    // short summary of checked options for the collapsed multi-select header
    private String multiSummary(Font font) {
        if (selected.isEmpty()) {
            return net.minecraft.client.resources.language.I18n.get("gui.dmz_ragnarok.dungeons.common.none");
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

    // draw the expanded list; call last so it sits on top of everything
    public void renderList(GuiGraphics g, Font font, int mouseX, int mouseY) {
        int top = listTop();
        int rows = visibleRows();
        int listH = rows * ROW_H;
        // the open list body is the commissioned dropdown (alt) art, spliced to size as ONE element (gradient
        // caps top/bottom, flat tiled middle).
        ThemeRender.dropdownList(g, x, top, w, listH);

        // same left inset as the header so open-list rows never hug the border either
        int inset = GuiTheme.DROPDOWN_TEXT_INSET;

        if (filtered.isEmpty()) {
            GuiText.drawFitted(g, font, Component.translatable("gui.dmz_ragnarok.dungeons.dropdown.no_matches").getString(),
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
            // selection/hover are subtle OVERLAYS on the single spliced list body (not redrawn per row): selected
            // row a soft grey wash, hovered row a faint green wash. neither is a texture blit, so no per-row gradient.
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

        // commissioned scrollbar art (track + thumb) on the right when the list overflows, at native width just
        // inside the list's right edge. thumb clamped fully inside the track; dragTo inverts the same band.
        if (overflow()) {
            int barX = x + w - SCROLLBAR_W;
            int maxScroll = filtered.size() - rows;
            int thumbH = thumbHeight();
            int thumbY = GuiTheme.scrollThumbY(top, listH, thumbH, scroll, maxScroll);
            ThemeRender.scrollbar(g, barX, top, listH, thumbY, thumbH);
        }
    }
}
