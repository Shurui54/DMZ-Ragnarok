package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_raid_bosses.client.GameRegistries;
import net.shurui.dev.shuruis_raid_bosses.raid.EnemyWave;

import java.util.ArrayList;
import java.util.List;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * Edit one Parallel Quest enemy wave: a searchable entity dropdown (every entity type, and every ragnarok
 * character as its own row) plus amount and per-enemy stat inputs (health, melee damage, ki power, scale).
 * Battle power is not editable; it is derived at spawn as round(melee + ki power). Writes the result back into
 * the backing token list.
 */
public class EnemyWaveEditScreen extends BaseEditScreen {
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final List<String> backing;
    private final int index;
    private final EnemyWave wave;
    /** Every entity type PLUS every ragnarok character as its own row (see {@code RgNpcPicker}). */
    private final List<String> entityIds;

    private DmzDropdown entityDropdown;
    private EditBox countField, waveField, healthField, meleeField, kiField, defenseField, scaleField;

    public EnemyWaveEditScreen(Screen parent, List<String> backing, int index) {
        super(Component.translatable("gui.dmz_ragnarok.raid.enemy_edit.title"), UI_W, UI_H, parent);
        this.backing = backing;
        this.index = index;
        this.wave = EnemyWave.fromToken(backing.get(index));
        // The ragnarok characters are IN the entity list, each its own row, because they all share one entity
        // type and the registry can only offer that one. See RgNpcPicker.
        this.entityIds = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.options(GameRegistries.entityIds());
    }

    @Override
    protected void init() {
        super.init();
        // Named entity: show the wave's entity id in the panel's top left.
        panelName = wave.entityType;
        label(I18n.get("gui.dmz_ragnarok.raid.enemy_edit.entity"), 12, 26);
        List<Component> opts = new ArrayList<>();
        for (String s : entityIds) opts.add(Component.literal(s));
        int idx = Math.max(0, entityIds.indexOf(
                net.shurui.shuruisutilities.ragnarok.RgNpcPicker.value(wave.entityType, wave.rgModelId)));
        entityDropdown = dropdown(72, 22, 216, opts, idx).searchable();
        tooltip(12, 20, 276, 12, I18n.get("gui.dmz_ragnarok.raid.enemy_edit.entity_tip"));

        int y = 44;
        label(I18n.get("gui.dmz_ragnarok.raid.enemy_edit.amount"), 12, y + 3);
        countField = field(72, y, 50, Integer.toString(wave.count));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.enemy_edit.amount_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.enemy_edit.wave"), 12, y + 3);
        waveField = field(72, y, 50, Integer.toString(wave.wave));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.enemy_edit.wave_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.enemy_edit.health"), 12, y + 3);
        healthField = field(72, y, 60, trim(wave.health));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.enemy_edit.health_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.enemy_edit.melee"), 12, y + 3);
        meleeField = field(96, y, 70, trim(wave.meleeDamage));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.enemy_edit.melee_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.enemy_edit.ki"), 12, y + 3);
        kiField = field(72, y, 70, trim(wave.kiPower));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.enemy_edit.ki_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.enemy_edit.defense"), 12, y + 3);
        defenseField = field(72, y, 60, trim(wave.defense));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.enemy_edit.defense_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.enemy_edit.scale"), 12, y + 3);
        scaleField = field(72, y, 60, trim(wave.scale));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.enemy_edit.scale_tip"));

        btn(UI_W / 2 - 55, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.common.save_plain"), () -> {
            apply();
            backing.set(index, wave.toToken());
            back();
        });
    }

    @Override
    protected void onDropdownSelect(DmzDropdown dropdown, int row) {
        apply();
        if (dropdown == entityDropdown && row >= 0 && row < entityIds.size()) {
            String picked = entityIds.get(row);
            wave.entityType = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.entityOf(picked);
            wave.rgModelId = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.modelOf(picked);
        }
        rebuildWidgets();
    }

    private void apply() {
        if (countField != null) wave.count = Math.max(1, parseInt(countField.getValue(), wave.count));
        if (waveField != null) wave.wave = Math.max(1, parseInt(waveField.getValue(), wave.wave));
        if (healthField != null) wave.health = parseDouble(healthField.getValue(), wave.health);
        if (meleeField != null) wave.meleeDamage = parseDouble(meleeField.getValue(), wave.meleeDamage);
        if (kiField != null) wave.kiPower = parseDouble(kiField.getValue(), wave.kiPower);
        if (defenseField != null) wave.defense = parseDouble(defenseField.getValue(), wave.defense);
        if (scaleField != null) wave.scale = parseDouble(scaleField.getValue(), wave.scale);
    }

    private static String trim(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return Long.toString((long) v);
        return Double.toString(v);
    }

    private static int parseInt(String s, int fb) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fb; }
    }

    private static double parseDouble(String s, double fb) {
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return fb; }
    }
}
