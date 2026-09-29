package net.shurui.dev.sdu.client.gui.race;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.DmzAssets;
import net.shurui.dev.sdu.client.DmzRacials;
import net.shurui.dev.sdu.client.GeneratedLang;
import net.shurui.dev.sdu.client.gui.DmzDropdown;
import net.shurui.dev.sdu.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.race.RaceData;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.SaveRacePacket;

import java.util.ArrayList;
import java.util.List;

/** Edit a race's character.json (identity / appearance / colours), its display names, and class stats. */
public class RaceEditScreen extends FieldEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final Gson GSON = new Gson();
    private static final String[] SECTIONS = {
            "gui.dmz_ragnarok.npc.race_edit.tab.identity", "gui.dmz_ragnarok.npc.race_edit.tab.names", "gui.dmz_ragnarok.npc.race_edit.tab.body",
            "gui.dmz_ragnarok.npc.race_edit.tab.colors", "gui.dmz_ragnarok.npc.race_edit.tab.classes", "gui.dmz_ragnarok.npc.race_edit.tab.costs"};

    private final RaceData race;
    // Display strings, stored in the client lang overlay (not DMZ's character.json).
    private String displayName;
    private String description;
    // Racial ability name/description (package-visible: edited by RacialAbilityScreen).
    String racialName;
    String racialDesc;
    private DmzDropdown racialDropdown;
    private DmzDropdown addClassDropdown;
    // Form-cost rows [formSkill, csvCosts], mirroring DMZ's formSkillsCosts; edited on the Costs tab.
    private final List<String[]> formCostRows = new ArrayList<>();
    private final List<EditBox> costKeyBoxes = new ArrayList<>();
    private final List<EditBox> costCsvBoxes = new ArrayList<>();
    private int section;
    // Parallel value lists mapping the action dropdowns' option index -> underlying value/sentinel.
    private List<String> racialValues = new ArrayList<>();
    private List<String> addClassValues = new ArrayList<>();

    /** Sentinel option label for creating a new racial ability / class from a dropdown. */
    private static final String NEW_RACIAL = "§a+ New racial ability…";
    private static final String NEW_CLASS = "§a+ New class…";

    public RaceEditScreen(Screen parent, RaceData race) {
        super(Component.translatable("gui.dmz_ragnarok.npc.race_edit.edit_race"), UI_W, UI_H, parent);
        this.race = race;
        // Prefer names stored in the race config (new races carry them); fall back to the lang overlay
        // for races made before names were stored in character.json.
        this.displayName = !race.displayName.isBlank() ? race.displayName : GeneratedLang.raceName(race.raceId);
        this.description = !race.description.isBlank() ? race.description : GeneratedLang.raceDesc(race.raceId);
        this.racialName = !race.racialName.isBlank() ? race.racialName : GeneratedLang.racialName(race.raceId, race.racialSkill);
        this.racialDesc = !race.racialDesc.isBlank() ? race.racialDesc : GeneratedLang.racialDesc(race.raceId, race.racialSkill);
        loadFormCostRows();
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        costKeyBoxes.clear();
        costCsvBoxes.clear();
        int belowTabs = buildTabHeader(tr("gui.dmz_ragnarok.npc.race_edit.subtitle", race.raceId), trAll(SECTIONS), section, this::selectSection);
        // The shorter shared canvas cannot show every tab's rows at once (Classes/Costs grow with the race), so
        // the section body scrolls inside a content band; the tab header above and Save/Back below stay pinned.
        int contentTop = belowTabs;
        int contentBottom = footerY() - 4;
        beginScrollBand(contentTop, contentBottom);
        rowY = contentTop - scroll;
        buildSection();
        finishScrollBand(contentTop, contentBottom, rowY);

        // Save commits every field first; if a numeric field rejected its text, stay on the screen so the
        // admin sees the banner instead of silently persisting the old value under a fresh-looking entry.
        btn(UI_W / 2 - 84, footerY(), 80, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.race_edit.save_race"), () -> {
            applyFields();
            if (hasFieldWarning()) {
                rebuildWidgets();
                return;
            }
            save();
        });
        btn(UI_W / 2 + 4, footerY(), 80, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.back"), () -> { applyFields(); back(); });
    }

    private void selectSection(int i) {
        applyFields();
        section = i;
        // Back to the top of the new tab: a short tab would otherwise open scrolled onto blank space.
        scroll = 0;
        rebuildWidgets();
    }

    @Override
    protected void onExtraDropdown(DmzDropdown dropdown, int row) {
        if (dropdown == racialDropdown) {
            String v = valueAt(racialValues, row);
            if (NEW_RACIAL.equals(v)) {
                newRacial();
            } else {
                selectRacial(v);
            }
        } else if (dropdown == addClassDropdown) {
            String v = valueAt(addClassValues, row);
            if (NEW_CLASS.equals(v)) {
                newClass();
            } else if (v != null && !v.isBlank()) {
                race.classes.putIfAbsent(v, new RaceData.ClassStats());
                rebuildWidgets();
            }
        }
    }

    private static String valueAt(List<String> values, int i) {
        return (values != null && i >= 0 && i < values.size()) ? values.get(i) : "";
    }

    private void buildSection() {
        switch (section) {
            case 0 -> {
                tf( tr("gui.dmz_ragnarok.npc.race_edit.race_id"), race.raceId, v -> race.raceId = net.shurui.dev.sdu.util.SduIds.sanitize(v));
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_internal_id_and_conf"));
                buildRacialRow();
                df( tr("gui.dmz_ragnarok.npc.race_edit.custom_model"), DmzAssets.customModels(), race.customModel, v -> race.customModel = v);
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_model_this_race_uses"));
                df( tr("gui.dmz_ragnarok.npc.race_edit.aura_type"), DmzAssets.auraTypes(), race.auraType, v -> race.auraType = v);
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_default_aura_shown_f"));
                tf( tr("gui.dmz_ragnarok.npc.race_edit.aura_width"), dbl(race.auraWidth),
                        v -> race.auraWidth = auraSizeField(tr("gui.dmz_ragnarok.npc.race_edit.aura_width"), v, race.auraWidth));
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_aura_width"));
                tf( tr("gui.dmz_ragnarok.npc.race_edit.aura_height"), dbl(race.auraHeight),
                        v -> race.auraHeight = auraSizeField(tr("gui.dmz_ragnarok.npc.race_edit.aura_height"), v, race.auraHeight));
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_aura_height"));
                tf( tr("gui.dmz_ragnarok.npc.race_edit.head_bones"), String.join(",", race.headBones), v -> replaceList(race.headBones, splitCsv(v)));
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_comma_separated_mode"));
                bf( tr("gui.dmz_ragnarok.npc.race_edit.has_gender"), race.hasGender, () -> race.hasGender = !race.hasGender);
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_whether_characters_o"));
                bf( tr("gui.dmz_ragnarok.npc.race_edit.use_vanilla_skin"), race.useVanillaSkin, () -> race.useVanillaSkin = !race.useVanillaSkin);
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_use_the_player_s_min"));
                bf( tr("gui.dmz_ragnarok.npc.race_edit.is_layered"), race.isLayered, () -> race.isLayered = !race.isLayered);
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_render_body_layers_f"));
                bf( tr("gui.dmz_ragnarok.npc.race_edit.has_saiyan_tail"), race.hasSaiyanTail, () -> {
                    race.hasSaiyanTail = !race.hasSaiyanTail;
                    // DMZ shows a tail for a model-less custom race regardless of this flag; steer the base
                    // model so the toggle works (tail off + no model -> tailless human base; tail on -> clear it).
                    if (!race.hasSaiyanTail) {
                        if (race.customModel == null || race.customModel.isBlank()) {
                            race.customModel = "human";
                        }
                    } else if ("human".equals(race.customModel)) {
                        race.customModel = "";
                    }
                });
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_give_this_race_a_sai"));
            }
            case 1 -> {
                tf( tr("gui.dmz_ragnarok.npc.race_edit.display_name"), displayName, v -> displayName = v);
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_the_race_s_shown_nam"));
                tf( tr("gui.dmz_ragnarok.npc.race_edit.description"), description, v -> description = v);
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_the_race_description"));
                label( tr("gui.dmz_ragnarok.npc.race_edit.racial_ability_name_desc"), 14, rowY + 2);
                rowY += 10;
                label( tr("gui.dmz_ragnarok.npc.race_edit.the_identity_section_s_r"), 14, rowY + 2);
                rowY += 12;
            }
            case 2 -> {
                tf( tr("gui.dmz_ragnarok.npc.race_edit.body_type"), intStr(race.defaultBodyType),
                        v -> race.defaultBodyType = parseIntField(tr("gui.dmz_ragnarok.npc.race_edit.body_type"), v, race.defaultBodyType));
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_default_body_shape_i"));
                tf( tr("gui.dmz_ragnarok.npc.race_edit.hair_type"), intStr(race.defaultHairType),
                        v -> race.defaultHairType = parseIntField(tr("gui.dmz_ragnarok.npc.race_edit.hair_type"), v, race.defaultHairType));
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_default_hair_style_i"));
                faceField(tr("gui.dmz_ragnarok.npc.race_edit.eyes_type"), "eye", race.defaultEyesType, v -> race.defaultEyesType = v);
                tip(tr("gui.dmz_ragnarok.npc.race_edit.tip_eyes", race.raceId, race.raceId));
                faceField(tr("gui.dmz_ragnarok.npc.race_edit.nose_type"), "nose", race.defaultNoseType, v -> race.defaultNoseType = v);
                tip(tr("gui.dmz_ragnarok.npc.race_edit.tip_nose", race.raceId));
                faceField(tr("gui.dmz_ragnarok.npc.race_edit.mouth_type"), "mouth", race.defaultMouthType, v -> race.defaultMouthType = v);
                tip(tr("gui.dmz_ragnarok.npc.race_edit.tip_mouth", race.raceId));
                tf( tr("gui.dmz_ragnarok.npc.race_edit.tattoo_type"), intStr(race.defaultTattooType),
                        v -> race.defaultTattooType = parseIntField(tr("gui.dmz_ragnarok.npc.race_edit.tattoo_type"), v, race.defaultTattooType));
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_default_tattoo_marki"));
                tf( tr("gui.dmz_ragnarok.npc.race_edit.model_scale_x_y_z"), scaleStr(), this::setScale);
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_default_model_scale"));
            }
            case 3 -> {
                cf( tr("gui.dmz_ragnarok.npc.race_edit.body_color_1"), () -> race.defaultBodyColor, v -> race.defaultBodyColor = v);
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_default_primary_skin"));
                cf( tr("gui.dmz_ragnarok.npc.race_edit.body_color_2"), () -> race.defaultBodyColor2, v -> race.defaultBodyColor2 = v);
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_default_secondary_bo"));
                cf( tr("gui.dmz_ragnarok.npc.race_edit.body_color_3"), () -> race.defaultBodyColor3, v -> race.defaultBodyColor3 = v);
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_default_tertiary_bod"));
                cf( tr("gui.dmz_ragnarok.npc.race_edit.hair_color"), () -> race.defaultHairColor, v -> race.defaultHairColor = v);
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_default_hair_colour"));
                cf( tr("gui.dmz_ragnarok.npc.race_edit.eye_1_color"), () -> race.defaultEye1Color, v -> race.defaultEye1Color = v);
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_default_colour_of_th"));
                cf( tr("gui.dmz_ragnarok.npc.race_edit.eye_2_color"), () -> race.defaultEye2Color, v -> race.defaultEye2Color = v);
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_default_colour_of_th_2"));
                cf( tr("gui.dmz_ragnarok.npc.race_edit.aura_color"), () -> race.defaultAuraColor, v -> race.defaultAuraColor = v);
                tip( tr("gui.dmz_ragnarok.npc.race_edit.t_default_aura_colour"));
            }
            case 4 -> buildClasses();
            case 5 -> buildFormCosts();
            default -> { }
        }
    }

    /** Seed the editable cost rows from the race's {@code formSkillsCosts} map. */
    private void loadFormCostRows() {
        formCostRows.clear();
        for (String key : race.formSkillsCosts.keySet()) {
            JsonElement el = race.formSkillsCosts.get(key);
            StringBuilder csv = new StringBuilder();
            if (el != null && el.isJsonArray()) {
                JsonArray a = el.getAsJsonArray();
                for (int i = 0; i < a.size(); i++) {
                    if (i > 0) {
                        csv.append(',');
                    }
                    csv.append(a.get(i).getAsInt());
                }
            }
            formCostRows.add(new String[]{key, csv.toString()});
        }
    }

    /**
     * The "Costs" tab (mirrors DMZ's per-race {@code formSkillsCosts}): one row per form skill with its
     * name and a comma-separated list of per-level TP costs (list length = that skill's max level), plus
     * a delete button and an "Add Group" button. Default forms are gated exactly this way.
     */
    private void buildFormCosts() {
        int topY = rowY;
        label( tr("gui.dmz_ragnarok.npc.race_edit.form_skill"), 14, rowY);
        label( tr("gui.dmz_ragnarok.npc.race_edit.per_level_tp_costs_comma"), 96, rowY);
        tooltip(14, rowY, 268, 10, tr("gui.dmz_ragnarok.npc.race_edit.t_one_row_per_form_ski"));
        rowY += 12;
        for (int i = 0; i < formCostRows.size(); i++) {
            final int idx = i;
            String[] r = formCostRows.get(i);
            EditBox key = field(14, rowY, 78, r[0]);
            EditBox csv = field(96, rowY, 156, r[1]);
            costKeyBoxes.add(key);
            costCsvBoxes.add(csv);
            iconBtnAt(256, rowY - 1, 12, Component.translatable("gui.dmz_ragnarok.npc.btn.x"), () -> {
                syncFormCosts();
                if (idx < formCostRows.size()) {
                    formCostRows.remove(idx);
                }
                rebuildWidgets();
            });
            rowY += 14;
        }
        rowY += 2;
        commitBtn(80, rowY, 132, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.race_edit.add_group"), () -> {
            syncFormCosts();
            formCostRows.add(new String[]{"", ""});
            rebuildWidgets();
        });
        rowY += 20;
        if (formCostRows.isEmpty()) {
            label( tr("gui.dmz_ragnarok.npc.race_edit.no_form_skills_yet_add_g"), 14, topY + 14);
        }
    }

    /** Read the cost boxes back into {@link #formCostRows} and rebuild the race's {@code formSkillsCosts}. */
    private void syncFormCosts() {
        if (costKeyBoxes.isEmpty()) {
            return; // not currently on the Costs tab
        }
        for (int i = 0; i < costKeyBoxes.size() && i < formCostRows.size(); i++) {
            formCostRows.get(i)[0] = costKeyBoxes.get(i).getValue().trim();
            formCostRows.get(i)[1] = costCsvBoxes.get(i).getValue().trim();
        }
        JsonObject rebuilt = new JsonObject();
        for (String[] r : formCostRows) {
            String key = net.shurui.dev.sdu.util.SduIds.sanitize(r[0]);
            if (key.isEmpty()) {
                continue;
            }
            JsonArray arr = new JsonArray();
            for (String part : r[1].split(",")) {
                String t = part.trim();
                if (t.isEmpty()) {
                    continue;
                }
                try {
                    arr.add(Integer.parseInt(t));
                } catch (NumberFormatException ignored) {
                }
            }
            rebuilt.add(key, arr);
        }
        race.formSkillsCosts = rebuilt;
    }

    @Override
    protected void applyFields() {
        super.applyFields();
        syncFormCosts();
    }

    private void buildClasses() {
        label( tr("gui.dmz_ragnarok.npc.race_edit.classes"), 14, rowY);
        rowY += 12;
        List<String> names = new ArrayList<>(race.classes.keySet());
        for (String name : names) {
            label("§7- §r" + name, 16, rowY + 4);
            btn(150, rowY, 60, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.edit"),
                    () -> minecraft.setScreen(new RaceClassStatsScreen(this, race, name)));
            // Unified circular X delete (was a "Remove" word), placed just right of Edit.
            iconBtnAt(214, rowY, 16, Component.translatable("gui.dmz_ragnarok.npc.btn.x"),
                    () -> { race.classes.remove(name); rebuildWidgets(); });
            rowY += 18;
        }
        label( tr("gui.dmz_ragnarok.npc.race_edit.add_class"), 14, rowY + 5);
        addClassValues = new ArrayList<>();
        List<Component> opts = new ArrayList<>();
        addClassValues.add(NEW_CLASS);
        opts.add(Component.literal(NEW_CLASS));
        addClassValues.add("");
        opts.add(Component.translatable("gui.dmz_ragnarok.npc.race_edit.add_existing"));
        for (String c : DmzAssets.raceClasses()) {
            if (!race.classes.containsKey(c)) {
                addClassValues.add(c);
                opts.add(Component.literal(c));
            }
        }
        addClassDropdown = dropdown(150, rowY, 132, opts, 1).searchable();
        tip( tr("gui.dmz_ragnarok.npc.race_edit.t_make_a_brand_new_cla"));
        rowY += ROW_H;
    }

    /**
     * Human-readable label for a racial-ability id: its resolved display name (DMZ's built-in name,
     * or a custom one from our lang overlay) if the {@code skill.dragonminez.racial_<id>} key resolves,
     * otherwise a tidied version of the id - never the raw key/id, which read as "placeholder text".
     */
    private static String racialLabel(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String key = "skill.dragonminez.racial_" + id;
        return net.minecraft.client.resources.language.I18n.exists(key)
                ? net.minecraft.client.resources.language.I18n.get(key)
                : GeneratedLang.prettify(id);
    }

    /** Identity row: pick an existing racial ability, edit it, or create a new one. */
    private void buildRacialRow() {
        label( tr("gui.dmz_ragnarok.npc.race_edit.racial_ability"), 14, rowY + 5);
        racialValues = new ArrayList<>();
        List<Component> opts = new ArrayList<>();
        racialValues.add("");
        opts.add(Component.translatable("gui.dmz_ragnarok.npc.race_edit.none"));
        for (String a : DmzRacials.abilityIds()) {
            racialValues.add(a);
            opts.add(Component.literal(racialLabel(a)));
        }
        String cur = race.racialSkill == null ? "" : race.racialSkill;
        int idx = racialValues.indexOf(cur);
        if (idx < 0) {   // off-list current value: keep it selectable
            racialValues.add(cur);
            opts.add(Component.literal(racialLabel(cur) + " §7(current)"));
            idx = racialValues.size() - 1;
        }
        racialValues.add(NEW_RACIAL);
        opts.add(Component.literal(NEW_RACIAL));
        racialDropdown = dropdown(150, rowY, 100, opts, Math.max(0, idx)).searchable();
        btn(252, rowY, 32, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.edit"), () -> { applyFields(); editRacial(); });
        tip( tr("gui.dmz_ragnarok.npc.race_edit.t_the_racial_passive_t"));
        rowY += ROW_H;
    }

    private void selectRacial(String v) {
        race.racialSkill = v;
        // Reflect the chosen ability's stored display name/description.
        racialName = GeneratedLang.racialName(race.raceId, v);
        racialDesc = GeneratedLang.racialDesc(race.raceId, v);
    }

    private void newRacial() {
        race.racialSkill = uniqueRacialId("custom_racial");
        racialName = "";
        racialDesc = "";
        minecraft.setScreen(new RacialAbilityScreen(this, race));
    }

    private void editRacial() {
        if (race.racialSkill == null || race.racialSkill.isBlank()) {
            newRacial();
        } else {
            minecraft.setScreen(new RacialAbilityScreen(this, race));
        }
    }

    private static String uniqueRacialId(String base) {
        List<String> existing = DmzRacials.abilityIds();
        String id = base;
        int n = 2;
        while (existing.contains(id)) {
            id = base + "_" + n++;
        }
        return id;
    }

    private void newClass() {
        String name = uniqueClassId("new_class");
        race.classes.put(name, new RaceData.ClassStats());
        minecraft.setScreen(new RaceClassStatsScreen(this, race, name));
    }

    private String uniqueClassId(String base) {
        String id = base;
        int n = 2;
        while (race.classes.containsKey(id)) {
            id = base + "_" + n++;
        }
        return id;
    }

    private void save() {
        // Store the names on the race so they persist in character.json and the server can rebuild the
        // lang from the config alone (not just from the client lang packet).
        race.displayName = displayName == null ? "" : displayName;
        race.description = description == null ? "" : description;
        race.racialName = racialName == null ? "" : racialName;
        race.racialDesc = racialDesc == null ? "" : racialDesc;
        GeneratedLang.putRace(race.raceId, race.racialSkill, displayName, description, racialName, racialDesc);
        DmzNet.sendLargeToServer("race", GSON.toJson(race.toBundle()));
        back();
    }

    /**
     * Eyes/nose/mouth index picker: a dropdown of the indices the resource pack actually ships for this
     * race (scanned from its face textures), or a plain number field when the race has none yet.
     */
    private void faceField(String label, String feature, int current, java.util.function.IntConsumer setter) {
        String folder = DmzAssets.appearanceFolder(race.raceId, race.customModel);
        List<Integer> opts = DmzAssets.faceOptions(folder, feature);
        if (opts.isEmpty()) {
            tf(label, intStr(current), v -> setter.accept(parseIntField(label, v, current)));
        } else {
            List<String> strs = new ArrayList<>();
            for (int i : opts) {
                strs.add(String.valueOf(i));
            }
            df(label, strs, String.valueOf(current), v -> setter.accept(parseI(v, current)));
        }
    }

    private static List<String> splitCsv(String v) {
        List<String> out = new ArrayList<>();
        for (String part : v.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private String scaleStr() {
        float[] s = race.defaultModelScaling;
        return dbl(s[0]) + ", " + dbl(s[1]) + ", " + dbl(s[2]);
    }

    private void setScale(String v) {
        String[] parts = v.split(",");
        float[] s = race.defaultModelScaling.length >= 3 ? race.defaultModelScaling : new float[]{1, 1, 1};
        for (int i = 0; i < 3 && i < parts.length; i++) {
            String t = parts[i].trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                s[i] = Float.parseFloat(t);
            } catch (NumberFormatException e) {
                // Surface the bad axis instead of silently keeping the old scale for it.
                warnField(tr("gui.dmz_ragnarok.npc.race_edit.model_scale_x_y_z"), t, tr("gui.dmz_ragnarok.npc.field.needs_number"));
            }
        }
        race.defaultModelScaling = s;
    }

    /**
     * Parse an aura-size multiplier and floor it at {@value #AURA_MIN}. There is deliberately no ceiling: 5.0 was a
     * number somebody picked, the model scale beside it was never bounded, and an editor that quietly saves a
     * different number than the one typed is worse than an absurd billboard. A non-number arms the warning banner
     * (like the other numeric fields); a value that had to be floored is also reported, so the bound is not invisible.
     */
    private float auraSizeField(String label, String raw, float fallback) {
        double parsed = parseDoubleField(label, raw, fallback);
        // the floor stays: the aura is a multiplier, so 0 or less is a missing billboard rather than a small one
        double clamped = Math.max(AURA_MIN, parsed);
        if (clamped != parsed) {
            warnClamped(label, dbl(parsed), dbl(clamped));
        }
        return (float) clamped;
    }

    private static final double AURA_MIN = 0.1;
}
