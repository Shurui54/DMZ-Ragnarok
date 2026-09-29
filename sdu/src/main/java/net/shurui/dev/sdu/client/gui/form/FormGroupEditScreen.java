package net.shurui.dev.sdu.client.gui.form;

import com.google.gson.Gson;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.DmzAssets;
import net.shurui.dev.sdu.client.DmzRaces;
import net.shurui.dev.sdu.client.gui.DmzDropdown;
import net.shurui.dev.sdu.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.form.FormData;
import net.shurui.dev.sdu.form.FormGroupData;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.SaveFormPacket;

import java.util.ArrayList;
import java.util.List;

/** Edit a form group's meta ({@code groupName}/{@code formType}/owning race) and its list of forms. */
public class FormGroupEditScreen extends SagaBaseScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final Gson GSON = new Gson();

    /** Copied form, shared so it can be pasted across groups. */
    private static FormData clipboard;
    private static String clipboardKey = "form";

    private final FormGroupData group;
    private EditBox nameField, displayField;
    private DmzDropdown typeDropdown;
    /** Form-type option values parallel to {@link #typeDropdown}'s options. */
    private final List<String> typeOptions = new ArrayList<>();
    private DmzDropdown raceDropdown;
    /** Race option values parallel to the dropdown options; index 0 == "" (the stack folder). */
    private final List<String> raceOptions = new ArrayList<>();
    private int scroll;
    /** Form key armed for deletion: first X click arms it, a second confirms. Reset on screen change. */
    private String pendingDelete;

    public FormGroupEditScreen(Screen parent, FormGroupData group) {
        super(Component.translatable("gui.dmz_ragnarok.npc.formgroup_edit.edit_form_group"), UI_W, UI_H, parent);
        this.group = group;
    }

    @Override
    protected void init() {
        super.init();
        group.normalizeFormKeys(); // keep each form's map key == its name/id (fixes requisite showing "new_form")
        label( tr("gui.dmz_ragnarok.npc.formgroup_edit.group"), 12, 26);
        nameField = field(70, 22, 200, group.groupName);
        tooltip(12, 20, 258, 16, tr("gui.dmz_ragnarok.npc.formgroup_edit.t_internal_group_categ"));
        label( tr("gui.dmz_ragnarok.npc.formgroup_edit.name"), 12, 46);
        displayField = field(70, 42, 200, group.displayName);
        tooltip(12, 40, 258, 16, tr("gui.dmz_ragnarok.npc.formgroup_edit.t_the_category_name_sh"));
        label( tr("gui.dmz_ragnarok.npc.formgroup_edit.type"), 12, 66);
        typeOptions.clear();
        List<Component> typeOpts = new ArrayList<>();
        for (String t : DmzAssets.formTypes()) {
            typeOptions.add(t);
            typeOpts.add(Component.literal(t));
        }
        String curType = group.formType == null ? "" : group.formType.trim();
        int typeSel = typeOptions.indexOf(curType);
        if (typeSel < 0) {
            typeOptions.add(curType);
            typeOpts.add(Component.literal((curType.isEmpty() ? "(none)" : curType) + " §7(current)"));
            typeSel = typeOptions.size() - 1;
        }
        typeDropdown = dropdown(70, 62, 200, typeOpts, typeSel).searchable();
        tooltip(12, 60, 258, 16, tr("gui.dmz_ragnarok.npc.formgroup_edit.t_form_skill_this_grou"));

        // Race multi-select: which race folders this group is written to. Searchable; blank = stack.
        label( tr("gui.dmz_ragnarok.npc.formgroup_edit.races"), 12, 86);
        raceOptions.clear();
        raceOptions.add(""); // stack folder
        List<Component> raceOpts = new ArrayList<>();
        raceOpts.add(Component.translatable("gui.dmz_ragnarok.npc.formgroup_edit.stack_no_race"));
        for (String r : DmzRaces.raceIds()) {
            raceOptions.add(r);
            raceOpts.add(Component.literal(r));
        }
        raceDropdown = dropdown(70, 82, 200, raceOpts, 0).searchable().multiSelect();
        List<Integer> selected = new ArrayList<>();
        for (String owner : group.ownerRaces) {
            int idx = raceOptions.indexOf(owner);
            if (idx >= 0) {
                selected.add(idx);
            }
        }
        raceDropdown.setSelected(selected);
        tooltip(12, 82, 258, 16, tr("gui.dmz_ragnarok.npc.formgroup_edit.t_which_races_get_this"));
        label( tr("gui.dmz_ragnarok.npc.formgroup_edit.check_every_race_that_sh"), 12, 100);

        int listTop = 122;
        // Reserve 16px so the list stops above the add/paste button row at y=220 (contentBottom 236 - 220).
        int maxRows = rowsThatFit(listTop, ROW_H, 16);
        List<String> keys = new ArrayList<>(group.forms.keySet());
        int total = keys.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);
        String more = total > maxRows ? "  §7(" + (scroll + 1) + "-" + end + "/" + total + ")" : "";
        label("§e" + tr("gui.dmz_ragnarok.npc.formgroup_edit.hdr_forms") + more, 12, 112);

        int y = listTop;
        for (int i = scroll; i < end; i++) {
            final String key = keys.get(i);
            final FormData form = group.forms.get(key);
            // Compact skill-gate readout ("L<level>", gray) so admins see the gate that travels with each form
            // on reorder. Placed left of the ↑/↓ arrows.
            label("§8L§7" + form.unlockOnSkillLevel, 128, y + 5);
            String shown = form.displayName != null && !form.displayName.isBlank() ? form.displayName : key;
            label("§7- §r" + net.shurui.dev.sdu.util.ColorCodes.translate(shown), 16, y + 5);
            // Reorder arrows. Disabled (rendered gray, no-op) at the overall first/last of the whole map,
            // not just the visible window.
            boolean canUp = i > 0;
            boolean canDown = i < total - 1;
            // circular icon move arrows at the standard icon size
            iconBtnAt(150, y, ROW_H, Component.literal(canUp ? "§f↑" : "§8↑"), () -> {
                if (canUp && group.moveForm(key, -1)) {
                    rebuildWidgets();
                }
            });
            if (canUp) {
                tooltip(150, y, iconSize(), ROW_H, tr("gui.dmz_ragnarok.npc.formgroup_edit.move_up"));
            }
            iconBtnAt(166, y, ROW_H, Component.literal(canDown ? "§f↓" : "§8↓"), () -> {
                if (canDown && group.moveForm(key, 1)) {
                    rebuildWidgets();
                }
            });
            if (canDown) {
                tooltip(166, y, iconSize(), ROW_H, tr("gui.dmz_ragnarok.npc.formgroup_edit.move_down"));
            }
            btn(184, y, 30, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.edit"), () -> minecraft.setScreen(new FormEditScreen(this, key, form, group.primaryOwner(), group.groupName, group.ownerRaces)));
            btn(216, y, 28, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.copy"), () -> { clipboard = form.copy(); clipboardKey = key; });
            boolean armed = key.equals(pendingDelete);
            // circular X delete, right-aligned to the reserved scrollbar column; armed swaps to confirm
            iconBtnRight(rowControlRight(), y, ROW_H,
                    Component.translatable(armed ? "gui.dmz_ragnarok.npc.btn.confirm_icon" : "gui.dmz_ragnarok.npc.btn.x"), () -> {
                if (armed) {
                    group.forms.remove(key);
                    pendingDelete = null;
                } else {
                    pendingDelete = key;
                }
                rebuildWidgets();
            });
            y += ROW_H;
        }
        scrollList(14, uiWidth, listTop, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });

        // The add/paste pair sits at y=220, which the list's reserveBelow above keeps clear, below the footer row.
        commitBtn(12, 220, 120, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.formgroup_edit.add_form"), this::addForm);
        tooltip(12, 220, 120, GuiTheme.BUTTON_HEIGHT, tr("gui.dmz_ragnarok.npc.formgroup_edit.t_add_a_new_form_to_th"));
        if (clipboard != null) {
            commitBtn(140, 220, 120, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.formgroup_edit.paste_form"), this::pasteForm);
            tooltip(140, 220, 120, GuiTheme.BUTTON_HEIGHT, tr("gui.dmz_ragnarok.npc.formgroup_edit.t_paste_the_copied_for"));
        }
        commitBtn(UI_W / 2 - 118, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.formgroup_edit.save_group"), this::save);
        tooltip(UI_W / 2 - 118, UI_H - 24, 110, 18, tr("gui.dmz_ragnarok.npc.formgroup_edit.t_write_this_group_to"));
        btn(UI_W / 2 + 8, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.back"), this::applyAndBack);
        tooltip(UI_W / 2 + 8, UI_H - 24, 110, 18, tr("gui.dmz_ragnarok.npc.formgroup_edit.t_return_to_the_form_l"));
    }

    private void addForm() {
        applyFields();
        String key = uniqueKey("new_form");
        FormData f = new FormData();
        f.name = key;
        // Default the unlock level to the next free tier in this group (first form = 1, second = 2, ...) so each
        // new form lands on its own skill level instead of piling onto level 0 (which the cost writer skips).
        // FormData's field default stays 0 for Gson back-compat with existing JSONs.
        f.unlockOnSkillLevel = group.forms.size() + 1;
        group.forms.put(key, f);
        minecraft.setScreen(new FormEditScreen(this, key, f, group.primaryOwner(), group.groupName, group.ownerRaces));
    }

    private void pasteForm() {
        applyFields();
        String key = uniqueKey(clipboardKey);
        FormData f = clipboard.copy();
        f.name = key;
        group.forms.put(key, f);
        rebuildWidgets();
    }

    private String uniqueKey(String base) {
        String key = net.shurui.dev.sdu.util.SduIds.sanitize(base);
        String candidate = key;
        int n = 2;
        while (group.forms.containsKey(candidate)) {
            candidate = key + "_" + n++;
        }
        return candidate;
    }

    @Override
    protected void onDropdownSelect(DmzDropdown dropdown, int row) {
        if (dropdown == raceDropdown) {
            syncRaces();
        } else if (dropdown == typeDropdown && row >= 0 && row < typeOptions.size()) {
            group.formType = typeOptions.get(row);
        }
    }

    /** Copy the checked race options into {@link FormGroupData#ownerRaces}. */
    private void syncRaces() {
        group.ownerRaces.clear();
        for (int idx : raceDropdown.getSelected()) {
            if (idx >= 0 && idx < raceOptions.size()) {
                group.ownerRaces.add(raceOptions.get(idx));
            }
        }
        // A group is EITHER the global stack ("") OR one or more races, never both: writing to both folders
        // makes the form appear twice (stack radial + per-race list). If any real race is picked, drop the
        // stack "" so the two can't be saved together. The server enforces the same rule in FormFileManager.
        if (group.ownerRaces.stream().anyMatch(o -> o != null && !o.isBlank())) {
            group.ownerRaces.removeIf(o -> o == null || o.isBlank());
        }
    }

    private void applyFields() {
        group.groupName = net.shurui.dev.sdu.util.SduIds.sanitize(nameField.getValue());
        group.displayName = displayField.getValue().trim();
        if (typeDropdown != null) {
            int i = typeDropdown.getIndex();
            if (i >= 0 && i < typeOptions.size()) {
                group.formType = typeOptions.get(i);
            }
        }
        if (raceDropdown != null) {
            syncRaces();
        }
    }

    private void applyAndBack() {
        applyFields();
        back();
    }

    private void save() {
        applyFields();
        group.normalizeFormKeys();
        net.shurui.dev.sdu.client.GeneratedLang.putForms(group);
        DmzNet.sendLargeToServer("form", GSON.toJson(group.toBundle()));
        back();
    }
}
