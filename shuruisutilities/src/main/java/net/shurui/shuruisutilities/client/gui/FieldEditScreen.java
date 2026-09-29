package net.shurui.shuruisutilities.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

// SagaBaseScreen + a labelled-field toolkit for the config editors: text (tf), toggles (bf), searchable
// single/multi dropdowns (df/dfMulti) and color swatches (cf) that open a DmzColorPicker. subclasses lay rows
// with the rowY cursor, clearFields() in init(), and read back with applyFields(). extra dropdowns via
// onExtraDropdown.
public abstract class FieldEditScreen extends SagaBaseScreen {

    protected static final int ROW_H = 12;

    private record TextEntry(EditBox box, Consumer<String> setter) {
    }

    private record ColorRow(int x, int y, int w, int h, Supplier<String> getter, Consumer<String> setter) {
    }

    private final List<TextEntry> textEntries = new ArrayList<>();
    private final List<ColorRow> colorRows = new ArrayList<>();
    private final Map<DmzDropdown, Runnable> dropdownSync = new HashMap<>();
    private DmzColorPicker openPicker;
    private Consumer<String> pickerSetter;
    protected int rowY;

    protected FieldEditScreen(Component title, int w, int h, Screen parent) {
        super(title, w, h, parent);
    }

    // reset the field/dropdown/color registries; call at the top of the subclass init()
    protected void clearFields() {
        textEntries.clear();
        colorRows.clear();
        dropdownSync.clear();
    }

    // standard editor header for a GENERIC (unnamed) tabbed screen: gray centred subtitle + tab bar. onSelect
    // should apply pending fields and switch section. returns the rowY where fields start.
    protected int buildTabHeader(String subtitle, String[] sections, int active, java.util.function.IntConsumer onSelect) {
        headerSubtitle = subtitle;
        int belowTabs = tabs(10, 28, uiWidth - 20, sections, active, onSelect);
        return belowTabs + 6;
    }

    // header for a NAMED-entity tabbed editor (a hologram / group / region / crate / ... being edited): the entity
    // name is drawn TOP-LEFT of the panel instead of centred under the logo (where it collided with the tab row),
    // per the suite-wide "named entity -> top left" rule. Same tab bar + return contract as buildTabHeader.
    protected int buildNamedTabHeader(String name, String[] sections, int active, java.util.function.IntConsumer onSelect) {
        headerName = name;
        int belowTabs = tabs(10, 28, uiWidth - 20, sections, active, onSelect);
        return belowTabs + 6;
    }

    // The X and width of the shared label+field column, clamped to the panel. Screens historically hardcoded a
    // field at x=150 w=132 (sized for the 330-wide group editor); on a narrower panel (e.g. the 240-wide PvP
    // toggle, the 260-wide economy edit) that ran the field/toggle out past the right border (the "text bar going
    // out of the background" / "pvp button extends off gui" defects). The field now starts at the same x but its
    // width is trimmed so its right edge never crosses the panel's inner-right edge, whatever the panel width.
    protected int fieldColX() {
        return 150;
    }

    protected int fieldColW() {
        int maxRight = uiWidth - net.shurui.dev.sdu.client.gui.theme.GuiTheme.CONTENT_PADDING;
        return Math.max(1, Math.min(132, maxRight - fieldColX()));
    }

    // attach hover-help to the row just added (call right after tf/bf/cf/df/dfMulti)
    protected void tip(String text) {
        tooltip(12, rowY - ROW_H, 272, ROW_H, text);
    }

    protected void tf(String labelText, String value, Consumer<String> setter) {
        label(labelText, 14, rowY + 2);
        EditBox b = field(fieldColX(), rowY + 1, fieldColW(), value);
        textEntries.add(new TextEntry(b, setter));
        rowY += ROW_H;
    }

    // bare positioned text field (no label / no rowY advance) still read back on applyFields(). for inline
    // multi-column rows like item/min/max/chance.
    protected EditBox rawField(int x, int y, int w, String value, Consumer<String> setter) {
        EditBox b = field(x, y, w, value);
        textEntries.add(new TextEntry(b, setter));
        return b;
    }

    protected void bf(String labelText, boolean value, Runnable toggle) {
        label(labelText, 14, rowY + 2);
        btn(fieldColX(), rowY, fieldColW(), 11, Component.translatable(value ? "gui.dmz_ragnarok.core.common.on" : "gui.dmz_ragnarok.core.common.off"),
                () -> { applyFields(); toggle.run(); rebuildWidgets(); });
        rowY += ROW_H;
    }

