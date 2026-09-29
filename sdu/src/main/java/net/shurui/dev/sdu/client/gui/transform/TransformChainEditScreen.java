package net.shurui.dev.sdu.client.gui.transform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.client.ClientCloneList;
import net.shurui.dev.sdu.client.GameEntities;
import net.shurui.dev.sdu.client.gui.DmzDropdown;
import net.shurui.dev.sdu.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.transform.TransformChain;
import net.shurui.dev.sdu.transform.TransformForm;

import java.util.ArrayList;
import java.util.List;

/**
 * A reusable editor for an NPC's {@link TransformChain}. The chain fires its forms in list order as the
 * NPC's HP drops, so the list order IS the transform sequence. One form is edited at a time (chosen with the
 * top "Form" selector); its many fields are split into sections (Target / Stats / Dodge / Buffs) picked from
 * a section dropdown, mirroring {@link net.shurui.dev.sdu.client.gui.form.FormEditScreen}.
 *
 * <p>Edits the passed {@link TransformChain} IN PLACE and runs {@code onDone} when the user leaves, so the
 * caller can persist. Not yet wired to any caller - later batches (saga, dungeon, raid) open it.</p>
 */
public class TransformChainEditScreen extends FieldEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    /** Section lang keys, resolved for the tab bar in {@link #buildTabHeaderAt}. */
    private static final String[] SECTION_KEYS = {
            "gui.dmz_ragnarok.npc.transform_edit.tab_target",
            "gui.dmz_ragnarok.npc.transform_edit.tab_stats",
            "gui.dmz_ragnarok.npc.transform_edit.tab_dodge",
            "gui.dmz_ragnarok.npc.transform_edit.tab_buffs"};

    /** Buff stat keys, in the order the forms editor lists them. */
    private static final String[] BUFF_KEYS = {"STR", "SKP", "RES", "VIT", "PWR", "ENE"};

    private final TransformChain chain;
    private final Runnable onDone;

    private int selectedForm;
    private int section;

    // --- Target-dropdown backing data (rebuilt each init). Parallel lists: option label vs the raw value
    // stored into form.target. Clones store "cnpc$"+token; entities store the plain entity id. ---
    private DmzDropdown formDropdown;
    private DmzDropdown targetDropdown;
    private final List<String> targetValues = new ArrayList<>();

    public TransformChainEditScreen(Screen parent, TransformChain chain, Component title, Runnable onDone) {
        super(title, UI_W, UI_H, parent);
        this.chain = chain;
        this.onDone = onDone;
        // Populate the clone list for the Target picker the same way ObjectiveEditScreen does: ask the server
        // for the saved Custom NPC clones. If they're already cached this is harmless; the reply rebuilds us.
        net.shurui.dev.sdu.network.DmzNet.sendToServer(new net.shurui.dev.sdu.network.RequestClonesPacket());
    }

    /** Rebuild if this screen is open when the saved-clone list arrives from the server. */
    public static void onClonesSynced() {
        if (Minecraft.getInstance().screen instanceof TransformChainEditScreen s) {
            s.rebuildWidgets();
        }
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        clampSelection();

        headerSubtitle = tr("gui.dmz_ragnarok.npc.transform_edit.subtitle");

        // Start the first (form) row at CONTENT_TOP so the dropdown clears the header/logo (logo bottom y=23).
        int y = contentTop(26);
        // Form selector (custom dropdown: label includes the target, value = index).
        label(tr("gui.dmz_ragnarok.npc.transform_edit.form"), 12, y + 3);
        List<Component> formOpts = new ArrayList<>();
        for (int i = 0; i < chain.forms.size(); i++) {
            formOpts.add(Component.literal(formLabel(i)));
        }
        if (formOpts.isEmpty()) {
            formOpts.add(Component.translatable("gui.dmz_ragnarok.npc.transform_edit.no_forms"));
        }
        formDropdown = dropdown(48, y - 1, 160, formOpts, Math.max(0, selectedForm)).searchable();
        // Add Form is always available; the rest only when there's a form to act on.
        commitBtn(212, y - 1, 76, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.transform_edit.add_form"), this::addForm);
        y += 16;

        if (!chain.forms.isEmpty()) {
            int bw = 68, gap = 4;
            btn(12, y, bw, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.transform_edit.remove"), this::removeForm);
            btn(12 + (bw + gap), y, bw, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.transform_edit.move_up"), () -> moveForm(-1));
            btn(12 + (bw + gap) * 2, y, bw, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.transform_edit.move_down"), () -> moveForm(1));
            y += 16;
        }

        if (!chain.forms.isEmpty()) {
            rowY = buildTabHeaderAt(y);
            buildSection();
        } else {
            label(tr("gui.dmz_ragnarok.npc.transform_edit.empty_hint"), 14, y + 4);
        }

        btn(UI_W / 2 - 55, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.back"),
                () -> { applyFields(); leave(); });
    }

    /** Like {@link #buildTabHeader} but the tab bar is placed at an explicit {@code y} (below the top controls). */
    private int buildTabHeaderAt(int y) {
        String[] sections = new String[SECTION_KEYS.length];
        for (int i = 0; i < SECTION_KEYS.length; i++) {
            sections[i] = tr(SECTION_KEYS[i]);
        }
        int belowTabs = tabs(10, y, tabBarWidth() - 20, sections, section, this::selectSection);
        return belowTabs + 6;
    }

    private void selectSection(int i) {
        applyFields();
        section = i;
        rebuildWidgets();
    }

    private TransformForm current() {
        return chain.forms.get(selectedForm);
    }

    private void buildSection() {
        TransformForm f = current();
        switch (section) {
            case 0 -> buildTarget(f);
            case 1 -> buildStats(f);
            case 2 -> buildDodge(f);
            case 3 -> buildBuffs(f);
            default -> { }
        }
    }

    private void buildTarget(TransformForm f) {
        // Custom dropdown: options are every entity id (label = id, value = id) plus one "Clone: <label>"
        // entry per saved clone (value = "cnpc$"+token). Pre-select the option matching form.target.
        label(tr("gui.dmz_ragnarok.npc.transform_edit.target"), 14, rowY + 5);
        targetValues.clear();
        List<Component> opts = new ArrayList<>();
        // Entity ids, and the ragnarok characters among them rather than behind a second control: they all share
        // one entity type, so a registry list offers the whole cast as a single "rgnpc" row.
        List<String> ids = new ArrayList<>();
        for (ResourceLocation r : GameEntities.mobIds()) {
            ids.add(r.toString());
        }
        ids = net.shurui.dev.sdu.compat.ragnarok.RagnarokLook.options(ids);
        for (String id : ids) {
            targetValues.add(id);
            opts.add(Component.literal(id));
        }
        for (String tok : ClientCloneList.tokens()) {
            targetValues.add("cnpc$" + tok);
            opts.add(Component.translatable("gui.dmz_ragnarok.npc.transform_edit.clone_option", ClientCloneList.label(tok)));
        }
        String cur = net.shurui.dev.sdu.compat.ragnarok.RagnarokLook.value(f.target, f.rgModelId);
        int idx = targetValues.indexOf(cur);
        if (idx < 0 && !cur.isBlank()) {
            // Preserve an off-list value (e.g. a clone not yet synced) as its own option.
            targetValues.add(cur);
            opts.add(Component.literal(cur + " §7(current)"));
            idx = targetValues.size() - 1;
        }
        targetDropdown = dropdown(150, rowY, 132, opts, Math.max(0, idx)).searchable();
        rowY += ROW_H;
        tip(tr("gui.dmz_ragnarok.npc.transform_edit.tip_target"));

        tf(tr("gui.dmz_ragnarok.npc.transform_edit.trigger_hp"), dbl(f.trigger * 100), v -> f.trigger = clampPct(parseD(v, f.trigger * 100)) / 100.0);
        tip(tr("gui.dmz_ragnarok.npc.transform_edit.tip_trigger"));
    }

    private void buildStats(TransformForm f) {
        tf(tr("gui.dmz_ragnarok.npc.transform_edit.health_mult"), dbl(f.hpMult), v -> f.hpMult = parseD(v, 1.0));
        tf(tr("gui.dmz_ragnarok.npc.transform_edit.melee_mult"), dbl(f.meleeMult), v -> f.meleeMult = parseD(v, 1.0));
        tf(tr("gui.dmz_ragnarok.npc.transform_edit.ki_mult"), dbl(f.kiMult), v -> f.kiMult = parseD(v, 1.0));
        tf(tr("gui.dmz_ragnarok.npc.transform_edit.speed_mult"), dbl(f.speedMult), v -> f.speedMult = parseD(v, 1.0));
        tf(tr("gui.dmz_ragnarok.npc.transform_edit.defense_mult"), dbl(f.defMult), v -> f.defMult = parseD(v, 1.0));
        tip(tr("gui.dmz_ragnarok.npc.transform_edit.tip_stats"));
    }

    private void buildDodge(TransformForm f) {
        tf(tr("gui.dmz_ragnarok.npc.transform_edit.dodge_physical"), dbl(f.dodgePhysical), v -> f.dodgePhysical = clampPct(parseD(v, f.dodgePhysical)));
        tf(tr("gui.dmz_ragnarok.npc.transform_edit.dodge_melee_skill"), dbl(f.dodgeMeleeSkill), v -> f.dodgeMeleeSkill = clampPct(parseD(v, f.dodgeMeleeSkill)));
        tf(tr("gui.dmz_ragnarok.npc.transform_edit.dodge_energy_skill"), dbl(f.dodgeEnergySkill), v -> f.dodgeEnergySkill = clampPct(parseD(v, f.dodgeEnergySkill)));
        tf(tr("gui.dmz_ragnarok.npc.transform_edit.damage_mitigation"), dbl(f.damageMitigation), v -> f.damageMitigation = clampPct(parseD(v, f.damageMitigation)));
        tip(tr("gui.dmz_ragnarok.npc.transform_edit.tip_dodge"));
    }

    private void buildBuffs(TransformForm f) {
        for (String key : BUFF_KEYS) {
            tf(key, dbl(gain(f, key)), v -> setGain(f, key, parseD(v, gain(f, key))));
        }
        tf(tr("gui.dmz_ragnarok.npc.transform_edit.max_bonus"), dbl(f.maxBonusPercent), v -> f.maxBonusPercent = Math.max(0, parseD(v, f.maxBonusPercent)));
        tip(tr("gui.dmz_ragnarok.npc.transform_edit.tip_buffs"));
    }

    private static double gain(TransformForm f, String key) {
        Double v = f.statGainPercent.get(key);
        return v == null ? 0.0 : v;
    }

    private static void setGain(TransformForm f, String key, double v) {
        f.statGainPercent.put(key, v); // put the key even if 0, per the model's LinkedHashMap contract
    }

    @Override
    protected void onExtraDropdown(DmzDropdown dropdown, int row) {
        if (dropdown == formDropdown) {
            if (!chain.forms.isEmpty()) {
                applyFields();
                selectedForm = Math.max(0, Math.min(row, chain.forms.size() - 1));
                rebuildWidgets();
            }
        } else if (dropdown == targetDropdown) {
            if (!chain.forms.isEmpty() && row >= 0 && row < targetValues.size()) {
                // A clone ref ("cnpc$...") and a plain entity id both come back unchanged; only a ragnarok
                // character row splits into a target plus a character.
                String picked = targetValues.get(row);
                current().target = net.shurui.dev.sdu.compat.ragnarok.RagnarokLook.entityOf(picked);
                current().rgModelId = net.shurui.dev.sdu.compat.ragnarok.RagnarokLook.modelOf(picked);
                rebuildWidgets();
            }
        }
    }

    private void addForm() {
        applyFields();
        chain.forms.add(new TransformForm());
        selectedForm = chain.forms.size() - 1;
        rebuildWidgets();
    }

    private void removeForm() {
        if (chain.forms.isEmpty()) {
            return;
        }
        applyFields();
        chain.forms.remove(selectedForm);
        clampSelection();
        rebuildWidgets();
    }

    private void moveForm(int dir) {
        if (chain.forms.size() < 2) {
            return;
        }
        applyFields();
        int to = selectedForm + dir;
        if (to < 0 || to >= chain.forms.size()) {
            return;
        }
        TransformForm f = chain.forms.remove(selectedForm);
        chain.forms.add(to, f);
        selectedForm = to;
        rebuildWidgets();
    }

    private void clampSelection() {
        if (chain.forms.isEmpty()) {
            selectedForm = 0;
        } else {
            selectedForm = Math.max(0, Math.min(selectedForm, chain.forms.size() - 1));
        }
    }

    private String formLabel(int i) {
        TransformForm f = chain.forms.get(i);
        String tgt = f.target == null || f.target.isBlank() ? tr("gui.dmz_ragnarok.npc.transform_edit.no_target") : f.target;
        return tr("gui.dmz_ragnarok.npc.transform_edit.form_label", i + 1, tgt);
    }

    private void leave() {
        if (onDone != null) {
            try {
                onDone.run();
            } catch (Exception ignored) {
            }
        }
        minecraft.setScreen(parent);
    }

    private static double clampPct(double v) {
        return Math.max(0.0, Math.min(100.0, v));
    }
}
