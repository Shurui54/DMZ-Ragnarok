package net.shurui.dev.sdu.client.gui.form;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.DmzAssets;
import net.shurui.dev.sdu.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.preview.FormPreview;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.form.FormAuraData;
import net.shurui.dev.sdu.form.FormData;

/**
 * Edits one {@link FormData}. Because a form has ~50 fields, they're split into sections chosen from a
 * dropdown; only the current section's controls are shown. Uses {@link FieldEditScreen}'s text/toggle/
 * dropdown/colour-picker rows, all read back into the model on section switch and on Back.
 */
public class FormEditScreen extends FieldEditScreen {

    /** Width of the content column (tabs + field rows). The canvas is wider to hold the preview column. */
    private static final int UI_W = GuiTheme.SCREEN_W;
    /** Right-hand column that shows the live character preview (large, fills the right of the panel). */
    private static final int PREVIEW_W = 288;
    private static final int TOTAL_W = UI_W + PREVIEW_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final String[] SECTIONS = {
            "gui.dmz_ragnarok.npc.form_edit.tab.identity", "gui.dmz_ragnarok.npc.form_edit.tab.colors", "gui.dmz_ragnarok.npc.form_edit.tab.aura",
            "gui.dmz_ragnarok.npc.form_edit.tab.stats", "gui.dmz_ragnarok.npc.form_edit.tab.drains", "gui.dmz_ragnarok.npc.form_edit.tab.mastery",
            "gui.dmz_ragnarok.npc.form_edit.tab.shader", "gui.dmz_ragnarok.npc.form_edit.tab.dodge", "gui.dmz_ragnarok.npc.form_edit.tab.buffs"};

    private final String key;
    private final FormData form;
    /** Owning race + group of this form, so the preview can render the DMZ-resolved model/aura. */
    private final String race;
    private final String group;
    /** All races this form's group is written to; drives per-race colour editing for multi-race forms. */
    private final java.util.List<String> ownerRaces;
    private int section;
    // Vertical scroll for section content (some tabs, e.g. Aura with stacked layers, exceed the panel) uses
    // the inherited SagaBaseScreen scroll band.

    /** Per-form chosen preview race for race-agnostic (stack) forms; persists across navigation this session. */
    private static final java.util.Map<String, String> PREVIEW_RACE = new java.util.HashMap<>();
    private final FormPreview preview = new FormPreview();
    private boolean auraOn = true;
    // Face the camera by default: the DMZ model's front sits at a 180° yaw from the inventory's default.
    private float previewYaw = (float) Math.PI;
    private float previewPitch;
    private boolean hairAutoApplied;
    private boolean draggingPreview;
    // Preview column geometry (virtual coords), captured in init() so render()/input can use it.
    private int previewTop;
    private int previewBottom;

    public FormEditScreen(Screen parent, String key, FormData form, String race, String group, java.util.List<String> ownerRaces) {
        super(Component.translatable("gui.dmz_ragnarok.npc.form_edit.edit_form"), TOTAL_W, UI_H, parent);
        this.key = key;
        this.form = form;
        this.race = race == null ? "" : race;
        this.group = group == null ? "" : group;
        this.ownerRaces = ownerRaces == null ? java.util.List.of() : ownerRaces;
    }

