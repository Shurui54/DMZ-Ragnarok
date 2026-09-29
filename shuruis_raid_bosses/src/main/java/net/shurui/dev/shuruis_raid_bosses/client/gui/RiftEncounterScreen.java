package net.shurui.dev.shuruis_raid_bosses.client.gui;

import java.util.List;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import net.shurui.dev.shuruis_raid_bosses.client.GameRegistries;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidType;
import net.shurui.dev.shuruis_raid_bosses.rift.RiftDef;

/**
 * The fight on the other side of a tear, edited on its own terms.
 *
 * <p>This used to be the raid editor pointed at the rift's definition. That worked, because the definition IS a raid
 * definition, but it asked the operator about a great deal that a tear does not have: an arena to draw, a sign-up
 * window and its reminders, a participant range, a schedule, a host NPC to talk to, and five broadcast templates the
 * tear never sends (a tear run is silent; the rift does the talking). Worse, several of those did nothing at all
 * here: the arena and the player spawn are supplied per run by the tear, and the roster is pinned to one.
 *
 * <p>So this screen carries only what a tear actually uses, in the order somebody building one thinks about it: the
 * boss, then how the fight goes, then who fights ALONGSIDE the player, then what they get for winning. Everything
 * deeper is still the shared list editors (stages, waves, friendlies, ki moves, rewards), because those edit token
 * lists rather than raids and are no more "the raid GUI" than a text field is.
 */
