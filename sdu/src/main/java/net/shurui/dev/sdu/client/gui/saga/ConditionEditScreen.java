package net.shurui.dev.sdu.client.gui.saga;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.DmzRaces;
import net.shurui.dev.sdu.client.gui.DmzDropdown;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.saga.SagaData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Edit one requirement condition: type dropdown + per-type fields. */
public class ConditionEditScreen extends SagaBaseScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final SagaData.Condition cond;
    private DmzDropdown typeDropdown, timeModeDropdown, raceDropdown;
    private EditBox minLevelField, dimField, biomeField, minField, maxField, sagaIdField, questIdField, amountField;
    /** Race ids backing {@link #raceDropdown} (parallel to its rows), from {@link DmzRaces#raceIds()}. */
    private final List<String> raceIds = new ArrayList<>();

    public ConditionEditScreen(Screen parent, SagaData.Condition cond) {
        super(Component.translatable("gui.dmz_ragnarok.npc.condition_edit.edit_requirement"), UI_W, UI_H, parent);
        this.cond = cond;
    }

    @Override
    protected void init() {
        super.init();
        // Start the first (type) row at CONTENT_TOP so the dropdown clears the header/logo (logo bottom y=23).
        int row1 = contentTop(22);
        label( tr("gui.dmz_ragnarok.npc.condition_edit.type"), 12, row1 + 4);
        typeDropdown = dropdown(48, row1, 140, options(SagaData.CONDITION_TYPES), indexOf(SagaData.CONDITION_TYPES, cond.type));
        tooltip(12, row1 - 2, 180, 18, tr("gui.dmz_ragnarok.npc.condition_edit.t_condition_to_unlock"));

        // Clear the per-type widget refs so a stale one from the previous type isn't read on apply().
        minLevelField = dimField = biomeField = minField = maxField = sagaIdField = questIdField = amountField = null;
        timeModeDropdown = null;
        raceDropdown = null;

        int y = 52;
        switch (cond.type) {
            case "LEVEL" -> minLevelField = labeled(tr("gui.dmz_ragnarok.npc.condition_edit.min_level"), 12, y, 78, 60, Integer.toString(cond.minLevel));
            case "DIMENSION" -> dimField = labeled(tr("gui.dmz_ragnarok.npc.condition_edit.dimension"), 12, y, 78, 194, cond.dimension);
            case "BIOME" -> biomeField = labeled(tr("gui.dmz_ragnarok.npc.condition_edit.biome"), 12, y, 62, 210, cond.biome);
            case "ALIGNMENT" -> {
                minField = labeled(tr("gui.dmz_ragnarok.npc.condition_edit.min"), 12, y, 48, 60, Integer.toString(cond.min));
                maxField = labeled(tr("gui.dmz_ragnarok.npc.condition_edit.max"), 120, y, 152, 60, Integer.toString(cond.max));
            }
            case "TIME" -> {
                label(tr("gui.dmz_ragnarok.npc.condition_edit.mode"), 12, y + 4);
                timeModeDropdown = dropdown(48, y, 118, options(SagaData.TIME_MODES), indexOf(SagaData.TIME_MODES, cond.timeMode));
                y += 24;
                amountField = labeled(tr("gui.dmz_ragnarok.npc.condition_edit.delay"), 12, y, 48, 120, Long.toString(cond.timeAmount));
                // The timer starts once the quest's other requirements are met; this delay before it
                // becomes available is DMZ's repeatable/daily cooldown. Units follow the mode.
                tooltip(12, y - 2, 260, 18, tr("gui.dmz_ragnarok.npc.condition_edit.t_time_delay"));
            }
            case "SAGA_QUEST" -> {
                sagaIdField = labeled(tr("gui.dmz_ragnarok.npc.condition_edit.saga_id"), 12, y, 70, 200, cond.sagaId);
                y += 20;
                questIdField = labeled(tr("gui.dmz_ragnarok.npc.condition_edit.quest_id"), 12, y, 70, 60, Integer.toString(cond.questId));
            }
            case "RACE" -> {
                label(tr("gui.dmz_ragnarok.npc.condition_edit.race"), 12, y + 4);
                raceIds.clear();
                raceIds.addAll(DmzRaces.raceIds());
                List<Component> opts = new ArrayList<>();
                if (raceIds.isEmpty()) {
                    opts.add(Component.literal(cond.race));
                } else {
                    for (String r : raceIds) {
                        opts.add(Component.literal(r));
                    }
                }
                int idx = Math.max(0, raceIds.indexOf(cond.race));
                raceDropdown = dropdown(58, y, 214, opts, idx).searchable();
            }
            default -> { }
        }
        btn(UI_W / 2 - 55, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.back"), () -> { apply(); back(); });
    }

    private EditBox labeled(String lbl, int lblX, int y, int fieldX, int w, String value) {
        label(lbl, lblX, y + 4);
        return field(fieldX, y, w, value);
    }

    @Override
    protected void onDropdownSelect(DmzDropdown dropdown, int row) {
        apply();
        if (dropdown == typeDropdown) {
            cond.type = SagaData.CONDITION_TYPES[row];
        } else if (dropdown == timeModeDropdown) {
            cond.timeMode = SagaData.TIME_MODES[row];
        } else if (dropdown == raceDropdown) {
            if (!raceIds.isEmpty()) {
                cond.race = raceIds.get(row).toLowerCase(Locale.ROOT);
            }
        }
        rebuildWidgets();
    }

    private void apply() {
        if (minLevelField != null) cond.minLevel = parseInt(minLevelField.getValue(), cond.minLevel);
        if (dimField != null) cond.dimension = dimField.getValue().trim();
        if (biomeField != null) cond.biome = biomeField.getValue().trim();
        if (minField != null) cond.min = parseInt(minField.getValue(), cond.min);
        if (maxField != null) cond.max = parseInt(maxField.getValue(), cond.max);
        if (sagaIdField != null) cond.sagaId = sagaIdField.getValue().trim();
        if (questIdField != null) cond.questId = parseInt(questIdField.getValue(), cond.questId);
        if (amountField != null) cond.timeAmount = Math.max(0, parseLong(amountField.getValue(), cond.timeAmount));
        if (raceDropdown != null && !raceIds.isEmpty()) {
            int i = raceDropdown.getIndex();
            if (i >= 0 && i < raceIds.size()) {
                cond.race = raceIds.get(i).toLowerCase(Locale.ROOT);
            }
        }
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) return i;
        }
        return 0;
    }

    private static int parseInt(String s, int fb) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fb; }
    }

    private static long parseLong(String s, long fb) {
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return fb; }
    }
}