    // single-select searchable dropdown; adds "(none)" and preserves an off-list value
    protected void df(String labelText, List<String> options, String current, Consumer<String> setter) {
        label(labelText, 14, rowY + 5);
        List<String> values = new ArrayList<>();
        values.add("");
        values.addAll(options);
        List<Component> opts = new ArrayList<>();
        opts.add(Component.translatable("gui.dmz_ragnarok.core.common.none"));
        for (String s : options) {
            opts.add(Component.literal(s));
        }
        String cur = current == null ? "" : current;
        int idx = Math.max(0, values.indexOf(cur));
        if (idx == 0 && !cur.isBlank()) {
            values.add(cur);
            opts.add(Component.literal(cur + " §7").append(Component.translatable("gui.dmz_ragnarok.core.common.current")));
            idx = values.size() - 1;
        }
        DmzDropdown d = dropdown(fieldColX(), rowY, fieldColW(), opts, idx).searchable();
        dropdownSync.put(d, () -> setter.accept(values.get(Math.max(0, Math.min(d.getIndex(), values.size() - 1)))));
        rowY += ROW_H;
    }

    // single-select dropdown that DISPLAYS friendly labels but STORES stable values. values.get(i) is what is
    // saved, labels.get(i) is what the admin reads. Adds "(none)" -> "" and preserves an off-list current value
    // (shown marked) so a saved id that is no longer in the option list is never silently wiped. When the option
    // list is empty AND nothing is currently set, a disabled "empty" line is drawn instead of a blank dropdown.
    protected void dfNamed(String labelText, List<String> values, List<String> labels, String current,
            Consumer<String> setter, String emptyMessage) {
        label(labelText, 14, rowY + 5);
        boolean empty = (values == null || values.isEmpty()) && (current == null || current.isBlank());
        if (empty) {
            label(emptyMessage, fieldColX() + 2, rowY + 5, 0xFF808080);
            rowY += ROW_H;
            return;
        }
        dfNamedAt(fieldColX(), rowY, fieldColW(), values, labels, current, setter);
        rowY += ROW_H;
    }

    // positioned label-less variant of dfNamed. no rowY advance.
    protected void dfNamedAt(int x, int y, int w, List<String> values, List<String> labels, String current,
            Consumer<String> setter) {
        List<String> vals = new ArrayList<>();
        List<Component> opts = new ArrayList<>();
        vals.add("");
        opts.add(Component.translatable("gui.dmz_ragnarok.core.common.none"));
        List<String> src = values == null ? new ArrayList<>() : values;
        for (int i = 0; i < src.size(); i++) {
            vals.add(src.get(i));
            String lab = labels != null && i < labels.size() && labels.get(i) != null && !labels.get(i).isBlank()
                    ? labels.get(i) : src.get(i);
            opts.add(Component.literal(lab));
        }
        String cur = current == null ? "" : current;
        int idx = Math.max(0, vals.indexOf(cur));
        if (idx == 0 && !cur.isBlank()) {
            vals.add(cur);
            opts.add(Component.literal(cur + " §7").append(Component.translatable("gui.dmz_ragnarok.core.common.current")));
            idx = vals.size() - 1;
        }
        DmzDropdown d = dropdown(x, y, w, opts, idx).searchable();
        dropdownSync.put(d, () -> setter.accept(vals.get(Math.max(0, Math.min(d.getIndex(), vals.size() - 1)))));
    }

    // positioned label-less searchable dropdown (same syncing as df) for custom rows. no rowY advance.
    protected void dfAt(int x, int y, int w, List<String> options, String current, Consumer<String> setter) {
        List<String> values = new ArrayList<>();
        values.add("");
        values.addAll(options);
        List<Component> opts = new ArrayList<>();
        opts.add(Component.translatable("gui.dmz_ragnarok.core.common.none"));
        for (String s : options) {
            opts.add(Component.literal(s));
        }
        String cur = current == null ? "" : current;
        int idx = Math.max(0, values.indexOf(cur));
        if (idx == 0 && !cur.isBlank()) {
            values.add(cur);
            opts.add(Component.literal(cur + " §7").append(Component.translatable("gui.dmz_ragnarok.core.common.current")));
            idx = values.size() - 1;
        }
        DmzDropdown d = dropdown(x, y, w, opts, idx).searchable();
        dropdownSync.put(d, () -> setter.accept(values.get(Math.max(0, Math.min(d.getIndex(), values.size() - 1)))));
    }

    // multi-select searchable dropdown writing checked options into a list
    protected void dfMulti(String labelText, List<String> options, List<String> current, Consumer<List<String>> setter) {
        label(labelText, 14, rowY + 5);
        List<String> values = new ArrayList<>(options);
        for (String c : current) {
            if (c != null && !c.isBlank() && !values.contains(c)) {
                values.add(c);
            }
        }
        List<Component> opts = new ArrayList<>();
        for (String s : values) {
            opts.add(Component.literal(s));
        }
        DmzDropdown d = dropdown(fieldColX(), rowY, fieldColW(), opts, 0).searchable().multiSelect();
        List<Integer> sel = new ArrayList<>();
        for (String c : current) {
            int i = values.indexOf(c);
            if (i >= 0) {
                sel.add(i);
            }
        }
        d.setSelected(sel);
        dropdownSync.put(d, () -> {
            List<String> out = new ArrayList<>();
            for (int i : d.getSelected()) {
                if (i >= 0 && i < values.size()) {
                    out.add(values.get(i));
                }
            }
            setter.accept(out);
        });
        rowY += ROW_H;
    }

