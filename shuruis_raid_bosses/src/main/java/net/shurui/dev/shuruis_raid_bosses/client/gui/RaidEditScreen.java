package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.shurui.dev.shuruis_raid_bosses.client.GameRegistries;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_raid_bosses.network.RaidNet;
import net.shurui.dev.shuruis_raid_bosses.network.SaveDefPacket;
import net.shurui.dev.shuruis_raid_bosses.network.SetBoundsPacket;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef;
import net.shurui.dev.shuruis_raid_bosses.region.Region;

/**
 * The tabbed raid editor. Options are grouped into tabs (General, Arena, Scaling, Rules, Schedule,
 * Messages, Rewards) so a single screen isn't overwhelming. Built on the ported {@link FieldEditScreen}
 * toolkit: labelled fields, toggles, searchable dropdowns and hover tooltips.
 */
public class RaidEditScreen extends FieldEditScreen {
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final String[] TAB_KEYS = {
            "gui.dmz_ragnarok.raid.edit.tab_general", "gui.dmz_ragnarok.raid.edit.tab_type",
            "gui.dmz_ragnarok.raid.edit.tab_arena", "gui.dmz_ragnarok.raid.edit.tab_scaling",
            "gui.dmz_ragnarok.raid.edit.tab_boss", "gui.dmz_ragnarok.raid.edit.tab_rules",
            "gui.dmz_ragnarok.raid.edit.tab_schedule", "gui.dmz_ragnarok.raid.edit.tab_messages",
            "gui.dmz_ragnarok.raid.edit.tab_rewards"};

    private final RaidBossDef def;
    private int tab = 0;
    private DmzDropdown typeDropdown;

    public RaidEditScreen(RaidBossDef def, Screen parent) {
        super(Component.translatable("gui.dmz_ragnarok.raid.edit.title"), UI_W, UI_H, parent);
        this.def = def;
    }

    /** Persist the definition to the raid list it came from. */
    private void persist() {
        RaidNet.sendToServer(new SaveDefPacket(def.save()));
    }

    private static String[] resolvedTabs() {
        String[] out = new String[TAB_KEYS.length];
        for (int i = 0; i < TAB_KEYS.length; i++) out[i] = I18n.get(TAB_KEYS[i]);
        return out;
    }

