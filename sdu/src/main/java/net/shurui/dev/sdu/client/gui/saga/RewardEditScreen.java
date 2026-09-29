package net.shurui.dev.sdu.client.gui.saga;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.client.DmzSkills;
import net.shurui.dev.sdu.client.GameItems;
import net.shurui.dev.sdu.client.gui.DmzDropdown;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.compat.DmzTechniques;
import net.shurui.dev.sdu.saga.SagaData;

import java.util.ArrayList;
import java.util.List;

/** Edit one reward: type dropdown + per-type inputs (TPS amount, ITEM+count, SKILL+level, TECHNIQUE, COMMAND). */
public class RewardEditScreen extends SagaBaseScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final SagaData.Reward reward;
    private final List<ResourceLocation> itemIds = new ArrayList<>();
    private final List<String> skillIds = new ArrayList<>();
    private final List<String> techniqueIds = new ArrayList<>();
    private final List<String> formIds = new ArrayList<>();

    private DmzDropdown typeDropdown, itemDropdown, skillDropdown, techniqueDropdown, formDropdown;
    private EditBox amountField, countField, levelField, commandField, nbtField;

    public RewardEditScreen(Screen parent, SagaData.Reward reward) {
        super(Component.translatable("gui.dmz_ragnarok.npc.reward_edit.edit_reward"), UI_W, UI_H, parent);
        this.reward = reward;
    }

    @Override
    protected void init() {
        super.init();
        // Start the first (type) row at CONTENT_TOP so the dropdown clears the header/logo (logo bottom y=23).
        int row1 = contentTop(22);
        label( tr("gui.dmz_ragnarok.npc.reward_edit.type"), 12, row1 + 4);
        typeDropdown = dropdown(48, row1, 130, options(SagaData.REWARD_TYPES), indexOf(SagaData.REWARD_TYPES, reward.type));
        tooltip(12, row1 - 2, 170, 18, tr("gui.dmz_ragnarok.npc.reward_edit.t_reward_on_completion"));

        int y = 52;
        switch (reward.type) {
            case "TPS" -> amountField = labeled(tr("gui.dmz_ragnarok.npc.reward_edit.amount"), 12, y, 70, 190, Integer.toString(reward.amount));
            case "ITEM" -> {
                label( tr("gui.dmz_ragnarok.npc.reward_edit.item"), 12, y + 4);
                itemIds.clear();
                itemIds.addAll(GameItems.itemIds());
                List<Component> opts = new ArrayList<>();
                for (ResourceLocation r : itemIds) {
                    opts.add(Component.literal(r.toString()));
                }
                int idx = Math.max(0, itemIds.indexOf(ResourceLocation.tryParse(reward.item)));
                itemDropdown = dropdown(58, y, 214, opts, idx).searchable();
                y += 22;
                countField = labeled(tr("gui.dmz_ragnarok.npc.reward_edit.count"), 12, y, 58, 60, Integer.toString(reward.count));
                y += 22;
                // Optional SNBT applied to the granted item (a {..} compound): enchants, a name, modded data.
                // Blank = a plain item exactly as before. Delivered via a /give command when set.
                nbtField = labeled(tr("gui.dmz_ragnarok.npc.reward_edit.nbt"), 12, y, 42, 230, reward.nbt);
                tooltip(12, y, 260, 16, tr("gui.dmz_ragnarok.npc.reward_edit.t_nbt"));
            }
            case "SKILL" -> {
                label( tr("gui.dmz_ragnarok.npc.reward_edit.skill"), 12, y + 4);
                skillIds.clear();
                skillIds.addAll(DmzSkills.skillIds());
                skillIds.removeAll(DmzTechniques.techniqueIds()); // ki/strike attacks belong to the TECHNIQUE reward
                List<Component> opts = new ArrayList<>();
                if (skillIds.isEmpty()) {
                    opts.add(Component.translatable("gui.dmz_ragnarok.npc.reward_edit.no_skills_found"));
                } else {
                    for (String s : skillIds) {
                        opts.add(Component.literal(s));
                    }
                }
                int idx = Math.max(0, skillIds.indexOf(reward.skill));
                skillDropdown = dropdown(58, y, 214, opts, idx).searchable();
                y += 22;
                levelField = labeled(tr("gui.dmz_ragnarok.npc.reward_edit.level"), 12, y, 58, 60, Integer.toString(reward.level));
            }
            case "TECHNIQUE" -> {
                label( tr("gui.dmz_ragnarok.npc.reward_edit.technique"), 12, y + 4);
                techniqueIds.clear();
                techniqueIds.addAll(DmzTechniques.techniqueIds());
                List<Component> opts = new ArrayList<>();
                if (techniqueIds.isEmpty()) {
                    opts.add(Component.translatable("gui.dmz_ragnarok.npc.reward_edit.no_techniques_found"));
                } else {
                    for (String s : techniqueIds) {
                        // Show the DMZ lang name (e.g. "Kamehame Ha"); the id is kept in techniqueIds.
                        opts.add(Component.translatable("technique.dragonminez." + s));
                    }
                }
                int idx = Math.max(0, techniqueIds.indexOf(reward.technique));
                techniqueDropdown = dropdown(72, y, 200, opts, idx).searchable();
                tooltip(12, y, 260, 16, tr("gui.dmz_ragnarok.npc.reward_edit.t_grants_a_ki_strike_t"));
            }
            case "COMMAND" -> commandField = labeled(tr("gui.dmz_ragnarok.npc.reward_edit.command"), 12, y, 74, 198, reward.command);
            case "FORM_PURCHASE" -> {
                label( tr("gui.dmz_ragnarok.npc.reward_edit.form"), 12, y + 4);
                formIds.clear();
                formIds.addAll(net.shurui.dev.sdu.client.DmzAssets.formKeys());
                List<Component> opts = new ArrayList<>();
                if (formIds.isEmpty()) {
                    opts.add(Component.translatable("gui.dmz_ragnarok.npc.reward_edit.no_forms_found"));
                } else {
                    for (String f : formIds) {
                        opts.add(Component.literal(f));
                    }
                }
                int idx = Math.max(0, formIds.indexOf(reward.formKey));
                formDropdown = dropdown(58, y, 214, opts, idx).searchable();
                tooltip(12, y, 260, 16, tr("gui.dmz_ragnarok.npc.reward_edit.t_unlocks_form_purchase"));
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
            reward.type = SagaData.REWARD_TYPES[row];
        } else if (dropdown == itemDropdown) {
            if (!itemIds.isEmpty()) {
                reward.item = itemIds.get(row).toString();
            }
        } else if (dropdown == skillDropdown) {
            if (!skillIds.isEmpty()) {
                reward.skill = skillIds.get(row);
            }
        } else if (dropdown == techniqueDropdown) {
            if (!techniqueIds.isEmpty()) {
                reward.technique = techniqueIds.get(row);
            }
        } else if (dropdown == formDropdown) {
            if (!formIds.isEmpty()) {
                reward.formKey = formIds.get(row);
            }
        }
        rebuildWidgets();
    }

    private void apply() {
        if (amountField != null) reward.amount = parseInt(amountField.getValue(), reward.amount);
        if (countField != null) reward.count = parseInt(countField.getValue(), reward.count);
        if (levelField != null) reward.level = parseInt(levelField.getValue(), reward.level);
        if (commandField != null) reward.command = commandField.getValue().trim();
        if (nbtField != null) reward.nbt = nbtField.getValue().trim();
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
}