    @Override
    protected int tabBarWidth() {
        return UI_W; // keep the tab bar over the content column, not the preview column
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        int belowTabs = buildTabHeader(tr("gui.dmz_ragnarok.npc.form_edit.subtitle", key), trAll(SECTIONS), section, this::selectSection);
        int contentTop = belowTabs;
        int contentBottom = UI_H - 26;
        // The preview column has no tabs above it, so its box can start near the panel top (below the
        // subtitle), giving the model + aura more vertical room so the top isn't clipped.
        previewTop = 26;
        // Box height shrunk ~20%; the control buttons sit just below it (see below).
        previewBottom = previewTop + Math.round((UI_H - 62 - previewTop) * 0.8F);

        // Preview column frame (added before the scroll band so it's never clipped/hidden by scrolling).
        int px = UI_W + 4, pw = PREVIEW_W - 8, ph = previewBottom - previewTop;
        rect(px, previewTop, pw, ph, 0xFF2A2A30);
        rect(px + 1, previewTop + 1, pw - 2, ph - 2, 0xFF121216);
        labelCentered(tr("gui.dmz_ragnarok.npc.form_edit.preview"), UI_W + PREVIEW_W / 2, previewTop + 3, 0xFFF6E27A);

        beginScrollBand(contentTop, contentBottom);
        rowY = contentTop - scroll;
        buildSection();
        // Total content height determines how far we can scroll; clamp, hide off-band widgets, draw thumb.
        finishScrollBand(contentTop, contentBottom, rowY);

        // Added after the clamp (like Back) so the preview controls are never hidden by the content band.
        if (!preview.isUnavailable()) {
            // Race selector: multi-race forms use it to author per-race colours; stack forms use it to
            // choose which race to preview. Single-race forms don't show it.
            if (!selectorRaces().isEmpty()) {
                String cur = editRace();
                btn(UI_W + 14, previewBottom + 4, PREVIEW_W - 28, GuiTheme.BUTTON_HEIGHT,
                        Component.literal(tr("gui.dmz_ragnarok.npc.form_edit.preview_race", cur.isBlank() ? "-" : cur)),
                        this::cyclePreviewRace);
            }
            btn(UI_W + 14, previewBottom + 22, PREVIEW_W - 28, GuiTheme.BUTTON_HEIGHT,
                    Component.translatable("gui.dmz_ragnarok.npc.form_edit.apply_hair"),
                    () -> { applyFields(); preview.applyHairCode(form.forcedHairCode); });
            btn(UI_W + 14, previewBottom + 40, PREVIEW_W - 28, GuiTheme.BUTTON_HEIGHT,
                    Component.translatable(auraOn ? "gui.dmz_ragnarok.npc.form_edit.aura_on" : "gui.dmz_ragnarok.npc.form_edit.aura_off"),
                    () -> { applyFields(); auraOn = !auraOn; rebuildWidgets(); });
        }

        btn(UI_W / 2 - 55, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.back"), () -> { applyFields(); back(); });
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        // Flush the panel + hover tooltip to the MAIN target now: the preview below binds an offscreen target
        // and calls g.flush(), which would otherwise flush the pending tooltip into that offscreen mask (losing it).
        g.flush();
        if (preview.isUnavailable()) {
            return;
        }
        // Keep the dummy in sync with the (possibly just-edited) form every frame so colours/aura are live.
        // resolvedFor folds the selected race's colour overrides into the previewed form.
        String er = editRace();
        preview.configure(form.resolvedFor(er), er, group, auraOn);
        // Auto-apply the forced hair code once on open, so the preview matches without pressing "Apply Hair Code".
        if (!hairAutoApplied) {
            hairAutoApplied = true;
            if (form.forcedHairCode != null && !form.forcedHairCode.isBlank()) {
                preview.applyHairCode(form.forcedHairCode);
            }
        }

        float infl = 1.0f;
        if (form.modelScaling != null && form.modelScaling.length >= 3) {
            infl = Math.max(form.modelScaling[0], Math.max(form.modelScaling[1], form.modelScaling[2]));
        }
        infl = Math.max(0.1f, infl);
        int boxH = previewBottom - previewTop;
        int cxV = UI_W + PREVIEW_W / 2;
        int modelHV = (int) (boxH * 0.68);                    // model height within the box (~20% smaller)
        int feetV = previewTop + boxH / 2 + modelHV / 2;      // centred in the box
        int scaleV = Math.max(4, (int) (modelHV / 1.9F / infl));
        int cxS = (int) Math.round(originX() + cxV * guiScale);
        int feetS = (int) Math.round(originY() + feetV * guiScale);
        int scaleS = Math.max(1, (int) Math.round(scaleV * guiScale));
        int modelHScreen = (int) Math.round(modelHV * guiScale);
        int centerS = feetS - modelHScreen / 2;

        // Clip to the preview box.
        int clipX0 = (int) Math.round(originX() + (UI_W + 5) * guiScale);
        int clipY0 = (int) Math.round(originY() + (previewTop + 1) * guiScale);
        int clipX1 = (int) Math.round(originX() + (TOTAL_W - 5) * guiScale);
        int clipY1 = (int) Math.round(originY() + (previewBottom - 1) * guiScale);
        g.enableScissor(clipX0, clipY0, clipX1, clipY1);
        // Aura BEHIND the model (only when toggled on); the opaque model draws on top, so it reads as a glow.
        if (auraOn) {
            preview.renderAura(g, form, cxS, centerS, modelHScreen);
        }
        // Outline shader: real edge-detected silhouette outline, animated primary<->secondary + noise.
        if (form.shaderEnabled()) {
            float[] primary = shaderRgb(form.shaderColor(true));
            float[] secondary = shaderRgb(form.shaderColor(false));
            float thick = (float) (form.shaderValue("outlineThickness", 1.0) * minecraft.getWindow().getGuiScale());
            float noiseScale = (float) form.shaderValue("noiseScale", 1.0);
            float mixSpeed = (float) form.shaderValue("colorMixSpeed", 1.0);
            preview.renderOutline(g, cxS, feetS, scaleS, previewYaw, previewPitch, thick, primary, secondary, noiseScale, mixSpeed);
        }
        preview.renderModel(g, cxS, feetS, scaleS, previewYaw, previewPitch);
        g.disableScissor();
    }

    /** Distinct non-blank owner races of this form's group. */
    private java.util.List<String> ownerRacesNonBlank() {
        return ownerRaces.stream().filter(r -> r != null && !r.isBlank()).distinct().toList();
    }

    /** True when this form is written to more than one race, so colours may be authored per race. */
    private boolean isMultiRace() {
        return ownerRacesNonBlank().size() > 1;
    }

    /**
     * The races the selector cycles: the owning races for a multi-race form (drives per-race colours),
     * or every race for a race-agnostic stack form (preview only). Empty for a single-race form.
     */
    private java.util.List<String> selectorRaces() {
        if (isMultiRace()) {
            return ownerRacesNonBlank();
        }
        if (race.isBlank()) {
            return net.shurui.dev.sdu.client.DmzRaces.raceIds();
        }
        return java.util.List.of();
    }

    /** The currently selected race (for preview + per-race colour editing). */
    private String editRace() {
        java.util.List<String> rs = selectorRaces();
        if (rs.isEmpty()) {
            return race;
        }
        String cur = PREVIEW_RACE.get(form.name);
        if (cur != null && rs.contains(cur)) {
            return cur;
        }
        // Default to the player's own race when it's an option, else the first race.
        String playerRace = FormPreview.currentPlayerRace();
        return rs.contains(playerRace) ? playerRace : rs.get(0);
    }

    /** Cycle the selected race and remember it for this form. */
    private void cyclePreviewRace() {
        applyFields();
        java.util.List<String> rs = selectorRaces();
        if (rs.isEmpty()) {
            return;
        }
        int i = rs.indexOf(editRace());
        PREVIEW_RACE.put(form.name, rs.get((i + 1 + rs.size()) % rs.size()));
        rebuildWidgets();
    }

    // colour field binding: per-race only for a form assigned to 2+ specific races (a DMZ per-race form).
    // Stack/race-agnostic forms are a single shared file in DMZ (no per-race colour possible), so they edit
    // the shared base (the race selector there is preview-only).
    private boolean perRaceColors() {
        return isMultiRace();
    }

    private String colorFieldGet(String field) {
        return perRaceColors() ? form.colorGet(editRace(), field) : form.baseColor(field);
    }

    private void colorFieldSet(String field, String v) {
        if (perRaceColors()) {
            form.colorSet(editRace(), field, v);
        } else {
            form.setBaseColor(field, v);
        }
    }

    /** "#RRGGBB" -> {r,g,b} in 0..1 for the outline shader; cyan-ish default when blank/invalid. */
    private static float[] shaderRgb(String hex) {
        int argb = hexToArgb(hex);
        if (argb == 0) {
            return new float[]{0.5F, 1.0F, 1.0F};
        }
        return new float[]{((argb >> 16) & 0xFF) / 255F, ((argb >> 8) & 0xFF) / 255F, (argb & 0xFF) / 255F};
    }

    /** Was the press inside the preview box? (virtual coords) */
    private boolean inPreview(double vx, double vy) {
        return vx >= UI_W + 4 && vx <= TOTAL_W - 4 && vy >= previewTop && vy <= previewBottom;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // Let widgets (preview-column buttons, dropdowns, colour rows) handle the click FIRST, so drag-to-rotate
        // doesn't swallow clicks landing on a button inside the preview column.
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (button == 0 && !preview.isUnavailable() && openDropdown == null
                && inPreview(toVirtualX(mx), toVirtualY(my))) {
            draggingPreview = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (draggingPreview) {
            // dragX/dragY here are raw screen-pixel deltas; ~0.9 deg per pixel feels like the inventory drag.
            previewYaw += (float) (dragX * 0.016);
            previewPitch += (float) (dragY * 0.016);
            previewPitch = Math.max(-1.2F, Math.min(1.2F, previewPitch));
            return true;
        }
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (draggingPreview && button == 0) {
            draggingPreview = false;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    /** Keep the content-band scrollbar over the content column, not the wide preview column. */
    @Override
    protected int bandRight() {
        return UI_W;
    }

    private void selectSection(int i) {
        applyFields();
        section = i;
        scroll = 0;
        rebuildWidgets();
    }

    /** Parse an alignment editor field: blank clears it (ALIGN_UNSET), else a value clamped to DMZ's 0..100. */
    private static int alignField(String v, int current) {
        if (v == null || v.trim().isEmpty()) {
            return net.shurui.dev.sdu.form.FormData.ALIGN_UNSET;
        }
        int fallback = current == net.shurui.dev.sdu.form.FormData.ALIGN_UNSET ? 0 : current;
        return Math.max(net.shurui.dev.sdu.form.FormAlignmentGateConfig.MIN_ALIGNMENT,
                Math.min(net.shurui.dev.sdu.form.FormAlignmentGateConfig.MAX_ALIGNMENT, parseI(v, fallback)));
    }

    private void buildSection() {
        switch (section) {
            case 0 -> {
                tf( tr("gui.dmz_ragnarok.npc.form_edit.name"), form.name, v -> form.name = v.trim());
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_internal_form_name_i"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.display_name"), form.displayName, v -> form.displayName = v);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_the_name_shown_in_ga"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.description"), form.description, v -> form.description = v);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_shown_under_the_form"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.unlock_skill_lv"), intStr(form.unlockOnSkillLevel), v -> form.unlockOnSkillLevel = parseI(v, form.unlockOnSkillLevel));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_level_of_this_race_s"));
                // Blank field = UNSET (editor never resolved / user cleared it) -> the save path leaves
                // DMZ's existing cost untouched. A number (including -1 for Priceless) is written as-is.
                tf( tr("gui.dmz_ragnarok.npc.form_edit.unlock_cost_tp"),
                        form.unlockCost == net.shurui.dev.sdu.form.FormData.UNSET_COST ? "" : intStr(form.unlockCost),
                        v -> form.unlockCost = v.trim().isEmpty()
                                ? net.shurui.dev.sdu.form.FormData.UNSET_COST
                                : parseI(v, form.unlockCost == net.shurui.dev.sdu.form.FormData.UNSET_COST ? -1 : form.unlockCost));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_tp_cost_to_unlock_th"));
                // Minimum character level to TRANSFORM INTO this form (0 / blank = no minimum). A separate axis
                // from the unlock skill level (buying) above: this gates USING the form and is enforced server-side.
                tf( tr("gui.dmz_ragnarok.npc.form_edit.min_level_to_use"),
                        form.minLevel <= 0 ? "" : intStr(form.minLevel),
                        v -> form.minLevel = v.trim().isEmpty() ? 0 : Math.max(0, parseI(v, form.minLevel)));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_min_level_to_use"));
                // DMZ ALIGNMENT windows (0-100). Blank = no bound on that side. unlock* gate BUYING the form;
                // use* gate transforming into / staying in it. Enforced server-side, a separate axis from level.
                tf( tr("gui.dmz_ragnarok.npc.form_edit.align_unlock_min"),
                        form.alignUnlockMin == net.shurui.dev.sdu.form.FormData.ALIGN_UNSET ? "" : intStr(form.alignUnlockMin),
                        v -> form.alignUnlockMin = alignField(v, form.alignUnlockMin));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_align_unlock"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.align_unlock_max"),
                        form.alignUnlockMax == net.shurui.dev.sdu.form.FormData.ALIGN_UNSET ? "" : intStr(form.alignUnlockMax),
                        v -> form.alignUnlockMax = alignField(v, form.alignUnlockMax));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_align_unlock"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.align_use_min"),
                        form.alignUseMin == net.shurui.dev.sdu.form.FormData.ALIGN_UNSET ? "" : intStr(form.alignUseMin),
                        v -> form.alignUseMin = alignField(v, form.alignUseMin));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_align_use"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.align_use_max"),
                        form.alignUseMax == net.shurui.dev.sdu.form.FormData.ALIGN_UNSET ? "" : intStr(form.alignUseMax),
                        v -> form.alignUseMax = alignField(v, form.alignUseMax));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_align_use"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.unlock_desc"), form.unlockDescription, v -> form.unlockDescription = v);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_unlock_desc"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.form_combo"), form.formCombo, v -> form.formCombo = v.trim());
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_key_combo_input_used"));
                df( tr("gui.dmz_ragnarok.npc.form_edit.custom_model"), DmzAssets.customModels(), form.customModel, v -> form.customModel = v);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_model_this_form_uses"));
                df( tr("gui.dmz_ragnarok.npc.form_edit.transform_anim"), DmzAssets.transformAnimations(), form.transformationAnimation, v -> form.transformationAnimation = v);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_animation_played_whe"));
                df( tr("gui.dmz_ragnarok.npc.form_edit.form_requisite"), DmzAssets.formKeys(), form.formRequisite, v -> form.formRequisite = v);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_another_form_that_mu"));
                bf( tr("gui.dmz_ragnarok.npc.form_edit.keep_base_head_bones"), form.keepBaseFormHeadBones, () -> form.keepBaseFormHeadBones = !form.keepBaseFormHeadBones);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_keep_the_base_model"));
            }
            case 1 -> {
                cf( tr("gui.dmz_ragnarok.npc.form_edit.body_color_1"), () -> colorFieldGet("bodyColor1"), v -> colorFieldSet("bodyColor1", v));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_primary_body_colour"));
                cf( tr("gui.dmz_ragnarok.npc.form_edit.body_color_2"), () -> colorFieldGet("bodyColor2"), v -> colorFieldSet("bodyColor2", v));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_secondary_body_colou"));
                cf( tr("gui.dmz_ragnarok.npc.form_edit.body_color_3"), () -> colorFieldGet("bodyColor3"), v -> colorFieldSet("bodyColor3", v));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_tertiary_body_colour"));
                df( tr("gui.dmz_ragnarok.npc.form_edit.extra_form_layer"), DmzAssets.formLayers(), form.extraFormLayer, v -> form.extraFormLayer = v);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_an_extra_body_overla"));
                cf( tr("gui.dmz_ragnarok.npc.form_edit.extra_form_color"), () -> form.extraFormColor, v -> form.extraFormColor = v);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_tint_colour_for_the"));
                df( tr("gui.dmz_ragnarok.npc.form_edit.hair_type"), DmzAssets.hairTypes(), form.hairType, v -> form.hairType = v);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_hair_style_used_in_t"));
                cf( tr("gui.dmz_ragnarok.npc.form_edit.hair_color"), () -> colorFieldGet("hairColor"), v -> colorFieldSet("hairColor", v));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_hair_colour_in_this"));
                // DMZ hair codes run to several thousand chars - a large cap so pasting one isn't truncated (the
                // 512 default silently cut codes down, producing an unparseable code that renders as base hair).
                tf( tr("gui.dmz_ragnarok.npc.form_edit.forced_hair_code"), form.forcedHairCode, v -> form.forcedHairCode = v.trim(), 16384);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_advanced_force_a_spe"));
                cf( tr("gui.dmz_ragnarok.npc.form_edit.tint_color"), () -> form.tintColor, v -> form.tintColor = v);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_tint_color"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.tint_intensity"), dbl(form.tintIntensity), v -> form.tintIntensity = parseD(v, form.tintIntensity));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_tint_intensity"));
            }
            case 2 -> {
                cf( tr("gui.dmz_ragnarok.npc.form_edit.eye_1_color"), () -> colorFieldGet("eye1Color"), v -> colorFieldSet("eye1Color", v));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_colour_of_the_first"));
                cf( tr("gui.dmz_ragnarok.npc.form_edit.eye_2_color"), () -> colorFieldGet("eye2Color"), v -> colorFieldSet("eye2Color", v));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_colour_of_the_second"));
                // "Match eyes" (copy eye 1's colour into eye 2) is a round icon button with an equals sign, same
                // treatment as the row X/arrows (DmzTextureButton.asIcon), in the swatch column (x=150) under the
                // two eye swatches. Old descriptive label kept as the hover tooltip.
                iconBtnAt(150, rowY, ROW_H, Component.translatable("gui.dmz_ragnarok.npc.form_edit.match_eyes_icon"),
                        () -> { applyFields(); colorFieldSet("eye2Color", colorFieldGet("eye1Color")); rebuildWidgets(); });
                tooltip(150, rowY, iconSize(), ROW_H, tr("gui.dmz_ragnarok.npc.form_edit.match_eyes"));
                rowY += ROW_H;
                df( tr("gui.dmz_ragnarok.npc.form_edit.aura_type"), DmzAssets.auraTypes(), form.auraType, v -> form.auraType = v);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_aura_shown_while_in"));
                df( tr("gui.dmz_ragnarok.npc.form_edit.aura_layer"), DmzAssets.auraLayers(), intStr(form.auraLayer), v -> form.auraLayer = parseI(v, form.auraLayer));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_which_aura_render_la"));
                cf( tr("gui.dmz_ragnarok.npc.form_edit.aura_color"), () -> form.auraColor, v -> form.auraColor = v);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_aura_colour"));
                bf( tr("gui.dmz_ragnarok.npc.form_edit.has_lightnings"), form.hasLightnings, () -> form.hasLightnings = !form.hasLightnings);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_show_electric_lightn"));
                cf( tr("gui.dmz_ragnarok.npc.form_edit.lightning_color"), () -> form.lightningColor, v -> form.lightningColor = v);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_colour_of_the_lightn"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.model_scale_x_y_z"), scaleStr(), this::setScale);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_model_scale_in_this"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.aura_width"), dbl(form.extraAura.width),
                        v -> form.extraAura.width = clampAuraSize(parseD(v, form.extraAura.width)));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_aura_width"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.aura_height"), dbl(form.extraAura.height),
                        v -> form.extraAura.height = clampAuraSize(parseD(v, form.extraAura.height)));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_aura_height"));
                buildAuraLayers();   // extra stacked aura layers live in this same Aura tab
            }
            case 3 -> {
                tf( tr("gui.dmz_ragnarok.npc.form_edit.str_x"), dbl(form.strMultiplier), v -> form.strMultiplier = parseD(v, form.strMultiplier));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_multiplies_strength"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.skp_x"), dbl(form.skpMultiplier), v -> form.skpMultiplier = parseD(v, form.skpMultiplier));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_multiplies_skill_pow"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.stm_x"), dbl(form.stmMultiplier), v -> form.stmMultiplier = parseD(v, form.stmMultiplier));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_multiplies_stamina"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.def_x"), dbl(form.defMultiplier), v -> form.defMultiplier = parseD(v, form.defMultiplier));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_multiplies_defense"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.vit_x"), dbl(form.vitMultiplier), v -> form.vitMultiplier = parseD(v, form.vitMultiplier));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_multiplies_vitality"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.pwr_x"), dbl(form.pwrMultiplier), v -> form.pwrMultiplier = parseD(v, form.pwrMultiplier));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_multiplies_power_ki"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.ene_x"), dbl(form.eneMultiplier), v -> form.eneMultiplier = parseD(v, form.eneMultiplier));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_multiplies_energy_ma"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.speed_x"), dbl(form.speedMultiplier), v -> form.speedMultiplier = parseD(v, form.speedMultiplier));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_multiplies_movement"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.attack_speed"), dbl(form.attackSpeed), v -> form.attackSpeed = parseD(v, form.attackSpeed));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_multiplies_attack_sp"));
            }
            case 4 -> {
                tf( tr("gui.dmz_ragnarok.npc.form_edit.stamina_drain_x"), dbl(form.staminaDrainMultiplier), v -> form.staminaDrainMultiplier = parseD(v, form.staminaDrainMultiplier));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_multiplies_how_fast"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.energy_drain"), dbl(form.energyDrain), v -> form.energyDrain = parseD(v, form.energyDrain));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_ki_drained_per_secon"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.stamina_drain"), dbl(form.staminaDrain), v -> form.staminaDrain = parseD(v, form.staminaDrain));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_stamina_drained_per"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.health_drain"), dbl(form.healthDrain), v -> form.healthDrain = parseD(v, form.healthDrain));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_health_drained_per_s"));
            }
            case 5 -> {
                tf( tr("gui.dmz_ragnarok.npc.form_edit.max_mastery"), dbl(form.maxMastery), v -> form.maxMastery = parseD(v, form.maxMastery));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_mastery_cap_for_this"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.mastery_hit_dealt"), dbl(form.masteryPerHitDealt), v -> form.masteryPerHitDealt = parseD(v, form.masteryPerHitDealt));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_mastery_gained_per_h"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.mastery_hit_recv"), dbl(form.masteryPerHitReceived), v -> form.masteryPerHitReceived = parseD(v, form.masteryPerHitReceived));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_mastery_gained_per_h_2"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.passive_mastery_5s"), dbl(form.passiveMasteryEveryFiveSeconds), v -> form.passiveMasteryEveryFiveSeconds = parseD(v, form.passiveMasteryEveryFiveSeconds));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_mastery_gained_every"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.max_cost_x"), dbl(form.maxCostMultiplier), v -> form.maxCostMultiplier = parseD(v, form.maxCostMultiplier));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_drain_upkeep_multipl"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.max_stats_x"), dbl(form.maxStatsMultiplier), v -> form.maxStatsMultiplier = parseD(v, form.maxStatsMultiplier));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_extra_stat_multiplie"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.unlock_mastery"), dbl(form.unlockOnMastery), v -> form.unlockOnMastery = parseD(v, form.unlockOnMastery));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_mastery_of_the_requi"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.stack_mastery"), dbl(form.stackOnMastery), v -> form.stackOnMastery = parseD(v, form.stackOnMastery));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_mastery_at_which_thi"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.instant_tf_mastery"), dbl(form.instantTransformOnMastery), v -> form.instantTransformOnMastery = parseD(v, form.instantTransformOnMastery));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_mastery_at_which_tra"));
                // "Always available" = DMZ's allowFreeTransformOnMastery == 0 (selectable from 0 mastery).
                // Off reveals a mastery-threshold field. Default 0, so a new form shows up in-game at once.
                boolean alwaysAvailable = form.allowFreeTransformOnMastery <= 0;
                bf( tr("gui.dmz_ragnarok.npc.form_edit.always_available"), alwaysAvailable,
                        () -> form.allowFreeTransformOnMastery = alwaysAvailable ? 50.0 : 0.0);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_always_available"));
                if (!alwaysAvailable) {
                    tf( tr("gui.dmz_ragnarok.npc.form_edit.free_tf_mastery"), dbl(form.allowFreeTransformOnMastery),
                            v -> form.allowFreeTransformOnMastery = Math.max(0, parseD(v, form.allowFreeTransformOnMastery)));
                    tip( tr("gui.dmz_ragnarok.npc.form_edit.t_free_tf_mastery"));
                }
            }
            case 6 -> {
                bf( tr("gui.dmz_ragnarok.npc.form_edit.form_stackable"), form.formStackable, () -> form.formStackable = !form.formStackable);
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_whether_this_form_ca"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.stack_drain_x"), dbl(form.stackDrainMultiplier), v -> form.stackDrainMultiplier = parseD(v, form.stackDrainMultiplier));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_extra_drain_multipli"));
                dfMulti( tr("gui.dmz_ragnarok.npc.form_edit.incompatible_with"), DmzAssets.formKeys(), form.incompatibleWith, list -> replaceList(form.incompatibleWith, list));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_forms_that_cannot_be"));
                dfMulti( tr("gui.dmz_ragnarok.npc.form_edit.share_mastery_with"), DmzAssets.formKeys(), form.shareMasteryWith, list -> replaceList(form.shareMasteryWith, list));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_forms_that_share_mas"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.share_mastery_x"), dbl(form.shareMasteryMultiplier), v -> form.shareMasteryMultiplier = parseD(v, form.shareMasteryMultiplier));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_fraction_of_mastery"));
                bf( tr("gui.dmz_ragnarok.npc.form_edit.outline_shader"), form.shaderEnabled(), () -> form.setShaderEnabled(!form.shaderEnabled()));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_draw_a_glowing_outli"));
                cf( tr("gui.dmz_ragnarok.npc.form_edit.outline_primary"), () -> form.shaderColor(true), v -> form.setShaderColor(true, v));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_primary_colour_of_th"));
                cf( tr("gui.dmz_ragnarok.npc.form_edit.outline_secondary"), () -> form.shaderColor(false), v -> form.setShaderColor(false, v));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_secondary_colour_of"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.outline_thickness"), dbl(form.shaderValue("outlineThickness", 1.0)), v -> form.setShaderValue("outlineThickness", parseD(v, form.shaderValue("outlineThickness", 1.0))));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_how_thick_the_outlin"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.outline_noise_scale"), dbl(form.shaderValue("noiseScale", 1.0)), v -> form.setShaderValue("noiseScale", parseD(v, form.shaderValue("noiseScale", 1.0))));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_scale_of_the_noise_p"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.outline_mix_speed"), dbl(form.shaderValue("colorMixSpeed", 1.0)), v -> form.setShaderValue("colorMixSpeed", parseD(v, form.shaderValue("colorMixSpeed", 1.0))));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_how_fast_the_primary"));
            }
            case 7 -> {
                label( tr("gui.dmz_ragnarok.npc.form_edit.chance_to_fully_avoid_an"), 14, rowY);
                rowY += ROW_H;
                tf( tr("gui.dmz_ragnarok.npc.form_edit.physical_dodge"), dbl(form.combat.dodgePhysical), v -> form.combat.dodgePhysical = clampPct(parseD(v, form.combat.dodgePhysical)));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_chance_to_dodge_vani"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.melee_skill_dodge"), dbl(form.combat.dodgeMeleeSkill), v -> form.combat.dodgeMeleeSkill = clampPct(parseD(v, form.combat.dodgeMeleeSkill)));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_chance_to_dodge_dmz"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.energy_skill_dodge"), dbl(form.combat.dodgeEnergySkill), v -> form.combat.dodgeEnergySkill = clampPct(parseD(v, form.combat.dodgeEnergySkill)));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_chance_to_dodge_dmz_2"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.damage_mitigation"), dbl(form.combat.damageMitigation), v -> form.combat.damageMitigation = clampPct(parseD(v, form.combat.damageMitigation)));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_of_a_non_dodged_hit"));
            }
            case 8 -> {
                label( tr("gui.dmz_ragnarok.npc.form_edit.each_hit_taken_buffs_sta"), 14, rowY);
                rowY += ROW_H;
                tf( tr("gui.dmz_ragnarok.npc.form_edit.str_gain"), dbl(form.combat.gain("STR")), v -> form.combat.setGain("STR", parseD(v, form.combat.gain("STR"))));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_temp_strength_added"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.skp_gain"), dbl(form.combat.gain("SKP")), v -> form.combat.setGain("SKP", parseD(v, form.combat.gain("SKP"))));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_temp_strike_power_ad"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.res_gain"), dbl(form.combat.gain("RES")), v -> form.combat.setGain("RES", parseD(v, form.combat.gain("RES"))));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_temp_resistance_adde"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.vit_gain"), dbl(form.combat.gain("VIT")), v -> form.combat.setGain("VIT", parseD(v, form.combat.gain("VIT"))));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_temp_vitality_added"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.pwr_gain"), dbl(form.combat.gain("PWR")), v -> form.combat.setGain("PWR", parseD(v, form.combat.gain("PWR"))));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_temp_ki_power_added"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.ene_gain"), dbl(form.combat.gain("ENE")), v -> form.combat.setGain("ENE", parseD(v, form.combat.gain("ENE"))));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_temp_energy_added_pe"));
                tf( tr("gui.dmz_ragnarok.npc.form_edit.max_bonus_of_base"), dbl(form.combat.maxBonusPercent), v -> form.combat.maxBonusPercent = Math.max(0, parseD(v, form.combat.maxBonusPercent)));
                tip( tr("gui.dmz_ragnarok.npc.form_edit.t_cap_on_the_buff_as_a"));
            }
            default -> { }
        }
    }

    /**
     * Extra aura layers: DMZ allows one aura per form, so this list lets a form render several stacked auras
     * at once (each its own type, layer slot and colour). Applied on top of DMZ's aura by our client mixin.
     */
    private void buildAuraLayers() {
        label(tr("gui.dmz_ragnarok.npc.form_edit.aura_layers_hdr"), 14, rowY);
        rowY += ROW_H;
        java.util.List<FormAuraData.Layer> layers = form.extraAura.layers;
        for (int i = 0; i < layers.size(); i++) {
            final int idx = i;
            final FormAuraData.Layer l = layers.get(i);
            label("§e" + tr("gui.dmz_ragnarok.npc.form_edit.aura_layer_n", i + 1), 14, rowY + 2);
            // circular X delete for this aura layer
            iconBtnAt(240, rowY, 11, Component.translatable("gui.dmz_ragnarok.npc.btn.x"), () -> {
                applyFields();
                if (idx < layers.size()) {
                    layers.remove(idx);
                }
                rebuildWidgets();
            });
            rowY += ROW_H;
            df( tr("gui.dmz_ragnarok.npc.form_edit.aura_layer_type"), DmzAssets.auraTypes(), l.type, v -> l.type = v);
            tip( tr("gui.dmz_ragnarok.npc.form_edit.t_aura_layer_type"));
            df( tr("gui.dmz_ragnarok.npc.form_edit.aura_layer_slot"), DmzAssets.auraLayers(), intStr(l.layer), v -> l.layer = parseI(v, l.layer));
            tip( tr("gui.dmz_ragnarok.npc.form_edit.t_aura_layer_slot"));
            cf( tr("gui.dmz_ragnarok.npc.form_edit.aura_layer_color"), () -> l.color, v -> l.color = v);
            tip( tr("gui.dmz_ragnarok.npc.form_edit.t_aura_layer_color"));
        }
        btn(14, rowY, 150, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.form_edit.add_aura_layer"), () -> {
            applyFields();
            layers.add(new FormAuraData.Layer("", 0, ""));
            rebuildWidgets();
        });
        rowY += ROW_H;
        tip( tr("gui.dmz_ragnarok.npc.form_edit.t_aura_layers"));
    }

    private static double clampPct(double v) {
        return Math.max(0.0, Math.min(100.0, v));
    }

    private String scaleStr() {
        float[] s = form.modelScaling;
        return dbl(s[0]) + ", " + dbl(s[1]) + ", " + dbl(s[2]);
    }

    private void setScale(String v) {
        String[] parts = v.split(",");
        float[] s = form.modelScaling.length >= 3 ? form.modelScaling : FormData.defaultModelScaling();
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                s[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        form.modelScaling = s;
    }

    /** Keep aura size multipliers in a sane 0.1..5.0 range so a typo can't produce an absurd billboard. */
    private static float clampAuraSize(double v) {
        return (float) Math.max(0.1, Math.min(5.0, v));
    }
}
