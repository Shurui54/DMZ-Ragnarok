package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.shuruis_raid_bosses.client.GameRegistries;
import net.shurui.dev.shuruis_raid_bosses.reward.Reward;

import java.util.ArrayList;
import java.util.List;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * Edit one reward: a type dropdown plus the inputs for that type (searchable item / skill dropdowns,
 * counts, command, training-point amount, message). Command and message fields may use damage placeholders like
 * {@code %player%} and {@code %damage_percent%}. Writes the result back into the backing token list.
 */
public class RewardEditScreen extends BaseEditScreen {
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final List<String> backing;
    private final int index;
    private final Reward reward;

    private DmzDropdown typeDropdown, itemDropdown, skillDropdown;
    private EditBox countField, levelField, commandField, tpField, messageField;
    private final List<ResourceLocation> itemIds = new ArrayList<>();
    private final List<String> skillIds = new ArrayList<>();

    public RewardEditScreen(Screen parent, List<String> backing, int index) {
        super(Component.translatable("gui.dmz_ragnarok.raid.reward_edit.title"), UI_W, UI_H, parent);
        this.backing = backing;
        this.index = index;
        this.reward = Reward.fromToken(backing.get(index));
    }

    @Override
    protected void init() {
        super.init();
        // Named entity: show the reward's type in the panel's top left.
        panelName = reward.type;
        label(I18n.get("gui.dmz_ragnarok.raid.reward_edit.type"), 12, 26);
        typeDropdown = dropdown(60, 22, 140, options(Reward.TYPES), indexOf(Reward.TYPES, reward.type));
        tooltip(12, 20, 200, 16, I18n.get("gui.dmz_ragnarok.raid.reward_edit.type_tip"));

        int y = 52;
        switch (reward.type) {
            case "ITEM" -> {
                label(I18n.get("gui.dmz_ragnarok.raid.reward_edit.item"), 12, y + 3);
                itemIds.clear();
                itemIds.addAll(GameRegistries.itemIds());
                List<Component> opts = new ArrayList<>();
                for (ResourceLocation r : itemIds) opts.add(Component.literal(r.toString()));
                int idx = Math.max(0, itemIds.indexOf(ResourceLocation.tryParse(reward.item)));
                itemDropdown = dropdown(60, y, 228, opts, idx).searchable();
                y += 22;
                countField = labeled(I18n.get("gui.dmz_ragnarok.raid.reward_edit.count"), 12, y, 60, 60, Integer.toString(reward.count));
            }
            case "SKILL" -> {
                label(I18n.get("gui.dmz_ragnarok.raid.reward_edit.skill"), 12, y + 3);
                skillIds.clear();
                skillIds.addAll(GameRegistries.skillIds());
                List<Component> opts = new ArrayList<>();
                if (skillIds.isEmpty()) opts.add(Component.translatable("gui.dmz_ragnarok.raid.reward_edit.no_skills"));
                else for (String s : skillIds) opts.add(Component.literal(s));
                int idx = Math.max(0, skillIds.indexOf(reward.skill));
                skillDropdown = dropdown(60, y, 228, opts, idx).searchable();
                y += 22;
                levelField = labeled(I18n.get("gui.dmz_ragnarok.raid.reward_edit.levels"), 12, y, 60, 60, Integer.toString(reward.level));
            }
            case "COMMAND" -> commandField = labeled(I18n.get("gui.dmz_ragnarok.raid.reward_edit.command"), 12, y, 76, 212, reward.command);
            case "TP" -> tpField = labeled(I18n.get("gui.dmz_ragnarok.raid.reward_edit.tp"), 12, y, 76, 212, reward.tp);
            default -> messageField = labeled(I18n.get("gui.dmz_ragnarok.raid.reward_edit.message"), 12, y, 76, 212, reward.message);
        }

        btn(UI_W / 2 - 55, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.common.save_plain"), () -> { apply(); backing.set(index, reward.toToken()); back(); });
    }

    private EditBox labeled(String lbl, int lblX, int y, int fieldX, int w, String value) {
        label(lbl, lblX, y + 3);
        return field(fieldX, y, w, value);
    }

    @Override
    protected void onDropdownSelect(DmzDropdown dropdown, int row) {
        apply();
        if (dropdown == typeDropdown) {
            reward.type = Reward.TYPES[row];
        } else if (dropdown == itemDropdown && !itemIds.isEmpty()) {
            reward.item = itemIds.get(row).toString();
        } else if (dropdown == skillDropdown && !skillIds.isEmpty()) {
            reward.skill = skillIds.get(row);
        }
        rebuildWidgets();
    }

    private void apply() {
        if (countField != null) reward.count = parseInt(countField.getValue(), reward.count);
        if (levelField != null) reward.level = parseInt(levelField.getValue(), reward.level);
        if (commandField != null) reward.command = commandField.getValue().trim();
        if (tpField != null) reward.tp = tpField.getValue().trim();
        if (messageField != null) reward.message = messageField.getValue();
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equalsIgnoreCase(v)) return i;
        return 0;
    }

    private static int parseInt(String s, int fb) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fb;
        }
    }
}