    @Override
    protected void init() {
        super.init();
        clearFields();

        // The raid is a named entity: show its display name in the TOP LEFT of the panel (suite convention),
        // not centred under the logo where it collided with the tab bar. I18n.get(def.name) is a pass-through for
        // the owner display name; ColorCodes (applied at draw) renders its & codes.
        panelName = I18n.get(def.name);
        int contentTop = buildTabHeader(null, resolvedTabs(), tab, this::selectSection);
        int contentBottom = UI_H - 26;
        beginScrollBand(contentTop, contentBottom);
        rowY = contentTop - scroll;
        switch (tab) {
            case 0 -> generalTab();
            case 1 -> typeTab();
            case 2 -> arenaTab();
            case 3 -> scalingTab();
            case 4 -> bossTab();
            case 5 -> rulesTab();
            case 6 -> scheduleTab();
            case 7 -> messagesTab();
            case 8 -> rewardsTab();
            default -> { }
        }
        finishScrollBand(contentTop, contentBottom, rowY);

        // Save/Back are added AFTER the band clamp so they're pinned and never hidden by scrolling.
        int by = UI_H - 22;
        btn(UI_W / 2 - 104, by, 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.common.save_plain"), this::save);
        btn(UI_W / 2 + 4, by, 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.common.back"), this::back);
    }

    private void selectSection(int i) {
        if (tab != i) {
            applyFields();
            tab = i;
            scroll = 0;
            rebuildWidgets();
        }
    }

    private void generalTab() {
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.display_name"), def.name, v -> def.name = v);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.display_name_tip"));
        // The ragnarok characters are IN this list; see RgNpcPicker for why they cannot come from the registry.
        df(I18n.get("gui.dmz_ragnarok.raid.edit.host_entity"),
                net.shurui.shuruisutilities.ragnarok.RgNpcPicker.options(GameRegistries.entityIds()),
                net.shurui.shuruisutilities.ragnarok.RgNpcPicker.value(def.npcEntityType, def.npcModelId),
                v -> {
                    if (v.isBlank()) return;
                    def.npcEntityType = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.entityOf(v);
                    def.npcModelId = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.modelOf(v);
                });
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.host_entity_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.npc_name"), def.npcName, v -> def.npcName = v);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.npc_name_tip"));
        df(I18n.get("gui.dmz_ragnarok.raid.edit.boss_entity"),
                net.shurui.shuruisutilities.ragnarok.RgNpcPicker.options(GameRegistries.entityIds()),
                net.shurui.shuruisutilities.ragnarok.RgNpcPicker.value(def.bossEntityType, def.bossModelId),
                v -> {
                    if (v.isBlank()) return;
                    def.bossEntityType = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.entityOf(v);
                    def.bossModelId = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.modelOf(v);
                    // Seed Duke Snipperjack's default track into the editable music field when his boss is first
                    // chosen and none is set; the operator can change or clear it. See RiftEncounterScreen.
                    if (net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef.SNIPPERJACK_ENTITY.equals(def.bossEntityType)
                            && (def.bossMusic == null || def.bossMusic.isBlank())) {
                        def.bossMusic = net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef.SNIPPERJACK_MUSIC;
                        def.spawnOnObsidian = true;
                    }
                });
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.boss_entity_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.boss_name"), def.bossName, v -> def.bossName = v);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.boss_name_tip"));
    }

    private void typeTab() {
        label(I18n.get("gui.dmz_ragnarok.raid.edit.raid_type"), 14, rowY + 5);
        java.util.List<net.minecraft.network.chat.Component> opts = new java.util.ArrayList<>();
        for (net.shurui.dev.shuruis_raid_bosses.raid.RaidType t : net.shurui.dev.shuruis_raid_bosses.raid.RaidType.values()) {
            opts.add(t.display());
        }
        typeDropdown = dropdown(150, rowY, 132, opts, def.raidType.ordinal());
        rowY += ROW_H;
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.raid_type_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.category"), def.category, v -> def.category = v.isBlank() ? "General" : v);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.category_tip"));
        bf(I18n.get("gui.dmz_ragnarok.raid.edit.show_in_npc"), def.showInNpcMenu, () -> def.showInNpcMenu = !def.showInNpcMenu);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.show_in_npc_tip"));
        bf(I18n.get("gui.dmz_ragnarok.raid.edit.event_only"), def.eventOnly, () -> def.eventOnly = !def.eventOnly);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.event_only_tip"));
        rowY += 4;

        // Random selection: when the pool is non-empty this raid stops running its own content and rolls one of the
        // pooled raids into ITS arena instead. Offered above the per-type rows because it overrides all of them.
        btn(20, rowY, 260, 16,
                Component.translatable("gui.dmz_ragnarok.raid.edit.random_pool", def.randomPool.size()), () -> {
                    applyFields();
                    java.util.List<net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef> known =
                            (this.parent instanceof RaidListScreen list)
                                    ? list.knownDefs()
                                    : java.util.List.of(def);
                    this.minecraft.setScreen(new RandomPoolListScreen(this, def.randomPool, known, def.id));
                });
        rowY += 18;
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.random_pool_tip"));
        rowY += 4;

        switch (def.raidType) {
            case PARALLEL_QUEST -> {
                btn(20, rowY, 260, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.raid.edit.enemies", def.enemies.size()), () -> {
                    applyFields();
                    this.minecraft.setScreen(new EnemyWaveListScreen(this, def.enemies));
                });
                rowY += 20;
                tf(I18n.get("gui.dmz_ragnarok.raid.edit.mvp_weight"), dbl(def.mvpKillWeight), v -> def.mvpKillWeight = parseD(v, def.mvpKillWeight));
                tip(I18n.get("gui.dmz_ragnarok.raid.edit.mvp_weight_tip"));
                rowY += 2;
                labelWrapped(I18n.get("gui.dmz_ragnarok.raid.edit.parallel_note"), 14, rowY);
            }
            case BOSS_RUSH -> {
                btn(20, rowY, 260, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.raid.edit.boss_stages", def.rushStages.size()), () -> {
                    applyFields();
                    this.minecraft.setScreen(new BossStageListScreen(this, def.rushStages));
                });
                rowY += 22;
                labelWrapped(I18n.get("gui.dmz_ragnarok.raid.edit.rush_note"), 14, rowY);
            }
            default -> labelWrapped(I18n.get("gui.dmz_ragnarok.raid.edit.standard_note"), 14, rowY);
        }
    }

    @Override
    protected void onExtraDropdown(DmzDropdown dropdown, int row) {
        if (dropdown == typeDropdown && row >= 0) {
            applyFields();
            def.raidType = net.shurui.dev.shuruis_raid_bosses.raid.RaidType.byOrdinal(row);
            rebuildWidgets();
        }
    }

    private void arenaTab() {
        boundsRow(I18n.get("gui.dmz_ragnarok.raid.edit.bounds_arena"), def.arena, "arena", r -> def.arena = r);
        boundsRow(I18n.get("gui.dmz_ragnarok.raid.edit.bounds_player_spawn"), def.playerSpawn, "playerspawn",
                r -> def.playerSpawn = r);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.bounds_player_spawn_tip"));
        rowY = labelWrapped(I18n.get("gui.dmz_ragnarok.raid.edit.arena_note1"), 14, rowY + 4);
        rowY = labelWrapped(I18n.get("gui.dmz_ragnarok.raid.edit.arena_note2"), 14, rowY + 2);
    }

    private void scalingTab() {
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.base_health"), dbl(def.baseHealth), v -> def.baseHealth = parseD(v, def.baseHealth));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.base_health_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.health_per_player"), dbl(def.healthPerPlayer), v -> def.healthPerPlayer = parseD(v, def.healthPerPlayer));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.health_per_player_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.max_health_cap"), dbl(def.maxHealthCap), v -> def.maxHealthCap = parseD(v, def.maxHealthCap));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.max_health_cap_tip"));
        bf(I18n.get("gui.dmz_ragnarok.raid.edit.scale_damage"), def.scaleDamage, () -> def.scaleDamage = !def.scaleDamage);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.scale_damage_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.damage_per_player"), dbl(def.damagePerPlayer), v -> def.damagePerPlayer = parseD(v, def.damagePerPlayer));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.damage_per_player_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.max_damage_pct"), dbl(def.maxDamagePercentPerHit),
                v -> def.maxDamagePercentPerHit = parseD(v, def.maxDamagePercentPerHit));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.max_damage_pct_tip"));
    }

    private void bossTab() {
        rowY = labelWrapped(I18n.get("gui.dmz_ragnarok.raid.edit.boss_note1"), 14, rowY) + 2;
        rowY = labelWrapped(I18n.get("gui.dmz_ragnarok.raid.edit.boss_note2"), 14, rowY) + 2;
        bf(I18n.get("gui.dmz_ragnarok.raid.edit.boss_finale"), def.parallelBoss, () -> def.parallelBoss = !def.parallelBoss);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.boss_finale_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.melee_damage"), dbl(def.baseMeleeDamage), v -> def.baseMeleeDamage = parseD(v, def.baseMeleeDamage));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.melee_damage_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.defense"), dbl(def.baseDefense), v -> def.baseDefense = parseD(v, def.baseDefense));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.defense_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.ki_damage"), dbl(def.kiBlastDamage), v -> def.kiBlastDamage = parseD(v, def.kiBlastDamage));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.ki_damage_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.move_speed"), dbl(def.moveSpeed), v -> def.moveSpeed = parseD(v, def.moveSpeed));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.move_speed_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.boss_scale"), dbl(def.bossScale), v -> def.bossScale = parseD(v, def.bossScale));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.boss_scale_tip"));
        df(I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier"), java.util.List.of(
                I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_1"),
                I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_2"),
                I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_3")), aiTierName(def.aiTier), v -> def.aiTier = aiTierIndex(v));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_tip"));
        rowY += 4;
        btn(20, rowY, 260, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.raid.edit.ki_moves", def.kiMoves.size()), () -> {
            applyFields();
            this.minecraft.setScreen(new KiMoveListScreen(this, def.kiMoves));
        });
        rowY += 20;
        labelWrapped(I18n.get("gui.dmz_ragnarok.raid.edit.ki_moves_note"), 14, rowY);

        // The NPCs that fight on the players' side from the start. The fight has understood allies for a while
        // (they spawn, they are steered, they are kept out of the victory count, a stage can turn them); this is
        // the button that was missing, so they no longer have to be written as tokens by hand.
        rowY += 16;
        btn(20, rowY, 260, GuiTheme.BUTTON_HEIGHT,
                Component.translatable("gui.dmz_ragnarok.raid.edit.allies", def.allies.size()), () -> {
                    applyFields();
                    this.minecraft.setScreen(new AllyListScreen(this, def.allies, false));
                });
        rowY += 20;
        labelWrapped(I18n.get("gui.dmz_ragnarok.raid.edit.allies_note"), 14, rowY);

        rowY += 4;
        bf(I18n.get("gui.dmz_ragnarok.raid.edit.use_default_transform"), def.useDefaultTransform, () -> def.useDefaultTransform = !def.useDefaultTransform);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.use_default_transform_tip"));
        rowY += 4;
        if (net.shurui.dev.shuruis_raid_bosses.compat.sdu.SduTransformCompat.available()) {
            int forms = def.bossTransform.getList("forms", net.minecraft.nbt.Tag.TAG_COMPOUND).size();
            btn(20, rowY, 260, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.raid.edit.transformations", forms), () -> {
                applyFields();
                net.shurui.dev.shuruis_raid_bosses.compat.sdu.SduTransformCompat.openEditor(
                        this, def.bossTransform, t -> def.bossTransform = t);
            });
            rowY += 20;
            labelWrapped(I18n.get("gui.dmz_ragnarok.raid.edit.transformations_note"), 14, rowY);
        } else {
            btn(20, rowY, 260, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.raid.edit.transformations_no_sdu"), () -> {}).active = false;
            rowY += 20;
            labelWrapped(I18n.get("gui.dmz_ragnarok.raid.edit.transformations_no_sdu_note"), 14, rowY);
        }
    }

    // aiTier is DMZ's 1-based id: 1=SIMPLE 2=TACTICAL 3=ADVANCED; 0/other = unset (keep default). The dropdown
    // labels are localized, so map by comparing against the resolved strings.
    private static String aiTierName(int tier) {
        return switch (tier) {
            case 1 -> I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_1");
            case 2 -> I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_2");
            case 3 -> I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_3");
            default -> "";
        };
    }

    private static int aiTierIndex(String name) {
        if (name.equals(I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_1"))) return 1;
        if (name.equals(I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_2"))) return 2;
        if (name.equals(I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_3"))) return 3;
        return 0;
    }

    private void rulesTab() {
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.signup_minutes"), str(def.signupMinutes), v -> def.signupMinutes = parseI(v, def.signupMinutes));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.signup_minutes_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.reminder"), str(def.signupReminderSeconds), v -> def.signupReminderSeconds = parseI(v, def.signupReminderSeconds));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.reminder_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.min_participants"), str(def.minParticipants), v -> def.minParticipants = parseI(v, def.minParticipants));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.min_participants_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.max_participants"), str(def.maxParticipants), v -> def.maxParticipants = parseI(v, def.maxParticipants));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.max_participants_tip"));
        bf(I18n.get("gui.dmz_ragnarok.raid.edit.require_character"), def.requireCharacter, () -> def.requireCharacter = !def.requireCharacter);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.require_character_tip"));
        bf(I18n.get("gui.dmz_ragnarok.raid.edit.heal_on_start"), def.healOnStart, () -> def.healOnStart = !def.healOnStart);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.heal_on_start_tip"));
        bf(I18n.get("gui.dmz_ragnarok.raid.edit.pvp_arena"), def.pvpInArena, () -> def.pvpInArena = !def.pvpInArena);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.pvp_arena_tip"));
        bf(I18n.get("gui.dmz_ragnarok.raid.edit.prevent_reentry"), def.preventReentry, () -> def.preventReentry = !def.preventReentry);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.prevent_reentry_tip"));
        bf(I18n.get("gui.dmz_ragnarok.raid.edit.vanilla_drops"), def.vanillaDrops, () -> def.vanillaDrops = !def.vanillaDrops);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.vanilla_drops_tip"));
        bf(I18n.get("gui.dmz_ragnarok.raid.edit.use_titles"), def.useTitles, () -> def.useTitles = !def.useTitles);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.use_titles_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.countdown"), str(def.countdownSeconds), v -> def.countdownSeconds = parseI(v, def.countdownSeconds));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.countdown_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.time_limit"), str(def.fightTimeLimit), v -> def.fightTimeLimit = parseI(v, def.fightTimeLimit));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.time_limit_tip"));
    }

    private void scheduleTab() {
        bf(I18n.get("gui.dmz_ragnarok.raid.edit.auto_schedule"), def.scheduleEnabled, () -> def.scheduleEnabled = !def.scheduleEnabled);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.auto_schedule_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.repeat_every"), str(def.scheduleIntervalMinutes), v -> def.scheduleIntervalMinutes = parseI(v, def.scheduleIntervalMinutes));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.repeat_every_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.daily_times"), String.join(", ", def.scheduleTimes), this::setScheduleTimes);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.daily_times_tip"));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.daily_times_tip2"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.hour"), str(def.scheduleHour), v -> def.scheduleHour = parseI(v, def.scheduleHour));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.hour_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.minute"), str(def.scheduleMinute), v -> def.scheduleMinute = parseI(v, def.scheduleMinute));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.minute_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.day_of_week"), str(def.scheduleDayOfWeek), v -> def.scheduleDayOfWeek = parseI(v, def.scheduleDayOfWeek));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.day_of_week_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.day_of_month"), str(def.scheduleDayOfMonth), v -> def.scheduleDayOfMonth = parseI(v, def.scheduleDayOfMonth));
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.day_of_month_tip"));
    }

    private void messagesTab() {
        df(I18n.get("gui.dmz_ragnarok.raid.edit.announce_sound"), GameRegistries.soundIds(), def.announceSound, v -> def.announceSound = v);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.announce_sound_tip"));
        df(I18n.get("gui.dmz_ragnarok.raid.edit.music"), GameRegistries.soundIds(), def.bossMusic, v -> def.bossMusic = v);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.music_tip"));
        bf(I18n.get("gui.dmz_ragnarok.raid.edit.spawn_on_block"), def.spawnOnObsidian,
                () -> def.spawnOnObsidian = !def.spawnOnObsidian);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.spawn_on_block_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.spawn_block"), def.spawnAnchorBlock, v -> def.spawnAnchorBlock = v);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.spawn_block_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.msg_signup_open"), def.msgSignupOpen, v -> def.msgSignupOpen = v);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.msg_signup_open_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.msg_starting"), def.msgStarting, v -> def.msgStarting = v);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.msg_starting_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.msg_boss_spawn"), def.msgBossSpawn, v -> def.msgBossSpawn = v);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.msg_boss_spawn_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.msg_victory"), def.msgVictory, v -> def.msgVictory = v);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.msg_victory_tip"));
        tf(I18n.get("gui.dmz_ragnarok.raid.edit.msg_timeout"), def.msgTimeout, v -> def.msgTimeout = v);
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.msg_timeout_tip"));
    }

    private void rewardsTab() {
        rowY = labelWrapped(I18n.get("gui.dmz_ragnarok.raid.edit.rewards_header"), 14, rowY) + 8;
        btn(20, rowY, 260, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.raid.edit.rewards_participant", def.participantRewards.size()),
                () -> this.minecraft.setScreen(new RewardListScreen(this, "gui.dmz_ragnarok.raid.reward_list.participant", def.participantRewards)));
        rowY += 20;
        btn(20, rowY, 260, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.raid.edit.rewards_top", def.topRewards.size()),
                () -> this.minecraft.setScreen(new RewardListScreen(this, "gui.dmz_ragnarok.raid.reward_list.top", def.topRewards)));
        rowY += 20;
        btn(20, rowY, 260, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.raid.edit.rewards_runner_up", def.runnerUpRewards.size()),
                () -> this.minecraft.setScreen(new RewardListScreen(this, "gui.dmz_ragnarok.raid.reward_list.runner_up", def.runnerUpRewards)));
        rowY += 22;
        rowY = labelWrapped(I18n.get("gui.dmz_ragnarok.raid.edit.rewards_note1"), 14, rowY) + 2;
        rowY = labelWrapped(I18n.get("gui.dmz_ragnarok.raid.edit.rewards_note2"), 14, rowY) + 2;
        labelWrapped(I18n.get("gui.dmz_ragnarok.raid.edit.rewards_note3"), 14, rowY);
    }

    private void boundsRow(String name, Region region, String key, java.util.function.Consumer<Region> setter) {
        String status = region == null ? I18n.get("gui.dmz_ragnarok.raid.edit.bounds_not_set")
                : I18n.get("gui.dmz_ragnarok.raid.edit.bounds_range", region.min().toShortString(), region.max().toShortString());
        label(I18n.get("gui.dmz_ragnarok.raid.edit.bounds_row", name, status), 14, rowY + 2);
        // NOTE: this button is the FIRST widget on the Arena tab, so its Y sits at the very top of the
        // scroll band (rowY == contentTop when scroll == 0). clampContentWidgets() hides any widget whose
        // getY() is strictly less than the band top, so a "rowY - 1" here would place it one pixel above the
        // band and make the button vanish. Keep it at rowY (== contentTop) so it always clamps visible.
        btn(212, rowY, 76, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.raid.edit.set_via_we"), () -> setBounds(key));
        rowY += ROW_H;

        // Manual coordinate entry for anyone not using WorldEdit: type the two corners and press "Set manually".
        BlockPos mn = region == null ? null : region.min();
        BlockPos mx = region == null ? null : region.max();
        label(I18n.get("gui.dmz_ragnarok.raid.edit.bounds_min"), 14, rowY + 2);
        EditBox minX = coordBox(42, mn == null ? "" : Integer.toString(mn.getX()), "x");
        EditBox minY = coordBox(90, mn == null ? "" : Integer.toString(mn.getY()), "y");
        EditBox minZ = coordBox(138, mn == null ? "" : Integer.toString(mn.getZ()), "z");
        rowY += ROW_H;
        label(I18n.get("gui.dmz_ragnarok.raid.edit.bounds_max"), 14, rowY + 2);
        EditBox maxX = coordBox(42, mx == null ? "" : Integer.toString(mx.getX()), "x");
        EditBox maxY = coordBox(90, mx == null ? "" : Integer.toString(mx.getY()), "y");
        EditBox maxZ = coordBox(138, mx == null ? "" : Integer.toString(mx.getZ()), "z");
        // Region keeps its own dimension when it already exists; otherwise use the dimension the editor is in.
        ResourceKey<Level> dim = region != null ? region.dimension()
                : this.minecraft.player.level().dimension();
        btn(212, rowY - 1, 76, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.raid.edit.set_manually"),
                () -> setBoundsManual(setter, dim, minX, minY, minZ, maxX, maxY, maxZ));
        rowY += ROW_H;
        tip(I18n.get("gui.dmz_ragnarok.raid.edit.bounds_tip", name));
    }

    /** A compact numeric coordinate box at the current row, pre-filled and hinted (x/y/z). */
    private EditBox coordBox(int x, String value, String hint) {
        EditBox b = field(x, rowY + 1, 44, value);
        b.setHint(Component.literal("§8" + hint));
        return b;
    }

    /** Build a Region from the six manually-typed corner coordinates and save it, if all six parse. */
    private void setBoundsManual(java.util.function.Consumer<Region> setter, ResourceKey<Level> dim,
            EditBox mnx, EditBox mny, EditBox mnz, EditBox mxx, EditBox mxy, EditBox mxz) {
        applyFields();
        Integer a = coordVal(mnx), b = coordVal(mny), c = coordVal(mnz);
        Integer d = coordVal(mxx), e = coordVal(mxy), f = coordVal(mxz);
        if (a == null || b == null || c == null || d == null || e == null || f == null)
            return; // incomplete/invalid input - leave the current bounds untouched
        setter.accept(new Region(dim, new BlockPos(a, b, c), new BlockPos(d, e, f)));
        persist();
        rebuildWidgets();
    }

    private static Integer coordVal(EditBox box) {
        try {
            return Integer.valueOf(box.getValue().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void setBounds(String key) {
        applyFields();
        persist();
        RaidNet.sendToServer(new SetBoundsPacket(def.id, key));
    }

    private void save() {
        applyFields();
        persist();
        back();
    }

    /** Called from the network layer when the server captured a WorldEdit selection for this def. */
    /**
     * Take the regions back off the definition the server just re-saved, and redraw.
     *
     * <p>EVERY region, not just the arena. playerSpawn was added after this method was written and never added
     * to it, so setting it from a WorldEdit selection did nothing visible: the server stored it and persisted it
     * quite happily, but the copy the editor is holding still had the old value, so the row kept reading "not
     * set" and its six coordinate boxes stayed empty.
     *
     * <p>That was the visible half. The damaging half is that this screen saves the copy it is holding: pressing
     * Save after setting a player spawn sent the STALE def back, and the region the server had just stored was
     * overwritten with nothing. So the selection did not merely fail to show, it was thrown away by the next
     * save. Anything region-shaped added to RaidBossDef in future has to be copied here too.
     */
    public void onBoundsUpdated(String defId, CompoundTag defNbt) {
        if (!def.id.equalsIgnoreCase(defId)) return;
        RaidBossDef updated = RaidBossDef.load(defNbt);
        def.arena = updated.arena;
        def.playerSpawn = updated.playerSpawn;
        rebuildWidgets();
    }

    /** Parse a comma-separated HH:MM list, keeping only well-formed 00:00-23:59 entries (malformed dropped). */
    private void setScheduleTimes(String raw) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String v = part.trim();
                if (v.isEmpty()) continue;
                int colon = v.indexOf(':');
                if (colon <= 0 || colon >= v.length() - 1) continue;
                try {
                    int h = Integer.parseInt(v.substring(0, colon).trim());
                    int m = Integer.parseInt(v.substring(colon + 1).trim());
                    if (h < 0 || h > 23 || m < 0 || m > 59) continue;
                    out.add(String.format("%02d:%02d", h, m));
                } catch (NumberFormatException ignored) {
                    // drop malformed entry
                }
            }
        }
        def.scheduleTimes = out;
    }

    private static String str(int v) {
        return Integer.toString(v);
    }
}