public class RiftEncounterScreen extends FieldEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final String[] TAB_KEYS = {
            "gui.dmz_ragnarok.rift.encounter.tab_boss",
            "gui.dmz_ragnarok.rift.encounter.tab_fight",
            "gui.dmz_ragnarok.rift.encounter.tab_friendlies",
            "gui.dmz_ragnarok.rift.encounter.tab_rewards"};

    private final RiftDef rift;
    private final RaidBossDef def;
    private final Runnable onSave;
    private int tab;

    public RiftEncounterScreen(Screen parent, RiftDef rift, Runnable onSave) {
        super(Component.translatable("gui.dmz_ragnarok.rift.encounter.title"), UI_W, UI_H, parent);
        this.rift = rift;
        this.def = rift.encounter;
        this.onSave = onSave;
    }

    private static String[] resolvedTabs() {
        String[] out = new String[TAB_KEYS.length];
        for (int i = 0; i < TAB_KEYS.length; i++) {
            out[i] = I18n.get(TAB_KEYS[i]);
        }
        return out;
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        panelName = I18n.get(rift.name);
        int contentTop = buildTabHeader(null, resolvedTabs(), tab, this::selectSection);
        int contentBottom = UI_H - 26;
        beginScrollBand(contentTop, contentBottom);
        rowY = contentTop - scroll;
        switch (tab) {
            case 0 -> bossTab();
            case 1 -> fightTab();
            case 2 -> friendliesTab();
            case 3 -> rewardsTab();
            default -> { }
        }
        finishScrollBand(contentTop, contentBottom, rowY);

        int by = UI_H - 22;
        btn(UI_W / 2 - 104, by, 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.raid.common.save_plain"), this::save);
        btn(UI_W / 2 + 4, by, 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.raid.common.back"), this::back);
    }

    private void selectSection(int i) {
        if (tab != i) {
            applyFields();
            tab = i;
            scroll = 0;
            rebuildWidgets();
        }
    }

    /** What the player is fighting. */
    private void bossTab() {
        // The ragnarok characters are IN this list, each its own row, because they all share one entity type and the
        // registry can only offer that one.
        df(I18n.get("gui.dmz_ragnarok.rift.encounter.boss_entity"),
                net.shurui.shuruisutilities.ragnarok.RgNpcPicker.options(GameRegistries.entityIds()),
                net.shurui.shuruisutilities.ragnarok.RgNpcPicker.value(def.bossEntityType, def.bossModelId),
                v -> {
                    if (v.isBlank()) {
                        return;
                    }
                    def.bossEntityType = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.entityOf(v);
                    def.bossModelId = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.modelOf(v);
                    // Seed Duke Snipperjack's spooky loop into the editable music field the first time his boss
                    // is chosen and no track is set. A default copied into a field the operator can change or
                    // clear, never a boss to track lookup at play time.
                    if (RaidBossDef.SNIPPERJACK_ENTITY.equals(def.bossEntityType)
                            && (def.bossMusic == null || def.bossMusic.isBlank())) {
                        def.bossMusic = RaidBossDef.SNIPPERJACK_MUSIC;
                        // He is a set-piece boss meant to be seated in the builder's scene, so default his spawn
                        // to sit on obsidian too. Both are editable defaults, never a play-time boss lookup.
                        def.spawnOnObsidian = true;
                    }
                });
        tip(I18n.get("gui.dmz_ragnarok.rift.encounter.boss_entity_tip"));
        tf(I18n.get("gui.dmz_ragnarok.rift.encounter.boss_name"), def.bossName, v -> def.bossName = v);
        tip(I18n.get("gui.dmz_ragnarok.rift.encounter.boss_name_tip"));
        // baseHealth ALONE, with no per-player term: a tear is one fighter, so the scaling fields a raid carries
        // would only ever multiply by one and are left out rather than shown as dead numbers.
        tf(I18n.get("gui.dmz_ragnarok.rift.encounter.health"), str(def.baseHealth),
                v -> def.baseHealth = parseD(v, def.baseHealth));
        tip(I18n.get("gui.dmz_ragnarok.rift.encounter.health_tip"));
        tf(I18n.get("gui.dmz_ragnarok.rift.encounter.melee"), str(def.baseMeleeDamage),
                v -> def.baseMeleeDamage = parseD(v, def.baseMeleeDamage));
        tip(I18n.get("gui.dmz_ragnarok.rift.encounter.melee_tip"));
        tf(I18n.get("gui.dmz_ragnarok.rift.encounter.ki"), str(def.kiBlastDamage),
                v -> def.kiBlastDamage = parseD(v, def.kiBlastDamage));
        tip(I18n.get("gui.dmz_ragnarok.rift.encounter.ki_tip"));
        tf(I18n.get("gui.dmz_ragnarok.rift.encounter.defense"), str(def.baseDefense),
                v -> def.baseDefense = parseD(v, def.baseDefense));
        tip(I18n.get("gui.dmz_ragnarok.rift.encounter.defense_tip"));
        tf(I18n.get("gui.dmz_ragnarok.rift.encounter.scale"), str(def.bossScale),
                v -> def.bossScale = parseD(v, def.bossScale));
        tip(I18n.get("gui.dmz_ragnarok.rift.encounter.scale_tip"));
        tf(I18n.get("gui.dmz_ragnarok.rift.encounter.battle_power"), String.valueOf(def.battlePower),
                v -> def.battlePower = parseI(v, def.battlePower));
        tip(I18n.get("gui.dmz_ragnarok.rift.encounter.battle_power_tip"));
        df(I18n.get("gui.dmz_ragnarok.rift.encounter.ai_tier"), List.of(
                        I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_1"),
                        I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_2"),
                        I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_3")),
                aiTierName(def.aiTier), v -> def.aiTier = aiTierIndex(v));
        tip(I18n.get("gui.dmz_ragnarok.rift.encounter.ai_tier_tip"));

        rowY += 4;
        transformRow();
    }

    /** How the fight goes: its shape, its phases and its ki. */
    private void fightTab() {
        df(I18n.get("gui.dmz_ragnarok.rift.encounter.type"), List.of(
                        I18n.get("gui.dmz_ragnarok.rift.encounter.type_standard"),
                        I18n.get("gui.dmz_ragnarok.rift.encounter.type_parallel"),
                        I18n.get("gui.dmz_ragnarok.rift.encounter.type_rush")),
                typeName(def.raidType), v -> def.raidType = typeOf(v));
        tip(I18n.get("gui.dmz_ragnarok.rift.encounter.type_tip"));
        tf(I18n.get("gui.dmz_ragnarok.rift.encounter.countdown"), String.valueOf(def.countdownSeconds),
                v -> def.countdownSeconds = parseI(v, def.countdownSeconds));
        tip(I18n.get("gui.dmz_ragnarok.rift.encounter.countdown_tip"));
        tf(I18n.get("gui.dmz_ragnarok.rift.encounter.time_limit"), String.valueOf(def.fightTimeLimit),
                v -> def.fightTimeLimit = parseI(v, def.fightTimeLimit));
        tip(I18n.get("gui.dmz_ragnarok.rift.encounter.time_limit_tip"));
        df(I18n.get("gui.dmz_ragnarok.rift.encounter.music"), GameRegistries.soundIds(), def.bossMusic,
                v -> def.bossMusic = v);
        tip(I18n.get("gui.dmz_ragnarok.rift.encounter.music_tip"));
        bf(I18n.get("gui.dmz_ragnarok.rift.encounter.spawn_on_block"), def.spawnOnObsidian,
                () -> def.spawnOnObsidian = !def.spawnOnObsidian);
        tip(I18n.get("gui.dmz_ragnarok.rift.encounter.spawn_on_block_tip"));
        tf(I18n.get("gui.dmz_ragnarok.rift.encounter.spawn_block"), def.spawnAnchorBlock,
                v -> def.spawnAnchorBlock = v);
        tip(I18n.get("gui.dmz_ragnarok.rift.encounter.spawn_block_tip"));
        bf(I18n.get("gui.dmz_ragnarok.rift.encounter.heal_on_start"), def.healOnStart,
                () -> def.healOnStart = !def.healOnStart);
        tip(I18n.get("gui.dmz_ragnarok.rift.encounter.heal_on_start_tip"));
        bf(I18n.get("gui.dmz_ragnarok.rift.encounter.vanilla_drops"), def.vanillaDrops,
                () -> def.vanillaDrops = !def.vanillaDrops);
        tip(I18n.get("gui.dmz_ragnarok.rift.encounter.vanilla_drops_tip"));

        rowY += 4;
        listButton("gui.dmz_ragnarok.rift.encounter.stages", def.rushStages.size(),
                () -> this.minecraft.setScreen(new BossStageListScreen(this, def.rushStages)));
        rowY = labelWrapped(I18n.get("gui.dmz_ragnarok.rift.encounter.stages_note"), 14, rowY) + 4;
        listButton("gui.dmz_ragnarok.rift.encounter.waves", def.enemies.size(),
                () -> this.minecraft.setScreen(new EnemyWaveListScreen(this, def.enemies)));
        rowY = labelWrapped(I18n.get("gui.dmz_ragnarok.rift.encounter.waves_note"), 14, rowY) + 4;
        listButton("gui.dmz_ragnarok.rift.encounter.ki_moves", def.kiMoves.size(),
                () -> this.minecraft.setScreen(new KiMoveListScreen(this, def.kiMoves)));
    }

    /** Who fights ALONGSIDE the player. */
    private void friendliesTab() {
        listButton("gui.dmz_ragnarok.rift.encounter.friendlies", def.allies.size(),
                () -> this.minecraft.setScreen(new AllyListScreen(this, def.allies, false)));
        rowY = labelWrapped(I18n.get("gui.dmz_ragnarok.rift.encounter.friendlies_note1"), 14, rowY) + 2;
        rowY = labelWrapped(I18n.get("gui.dmz_ragnarok.rift.encounter.friendlies_note2"), 14, rowY) + 2;
        labelWrapped(I18n.get("gui.dmz_ragnarok.rift.encounter.friendlies_note3"), 14, rowY);
    }

    /** What the fighter walks out with. */
    private void rewardsTab() {
        listButton("gui.dmz_ragnarok.rift.encounter.rewards", def.participantRewards.size(),
                () -> this.minecraft.setScreen(new RewardListScreen(
                        this, "gui.dmz_ragnarok.raid.reward_list.participant", def.participantRewards)));
        rowY = labelWrapped(I18n.get("gui.dmz_ragnarok.rift.encounter.rewards_note"), 14, rowY);
    }

    /** The transformation chain, when sdu is present to edit one. */
    private void transformRow() {
        if (net.shurui.dev.shuruis_raid_bosses.compat.sdu.SduTransformCompat.available()) {
            int forms = def.bossTransform.getList("forms", net.minecraft.nbt.Tag.TAG_COMPOUND).size();
            btn(20, rowY, 260, GuiTheme.BUTTON_HEIGHT,
                    Component.translatable("gui.dmz_ragnarok.raid.edit.transformations", forms), () -> {
                        applyFields();
                        net.shurui.dev.shuruis_raid_bosses.compat.sdu.SduTransformCompat.openEditor(
                                this, def.bossTransform, t -> def.bossTransform = t);
                    });
            rowY += 20;
            labelWrapped(I18n.get("gui.dmz_ragnarok.rift.encounter.transformations_note"), 14, rowY);
        } else {
            btn(20, rowY, 260, GuiTheme.BUTTON_HEIGHT,
                    Component.translatable("gui.dmz_ragnarok.raid.edit.transformations_no_sdu"), () -> { }).active = false;
            rowY += 20;
            labelWrapped(I18n.get("gui.dmz_ragnarok.raid.edit.transformations_no_sdu_note"), 14, rowY);
        }
    }

    /** A full-width button that opens one of the shared list editors, with its current count. */
    private void listButton(String key, int count, Runnable onPress) {
        btn(20, rowY, 260, GuiTheme.BUTTON_HEIGHT, Component.translatable(key, count), () -> {
            applyFields();
            onPress.run();
        });
        rowY += 20;
    }

    private void save() {
        applyFields();
        // A tear is one fighter, whatever this screen was given: the run pins the roster to one anyway, and leaving
        // a stale range on the definition would only mislead whoever reads it next.
        def.minParticipants = 1;
        def.maxParticipants = 1;
        if (onSave != null) {
            onSave.run();
        }
        back();
    }

    private static String typeName(RaidType type) {
        return switch (type) {
            case PARALLEL_QUEST -> I18n.get("gui.dmz_ragnarok.rift.encounter.type_parallel");
            case BOSS_RUSH -> I18n.get("gui.dmz_ragnarok.rift.encounter.type_rush");
            default -> I18n.get("gui.dmz_ragnarok.rift.encounter.type_standard");
        };
    }

    private static RaidType typeOf(String name) {
        if (I18n.get("gui.dmz_ragnarok.rift.encounter.type_parallel").equals(name)) {
            return RaidType.PARALLEL_QUEST;
        }
        if (I18n.get("gui.dmz_ragnarok.rift.encounter.type_rush").equals(name)) {
            return RaidType.BOSS_RUSH;
        }
        return RaidType.STANDARD;
    }

    private static String aiTierName(int tier) {
        return switch (tier) {
            case 1 -> I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_2");
            case 2 -> I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_3");
            default -> I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_1");
        };
    }

    private static int aiTierIndex(String name) {
        if (I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_2").equals(name)) {
            return 1;
        }
        if (I18n.get("gui.dmz_ragnarok.raid.edit.ai_tier_3").equals(name)) {
            return 2;
        }
        return 0;
    }

    private static String str(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }
}
