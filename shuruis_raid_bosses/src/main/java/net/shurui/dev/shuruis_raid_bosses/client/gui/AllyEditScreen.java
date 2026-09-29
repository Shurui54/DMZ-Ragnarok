package net.shurui.dev.shuruis_raid_bosses.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import net.shurui.dev.shuruis_raid_bosses.client.GameRegistries;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_raid_bosses.raid.AllySpawn;

/**
 * Edit one ALLY: the same fields an enemy wave has, because an ally is the same kind of NPC pointed the other way.
 *
 * <p>It carries a display NAME as well, which an enemy wave does not: an ally is somebody the player is meant to
 * recognise and keep alive, so "Krillin" reads very differently over its head than the entity id does.
 */
public class AllyEditScreen extends BaseEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final List<String> backing;
    private final int index;
    private final boolean nested;
    private final AllySpawn ally;
    /** Every entity type PLUS every ragnarok character as its own row (see {@code RgNpcPicker}). */
    private final List<String> entityIds;

    private DmzDropdown entityDropdown;
    private EditBox nameField, countField, healthField, meleeField, kiField, defenseField, scaleField;

    public AllyEditScreen(Screen parent, List<String> backing, int index, boolean nested) {
        super(Component.translatable("gui.dmz_ragnarok.raid.ally_edit.title"), UI_W, UI_H, parent);
        this.backing = backing;
        this.index = index;
        this.nested = nested;
        this.ally = nested ? AllySpawn.fromNestedToken(backing.get(index)) : AllySpawn.fromToken(backing.get(index));
        this.entityIds = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.options(GameRegistries.entityIds());
    }

    @Override
    protected void init() {
        super.init();
        panelName = ally.name == null || ally.name.isBlank() ? ally.entityType : ally.name;
        label(I18n.get("gui.dmz_ragnarok.raid.ally_edit.entity"), 12, 26);
        List<Component> opts = new ArrayList<>();
        for (String s : entityIds) {
            opts.add(Component.literal(s));
        }
        int idx = Math.max(0, entityIds.indexOf(
                net.shurui.shuruisutilities.ragnarok.RgNpcPicker.value(ally.entityType, ally.rgModelId)));
        entityDropdown = dropdown(72, 22, 216, opts, idx).searchable();
        tooltip(12, 20, 276, 12, I18n.get("gui.dmz_ragnarok.raid.ally_edit.entity_tip"));

        int y = 44;
        label(I18n.get("gui.dmz_ragnarok.raid.ally_edit.name"), 12, y + 3);
        nameField = field(72, y, 150, ally.name == null ? "" : ally.name);
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.ally_edit.name_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.ally_edit.amount"), 12, y + 3);
        countField = field(72, y, 50, Integer.toString(ally.count));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.ally_edit.amount_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.ally_edit.health"), 12, y + 3);
        healthField = field(72, y, 60, trim(ally.health));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.ally_edit.health_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.ally_edit.melee"), 12, y + 3);
        meleeField = field(96, y, 70, trim(ally.meleeDamage));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.ally_edit.melee_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.ally_edit.ki"), 12, y + 3);
        kiField = field(72, y, 70, trim(ally.kiPower));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.ally_edit.ki_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.ally_edit.defense"), 12, y + 3);
        defenseField = field(72, y, 60, trim(ally.defense));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.ally_edit.defense_tip"));
        y += 20;
        label(I18n.get("gui.dmz_ragnarok.raid.ally_edit.scale"), 12, y + 3);
        scaleField = field(72, y, 60, trim(ally.scale));
        tooltip(12, y, 276, 12, I18n.get("gui.dmz_ragnarok.raid.ally_edit.scale_tip"));

        btn(UI_W / 2 - 55, footerY(), 110, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.raid.common.save_plain"), () -> {
                    apply();
                    backing.set(index, nested ? ally.toNestedToken() : ally.toToken());
                    back();
                });
    }

    @Override
    protected void onDropdownSelect(DmzDropdown dropdown, int row) {
        apply();
        if (dropdown == entityDropdown && row >= 0 && row < entityIds.size()) {
            String picked = entityIds.get(row);
            ally.entityType = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.entityOf(picked);
            ally.rgModelId = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.modelOf(picked);
        }
        rebuildWidgets();
    }

    private void apply() {
        if (nameField != null) {
            ally.name = nameField.getValue().trim();
        }
        if (countField != null) {
            ally.count = Math.max(1, parseInt(countField.getValue(), ally.count));
        }
        if (healthField != null) {
            ally.health = parseDouble(healthField.getValue(), ally.health);
        }
        if (meleeField != null) {
            ally.meleeDamage = parseDouble(meleeField.getValue(), ally.meleeDamage);
        }
        if (kiField != null) {
            ally.kiPower = parseDouble(kiField.getValue(), ally.kiPower);
        }
        if (defenseField != null) {
            ally.defense = parseDouble(defenseField.getValue(), ally.defense);
        }
        if (scaleField != null) {
            ally.scale = parseDouble(scaleField.getValue(), ally.scale);
        }
    }

    private static String trim(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    private static int parseInt(String s, int fb) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fb;
        }
    }

    private static double parseDouble(String s, double fb) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fb;
        }
    }
}
