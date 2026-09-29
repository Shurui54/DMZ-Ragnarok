package net.shurui.dev.sdu.client.gui.saga;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.client.DmzSkills;
import net.shurui.dev.sdu.client.GameEntities;
import net.shurui.dev.sdu.client.GameItems;
import net.shurui.dev.sdu.client.gui.DmzDropdown;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.saga.SagaData;

import java.util.ArrayList;
import java.util.List;

/** Edit one objective: type dropdown + per-type fields; KILL can pick one of our NPC definitions. */
public class ObjectiveEditScreen extends SagaBaseScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final SagaData.Objective obj;
    /**
     * When true, the next DMZ-defaults reply for the selected entity is allowed to overwrite this objective's
     * Health / Melee Damage / Ki Damage / AI Tier. It is set ONLY for a brand-new objective (constructor arg)
     * or when the admin actively changes the entity dropdown, and is consumed (reset to false) after one fill.
     * Opening an EXISTING objective leaves it false, so its saved stats are never clobbered back to DMZ defaults
     * (that was the "quest NPC stats revert to default the moment you edit them" bug).
     */
    private boolean fillDefaultsOnReply;
    private final List<String> cloneTokens = new ArrayList<>();
    private final List<ResourceLocation> entityIds = new ArrayList<>();
    private final List<ResourceLocation> itemIds = new ArrayList<>();
    private final List<String> skillIds = new ArrayList<>();
    /** KILL entity picker rows: every mob id, plus every ragnarok character as {@code rgnpc#<character>}. */
    private final List<String> entityValues = new ArrayList<>();

    private DmzDropdown typeDropdown, entityDropdown, npcDropdown, itemDropdown, skillDropdown, aiTierDropdown;
    private EditBox countField, healthField, meleeField, kiField;
    private EditBox npcIdField, npcNameField, dimField, biomeField, structField, levelField;
    private EditBox coordXField, coordYField, coordZField, radiusField;

    /**
     * DMZ AI tier lang keys for the FIXED tiers, 1-based (1 = SIMPLE ... 3 = ADVANCED). The dropdown prepends
     * an "Auto" row (index 0) for obj.aiTier = -1, so a dropdown row maps to obj.aiTier as: row 0 -> -1
     * (Auto: DMZ scales the tier with server difficulty), row n>=1 -> n. Order is load-bearing (do not reorder).
     */
    private static final String[] AI_TIER_KEYS = {
            "gui.dmz_ragnarok.npc.objective_edit.tier_simple",
            "gui.dmz_ragnarok.npc.objective_edit.tier_tactical",
            "gui.dmz_ragnarok.npc.objective_edit.tier_advanced" };

    public ObjectiveEditScreen(Screen parent, SagaData.Objective obj, boolean autoFillDefaults) {
        super(Component.translatable("gui.dmz_ragnarok.npc.objective_edit.edit_objective"), UI_W, UI_H, parent);
        this.obj = obj;
        this.fillDefaultsOnReply = autoFillDefaults;
        // Ask the server for the saved Custom NPC clone list to populate the "Saved NPC" picker.
        net.shurui.dev.sdu.network.DmzNet.sendToServer(new net.shurui.dev.sdu.network.RequestClonesPacket());
        // Prime the KILL auto-fill ONLY for a brand-new objective. Opening an EXISTING objective must not fetch
        // and overwrite its saved stats with DMZ defaults; an explicit entity change re-arms it (onDropdownSelect).
        if (autoFillDefaults) {
            requestNpcDefaults(obj.entity);
        }
    }

    /** Ask the server for {@code entityId}'s DMZ default stats so the KILL fields can auto-fill on reply. */
    private static void requestNpcDefaults(String entityId) {
        if (entityId != null && !entityId.isBlank()) {
            net.shurui.dev.sdu.network.DmzNet.sendToServer(
                    new net.shurui.dev.sdu.network.RequestNpcDefaultsPacket(entityId));
        }
    }

    /** Rebuild if this screen is open when the saved-clone list arrives from the server. */
    public static void onClonesSynced() {
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof ObjectiveEditScreen s) {
            s.rebuildWidgets();
        }
    }

    /**
     * DMZ default stats for {@code entityId} arrived: if this KILL objective still targets that id, overwrite its
     * Health / Melee Damage / Ki Damage / AI Tier with the DMZ defaults and refresh the fields. The admin can then
     * edit them. Populate-on-selection semantics: we only auto-fill for the entity currently selected.
     */
    public static void onNpcDefaultsSynced(String entityId) {
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof ObjectiveEditScreen s
                && s.fillDefaultsOnReply
                && "KILL".equals(s.obj.type) && s.obj.entity != null && s.obj.entity.equals(entityId)) {
            net.shurui.dev.sdu.client.ClientNpcDefaults.Defaults d =
                    net.shurui.dev.sdu.client.ClientNpcDefaults.get(entityId);
            if (d != null) {
                s.obj.health = (float) d.health();
                s.obj.meleeDamage = (float) d.melee();
                s.obj.kiDamage = (float) d.ki();
                s.obj.aiTier = d.aiTier();
                s.fillDefaultsOnReply = false; // consume: a later reply must not clobber the admin's manual edits
                s.rebuildWidgets();
            }
        }
    }

    @Override
    protected void init() {
        super.init();
        // Start the first (type) row at CONTENT_TOP so the dropdown clears the header/logo (logo bottom y=23).
        int row1 = contentTop(22);
        label( tr("gui.dmz_ragnarok.npc.objective_edit.type"), 12, row1 + 4);
        // Searchable so all 8 types are findable: the list shows only MAX_VISIBLE (6) rows, so COORDS/SKILL
        // sit below the fold and look "missing" otherwise. Type "coord" to jump straight to the coords objective.
        typeDropdown = dropdown(48, row1, 130, options(SagaData.OBJECTIVE_TYPES), indexOf(SagaData.OBJECTIVE_TYPES, obj.type)).searchable();
        tooltip(12, row1 - 2, 170, 18, tr("gui.dmz_ragnarok.npc.objective_edit.t_what_the_player_must"));

        // The per-type body (esp. KILL, which stacks entity/NPC/stat rows + four buttons) can overflow the
        // panel, so lay it out inside a scroll band; the type dropdown above and Back below stay pinned.
        int contentTop = 48;
        int contentBottom = UI_H - 24;
        beginScrollBand(contentTop, contentBottom);
        int y = contentTop + 2 - scroll;
        switch (obj.type) {
            case "KILL" -> {
                label( tr("gui.dmz_ragnarok.npc.objective_edit.entity"), 12, y + 4);
                // THE RAGNAROK CHARACTERS ARE IN THIS LIST. They all share one entity type, so a list built from
                // the registry shows the whole cast as one "rgnpc" row; RagnarokLook adds each character as its
                // own row and splits the pick back into the objective's two fields.
                entityValues.clear();
                for (ResourceLocation r : GameEntities.mobIds()) {
                    entityValues.add(r.toString());
                }
                entityValues.addAll(net.shurui.dev.sdu.compat.ragnarok.RagnarokLook.options(List.of()));
                entityValues.sort(String::compareToIgnoreCase);
                List<Component> entOpts = new ArrayList<>();
                for (String id : entityValues) {
                    entOpts.add(Component.literal(id));
                }
                int entIdx = Math.max(0, entityValues.indexOf(
                        net.shurui.dev.sdu.compat.ragnarok.RagnarokLook.value(obj.entity, obj.rgModel)));
                entityDropdown = dropdown(58, y, 214, entOpts, entIdx).searchable();
                y += 22;
                label(tr("gui.dmz_ragnarok.npc.objective_edit.saved_npc"), 12, y + 4);
                cloneTokens.clear();
                cloneTokens.addAll(net.shurui.dev.sdu.client.ClientCloneList.tokens());
                List<Component> opts = new ArrayList<>();
                opts.add(Component.translatable("gui.dmz_ragnarok.npc.objective_edit.none_use_entity"));
                for (String tk : cloneTokens) {
                    opts.add(Component.literal(net.shurui.dev.sdu.client.ClientCloneList.label(tk)));
                }
                int sel = 0;
                if (obj.definition != null && obj.definition.startsWith("cnpc$")) {
                    int idx = cloneTokens.indexOf(obj.definition.substring("cnpc$".length()));
                    sel = idx >= 0 ? idx + 1 : 0;
                }
                npcDropdown = dropdown(58, y, 150, opts, sel).searchable();
                y += 22;
                countField = labeled(tr("gui.dmz_ragnarok.npc.objective_edit.count"), 12, y, 58, 44, Integer.toString(obj.count));
                // DMZ AI tier dropdown. Row 0 = Auto (obj.aiTier = -1: no AITier on disk, tier scales with
                // server difficulty). Rows 1..3 force a fixed tier (1=Simple/2=Tactical/3=Advanced).
                label(tr("gui.dmz_ragnarok.npc.objective_edit.ai_tier"), 130, y + 4);
                List<Component> tierOpts = new ArrayList<>();
                tierOpts.add(Component.translatable("gui.dmz_ragnarok.npc.objective_edit.tier_auto"));
                for (String t : AI_TIER_KEYS) {
                    tierOpts.add(Component.translatable(t));
                }
                int tierIdx = obj.aiTier > 0 ? Math.min(obj.aiTier, AI_TIER_KEYS.length) : 0;
                aiTierDropdown = dropdown(176, y, 96, tierOpts, tierIdx);
                y += 20;
                healthField = labeled(tr("gui.dmz_ragnarok.npc.objective_edit.health"), 12, y, 58, 44, fmt(obj.health));
                meleeField = labeled(tr("gui.dmz_ragnarok.npc.objective_edit.melee_damage"), 130, y, 200, 72, fmt(obj.meleeDamage));
                y += 20;
                kiField = labeled(tr("gui.dmz_ragnarok.npc.objective_edit.ki_damage"), 12, y, 74, 198, fmt(obj.kiDamage));
                y += 20;
                // Hold this KILL spawn back until the player reaches the quest's COORDS objective location.
                btn(12, y, 260, GuiTheme.BUTTON_HEIGHT, flag(tr("gui.dmz_ragnarok.npc.objective_edit.spawn_at_location"), obj.deferSpawnUntilLocation),
                        () -> { obj.deferSpawnUntilLocation = !obj.deferSpawnUntilLocation; apply(); rebuildWidgets(); });
                y += 20;
                // Open the reusable transform-chain editor; it edits obj.transformChain in place. onDone marks
                // the saga dirty (via apply) and rebuilds so the toggle/labels reflect any change on return.
                btn(12, y, 260, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.objective_edit.transformations_btn"),
                        () -> minecraft.setScreen(new net.shurui.dev.sdu.client.gui.transform.TransformChainEditScreen(
                                this, obj.transformChain, Component.translatable("gui.dmz_ragnarok.npc.objective_edit.transformations_title"),
                                () -> { apply(); rebuildWidgets(); })));
                y += 20;
                // Keep DMZ's built-in transformations; only consulted when no custom chain is set (chain wins).
                btn(12, y, 260, GuiTheme.BUTTON_HEIGHT, flag(tr("gui.dmz_ragnarok.npc.objective_edit.use_default_transform"), obj.useDefaultTransform),
                        () -> { obj.useDefaultTransform = !obj.useDefaultTransform; apply(); rebuildWidgets(); });
                tooltip(12, y, 260, 16, tr("gui.dmz_ragnarok.npc.objective_edit.tip_default_transform"));
            }
            case "TALK_TO" -> {
                npcIdField = labeled(tr("gui.dmz_ragnarok.npc.objective_edit.npc_id"), 12, y, 62, 210, obj.npcId);
                y += 22;
                npcNameField = labeled(tr("gui.dmz_ragnarok.npc.objective_edit.npc_name"), 12, y, 74, 198, obj.npcName);
                tooltip(12, y, 272, 16, tr("gui.dmz_ragnarok.npc.objective_edit.tip_npc_name"));
            }
            case "ITEM" -> {
                label( tr("gui.dmz_ragnarok.npc.objective_edit.item"), 12, y + 4);
                itemIds.clear();
                itemIds.addAll(GameItems.itemIds());
                List<Component> opts = new ArrayList<>();
                for (ResourceLocation r : itemIds) {
                    opts.add(Component.literal(r.toString()));
                }
                int idx = Math.max(0, itemIds.indexOf(ResourceLocation.tryParse(obj.item)));
                itemDropdown = dropdown(58, y, 214, opts, idx).searchable();
                y += 22;
                countField = labeled(tr("gui.dmz_ragnarok.npc.objective_edit.count"), 12, y, 58, 60, Integer.toString(obj.count));
            }
            case "DIMENSION" -> dimField = labeled(tr("gui.dmz_ragnarok.npc.objective_edit.dimension"), 12, y, 74, 198, obj.dimension);
            case "BIOME" -> biomeField = labeled(tr("gui.dmz_ragnarok.npc.objective_edit.biome"), 12, y, 62, 210, obj.biome);
            case "STRUCTURE" -> structField = labeled(tr("gui.dmz_ragnarok.npc.objective_edit.structure"), 12, y, 74, 198, obj.structure);
            case "COORDS" -> {
                coordXField = labeled(tr("gui.dmz_ragnarok.npc.objective_edit.coord_x"), 12, y, 30, 60, Integer.toString(obj.coordX));
                coordYField = labeled(tr("gui.dmz_ragnarok.npc.objective_edit.coord_y"), 108, y, 126, 60, Integer.toString(obj.coordY));
                coordZField = labeled(tr("gui.dmz_ragnarok.npc.objective_edit.coord_z"), 204, y, 222, 60, Integer.toString(obj.coordZ));
                y += 22;
                radiusField = labeled(tr("gui.dmz_ragnarok.npc.objective_edit.radius"), 12, y, 62, 60, Integer.toString(obj.radius));
                y += 22;
                btn(12, y, 200, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.objective_edit.use_my_coords"), this::fillCurrentCoords);
            }
            case "SKILL" -> {
                label( tr("gui.dmz_ragnarok.npc.objective_edit.skill"), 12, y + 4);
                skillIds.clear();
                skillIds.addAll(DmzSkills.skillIds());
                List<Component> opts = new ArrayList<>();
                if (skillIds.isEmpty()) {
                    opts.add(Component.translatable("gui.dmz_ragnarok.npc.objective_edit.no_skills_found"));
                } else {
                    for (String s : skillIds) {
                        opts.add(Component.literal(s));
                    }
                }
                int idx = Math.max(0, skillIds.indexOf(obj.skill));
                skillDropdown = dropdown(58, y, 214, opts, idx).searchable();
                y += 22;
                levelField = labeled(tr("gui.dmz_ragnarok.npc.objective_edit.level"), 12, y, 58, 60, Integer.toString(obj.level));
            }
            default -> { }
        }
        // Close the band: clamp scroll, hide off-band rows/fields, draw the thumb. Back is added AFTER so it
        // is never hidden by the clamp.
        finishScrollBand(contentTop, contentBottom, y);

        btn(UI_W / 2 - 55, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.back"), () -> { apply(); back(); });
    }

    private EditBox labeled(String lbl, int lblX, int y, int fieldX, int w, String value) {
        label(lbl, lblX, y + 4);
        return field(fieldX, y, w, value);
    }

    private static net.minecraft.network.chat.Component flag(String name, boolean on) {
        return net.minecraft.network.chat.Component.literal((on ? "§a[x] " : "§7[ ] ") + name);
    }

    /** Fill the X/Y/Z fields from the player's current block position (client-side convenience). */
    private void fillCurrentCoords() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null || coordXField == null) {
            return;
        }
        coordXField.setValue(Integer.toString(net.minecraft.util.Mth.floor(player.getX())));
        coordYField.setValue(Integer.toString(net.minecraft.util.Mth.floor(player.getY())));
        coordZField.setValue(Integer.toString(net.minecraft.util.Mth.floor(player.getZ())));
        obj.coordX = parseInt(coordXField.getValue(), obj.coordX);
        obj.coordY = parseInt(coordYField.getValue(), obj.coordY);
        obj.coordZ = parseInt(coordZField.getValue(), obj.coordZ);
    }

    /** Flush in-progress field text before a wheel-scroll rebuilds the band, so typing isn't lost. */
    @Override
    protected void applyBeforeBandScroll() {
        apply();
    }

    @Override
    protected void onDropdownSelect(DmzDropdown dropdown, int row) {
        apply();
        if (dropdown == typeDropdown) {
            obj.type = SagaData.OBJECTIVE_TYPES[row];
            scroll = 0; // a different type has a different-height body; start it at the top
        } else if (dropdown == entityDropdown) {
            String picked = row >= 0 && row < entityValues.size() ? entityValues.get(row) : "";
            obj.entity = net.shurui.dev.sdu.compat.ragnarok.RagnarokLook.entityOf(picked);
            obj.rgModel = net.shurui.dev.sdu.compat.ragnarok.RagnarokLook.modelOf(picked);
            if (!"dmz_ragnarok:dmz_fighter".equals(obj.entity)) {
                obj.definition = "";
            }
            // New entity selected: re-arm the auto-fill and fetch its DMZ defaults so the stat fields fill on
            // reply. The ENTITY id alone: a character rides the same type and has no defaults of its own.
            fillDefaultsOnReply = true;
            requestNpcDefaults(obj.entity);
        } else if (dropdown == aiTierDropdown) {
            obj.aiTier = row == 0 ? -1 : row; // row 0 = Auto (-1); rows 1..3 = DMZ 1-based tier id 1..3.
        } else if (dropdown == npcDropdown) {
            if (row == 0) {
                obj.definition = "";
            } else {
                obj.definition = "cnpc$" + cloneTokens.get(row - 1);
                obj.entity = "dmz_ragnarok:dmz_fighter";
            }
        } else if (dropdown == itemDropdown) {
            if (!itemIds.isEmpty()) {
                obj.item = itemIds.get(row).toString();
            }
        } else if (dropdown == skillDropdown) {
            if (!skillIds.isEmpty()) {
                obj.skill = skillIds.get(row);
            }
        }
        rebuildWidgets();
    }

    private void apply() {
        if (countField != null) obj.count = parseInt(countField.getValue(), obj.count);
        if (healthField != null) obj.health = parseFloat(healthField.getValue(), obj.health);
        if (meleeField != null) obj.meleeDamage = parseFloat(meleeField.getValue(), obj.meleeDamage);
        if (kiField != null) obj.kiDamage = parseFloat(kiField.getValue(), obj.kiDamage);
        if (npcIdField != null) obj.npcId = npcIdField.getValue().trim();
        if (npcNameField != null) obj.npcName = npcNameField.getValue().trim();
        if (dimField != null) obj.dimension = dimField.getValue().trim();
        if (biomeField != null) obj.biome = biomeField.getValue().trim();
        if (structField != null) obj.structure = structField.getValue().trim();
        if (coordXField != null) obj.coordX = parseInt(coordXField.getValue(), obj.coordX);
        if (coordYField != null) obj.coordY = parseInt(coordYField.getValue(), obj.coordY);
        if (coordZField != null) obj.coordZ = parseInt(coordZField.getValue(), obj.coordZ);
        if (radiusField != null) obj.radius = parseInt(radiusField.getValue(), obj.radius);
        if (levelField != null) obj.level = parseInt(levelField.getValue(), obj.level);
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

    private static float parseFloat(String s, float fb) {
        try { return Float.parseFloat(s.trim()); } catch (NumberFormatException e) { return fb; }
    }

    private static String fmt(float v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : Float.toString(v);
    }
}
