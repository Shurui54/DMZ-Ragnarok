package net.shurui.dev.sdu.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A {@link SagaBaseScreen} with a reusable "labelled field" toolkit for the config editors: text
 * fields ({@link #tf}), on/off toggles ({@link #bf}), searchable single/multi dropdowns
 * ({@link #df}/{@link #dfMulti}) and DMZ-style colour swatches ({@link #cf}) that open a
 * {@link DmzColorPicker} overlay. Subclasses lay rows out with the running {@link #rowY} cursor,
 * clear state via {@link #clearFields()} in {@code init()}, and read everything back with
 * {@link #applyFields()}. Extra (non-field) dropdowns are handled via {@link #onExtraDropdown}.
 */
public abstract class FieldEditScreen extends SagaBaseScreen {

    protected static final int ROW_H = GuiTheme.ROW_HEIGHT;

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

    // When a numeric field commits unparseable/out-of-range text, the parse helper records why here and it's
    // drawn over the footer. Without it a bad entry silently reverts, which reads to an admin as "my edit
    // didn't save". Rebuilt on every applyFields() so a clean commit clears it.
    private String fieldWarning;

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
        int belowTabs = tabs(10, 28, tabBarWidth() - 20, sections, active, onSelect);
        return belowTabs + 6;
    }

    /**
     * Width the tab bar (and content column) should span. Defaults to the full canvas; a screen that reserves
     * part of its canvas (e.g. {@code FormEditScreen}'s preview column) overrides this to keep tabs over the
     * content column.
     */
    protected int tabBarWidth() {
        return uiWidth;
    }

    /** Attach hover-help to the row just added (call right after tf/bf/cf/df/dfMulti). */
    protected void tip(String text) {
        tooltip(12, rowY - ROW_H, 272, ROW_H, text);
    }

    protected void tf(String labelText, String value, Consumer<String> setter) {
        tf(labelText, value, setter, 512);
    }

    /** Text row with an explicit character cap - use for values that can exceed 512 chars (e.g. a DMZ hair code). */
    protected void tf(String labelText, String value, Consumer<String> setter, int maxLength) {
        label(labelText, 14, rowY + 2);
        EditBox b = field(150, rowY + 1, 132, value, maxLength);
        textEntries.add(new TextEntry(b, setter));
        rowY += ROW_H;
    }

    protected void bf(String labelText, boolean value, Runnable toggle) {
        label(labelText, 14, rowY + 2);
        btn(150, rowY, 132, 11, Component.translatable(value ? "gui.dmz_ragnarok.npc.common.on" : "gui.dmz_ragnarok.npc.common.off"),
                () -> { applyFields(); toggle.run(); rebuildWidgets(); });
        rowY += ROW_H;
    }

    /** Single-select searchable dropdown; adds a "(none)" option and preserves an off-list value. */
    protected void df(String labelText, List<String> options, String current, Consumer<String> setter) {
        df(labelText, options, current, setter, 0);
    }

    /**
     * As {@link #df}, but holds {@code reserve} px clear on the RIGHT for a trailing control.
     *
     * <p>A dropdown normally fills the row to the scrollbar reserve, leaving a row that needs its own delete
     * button nowhere to put it: on the dropdown (header swallows the click) or in the scroll gutter (thumb grab
     * is tested first). Narrowing the dropdown is the only placement that leaves the button visible and clickable.
     */
    protected void df(String labelText, List<String> options, String current, Consumer<String> setter, int reserve) {
        label(labelText, 14, rowY + 5);
        List<String> values = new ArrayList<>();
        values.add("");
        values.addAll(options);
        List<Component> opts = new ArrayList<>();
        opts.add(Component.translatable("gui.dmz_ragnarok.npc.common.none"));
        for (String s : options) {
            opts.add(Component.literal(s));
        }
        String cur = current == null ? "" : current;
        int idx = Math.max(0, values.indexOf(cur));
        if (idx == 0 && !cur.isBlank()) {
            values.add(cur);
            opts.add(Component.literal(cur + " §7(current)"));
            idx = values.size() - 1;
        }
        DmzDropdown d = dropdown(150, rowY, Math.max(32, 132 - Math.max(0, reserve)), opts, idx).searchable();
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
     * Opt-in variant of {@link #dfMulti} with a free-text input + "add" button, so an admin can type an id not
     * in {@code options} (e.g. a runtime/MultiWorld dimension the client can't enumerate). The typed value goes
     * through {@code validator} (return {@code null} to accept), then {@code onAdd} appends it and the screen
     * rebuilds so it shows up checked.
     *
     * <p>Consumes TWO rows: the dropdown row, then the add-row beneath it.
     */
    protected void dfMultiFreeText(String labelText, List<String> options, List<String> current,
                                   Consumer<List<String>> setter, java.util.function.Function<String, String> validator,
                                   Consumer<String> onAdd, String addHint) {
        dfMulti(labelText, options, current, setter);
        EditBox add = field(150, rowY + 1, 100, "");
        if (addHint != null) {
            add.setHint(Component.literal(addHint));
        }
        btn(252, rowY, 30, 11, Component.translatable("gui.dmz_ragnarok.npc.common.add"), () -> {
            applyFields(); // flush the multi-select + any other fields before we mutate current
            String raw = add.getValue() == null ? "" : add.getValue().trim();
            if (raw.isEmpty()) {
                return;
            }
            String err = validator == null ? null : validator.apply(raw);
            if (err != null) {
                return;
            }
            onAdd.accept(raw);
            rebuildWidgets(); // re-init so the new id appears in the dropdown, pre-checked
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

    /** Wheel-scroll a content band by two rows, and flush in-progress fields first (base-class hooks). */
    @Override
    protected int bandStep() {
        return ROW_H * 2;
    }

    @Override
    protected void applyBeforeBandScroll() {
        applyFields();
    }

    protected void applyFields() {
        // Rebuild the rejection banner: the setters re-run parseIntField/parseDoubleField and re-arm it if a
        // value is still bad, so a fixed field clears the warning.
        fieldWarning = null;
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

    /** Write the picker's current colour to its target without closing it, so previews update live. */
    private void pushPickerLive() {
        if (openPicker != null && pickerSetter != null) {
            try {
                pickerSetter.accept(openPicker.hex());
            } catch (Exception ignored) {
            }
        }
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
        renderFieldWarning(g);
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
                } else {
                    pushPickerLive(); // reflect the pick immediately (e.g. in the form preview)
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
            pushPickerLive(); // live-update the value while dragging the picker
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

    /**
     * Instance variant of {@link #parseI} that makes a rejected entry VISIBLE instead of silently reverting. On
     * unparseable text it arms the field-warning banner and returns {@code fallback}, so the model keeps its
     * last good value but the admin is told. Blank is not an error. Use on the race editor path.
     */
    protected int parseIntField(String label, String s, int fallback) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            warnField(label, t, tr("gui.dmz_ragnarok.npc.field.needs_whole_number"));
            return fallback;
        }
    }

    /** Decimal counterpart of {@link #parseIntField}; arms the same banner on a non-numeric entry. */
    protected double parseDoubleField(String label, String s, double fallback) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            warnField(label, t, tr("gui.dmz_ragnarok.npc.field.needs_number"));
            return fallback;
        }
    }

    /**
     * Report a value clamped silently, so the clamp is no longer invisible: the admin sees which field was
     * adjusted and to what. Call from a setter AFTER clamping, when the clamp changed the entered value.
     */
    protected void warnClamped(String label, String entered, String clampedTo) {
        warnField(label, entered, tr("gui.dmz_ragnarok.npc.field.clamped_to", clampedTo));
    }

    /** Arm the banner (first failure of a commit wins, so the top-most bad field is the one named). */
    protected void warnField(String label, String entered, String reason) {
        if (fieldWarning == null) {
            fieldWarning = tr("gui.dmz_ragnarok.npc.field.rejected", label, entered, reason);
        }
    }

    /** True while a numeric field on this screen holds text that was rejected on the last commit. */
    protected boolean hasFieldWarning() {
        return fieldWarning != null;
    }

    /** Draw the rejection banner (if armed) as a wrapped red line just above the footer buttons. */
    private void renderFieldWarning(GuiGraphics g) {
        if (fieldWarning == null || this.font == null) {
            return;
        }
        int wrapW = uiWidth - 24;
        var lines = this.font.split(Component.literal(fieldWarning), wrapW);
        int y = footerY() - 4 - lines.size() * (this.font.lineHeight + 1);
        for (var line : lines) {
            g.drawString(this.font, line, 12, y, 0xFFFF5555, false);
            y += this.font.lineHeight + 1;
        }
    }
}