    // color row: swatch preview (opens the picker) + an editable "#RRGGBB" box. both edit the same value; the
    // box is read back + normalized on applyFields(), the picker commits via its setter.
    protected void cf(String labelText, Supplier<String> getter, Consumer<String> setter) {
        label(labelText, 14, rowY + 2);
        int sx = fieldColX(), sy = rowY, sw = 20, sh = 10;
        rect(sx, sy, sw, sh, 0xFF10281A);
        int argb = hexToArgb(getter.get());
        rect(sx + 1, sy + 1, sw - 2, sh - 2, argb == 0 ? 0xFF3A3A3A : argb);
        // swatch opens the picker; the box handles copy/paste + manual entry
        colorRows.add(new ColorRow(sx, sy, sw, sh, getter, setter));
        int boxX = sx + sw + 4;
        int boxW = Math.max(1, Math.min(108, (uiWidth - net.shurui.dev.sdu.client.gui.theme.GuiTheme.CONTENT_PADDING) - boxX));
        EditBox box = field(boxX, rowY + 1, boxW, getter.get());
        box.setHint(Component.literal("#RRGGBB"));
        textEntries.add(new TextEntry(box, v -> setter.accept(normalizeHex(v))));
        rowY += ROW_H;
    }

    // tidy a typed/pasted color: "" if blank, "#RRGGBB" upper-cased if valid hex, else as-is
    protected static String normalizeHex(String v) {
        if (v == null) {
            return "";
        }
        String s = v.trim();
        if (s.isEmpty()) {
            return "";
        }
        String body = s.startsWith("#") ? s.substring(1) : s;
        if (body.length() == 6) {
            boolean valid = true;
            for (int i = 0; i < 6; i++) {
                if (Character.digit(body.charAt(i), 16) < 0) {
                    valid = false;
                    break;
                }
            }
            if (valid) {
                return "#" + body.toUpperCase(java.util.Locale.ROOT);
            }
        }
        return s;
    }

    protected static void replaceList(List<String> target, List<String> src) {
        target.clear();
        target.addAll(src);
    }

    protected void applyFields() {
        for (TextEntry e : textEntries) {
            try {
                e.setter().accept(e.box().getValue());
            } catch (Exception ignored) {
            }
        }
        for (Runnable sync : dropdownSync.values()) {
            try {
                sync.run();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onDropdownSelect(DmzDropdown dropdown, int row) {
        Runnable sync = dropdownSync.get(dropdown);
        if (sync != null) {
            sync.run();
        } else {
            onExtraDropdown(dropdown, row);
        }
    }

    // hook for subclass dropdowns that aren't df/dfMulti fields
    protected void onExtraDropdown(DmzDropdown dropdown, int row) {
    }

    private void openPickerFor(ColorRow r) {
        applyFields();
        int px = Math.min(r.x(), uiWidth - 156);
        int py = Math.max(2, Math.min(r.y() + 16, uiHeight - 90));
        openPicker = new DmzColorPicker(px, py);
        openPicker.open(r.getter().get());
        pickerSetter = r.setter();
    }

    private void commitPicker() {
        if (openPicker != null && pickerSetter != null) {
            pickerSetter.accept(openPicker.hex());
        }
        openPicker = null;
        pickerSetter = null;
        rebuildWidgets();
    }

    @Override
    protected void renderTopOverlay(GuiGraphics g, int vmx, int vmy) {
        if (openPicker != null) {
            openPicker.render(g, this.font, vmx, vmy);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        double vx = toVirtualX(mx), vy = toVirtualY(my);
        if (openPicker != null) {
            if (openPicker.contains(vx, vy)) {
                openPicker.mouseClicked(vx, vy);
                if (openPicker.isCloseRequested()) {
                    commitPicker();
                }
            } else {
                commitPicker();
            }
            return true;
        }
        for (ColorRow r : colorRows) {
            if (inBand(r.y()) && vx >= r.x() && vx < r.x() + r.w() && vy >= r.y() && vy < r.y() + r.h()) {
                openPickerFor(r);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (openPicker != null) {
            openPicker.mouseDragged(toVirtualX(mx), toVirtualY(my));
            return true;
        }
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (openPicker != null) {
            openPicker.mouseReleased();
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    protected static int hexToArgb(String hex) {
        if (hex == null) {
            return 0;
        }
        String s = hex.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() != 6) {
            return 0;
        }
        try {
            return 0xFF000000 | Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    protected static String dbl(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    protected static String intStr(int v) {
        return Integer.toString(v);
    }

    protected static int parseI(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    protected static double parseD(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
