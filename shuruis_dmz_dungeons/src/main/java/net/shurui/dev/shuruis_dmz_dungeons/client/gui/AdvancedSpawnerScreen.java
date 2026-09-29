package net.shurui.dev.shuruis_dmz_dungeons.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.registries.ForgeRegistries;
import net.shurui.dev.shuruis_dmz_dungeons.block.SpawnerConfig;
import net.shurui.dev.shuruis_dmz_dungeons.client.ClientNpcDefaults;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_dmz_dungeons.network.RequestNpcDefaultsPacket;
import net.shurui.dev.shuruis_dmz_dungeons.network.SaveSpawnerPacket;
import net.shurui.dev.shuruis_dmz_dungeons.network.SddNet;

import java.util.ArrayList;
import java.util.List;

// advanced spawner editor on the shared DMZ toolkit (FieldEditScreen) so it matches the other addons' editors.
// entity picked from a searchable dropdown of every registered entity type.
// what applies where: Ki/Behaviour/Scale/Model only affect sdu:dmz_fighter (via the NBT keys it reads), ignored
// by everything else; stat fields hit any living entity via vanilla attributes; TP-per-kill goes to the killer
// through the DMZ TP compat. save is server-authoritative.
public class AdvancedSpawnerScreen extends FieldEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    // i18n key suffixes for the option arrays. index == ordinal (order MUST stay fixed: the NBT keys store
    // the ordinal), only the displayed label is translated via optArr() at build time.
    private static final String[] TAB_KEYS = {
            "general", "stats", "ki", "spawn", "rewards", "drops", "boss", "disguise"};
    // order MUST match sdu's DmzMoveset / NpcAiTier enums, the NBT keys store the ordinal
    private static final String[] MOVESET_KEYS = {
            "none", "saibamen", "frieza_soldier", "cui", "dodoria", "zarbon", "ginyu_force",
            "frieza", "androids", "cell", "babidi_soldier", "majin_buu"};
    private static final String[] AI_TIER_KEYS = {"simple", "tactical", "advanced"};
    private static final String[] BEHAVIOR_KEYS = {
            "passive", "wander", "dialogue", "follower", "guard", "hostile", "dmz_fighter"};
    // ordinals match SpawnerConfig.timeCondition / weatherCondition / redstoneMode
    private static final String[] TIME_KEYS = {"any", "day", "night", "morning", "dusk"};
    private static final String[] WEATHER_KEYS = {"any", "clear", "rain", "thunder"};
    private static final String[] REDSTONE_KEYS = {"ignore", "requires_signal", "requires_no_signal"};

    private static List<String> ENTITY_IDS;

    private final BlockPos pos;
    private final SpawnerConfig config;
    // callback mode (floor-boss editor): when non-null, Save applies the fields to the shared config in place and
    // returns to the parent instead of sending the block-bound SaveSpawnerPacket. the parent owns persisting it.
    private final Runnable onSaved;
    private int tab = 0;
    private int dropScroll = 0;

    // DMZ default-stats auto-fill. picking an entity asks the server for its defaults; remember the id and
    // whether it's the main or boss slot, and when the reply lands in ClientNpcDefaults (spotted via its
    // version counter) overwrite the matching stat fields.
    private int seenDefaultsVersion = ClientNpcDefaults.version();
    private String pendingMainId = null;
    private String pendingBossId = null;

    private DmzDropdown movesetDropdown;
    private DmzDropdown aiTierDropdown;
    private DmzDropdown behaviorDropdown;
    private DmzDropdown timeDropdown;
    private DmzDropdown weatherDropdown;
    private DmzDropdown redstoneDropdown;

    public AdvancedSpawnerScreen(BlockPos pos, SpawnerConfig config) {
        super(Component.translatable("gui.dmz_ragnarok.dungeons.spawner.title"), UI_W, UI_H, null);
        this.pos = pos;
        this.config = config;
        this.onSaved = null;
    }

    // floor-boss editor: edits `config` in place and returns to `parent` on Save (no block, no SaveSpawnerPacket).
    public AdvancedSpawnerScreen(net.minecraft.client.gui.screens.Screen parent, SpawnerConfig config, Runnable onSaved) {
        super(Component.translatable("gui.dmz_ragnarok.dungeons.spawner.title"), UI_W, UI_H, parent);
        this.pos = null;
        this.config = config;
        this.onSaved = onSaved;
    }

    public static void open(BlockPos pos, SpawnerConfig config) {
        Minecraft.getInstance().setScreen(new AdvancedSpawnerScreen(pos, config));
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        movesetDropdown = aiTierDropdown = behaviorDropdown = null;
        timeDropdown = weatherDropdown = redstoneDropdown = null;

        String subtitle = config.enabled
                ? I18n.get("gui.dmz_ragnarok.dungeons.spawner.subtitle", config.entityTypeId)
                : I18n.get("gui.dmz_ragnarok.dungeons.spawner.subtitle_disabled", config.entityTypeId);
        rowY = buildTabHeader(subtitle, optArr("tab", TAB_KEYS), tab, this::selectSection);

        // wrap the active tab in a scroll band so the ones that overflow the fixed canvas (Stats and Boss got
        // fat with transform + extra rows) can scroll instead of spilling past the panel. tabs that fit ->
        // maxScroll == 0, no scrollbar. Save/Cancel are pinned below the band.
        int contentTop = rowY;
        int contentBottom = UI_H - 26;
        beginScrollBand(contentTop, contentBottom);
        rowY = contentTop - scroll;
        switch (tab) {
            case 0 -> generalTab();
            case 1 -> statsTab();
            case 2 -> kiTab();
            case 3 -> spawnTab();
            case 4 -> rewardsTab();
            case 5 -> dropsTab();
            case 6 -> bossTab();
            case 7 -> disguiseTab();
            default -> { }
        }
        finishScrollBand(contentTop, contentBottom);

        int by = footerY();
        btn(UI_W / 2 - 104, by, 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.dungeons.common.save"), this::save);
        btn(UI_W / 2 + 4, by, 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.dungeons.common.cancel"), this::onClose);
    }

    private void selectSection(int index) {
        if (tab != index) {
            applyFields();
            tab = index;
            scroll = 0; // each tab starts at the top
            rebuildWidgets();
        }
    }

    private void generalTab() {
        // THE RAGNAROK CHARACTERS ARE IN THIS LIST, not in a second control beside it. All 386 of them share one
        // entity type, so the registry alone offers a single "rgnpc" row; RgNpcPicker adds each character as its
        // own option and splits the pick back into the two fields the config already has.
        df(I18n.get("gui.dmz_ragnarok.dungeons.spawner.entity"),
                net.shurui.shuruisutilities.ragnarok.RgNpcPicker.options(entityIds()),
                net.shurui.shuruisutilities.ragnarok.RgNpcPicker.value(config.entityTypeId, config.rgModelId),
                v -> {
                    if (v.isBlank()) return;
                    config.entityTypeId = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.entityOf(v);
                    config.rgModelId = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.modelOf(v);
                },
                // The stats auto-fill asks the server for an ENTITY's DMZ defaults, so it gets the entity id
                // alone; the character rides the same type and has no defaults of its own.
                v -> requestMainDefaults(net.shurui.shuruisutilities.ragnarok.RgNpcPicker.entityOf(v)));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.entity_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.display_name"), config.displayName, v -> config.displayName = v);
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.display_name_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.model_id"), config.modelId, v -> config.modelId = v.trim());
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.saved_npc"), config.savedNpcRef, v -> config.savedNpcRef = v.trim());
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.saved_npc_tip"));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.model_id_tip"));
    }

    private void statsTab() {
        // picking an entity on General auto-fills Health/Melee/Ki/AI Tier with that NPC's DMZ defaults
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.health"), fmt(config.maxHealth), v -> config.maxHealth = parseFloat(v, config.maxHealth));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.health_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.melee"), fmt(config.attackDamage), v -> config.attackDamage = parseFloat(v, config.attackDamage));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.melee_tip"));
        // one "Ki Damage" field bound to the ki-damage range: writing pins both bounds to a fixed amount.
        // the Ki tab still has the full Min/Max range and the explicit ki-blast loadout.
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.ki_damage"), intStr(config.kiDmgMax), v -> {
            int val = Math.max(0, parseI(v, config.kiDmgMax));
            config.kiDmgMin = val;
            config.kiDmgMax = val;
            config.kiEnabled = config.kiEnabled || val > 0;
        });
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.ki_damage_tip"));
        label(I18n.get("gui.dmz_ragnarok.dungeons.spawner.ai_tier"), 14, rowY + 5);
        aiTierDropdown = dropdown(150, rowY, 132, options(optArr("ai_tier", AI_TIER_KEYS)), clampIndex(config.aiTier, AI_TIER_KEYS));
        rowY += ROW_H;
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.ai_tier_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.scale"), fmt(config.scale), v -> config.scale = parseFloat(v, config.scale));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.scale_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.speed"), fmt(config.moveSpeed), v -> config.moveSpeed = parseFloat(v, config.moveSpeed));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.speed_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.defense"), fmt(config.defense), v -> config.defense = parseFloat(v, config.defense));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.defense_tip"));
        bf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.use_default_transform"), config.mainUseDefaultTransform,
                () -> config.mainUseDefaultTransform = !config.mainUseDefaultTransform);
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.use_default_transform_tip"));
        transformRow(config.mainTransform, t -> config.mainTransform = t);
    }

    // "Transformations..." row: opens sdu's shared transform-chain editor for chainNbt when sdu is loaded,
    // else a disabled "requires SDU" button. the chain is stored as raw NBT so config/spawning need no sdu
    // classes.
    private void transformRow(net.minecraft.nbt.CompoundTag chainNbt,
                              java.util.function.Consumer<net.minecraft.nbt.CompoundTag> onSave) {
        label(I18n.get("gui.dmz_ragnarok.dungeons.spawner.transformations"), 14, rowY + 2);
        if (net.shurui.dev.shuruis_dmz_dungeons.compat.sdu.SduTransformCompat.available()) {
            btn(150, rowY, 132, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.dungeons.spawner.transformations_btn"), () -> {
                applyFields();
                net.shurui.dev.shuruis_dmz_dungeons.compat.sdu.SduTransformCompat.openEditor(this, chainNbt, onSave);
            });
        } else {
            btn(150, rowY, 132, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.dungeons.spawner.transformations_no_sdu"), () -> { }).active = false;
        }
        rowY += ROW_H;
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.transformations_tip"));
    }

    private void kiTab() {
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.ki_min"), intStr(config.kiDmgMin), v -> config.kiDmgMin = Math.max(0, parseI(v, config.kiDmgMin)));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.ki_min_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.ki_max"), intStr(config.kiDmgMax), v -> config.kiDmgMax = Math.max(0, parseI(v, config.kiDmgMax)));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.ki_max_tip"));
        label(I18n.get("gui.dmz_ragnarok.dungeons.spawner.ki_blasts"), 14, rowY + 4);
        btn(150, rowY, 132, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.dungeons.spawner.edit_ki_blasts", config.kiMoves.size()),
                () -> { applyFields(); this.minecraft.setScreen(
                        new KiMoveEntryListScreen(this, I18n.get("gui.dmz_ragnarok.dungeons.ki_list.subtitle_entity", config.entityTypeId), config.kiMoves)); });
        rowY += ROW_H;
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.ki_blasts_tip"));
        label(I18n.get("gui.dmz_ragnarok.dungeons.spawner.moveset"), 14, rowY + 5);
        movesetDropdown = dropdown(150, rowY, 132, options(optArr("moveset", MOVESET_KEYS)), clampIndex(config.moveset, MOVESET_KEYS));
        rowY += ROW_H;
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.moveset_tip"));
        label(I18n.get("gui.dmz_ragnarok.dungeons.spawner.ki_attacks"), 14, rowY + 4);
        btn(150, rowY, 132, GuiTheme.BUTTON_HEIGHT, Component.translatable(config.kiEnabled
                        ? "gui.dmz_ragnarok.dungeons.spawner.ki_attacks_on"
                        : "gui.dmz_ragnarok.dungeons.spawner.ki_attacks_off"),
                () -> { config.kiEnabled = !config.kiEnabled; applyFields(); rebuildWidgets(); });
        rowY += ROW_H;
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.ki_attacks_tip"));
        label(I18n.get("gui.dmz_ragnarok.dungeons.spawner.behavior"), 14, rowY + 5);
        behaviorDropdown = dropdown(150, rowY, 132, options(optArr("behavior", BEHAVIOR_KEYS)), clampIndex(config.behavior, BEHAVIOR_KEYS));
        rowY += ROW_H;
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.behavior_tip"));
    }

    private void spawnTab() {
        bf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.enabled"), config.enabled, () -> config.enabled = !config.enabled);
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.enabled_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.spawn_amount"), intStr(config.maxSpawns), v -> config.maxSpawns = Math.max(1, parseI(v, config.maxSpawns)));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.spawn_amount_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.cooldown_ticks"), intStr(config.cooldownTicks), v -> config.cooldownTicks = Math.max(1, parseI(v, config.cooldownTicks)));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.cooldown_ticks_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.wander_dist"), intStr(config.wanderDistance), v -> config.wanderDistance = Math.max(0, parseI(v, config.wanderDistance)));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.wander_dist_tip"));
        label(I18n.get("gui.dmz_ragnarok.dungeons.spawner.time_of_day"), 14, rowY + 5);
        timeDropdown = dropdown(150, rowY, 132, options(optArr("time", TIME_KEYS)), clampIndex(config.timeCondition, TIME_KEYS));
        rowY += ROW_H;
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.time_of_day_tip"));
        label(I18n.get("gui.dmz_ragnarok.dungeons.spawner.weather"), 14, rowY + 5);
        weatherDropdown = dropdown(150, rowY, 132, options(optArr("weather", WEATHER_KEYS)), clampIndex(config.weatherCondition, WEATHER_KEYS));
        rowY += ROW_H;
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.weather_tip"));
        label(I18n.get("gui.dmz_ragnarok.dungeons.spawner.redstone"), 14, rowY + 5);
        redstoneDropdown = dropdown(150, rowY, 132, options(optArr("redstone", REDSTONE_KEYS)), clampIndex(config.redstoneMode, REDSTONE_KEYS));
        rowY += ROW_H;
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.redstone_tip"));
    }

    private void bossTab() {
        bf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.boss_enabled"), config.bossEnabled, () -> config.bossEnabled = !config.bossEnabled);
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.boss_enabled_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.boss_chance"), fmt(config.bossChance), v -> config.bossChance = clampChance(parseFloat(v, config.bossChance)));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.boss_chance_tip"));
        // Characters in the list, same as the General tab's entity row.
        df(I18n.get("gui.dmz_ragnarok.dungeons.spawner.boss_entity"),
                net.shurui.shuruisutilities.ragnarok.RgNpcPicker.options(entityIds()),
                net.shurui.shuruisutilities.ragnarok.RgNpcPicker.value(config.bossEntityTypeId, config.bossRgModelId),
                v -> {
                    if (v.isBlank()) return;
                    config.bossEntityTypeId = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.entityOf(v);
                    config.bossRgModelId = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.modelOf(v);
                },
                v -> requestBossDefaults(net.shurui.shuruisutilities.ragnarok.RgNpcPicker.entityOf(v)));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.boss_entity_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.boss_name"), config.bossName, v -> config.bossName = v);
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.boss_name_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.health"), fmt(config.bossHealth), v -> config.bossHealth = parseFloat(v, config.bossHealth));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.boss_health_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.melee"), fmt(config.bossDamage), v -> config.bossDamage = parseFloat(v, config.bossDamage));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.boss_melee_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.ki_min"), intStr(config.bossKiDmgMin), v -> config.bossKiDmgMin = Math.max(0, parseI(v, config.bossKiDmgMin)));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.boss_ki_min_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.ki_max"), intStr(config.bossKiDmgMax), v -> config.bossKiDmgMax = Math.max(0, parseI(v, config.bossKiDmgMax)));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.boss_ki_max_tip"));
        label(I18n.get("gui.dmz_ragnarok.dungeons.spawner.ki_blasts"), 14, rowY + 4);
        btn(150, rowY, 132, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.dungeons.spawner.edit_ki_blasts", config.bossKiMoves.size()),
                () -> { applyFields(); this.minecraft.setScreen(
                        new KiMoveEntryListScreen(this, I18n.get("gui.dmz_ragnarok.dungeons.ki_list.subtitle_boss"), config.bossKiMoves)); });
        rowY += ROW_H;
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.boss_ki_blasts_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.scale"), fmt(config.bossScale), v -> config.bossScale = parseFloat(v, config.bossScale));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.boss_scale_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.saved_npc"), config.bossSavedNpcRef, v -> config.bossSavedNpcRef = v.trim());
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.boss_saved_npc_tip"));
        bf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.use_default_transform"), config.bossUseDefaultTransform,
                () -> config.bossUseDefaultTransform = !config.bossUseDefaultTransform);
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.boss_use_default_transform_tip"));
        transformRow(config.bossTransform, t -> config.bossTransform = t);
    }

    private void rewardsTab() {
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.tp_min"), intStr(config.tpMin), v -> config.tpMin = Math.max(0, parseI(v, config.tpMin)));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.tp_min_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.tp_max"), intStr(config.tpMax), v -> config.tpMax = Math.max(0, parseI(v, config.tpMax)));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.tp_max_tip"));
        // The Zeni balance reward is paid only with the key (the economy is private), so keyless the two fields are not
        // drawn at all. Their stored values are untouched and still saved, so a keyed server sees them unchanged.
        if (net.shurui.dev.sdu.api.ClientGate.key()) {
            tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.balance_min"), intStr(config.balMin), v -> config.balMin = Math.max(0, parseI(v, config.balMin)));
            tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.balance_min_tip"));
            tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.balance_max"), intStr(config.balMax), v -> config.balMax = Math.max(0, parseI(v, config.balMax)));
            tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.balance_max_tip"));
        }
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.kill_commands"), config.killCommands, v -> config.killCommands = v);
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.kill_commands_tip"));
    }

    private void dropsTab() {
        bf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.vanilla_drops"), config.vanillaDrops, () -> config.vanillaDrops = !config.vanillaDrops);
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.vanilla_drops_tip"));
        // the shared CustomDrop list editor (also drives the crate Loot tab): captions, rows, Add / Add-held.
        DropListEditor.build(this, config.drops, dropScroll, v -> { dropScroll = v; rebuildWidgets(); });
    }

    private void disguiseTab() {
        tf(I18n.get("gui.dmz_ragnarok.dungeons.spawner.disguise"), config.disguise, v -> config.disguise = v.trim());
        tip(I18n.get("gui.dmz_ragnarok.dungeons.spawner.disguise_tip"));
    }

    @Override
    protected void onExtraDropdown(DmzDropdown dropdown, int row) {
        if (dropdown == movesetDropdown) {
            config.moveset = row;
        } else if (dropdown == aiTierDropdown) {
            config.aiTier = row;
        } else if (dropdown == behaviorDropdown) {
            config.behavior = row;
        } else if (dropdown == timeDropdown) {
            config.timeCondition = row;
        } else if (dropdown == weatherDropdown) {
            config.weatherCondition = row;
        } else if (dropdown == redstoneDropdown) {
            config.redstoneMode = row;
        }
    }

    private void save() {
        applyFields();
        if (onSaved != null) {
            // floor-boss mode: the config is edited in place; hand control back to the parent, which persists it.
            onSaved.run();
            back();
            return;
        }
        SddNet.sendToServer(new SaveSpawnerPacket(pos, config));
        onClose();
    }

    // ask the server for the picked main entity's DMZ defaults; applied when the reply lands (see render)
    private void requestMainDefaults(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        pendingMainId = id;
        // already cached (same id re-picked)? apply now, otherwise request it
        if (!tryApplyMainDefaults()) {
            SddNet.sendToServer(new RequestNpcDefaultsPacket(id));
        }
    }

    // boss variant of requestMainDefaults
    private void requestBossDefaults(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        pendingBossId = id;
        if (!tryApplyBossDefaults()) {
            SddNet.sendToServer(new RequestNpcDefaultsPacket(id));
        }
    }

    // if the pending main id has cached defaults, overwrite the main stat fields and rebuild. returns true
    // if applied; leaves fields alone when there are no defaults for the id.
    private boolean tryApplyMainDefaults() {
        if (pendingMainId == null) {
            return false;
        }
        ClientNpcDefaults.Defaults d = ClientNpcDefaults.get(pendingMainId);
        if (d == null) {
            return false;
        }
        applyFields(); // grab any in-flight edits before we overwrite and rebuild
        config.maxHealth = (float) d.health();
        config.attackDamage = (float) d.melee();
        int ki = (int) Math.round(d.ki());
        config.kiDmgMin = ki;
        config.kiDmgMax = ki;
        config.kiPower = ki;
        config.kiEnabled = config.kiEnabled || ki > 0;
        config.aiTier = clampIndex(d.aiTier1Based() - 1, AI_TIER_KEYS); // DMZ is 1-based, we store 0-based (sdu re-adds the +1)
        pendingMainId = null;
        rebuildWidgets();
        return true;
    }

    // boss variant of tryApplyMainDefaults: fills the boss health/melee/ki fields
    private boolean tryApplyBossDefaults() {
        if (pendingBossId == null) {
            return false;
        }
        ClientNpcDefaults.Defaults d = ClientNpcDefaults.get(pendingBossId);
        if (d == null) {
            return false;
        }
        applyFields();
        config.bossHealth = (float) d.health();
        config.bossDamage = (float) d.melee();
        int ki = (int) Math.round(d.ki());
        config.bossKiDmgMin = ki;
        config.bossKiDmgMax = ki;
        config.bossKiPower = ki;
        pendingBossId = null;
        rebuildWidgets();
        return true;
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // a defaults reply may have landed since last frame; flush any pending fills before drawing
        int v = ClientNpcDefaults.version();
        if (v != seenDefaultsVersion) {
            seenDefaultsVersion = v;
            tryApplyMainDefaults();
            tryApplyBossDefaults();
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clampIndex(int i, String[] arr) {
        return i < 0 || i >= arr.length ? 0 : i;
    }

    // translate a "<group>.<suffix>" option-key array into localized display strings, preserving index order
    private static String[] optArr(String group, String[] keys) {
        String[] out = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            out[i] = I18n.get("gui.dmz_ragnarok.dungeons.opt." + group + "." + keys[i]);
        }
        return out;
    }

    private static float clampChance(float v) {
        return Math.max(0.0f, Math.min(100.0f, v));
    }

    private static float parseFloat(String s, float fallback) {
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String fmt(float v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Float.toString(v);
    }

    private static List<String> entityIds() {
        if (ENTITY_IDS == null) {
            List<String> ids = new ArrayList<>();
            for (var key : ForgeRegistries.ENTITY_TYPES.getKeys()) {
                ids.add(key.toString());
            }
            ids.sort(String::compareTo);
            ENTITY_IDS = ids;
        }
        return ENTITY_IDS;
    }
}
