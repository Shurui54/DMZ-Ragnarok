package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_raid_bosses.client.GameRegistries;
import net.shurui.dev.shuruis_raid_bosses.raid.BossStage;

import java.util.ArrayList;
import java.util.List;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * Edit one Boss Rush stage: a searchable entity dropdown (every entity type, and every ragnarok character as
 * its own row), a display name, health / melee damage / ki power / scale stats, a pre-spawn delay, and a nested
 * ki-move editor. Battle power is not editable; it is derived at spawn as round(melee + ki power). Writes the
 * result back into the backing token list as a {@link BossStage} token.
 */
public class BossStageEditScreen extends BaseEditScreen {
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final List<String> backing;
    private final int index;
    private final BossStage stage;
    /** Every entity type PLUS every ragnarok character as its own row (see {@code RgNpcPicker}). */
    private final List<String> entityIds;

    private DmzDropdown entityDropdown;
    private EditBox nameField, healthField, meleeField, kiField, defenseField, scaleField, delayField;

    public BossStageEditScreen(Screen parent, List<String> backing, int index) {
        super(Component.translatable("gui.dmz_ragnarok.raid.stage_edit.title"), UI_W, UI_H, parent);
        this.backing = backing;
        this.index = index;
        this.stage = BossStage.fromToken(backing.get(index));
        // The ragnarok characters are IN the entity list, each its own row, because they all share one entity
        // type and the registry can only offer that one. See RgNpcPicker.
        this.entityIds = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.options(GameRegistries.entityIds());
    }

    @Override
    protected void init() {
        super.init();
        // Named entity: show the boss stage's name (its boss name, else its entity id) in the panel's top left.
        panelName = stage.bossName != null && !stage.bossName.isBlank() ? stage.bossName : stage.entityType;
        label(I18n.get("gui.dmz_ragnarok.raid.stage_edit.entity"), 12, 26);
        List<Component> opts = new ArrayList<>();
        for (String s : entityIds) opts.add(Component.literal(s));
        int idx = Math.max(0, entityIds.indexOf(
                net.shurui.shuruisutilities.ragnarok.RgNpcPicker.value(stage.entityType, stage.rgModelId)));
        entityDropdown = dropdown(72, 22, 216, opts, idx).searchable();
        tooltip(12, 20, 276, 12, I18n.get("gui.dmz_ragnarok.raid.stage_edit.entity_tip"));

        int y = 44;
        label(I18n.get("gui.dmz_ragnarok.raid.stage_edit.boss_name"), 12, y + 3);
        nameField = field(90, y, 198, stage.bossName);
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.stage_edit.boss_name_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.stage_edit.health"), 12, y + 3);
        healthField = field(110, y, 70, trim(stage.health));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.stage_edit.health_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.stage_edit.melee"), 12, y + 3);
        meleeField = field(96, y, 70, trim(stage.meleeDamage));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.stage_edit.melee_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.stage_edit.ki"), 12, y + 3);
        kiField = field(72, y, 70, trim(stage.kiPower));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.stage_edit.ki_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.stage_edit.defense"), 12, y + 3);
        defenseField = field(72, y, 70, trim(stage.defense));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.stage_edit.defense_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.stage_edit.scale"), 12, y + 3);
        scaleField = field(72, y, 60, trim(stage.scale));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.stage_edit.scale_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.stage_edit.delay"), 12, y + 3);
        delayField = field(110, y, 60, Integer.toString(stage.delaySeconds));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.stage_edit.delay_tip"));
        y += 20;
        btn(20, y, 260, 16, Component.translatable("gui.dmz_ragnarok.raid.edit.ki_moves", stage.kiMoves.size()), () -> {
            apply();
            this.minecraft.setScreen(new KiMoveListScreen(this, stage.kiMoves));
        });
        y += 20;
        // Reinforcements that arrive WITH this stage, on the players' side. Nested tokens: a stage's allies live
        // inside the stage's own token, so they use the nested separators.
        btn(20, y, 260, 16, Component.translatable("gui.dmz_ragnarok.raid.stage_edit.allies", stage.allies.size()),
                () -> {
                    apply();
                    this.minecraft.setScreen(new AllyListScreen(this, stage.allies, true));
                });
        y += 20;
        // The side swap: everyone alive changes sides as this stage begins, the ally turning on the players or the
        // enemy joining them. It happens BEFORE the stage's own boss and allies are spawned, so what this stage
        // brings is never caught by its own swap.
        btn(20, y, 260, 16, Component.translatable(stage.swapSides
                        ? "gui.dmz_ragnarok.raid.stage_edit.swap_on"
                        : "gui.dmz_ragnarok.raid.stage_edit.swap_off"),
                () -> {
                    apply();
                    stage.swapSides = !stage.swapSides;
                    rebuildWidgets();
                });
        tooltip(12, y, 276, 16, I18n.get("gui.dmz_ragnarok.raid.stage_edit.swap_tip"));

        btn(UI_W / 2 - 55, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.common.save_plain"), () -> {
            apply();
            backing.set(index, stage.toToken());
            back();
        });
    }

    @Override
    protected void onDropdownSelect(DmzDropdown dropdown, int row) {
        apply();
        if (dropdown == entityDropdown && row >= 0 && row < entityIds.size()) {
            String picked = entityIds.get(row);
            stage.entityType = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.entityOf(picked);
            stage.rgModelId = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.modelOf(picked);
        }
        rebuildWidgets();
    }

    private void apply() {
        if (nameField != null) stage.bossName = nameField.getValue();
        if (healthField != null) stage.health = parseDouble(healthField.getValue(), stage.health);
        if (meleeField != null) stage.meleeDamage = parseDouble(meleeField.getValue(), stage.meleeDamage);
        if (kiField != null) stage.kiPower = parseDouble(kiField.getValue(), stage.kiPower);
        if (defenseField != null) stage.defense = parseDouble(defenseField.getValue(), stage.defense);
        if (scaleField != null) stage.scale = parseDouble(scaleField.getValue(), stage.scale);
        if (delayField != null) stage.delaySeconds = Math.max(0, parseInt(delayField.getValue(), stage.delaySeconds));
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
