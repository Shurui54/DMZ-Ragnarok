package net.shurui.dev.shuruis_dmz_dungeons.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

// BaseEditScreen plus a labelled-field toolkit for the config editors: text fields (tf), on/off toggles
// (bf), searchable single/multi dropdowns (df/dfMulti) and colour swatches (cf) that open a DmzColorPicker.
// subclasses lay rows out with the running rowY cursor, clearFields() in init(), read back via applyFields().
// dropdowns that aren't df/dfMulti go through onExtraDropdown.
public abstract class FieldEditScreen extends BaseEditScreen {

    protected static final int ROW_H = 12;

    private record TextEntry(EditBox box, Consumer<String> setter) {
    }

    private record ColorRow(int x, int y, int w, int h, Supplier<String> getter, Consumer<String> setter) {
    }

    private final List<TextEntry> textEntries = new ArrayList<>();
    private final List<ColorRow> colorRows = new ArrayList<>();
    private final Map<DmzDropdown, Runnable> dropdownSync = new HashMap<>();
    // per-dropdown hooks fired only on an actual selection (the df onChange overload)
    private final Map<DmzDropdown, Runnable> dropdownChange = new HashMap<>();
    private DmzColorPicker openPicker;
    private Consumer<String> pickerSetter;
    protected int rowY;

    protected FieldEditScreen(Component title, int w, int h, Screen parent) {
        super(title, w, h, parent);
        ROW_STEP = ROW_H * 2; // two rows per notch, matches SDU's form editor feel
    }

    // re-run init() to rebuild this screen's widgets after a data change. exposed publicly because the shared
    // DropListEditor lives in this package but is NOT a Screen subclass, so it cannot reach vanilla Screen's
    // protected rebuildWidgets() itself; it calls this instead. both drop-editor hosts inherit it unchanged.
    public void rebuild() {
        rebuildWidgets();
    }

    // wipe field/dropdown/colour registries; call at the top of the subclass init()
    protected void clearFields() {
        textEntries.clear();
        colorRows.clear();
        dropdownSync.clear();
        dropdownChange.clear();
    }

    // draw the standard header (gray subtitle + tab bar). onSelect should apply pending fields and switch
    // section (e.g. this::selectSection). returns the rowY where the section's fields start.
    protected int buildTabHeader(String subtitle, String[] sections, int active, java.util.function.IntConsumer onSelect) {
        headerSubtitle = subtitle;
        int belowTabs = tabs(10, 28, uiWidth - 20, sections, active, onSelect);
        return belowTabs + 6;
    }

    // attach hover-help to the row just added (call right after tf/bf/cf/df/dfMulti)
    protected void tip(String text) {
        tooltip(12, rowY - ROW_H, 272, ROW_H, text);
    }

    // close the band using the running rowY cursor: content height = (rowY + scroll) - contentBottom.
    // compute maxScroll from that, then let the base clamp/hide/draw the thumb.
    @Override
    protected void finishScrollBand(int contentTop, int contentBottom) {
        maxScroll = Math.max(0, (rowY + scroll) - contentBottom);
        super.finishScrollBand(contentTop, contentBottom);
    }

    // flush unsaved EditBox text into the model before a content-scroll rebuild wipes the widgets
    @Override
    protected void beforeScrollRebuild() {
        applyFields();
    }

    protected void tf(String labelText, String value, Consumer<String> setter) {
        label(labelText, 14, rowY + 2);
        EditBox b = field(150, rowY + 1, 132, value);
        textEntries.add(new TextEntry(b, setter));
        rowY += ROW_H;
    }

    // register a custom-placed edit box so it's read back on applyFields(). for rows a subclass lays out
    // itself (e.g. a multi-column list) instead of via tf.
    protected EditBox rawField(int x, int y, int w, String value, Consumer<String> setter) {
        EditBox b = field(x, y, w, value);
        textEntries.add(new TextEntry(b, setter));
        return b;
    }

    protected void bf(String labelText, boolean value, Runnable toggle) {
        label(labelText, 14, rowY + 2);
        btn(150, rowY, 132, 11, Component.translatable(value
                        ? "gui.dmz_ragnarok.dungeons.common.toggle_on"
                        : "gui.dmz_ragnarok.dungeons.common.toggle_off"),
                () -> { applyFields(); toggle.run(); rebuildWidgets(); });
        rowY += ROW_H;
    }

