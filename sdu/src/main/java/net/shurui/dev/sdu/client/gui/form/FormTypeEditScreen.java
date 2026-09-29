package net.shurui.dev.sdu.client.gui.form;

import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.GeneratedLang;
import net.shurui.dev.sdu.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.SaveFormTypePacket;

/**
 * Create/rename a custom form type (a DMZ form skill). Saving registers the id in {@code skills.json}'s
 * {@code formSkills} (server-side, op-gated) and stores its display name/description in the client lang
 * overlay. Once registered, the id becomes selectable as a form group's {@code formType} and levelable
 * via each race's per-level costs (Form Costs tab / a form's Unlock Cost).
 */
public class FormTypeEditScreen extends FieldEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final FormTypesScreen owner;
    private final boolean isNew;
    /** The id this screen was opened on (edit path); the id field can now rename a NON-default type. */
    private final String originalId;
    /** True on the edit path when the id is editable (i.e. it's a non-default custom type). */
    private final boolean idEditable;
    private String typeId;
    private String displayName;
    private String description;
    // Stack form type: registered as a DMZ stack skill so a no-owner-race group with this formType holds
    // STACK forms (like Kaioken). costsCsv = per-level TP (its length = the skill's max level).
    private boolean isStack;
    private String costsCsv;
    // Presentation meta: which of DMZ's six stock icons this type borrows, and an optional tint override.
    // tintHex "" means "use the form's aura colour" (FormTypeMeta.USE_AURA_TINT); else "#RRGGBB".
    private String iconBase;
    private boolean tintOverride;
    private String tintHex;
    /** Virtual-space Y of the icon-base dropdown row, for drawing the live icon preview beside it. */
    private int iconPreviewY;
    /** Virtual-space Y just below the id row, where the reserved-substring warning is drawn (new-type only). */
    private int idWarnY;

    /** DMZ's stock form-skill ids - a type id that IS exactly one of these is not a "reserved substring" case. */
    private static final java.util.Set<String> STOCK_IDS = java.util.Set.of(
            "superforms", "legendaryforms", "godforms", "androidforms", "kaioken", "ultimate");

    /** DMZ's reserved substrings that its {@code getSkillNameForType} routes onto a stock {@code *forms} skill. */
    private static final String[] RESERVED = {"super", "legendary", "god", "android"};

    /**
     * True when {@code id} contains one of DMZ's reserved substrings but isn't itself a stock id, the case
     * where DMZ would (without sdu's routing mixin) mis-gate the type against a stock skill. Non-blocking: we
     * only surface a heads-up label; sdu now routes these correctly.
     */
    private static boolean isReservedSubstringId(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        if (STOCK_IDS.contains(lower)) {
            return false;
        }
        for (String r : RESERVED) {
            if (lower.contains(r)) {
                return true;
            }
        }
        return false;
    }

    public FormTypeEditScreen(FormTypesScreen owner, String existingId) {
        super(Component.translatable(existingId == null ? "gui.dmz_ragnarok.npc.formtype_edit.title_new" : "gui.dmz_ragnarok.npc.formtype_edit.title_edit"), UI_W, UI_H, owner);
        this.owner = owner;
        this.isNew = existingId == null;
        this.originalId = existingId == null ? "" : existingId;
        // A non-default existing type can have its id renamed; defaults keep a fixed id.
        this.idEditable = existingId != null && !FormTypesScreen.isDefault(existingId);
        this.typeId = existingId == null ? "" : existingId;
        this.displayName = existingId == null ? "" : GeneratedLang.formTypeName(existingId);
        this.description = existingId == null ? "" : GeneratedLang.formTypeDesc(existingId);
        this.isStack = existingId != null && isRegisteredStack(existingId);
        this.costsCsv = existingId == null ? "1000, 2500, 5000" : readCosts(existingId);
        net.shurui.dev.sdu.form.FormTypeMeta meta = existingId == null
                ? new net.shurui.dev.sdu.form.FormTypeMeta(
                        net.shurui.dev.sdu.form.FormTypeMeta.DEFAULT_ICON,
                        net.shurui.dev.sdu.form.FormTypeMeta.USE_AURA_TINT)
                : net.shurui.dev.sdu.client.DmzAssets.hasFormTypeMeta(existingId)
                        ? new net.shurui.dev.sdu.form.FormTypeMeta(
                                net.shurui.dev.sdu.client.DmzAssets.formTypeIcon(existingId),
                                net.shurui.dev.sdu.client.DmzAssets.formTypeTint(existingId))
                        : new net.shurui.dev.sdu.form.FormTypeMeta(
                                net.shurui.dev.sdu.form.FormTypeMeta.DEFAULT_ICON,
                                net.shurui.dev.sdu.form.FormTypeMeta.USE_AURA_TINT);
        this.iconBase = meta.iconBase;
        this.tintOverride = meta.tintArgb != net.shurui.dev.sdu.form.FormTypeMeta.USE_AURA_TINT;
        this.tintHex = tintOverride ? String.format(java.util.Locale.ROOT, "#%06X", meta.tintArgb & 0xFFFFFF) : "";
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        headerSubtitle = isNew ? "New" : typeId;
        rowY = 40;
        if (isNew || idEditable) {
            // New OR an existing non-default type: the id is a text field. On save a changed (sanitized) id on
            // the edit path dispatches a RenameFormTypePacket instead of a normal save.
            tf( tr("gui.dmz_ragnarok.npc.formtype_edit.type_id"), typeId, v -> typeId = net.shurui.dev.sdu.util.SduIds.sanitize(v));
            tip( isNew ? tr("gui.dmz_ragnarok.npc.formtype_edit.t_internal_id_for_the")
                       : tr("gui.dmz_ragnarok.npc.formtype_edit.t_rename_id"));
            // Reserve a slim row for the reserved-substring advisory drawn live in renderTopOverlay.
            idWarnY = rowY;
            rowY += 10;
        } else {
            // Default types keep a fixed id.
            label("§e" + tr("gui.dmz_ragnarok.npc.formtype_edit.type_id_value", typeId), 14, rowY + 2);
            rowY += ROW_H;
            idWarnY = -1; // id is fixed for defaults; no live warning row.
        }
        tf( tr("gui.dmz_ragnarok.npc.formtype_edit.display_name"), displayName, v -> displayName = v);
        tip( tr("gui.dmz_ragnarok.npc.formtype_edit.t_name_shown_for_this"));
        tf( tr("gui.dmz_ragnarok.npc.formtype_edit.description"), description, v -> description = v);
        tip( tr("gui.dmz_ragnarok.npc.formtype_edit.t_description_shown_un"));

        // Icon base: which of DMZ's six stock icons this type borrows for the radial X-menu / skills screen.
        // Custom types have no bundled icon, so this makes them render a real image. A small live preview is
        // drawn left of the (14px-indented) dropdown label.
        iconPreviewY = rowY;
        df( tr("gui.dmz_ragnarok.npc.formtype_edit.icon_base"),
                net.shurui.dev.sdu.form.FormTypeMeta.STOCK_ICONS, iconBase,
                v -> iconBase = net.shurui.dev.sdu.form.FormTypeMeta.normalizeIcon(v));

        // Tint: toggle between "use the form's aura colour" (default) and an explicit hex override.
        bf( tr("gui.dmz_ragnarok.npc.formtype_edit.icon_tint"), tintOverride, () -> {
            tintOverride = !tintOverride;
            if (tintOverride && (tintHex == null || tintHex.isBlank())) {
                tintHex = "#FFFFFF";
            }
        });
        if (tintOverride) {
            cf("#RRGGBB", () -> tintHex, v -> tintHex = v);
        } else {
            label( tr("gui.dmz_ragnarok.npc.formtype_edit.tint_off"), 14, rowY + 2);
            rowY += ROW_H;
        }

        // kaioken/ultimate are DMZ STACK defaults: they MUST stay stack forms or DMZ drops them from the form
        // purchase menu (and un-stacking deletes their cost block). Lock the toggle checked+non-interactive for
        // these; everything else keeps a live toggle.
        boolean stackLocked = net.shurui.dev.sdu.form.FormTypeManager.STACK_DEFAULTS.contains(
                net.shurui.dev.sdu.util.SduIds.sanitize(typeId));
        if (stackLocked) {
            isStack = true; // enforce the invariant even if a stale save had flipped it off
            label( tr("gui.dmz_ragnarok.npc.formtype_edit.stack_type") + " §7(§a" + tr("gui.dmz_ragnarok.npc.common.on") + "§7, " + tr("gui.dmz_ragnarok.npc.common.locked") + ")", 14, rowY + 2);
            rowY += ROW_H;
        } else {
            bf( tr("gui.dmz_ragnarok.npc.formtype_edit.stack_type"), isStack, () -> isStack = !isStack);
            tip( tr("gui.dmz_ragnarok.npc.formtype_edit.t_stack_type"));
        }
        if (isStack) {
            tf( tr("gui.dmz_ragnarok.npc.formtype_edit.skill_costs"), costsCsv, v -> costsCsv = v);
            tip( tr("gui.dmz_ragnarok.npc.formtype_edit.t_skill_costs"));
        } else {
            label( tr("gui.dmz_ragnarok.npc.formtype_edit.set_per_race_unlock_cost"), 14, rowY + 2);
            rowY += 10;
            label( tr("gui.dmz_ragnarok.npc.formtype_edit.costs_tab_or_a_form_s_un"), 14, rowY + 2);
            rowY += 12;
        }

        commitBtn(uiWidth / 2 - 84, footerY(), 80, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.save"), () -> { applyFields(); save(); });
        btn(uiWidth / 2 + 4, footerY(), 80, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.cancel"), () -> { applyFields(); back(); });
    }

    /**
     * Draw the live 16x16 preview of the picked stock icon beside the icon-base row. Uses the base screen's
     * top-overlay hook so it renders inside the scaled canvas (under any open picker / dropdown). The icon lives
     * at {@code dragonminez:textures/gui/radial/<name>.png} (18x18); scaled into a 16x16 slot left of the row's
     * 150px-inset dropdown.
     */
    @Override
    protected void renderTopOverlay(net.minecraft.client.gui.GuiGraphics g, int vmx, int vmy) {
        super.renderTopOverlay(g, vmx, vmy);

        // Non-blocking heads-up: a new-type id containing a DMZ reserved substring (super/legendary/god/android)
        // but not itself a stock id. sdu routes these correctly now, so this only advises. Read the live typed
        // value (applyFields pulls the id box into typeId) so it updates as you type.
        if (idWarnY >= 0 && (isNew || idEditable)) {
            applyFields();
            if (isReservedSubstringId(typeId)) {
                // The lang string is long; render it at half scale so it fits the 300px-wide canvas.
                g.pose().pushPose();
                g.pose().translate(14, idWarnY, 0);
                g.pose().scale(0.5f, 0.5f, 1f);
                g.drawString(this.font, tr("gui.dmz_ragnarok.npc.formtype_edit.reserved_id"), 0, 0, 0xFFFFB84D, false);
                g.pose().popPose();
            }
        }

        String name = net.shurui.dev.sdu.form.FormTypeMeta.normalizeIcon(iconBase);
        net.minecraft.resources.ResourceLocation tex =
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        "dragonminez", "textures/gui/radial/" + name + ".png");
        int ix = 130;
        int iy = iconPreviewY;
        g.fill(ix - 1, iy - 1, ix + 17, iy + 17, 0xFF10281A);
        try {
            g.blit(tex, ix, iy, 16, 16, 0f, 0f, 18, 18, 18, 18);
        } catch (Throwable ignored) {
            // Missing texture / atlas hiccup: leave the swatch backdrop, don't break the screen.
        }
    }

    private void save() {
        String id = net.shurui.dev.sdu.util.SduIds.sanitize(typeId);
        if (id.isEmpty()) {
            return; // nothing to register
        }
        int tintArgb = tintOverride
                ? net.shurui.dev.sdu.client.gui.FieldEditScreen.hexToArgb(tintHex) & 0xFFFFFF
                : net.shurui.dev.sdu.form.FormTypeMeta.USE_AURA_TINT;
        // hexToArgb returns 0 (opaque black stripped to 0x000000) for a blank/invalid hex; if the override was
        // toggled on but the field left empty, fall back to "use aura colour" rather than tinting everything black.
        if (tintOverride && (tintHex == null || FieldEditScreen.normalizeHex(tintHex).isBlank())) {
            tintArgb = net.shurui.dev.sdu.form.FormTypeMeta.USE_AURA_TINT;
        }
        net.shurui.dev.sdu.form.FormTypeMeta meta =
                new net.shurui.dev.sdu.form.FormTypeMeta(iconBase, tintArgb);

        // Edit path, non-default type, id actually changed: this is a RENAME, not a save. Dispatch the rename
        // packet (the server runs the full cascade) and migrate this client's own radial ordering + session
        // caches optimistically, then don't also send the save under the new id.
        boolean renaming = idEditable && !id.equals(net.shurui.dev.sdu.util.SduIds.sanitize(originalId));
        if (renaming) {
            String from = net.shurui.dev.sdu.util.SduIds.sanitize(originalId);
            DmzNet.sendToServer(new net.shurui.dev.sdu.network.RenameFormTypePacket(from, id));
            // Optimistic client migration so the radial keeps its saved order + this session sees the new id.
            net.shurui.dev.sdu.client.gui.radial.RadialOrdering.renameFormTypeOrder(from, id);
            net.shurui.dev.sdu.client.DmzAssets.registerSessionFormType(id, meta);
            GeneratedLang.putFormType(id, displayName, description);
            owner.addType(id);
            back();
            return;
        }

        GeneratedLang.putFormType(id, displayName, description);
        net.shurui.dev.sdu.client.DmzAssets.registerSessionFormType(id, meta);
        DmzNet.sendToServer(new SaveFormTypePacket(id, false, isStack, parseCosts(costsCsv),
                meta.iconBase, meta.tintArgb));
        owner.addType(id);
        back();
    }

    private static java.util.List<Integer> parseCosts(String csv) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                int v = Integer.parseInt(t);
                // Preserve the -1 sentinel (DMZ's "always-available" special cost, used by ultimate); floor
                // only other negatives to 0.
                out.add(v == -1 ? -1 : Math.max(0, v));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** True if {@code id} is already a registered DMZ stack skill. */
    private static boolean isRegisteredStack(String id) {
        try {
            var skills = com.dragonminez.common.config.ConfigManager.getSkillsConfig();
            return skills != null && skills.getStackSkills() != null && skills.getStackSkills().contains(id);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The existing per-level costs of a stack skill as CSV, or a sensible default. */
    private static String readCosts(String id) {
        try {
            var skills = com.dragonminez.common.config.ConfigManager.getSkillsConfig();
            if (skills != null && skills.getSkills() != null) {
                var sc = skills.getSkills().get(id);
                if (sc != null && sc.getCosts() != null && !sc.getCosts().isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (int c : sc.getCosts()) {
                        if (sb.length() > 0) {
                            sb.append(", ");
                        }
                        sb.append(c);
                    }
                    return sb.toString();
                }
            }
        } catch (Throwable ignored) {
        }
        return "1000, 2500, 5000";
    }
}
