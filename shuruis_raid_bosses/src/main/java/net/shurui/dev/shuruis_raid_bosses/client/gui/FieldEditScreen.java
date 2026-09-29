package net.shurui.dev.shuruis_raid_bosses.client.gui;

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

/**
 * A {@link BaseEditScreen} with a reusable "labelled field" toolkit for the config editors: text
 * fields ({@link #tf}), on/off toggles ({@link #bf}), searchable single/multi dropdowns
 * ({@link #df}/{@link #dfMulti}) and DMZ-style colour swatches ({@link #cf}) that open a
 * {@link DmzColorPicker} overlay. Subclasses lay rows out with the running {@link #rowY} cursor,
 * clear state via {@link #clearFields()} in {@code init()}, and read everything back with
 * {@link #applyFields()}. Extra (non-field) dropdowns are handled via {@link #onExtraDropdown}.
 */
public abstract class FieldEditScreen extends BaseEditScreen {

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

    /** Reset field/dropdown/colour registries; call at the start of the subclass {@code init()}. */
    protected void clearFields() {
        textEntries.clear();
        colorRows.clear();
        dropdownSync.clear();
    }

    /**
     * Draw the standard editor header: a gray subtitle under the title plus a tab bar of {@code sections}.
     * {@code onSelect} should apply pending fields and switch section (e.g. {@code this::selectSection}).
     * Returns the {@code rowY} at which the section's fields should start.
     */
    protected int buildTabHeader(String subtitle, String[] sections, int active, java.util.function.IntConsumer onSelect) {
        headerSubtitle = subtitle;
        int belowTabs = tabs(10, 28, uiWidth - 20, sections, active, onSelect);
        return belowTabs + 6;
    }

    /** Attach hover-help to the row just added (call right after tf/bf/cf/df/dfMulti). */
    protected void tip(String text) {
        tooltip(12, rowY - ROW_H, 272, ROW_H, text);
    }

    protected void tf(String labelText, String value, Consumer<String> setter) {
        label(labelText, 14, rowY + 2);
        EditBox b = field(150, rowY + 1, 132, value);
        textEntries.add(new TextEntry(b, setter));
        rowY += ROW_H;
    }

    protected void bf(String labelText, boolean value, Runnable toggle) {
        label(labelText, 14, rowY + 2);
        btn(150, rowY, 132, 11, Component.translatable(value
                        ? "gui.dmz_ragnarok.raid.common.toggle_on" : "gui.dmz_ragnarok.raid.common.toggle_off"),
                () -> { applyFields(); toggle.run(); rebuildWidgets(); });
        rowY += ROW_H;
    }

    /** Single-select searchable dropdown; adds a "(none)" option and preserves an off-list value. */
    protected void df(String labelText, List<String> options, String current, Consumer<String> setter) {
        label(labelText, 14, rowY + 5);
        List<String> values = new ArrayList<>();
        values.add("");
        values.addAll(options);
        List<Component> opts = new ArrayList<>();
        opts.add(Component.translatable("gui.dmz_ragnarok.raid.common.none"));
        for (String s : options) {
            opts.add(Component.literal(s));
        }
        String cur = current == null ? "" : current;
        int idx = Math.max(0, values.indexOf(cur));
        if (idx == 0 && !cur.isBlank()) {
            values.add(cur);
            opts.add(Component.translatable("gui.dmz_ragnarok.raid.common.current", cur));
            idx = values.size() - 1;
        }
        DmzDropdown d = dropdown(150, rowY, 132, opts, idx).searchable();
        dropdownSync.put(d, () -> setter.accept(values.get(Math.max(0, Math.min(d.getIndex(), values.size() - 1)))));
        rowY += ROW_H;
    }

    /** Multi-select searchable dropdown writing the checked options into a list. */
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

    /**
     * A colour row: a small swatch preview that opens the {@link DmzColorPicker} on click, plus an
     * editable "#RRGGBB" text box you can type or paste a hex code into. Both edit the same value; the
     * box is read back (and normalised) on {@link #applyFields()}, the picker commits via its setter.
     */
    protected void cf(String labelText, Supplier<String> getter, Consumer<String> setter) {
        label(labelText, 14, rowY + 2);
        int sx = 150, sy = rowY, sw = 20, sh = 10;
        rect(sx, sy, sw, sh, 0xFF10281A);
        int argb = hexToArgb(getter.get());
        rect(sx + 1, sy + 1, sw - 2, sh - 2, argb == 0 ? 0xFF3A3A3A : argb);
        // Clicking the swatch opens the picker; the box handles copy/paste + manual entry.
        colorRows.add(new ColorRow(sx, sy, sw, sh, getter, setter));
        EditBox box = field(sx + sw + 4, rowY + 1, 108, getter.get());
        box.setHint(Component.literal("#RRGGBB"));
        textEntries.add(new TextEntry(box, v -> setter.accept(normalizeHex(v))));
        rowY += ROW_H;
    }

    /** Tidy a typed/pasted colour: "" when blank, "#RRGGBB" (upper-cased) when a valid 6-digit hex, else as-is. */
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

    @Override
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

    /** Hook for dropdowns a subclass adds that aren't {@code df}/{@code dfMulti} fields. */
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