    // single-select searchable dropdown; adds a "(none)" option and keeps an off-list current value
    protected void df(String labelText, List<String> options, String current, Consumer<String> setter) {
        df(labelText, options, current, setter, null);
    }

    // df with an onChange hook fired (with the new value) when you pick a row, after it's synced into the
    // config. used for side effects like requesting an entity's DMZ defaults. only fires on an actual
    // selection, not on the background applyFields sync.
    protected void df(String labelText, List<String> options, String current, Consumer<String> setter,
                      Consumer<String> onChange) {
        label(labelText, 14, rowY + 5);
        List<String> values = new ArrayList<>();
        values.add("");
        values.addAll(options);
        List<Component> opts = new ArrayList<>();
        opts.add(Component.translatable("gui.dmz_ragnarok.dungeons.common.none"));
        for (String s : options) {
            opts.add(Component.literal(s));
        }
        String cur = current == null ? "" : current;
        int idx = Math.max(0, values.indexOf(cur));
        if (idx == 0 && !cur.isBlank()) {
            values.add(cur);
            opts.add(Component.translatable("gui.dmz_ragnarok.dungeons.common.current", cur));
            idx = values.size() - 1;
        }
        DmzDropdown d = dropdown(150, rowY, 132, opts, idx).searchable();
        dropdownSync.put(d, () -> setter.accept(values.get(Math.max(0, Math.min(d.getIndex(), values.size() - 1)))));
        if (onChange != null) {
            dropdownChange.put(d, () -> onChange.accept(values.get(Math.max(0, Math.min(d.getIndex(), values.size() - 1)))));
        }
        rowY += ROW_H;
    }

    // positioned, label-less searchable dropdown (same value sync as df) for custom rows like the per-drop
    // item picker. does NOT advance rowY.
    protected void dfAt(int x, int y, int w, List<String> options, String current, Consumer<String> setter) {
        List<String> values = new ArrayList<>();
        values.add("");
        values.addAll(options);
        List<Component> opts = new ArrayList<>();
        opts.add(Component.translatable("gui.dmz_ragnarok.dungeons.common.none"));
        for (String s : options) {
            opts.add(Component.literal(s));
        }
        String cur = current == null ? "" : current;
        int idx = Math.max(0, values.indexOf(cur));
        if (idx == 0 && !cur.isBlank()) {
            values.add(cur);
            opts.add(Component.translatable("gui.dmz_ragnarok.dungeons.common.current", cur));
            idx = values.size() - 1;
        }
        DmzDropdown d = dropdown(x, y, w, opts, idx).searchable();
        dropdownSync.put(d, () -> setter.accept(values.get(Math.max(0, Math.min(d.getIndex(), values.size() - 1)))));
    }

    // multi-select searchable dropdown that writes the checked options into a list
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
        DmzDropdown d = dropdown(150, rowY, 132, opts, 0).searchable().multiSelect();
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

    // colour row: a swatch preview that opens the DmzColorPicker on click, plus an editable "#RRGGBB" box
    // you can type/paste into. both edit the same value; the box is normalised on applyFields(), the picker
    // commits via its setter.
    protected void cf(String labelText, Supplier<String> getter, Consumer<String> setter) {
        label(labelText, 14, rowY + 2);
        int sx = 150, sy = rowY, sw = 20, sh = 10;
        rect(sx, sy, sw, sh, 0xFF10281A);
        int argb = hexToArgb(getter.get());
        rect(sx + 1, sy + 1, sw - 2, sh - 2, argb == 0 ? 0xFF3A3A3A : argb);
        // swatch click opens the picker; the box handles copy/paste + manual entry
        colorRows.add(new ColorRow(sx, sy, sw, sh, getter, setter));
        EditBox box = field(sx + sw + 4, rowY + 1, 108, getter.get());
        box.setHint(Component.literal("#RRGGBB"));
        textEntries.add(new TextEntry(box, v -> setter.accept(normalizeHex(v))));
        rowY += ROW_H;
    }

    // tidy a typed/pasted colour: "" if blank, "#RRGGBB" upper-cased if valid 6-digit hex, else leave as-is
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
            Runnable change = dropdownChange.get(dropdown);
            if (change != null) {
                change.run();
            }
        } else {
            onExtraDropdown(dropdown, row);
        }
    }

    // hook for dropdowns a subclass adds that aren't df/dfMulti fields
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
            if (vx >= r.x() && vx < r.x() + r.w() && vy >= r.y() && vy < r.y() + r.h()) {
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
