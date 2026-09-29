package net.shurui.dev.shuruis_dmz_dungeons.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_dmz_dungeons.block.CrateTier;
import net.shurui.dev.shuruis_dmz_dungeons.block.DungeonCrateLoot;
import net.shurui.dev.shuruis_dmz_dungeons.block.SpawnerConfig;
import net.shurui.dev.shuruis_dmz_dungeons.block.SpawnerPreset;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloorConfig;

import java.util.ArrayList;
import java.util.List;

// per-floor sub-editor opened from DungeonConfigScreen's floor list. edits ONE DungeonFloorConfig in place (the
// same object the list holds), so the parent's Save is what actually persists it. Three tabs, on the same tab
// pattern DungeonConfigScreen uses (buildTabHeader + a scroll band):
//   * General - type, theme, layout style, size, depth (and, for a BOSS floor, its guardian editor).
//   * Loot    - the floor's DungeonCrateLoot: refresh window, barrel share, and per-tier weight / zeni range /
//               drop pool (the drop pool opens the shared DropListEditor via DropListScreen).
//   * NPCs    - the floor's single enemy SpawnerConfig, edited through the shared AdvancedSpawnerScreen (the same
//               in-place callback mechanism the boss editor uses).
//
// layout style / type / theme names are carried as plain strings here so this screen never classloads the gen
// package (the same convention DungeonFloorConfig keeps); an empty layout string means "inherit the config default".
public class DungeonFloorEditScreen extends FieldEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    // enum-name strings for LayoutStyle, in its declaration order, WITHOUT classloading the gen enum. index 0 in
    // the dropdown is the "(inherit default)" option (empty layoutStyle); these follow it.
    private static final String[] LAYOUT_NAMES = {
            "SPINE_AND_RIBS", "ROADS_AND_BUILDINGS", "INSUFFERABLE_CRYPT"};

    private final DungeonFloorConfig config;
    private final int floorNumber;

    private int tab = 0;
    private int presetScroll = 0;

    // clipboard shared across editor instances: the last Copy'd floor's preset list, for copy/paste between floors.
    // pure client-side, deep-copied on both ends so neither side can alias it. persistence rides the parent Save.
    private static List<SpawnerPreset> clipboardPresets;

    private DmzDropdown typeDropdown;
    private DmzDropdown themeDropdown;
    private DmzDropdown layoutDropdown;

    public DungeonFloorEditScreen(Screen parent, DungeonFloorConfig config, int floorNumber) {
        super(Component.translatable("gui.dmz_ragnarok.dungeons.floor_edit.title"), UI_W, UI_H, parent);
        this.config = config;
        this.floorNumber = floorNumber;
    }

    private String[] sections() {
        return new String[]{
                I18n.get("gui.dmz_ragnarok.dungeons.floor_edit.tab_general"),
                I18n.get("gui.dmz_ragnarok.dungeons.floor_edit.tab_loot"),
                I18n.get("gui.dmz_ragnarok.dungeons.floor_edit.tab_npcs")};
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        typeDropdown = null;
        themeDropdown = null;
        layoutDropdown = null;

        rowY = buildTabHeader(null, sections(), tab, this::selectSection);

        int contentTop = rowY;
        int contentBottom = UI_H - 26;
        beginScrollBand(contentTop, contentBottom);
        rowY = contentTop - scroll;
        switch (tab) {
            case 1 -> lootTab();
            case 2 -> npcsTab();
            default -> generalTab();
        }
        finishScrollBand(contentTop, contentBottom);

        btn(UI_W / 2 - 55, footerY(), 110, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.dungeons.common.back"), () -> {
                    applyFields();
                    back();
                });
    }

    private void selectSection(int index) {
        if (tab != index) {
            applyFields();
            tab = index;
            scroll = 0;
            rebuildWidgets();
        }
    }

    private void generalTab() {
        boolean boss = config.isBoss();

        label(I18n.get("gui.dmz_ragnarok.dungeons.floor_edit.type"), 14, rowY + 5);
        typeDropdown = dropdown(150, rowY, 132, options(typeDisplays()), typeIndex());
        rowY += ROW_H;
        tip(I18n.get("gui.dmz_ragnarok.dungeons.floor_edit.type_tip"));

        label(I18n.get("gui.dmz_ragnarok.dungeons.floor_edit.theme"), 14, rowY + 5);
        themeDropdown = dropdown(150, rowY, 132, options(themeDisplays()), themeIndex());
        rowY += ROW_H;
        tip(I18n.get("gui.dmz_ragnarok.dungeons.floor_edit.theme_tip"));

        if (!boss) {
            label(I18n.get("gui.dmz_ragnarok.dungeons.floor_edit.layout"), 14, rowY + 5);
            layoutDropdown = dropdown(150, rowY, 132, options(layoutDisplays()), layoutIndex());
            rowY += ROW_H;
            tip(I18n.get("gui.dmz_ragnarok.dungeons.floor_edit.layout_tip"));
        }

        tf(I18n.get("gui.dmz_ragnarok.dungeons.floor_edit.size"), intStr(config.size),
                v -> config.size = clamp(parseI(v, config.size), DungeonFloorConfig.MIN_SIZE, DungeonFloorConfig.MAX_SIZE));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.floor_edit.size_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.floor_edit.depth"), intStr(config.depth),
                v -> config.depth = clamp(parseI(v, config.depth), DungeonFloorConfig.MIN_DEPTH, DungeonFloorConfig.MAX_DEPTH));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.floor_edit.depth_tip"));

        if (boss) {
            bf(I18n.get("gui.dmz_ragnarok.dungeons.floor_edit.boss_enabled"), config.bossEnabled,
                    () -> config.bossEnabled = !config.bossEnabled);
            tip(I18n.get("gui.dmz_ragnarok.dungeons.floor_edit.boss_enabled_tip"));
            label(I18n.get("gui.dmz_ragnarok.dungeons.floor_edit.boss"), 14, rowY + 2);
            btn(150, rowY, 132, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.dungeons.floor_edit.boss_btn"), () -> {
                applyFields();
                this.minecraft.setScreen(new AdvancedSpawnerScreen(this, config.boss, () -> { }));
            });
            rowY += ROW_H;
            tip(I18n.get("gui.dmz_ragnarok.dungeons.floor_edit.boss_tip"));
        }
    }

    private void lootTab() {
        DungeonCrateLoot loot = config.crateLoot != null ? config.crateLoot : (config.crateLoot = new DungeonCrateLoot());

        tf(I18n.get("gui.dmz_ragnarok.dungeons.loot.refresh_hours"), intStr(loot.refreshHours),
                v -> loot.refreshHours = Math.max(0, parseI(v, loot.refreshHours)));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.loot.refresh_hours_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.loot.chest_chance"), intStr(loot.chestChancePercent),
                v -> loot.chestChancePercent = clamp(parseI(v, loot.chestChancePercent), 0, 100));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.loot.chest_chance_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.loot.barrel_chance"), intStr(loot.barrelChancePercent),
                v -> loot.barrelChancePercent = clamp(parseI(v, loot.barrelChancePercent), 0, 100));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.loot.barrel_chance_tip"));

        for (CrateTier t : CrateTier.values()) {
            DungeonCrateLoot.TierPool pool = loot.pool(t);
            String tierName = I18n.get(t.langKey());
            label(tierName, 14, rowY + 2);
            btn(150, rowY, 132, GuiTheme.BUTTON_HEIGHT,
                    Component.translatable("gui.dmz_ragnarok.dungeons.loot.edit_drops", pool.drops.size()), () -> {
                        applyFields();
                        this.minecraft.setScreen(new DropListScreen(this, tierName, pool.drops));
                    });
            rowY += ROW_H;
            tip(I18n.get("gui.dmz_ragnarok.dungeons.loot.edit_drops_tip"));

            tf(I18n.get("gui.dmz_ragnarok.dungeons.loot.weight"), intStr(pool.weight),
                    v -> pool.weight = Math.max(0, parseI(v, pool.weight)));
            tip(I18n.get("gui.dmz_ragnarok.dungeons.loot.weight_tip"));
            tf(I18n.get("gui.dmz_ragnarok.dungeons.loot.zeni_min"), intStr(pool.zeniMin),
                    v -> pool.zeniMin = Math.max(0, parseI(v, pool.zeniMin)));
            tip(I18n.get("gui.dmz_ragnarok.dungeons.loot.zeni_min_tip"));
            tf(I18n.get("gui.dmz_ragnarok.dungeons.loot.zeni_max"), intStr(pool.zeniMax),
                    v -> pool.zeniMax = Math.max(0, parseI(v, pool.zeniMax)));
            tip(I18n.get("gui.dmz_ragnarok.dungeons.loot.zeni_max_tip"));
        }
    }

    // the enabled toggle plus a scrollable list of weighted enemy presets. Generation drops a disguised spawner into the
    // floor block under each harvested enemy marker and picks one preset at random (by weight) per marker, so a
    // floor can mix enemy families. Each preset is a full SpawnerConfig edited through the shared spawner editor.
    private void npcsTab() {
        bf(I18n.get("gui.dmz_ragnarok.dungeons.npcs.enabled"), config.enemiesEnabled,
                () -> config.enemiesEnabled = !config.enemiesEnabled);
        tip(I18n.get("gui.dmz_ragnarok.dungeons.npcs.enabled_tip"));

        int listTop = rowY;
        // listTop already sits below the enabled toggle, so reserve only for the Add button and the band bottom (234).
        int maxRows = rowsThatFit(listTop, ROW_H, 18);
        List<SpawnerPreset> presets = config.enemyPresets;
        int count = presets.size();
        presetScroll = Math.max(0, Math.min(presetScroll, Math.max(0, count - maxRows)));
        int end = Math.min(count, presetScroll + maxRows);
        for (int i = presetScroll; i < end; i++) {
            presetRow(i);
        }
        scrollList(12, uiWidth, listTop, ROW_H, maxRows, count, presetScroll,
                v -> { presetScroll = v; rebuildWidgets(); });

        int addY = listTop + maxRows * ROW_H + 2;
        btn(14, addY, 88, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.dungeons.npcs.add"), () -> {
            applyFields();
            presets.add(new SpawnerPreset(DungeonFloorConfig.defaultEnemyConfig(), 1));
            presetScroll = Math.max(0, presets.size() - maxRows);
            rebuildWidgets();
        });
        // Copy this floor's whole preset list to the shared clipboard; paste it into any other floor's editor.
        btn(106, addY, 88, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.dungeons.npcs.copy"), this::copyPresets);
        boolean canPaste = clipboardPresets != null && !clipboardPresets.isEmpty();
        btn(198, addY, 88, GuiTheme.BUTTON_HEIGHT,
                Component.literal(canPaste ? "" : "§8").append(Component.translatable("gui.dmz_ragnarok.dungeons.npcs.paste")),
                this::pastePresets);
    }

    // copy this floor's preset list to the shared clipboard (deep copy so a later edit here cannot mutate it)
    private void copyPresets() {
        applyFields();
        clipboardPresets = new ArrayList<>();
        for (SpawnerPreset p : config.enemyPresets) {
            clipboardPresets.add(p.copy());
        }
        rebuildWidgets();
    }

    // replace this floor's preset list with a deep copy of the clipboard (deep copy so the clipboard stays reusable)
    private void pastePresets() {
        if (clipboardPresets == null || clipboardPresets.isEmpty()) {
            return;
        }
        applyFields();
        config.enemyPresets.clear();
        for (SpawnerPreset p : clipboardPresets) {
            config.enemyPresets.add(p.copy());
        }
        presetScroll = 0;
        rebuildWidgets();
    }

    private void presetRow(int index) {
        SpawnerPreset p = config.enemyPresets.get(index);
        int y = rowY;
        label(presetLabel(p, index), 14, y + 2);
        rawField(150, y + 1, 40, intStr(Math.max(1, p.weight)), v -> p.weight = Math.max(1, parseI(v, p.weight)));
        tooltip(150, y, 40, 11, I18n.get("gui.dmz_ragnarok.dungeons.npcs.weight_tip"));
        btn(196, y, 38, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.dungeons.common.edit"), () -> {
            applyFields();
            this.minecraft.setScreen(new AdvancedSpawnerScreen(this, p.config, () -> { }));
        });
        tooltip(196, y, 38, 11, I18n.get("gui.dmz_ragnarok.dungeons.npcs.edit_tip"));
        // ICON_BUTTON_MIN_SIZE, not the usual ICON_BUTTON_SIZE. This list's stride is ROW_H (12), and an 18px
        // square overflows that by 6, so at 18 each row's icons ran into the row below and read as one oversized
        // block rather than two controls. The theme's own floor (14) is the largest size that still lines up with
        // the BUTTON_HEIGHT Edit button beside it, so the three controls read as one family across the row.
        final int icon = GuiTheme.ICON_BUTTON_MIN_SIZE;
        // deep-copied duplicate inserted directly after this row, so the clone is easy to find and never shares
        // state with the original (presets carry no unique id, so there is no collision to resolve).
        btn(236, y, icon, icon, Component.translatable("gui.dmz_ragnarok.dungeons.npcs.duplicate"), () -> {
            applyFields();
            config.enemyPresets.add(index + 1, p.copy());
            rebuildWidgets();
        });
        tooltip(236, y, icon, icon, I18n.get("gui.dmz_ragnarok.dungeons.npcs.duplicate_tip"));
        btn(252, y, icon, icon, Component.translatable("gui.dmz_ragnarok.dungeons.config.floor_remove"), () -> {
            config.enemyPresets.remove(index);
            if (presetScroll > 0 && presetScroll >= config.enemyPresets.size()) {
                presetScroll--;
            }
            rebuildWidgets();
        });
        // hover rect tracks the button it explains: both are the same icon size, so the help does not stop a few
        // px short of the button's edge.
        tooltip(252, y, icon, icon, I18n.get("gui.dmz_ragnarok.dungeons.npcs.remove_tip"));
        rowY += ROW_H;
    }

    // the row caption: a saved-NPC ref if set, else the display name, else the entity id, prefixed with the index and
    // trimmed to the label column so it never runs into the weight field.
    private String presetLabel(SpawnerPreset p, int index) {
        SpawnerConfig c = p.config;
        String name;
        if (c.savedNpcRef != null && !c.savedNpcRef.isBlank()) {
            name = c.savedNpcRef;
        } else if (c.displayName != null && !c.displayName.isBlank()) {
            name = c.displayName;
        } else {
            name = c.entityTypeId == null ? "" : c.entityTypeId;
        }
        return this.font.plainSubstrByWidth((index + 1) + ". " + name, 130);
    }

    @Override
    protected void onExtraDropdown(DmzDropdown dropdown, int row) {
        if (dropdown == typeDropdown && row >= 0 && row < DungeonFloorConfig.TYPES.length) {
            config.type = DungeonFloorConfig.TYPES[row];
            applyFields();
            rebuildWidgets(); // switch the visible General rows (layout vs boss editor) to match the new type.
        } else if (dropdown == themeDropdown && row >= 0 && row < DungeonFloorConfig.THEMES.length) {
            config.theme = DungeonFloorConfig.THEMES[row];
        } else if (dropdown == layoutDropdown && row >= 0) {
            // row 0 is the inherit-default option; the rest map to LAYOUT_NAMES shifted by one.
            config.layoutStyle = row == 0 ? "" : LAYOUT_NAMES[row - 1];
        }
    }

    private int typeIndex() {
        for (int i = 0; i < DungeonFloorConfig.TYPES.length; i++) {
            if (DungeonFloorConfig.TYPES[i].equalsIgnoreCase(config.type)) {
                return i;
            }
        }
        return 0;
    }

    private int themeIndex() {
        for (int i = 0; i < DungeonFloorConfig.THEMES.length; i++) {
            if (DungeonFloorConfig.THEMES[i].equalsIgnoreCase(config.theme)) {
                return i;
            }
        }
        return 0;
    }

    private int layoutIndex() {
        if (config.layoutStyle == null || config.layoutStyle.isBlank()) {
            return 0;
        }
        for (int i = 0; i < LAYOUT_NAMES.length; i++) {
            if (LAYOUT_NAMES[i].equalsIgnoreCase(config.layoutStyle)) {
                return i + 1;
            }
        }
        return 0;
    }

    private static String[] typeDisplays() {
        String[] out = new String[DungeonFloorConfig.TYPES.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = I18n.get("gui.dmz_ragnarok.dungeons.opt.floortype."
                    + DungeonFloorConfig.TYPES[i].toLowerCase(java.util.Locale.ROOT));
        }
        return out;
    }

    private static String[] themeDisplays() {
        String[] out = new String[DungeonFloorConfig.THEMES.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = I18n.get("gui.dmz_ragnarok.dungeons.opt.theme."
                    + DungeonFloorConfig.THEMES[i].toLowerCase(java.util.Locale.ROOT));
        }
        return out;
    }

    private static String[] layoutDisplays() {
        List<String> out = new ArrayList<>();
        out.add(I18n.get("gui.dmz_ragnarok.dungeons.opt.layout.default"));
        for (String name : LAYOUT_NAMES) {
            out.add(I18n.get("gui.dmz_ragnarok.dungeons.opt.layout." + name.toLowerCase(java.util.Locale.ROOT)));
        }
        return out.toArray(new String[0]);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
