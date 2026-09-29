package net.shurui.dev.shuruis_raid_bosses.client.gui;

import java.util.List;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_raid_bosses.network.RaidNet;
import net.shurui.dev.shuruis_raid_bosses.network.SaveRiftPacket;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef;
import net.shurui.dev.shuruis_raid_bosses.rift.RiftDef;
import net.shurui.dev.shuruis_raid_bosses.util.ColorCodes;

/**
 * The rift editor: where a tear opens, how often, what it says, and WHAT IS ON THE OTHER SIDE.
 *
 * <p>A rift owns its encounter. The Encounter button opens the tear's own encounter screen on that definition, so a
 * tear is configured exactly like a raid (boss, Parallel Quest or Boss Rush as readily as a single boss, stages,
 * waves, allies, ki moves, rewards) without an operator having to create a raid and then arrange for it never to be
 * scheduled. Two fields of that definition are not theirs to set and are overwritten per run: the arena and the
 * player spawn, which the tear supplies from the cell it was given.
 *
 * <p>A rift made before this could point at a stored raid by id, and still can: the pointer row appears only while
 * a rift has no encounter of its own, and opening the encounter editor on such a rift hands it a copy of the raid
 * it was pointing at.
 */
public class RiftEditScreen extends FieldEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final String[] TAB_KEYS = {
            "gui.dmz_ragnarok.rift.edit.tab_general",
            "gui.dmz_ragnarok.rift.edit.tab_timing",
            "gui.dmz_ragnarok.rift.edit.tab_regions",
            "gui.dmz_ragnarok.rift.edit.tab_messages"};

    /**
     * The dungeon themes an arena may be cut into. Hard-coded rather than read from the dungeon addon, because
     * this list is what the SERVER will accept: an unknown theme silently degrades to the overworld dimension, and
     * a dropdown that can only offer valid answers is the point of having one.
     */
    private static final List<String> THEMES = List.of(
            "overworld", "namek", "kaio", "stony", "otherworld", "nether", "end", "vegeta", "beerus");

    /** The portal LOOKS a tear may wear. "default" is the normal per-uuid swirl; "halloween" is the pumpkin tear. */
    private static final List<String> LOOKS = List.of("default", "halloween");

    private final RiftDef def;
    private final RiftListScreen list;
    private int tab;

    public RiftEditScreen(RiftDef def, RiftListScreen parent) {
        super(Component.translatable("gui.dmz_ragnarok.rift.edit.title"), UI_W, UI_H, parent);
        this.def = def;
        this.list = parent;
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
        panelName = I18n.get(def.name);
        int contentTop = buildTabHeader(null, resolvedTabs(), tab, this::selectSection);
        int contentBottom = UI_H - 26;
        beginScrollBand(contentTop, contentBottom);
        rowY = contentTop - scroll;
        switch (tab) {
            case 0 -> generalTab();
            case 1 -> timingTab();
            case 2 -> regionsTab();
            case 3 -> messagesTab();
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

    private void generalTab() {
        tf(I18n.get("gui.dmz_ragnarok.rift.edit.display_name"), def.name, v -> def.name = v);
        tip(I18n.get("gui.dmz_ragnarok.rift.edit.display_name_tip"));
        tf(I18n.get("gui.dmz_ragnarok.rift.edit.tear_label"), def.tearLabel, v -> def.tearLabel = v);
        tip(I18n.get("gui.dmz_ragnarok.rift.edit.tear_label_tip"));
        bf(I18n.get("gui.dmz_ragnarok.rift.edit.enabled"), def.enabled, () -> def.enabled = !def.enabled);
        tip(I18n.get("gui.dmz_ragnarok.rift.edit.enabled_tip"));
        bf(I18n.get("gui.dmz_ragnarok.rift.edit.event_only"), def.eventOnly, () -> def.eventOnly = !def.eventOnly);
        tip(I18n.get("gui.dmz_ragnarok.rift.edit.event_only_tip"));
        df(I18n.get("gui.dmz_ragnarok.rift.edit.look"), LOOKS,
                def.look == null || def.look.isBlank() ? "default" : def.look,
                v -> def.look = "default".equalsIgnoreCase(v) ? "" : v);
        tip(I18n.get("gui.dmz_ragnarok.rift.edit.look_tip"));
        // The encounter this tear leads to, edited HERE rather than by pointing at a raid in the raid list. The
        // button opens the raid editor against the rift's own definition, so a tear gets the whole thing (boss,
        // Parallel Quest and Boss Rush, stages, waves, allies, ki moves, rewards) with no second job of creating a
        // raid that must then never be scheduled.
        btn(20, rowY, 260, GuiTheme.BUTTON_HEIGHT,
                Component.translatable("gui.dmz_ragnarok.rift.edit.encounter_button",
                        def.encounter != null ? ColorCodes.translate(I18n.get(def.encounter.name))
                                : I18n.get("gui.dmz_ragnarok.rift.edit.encounter_none")),
                this::openEncounter);
        rowY += 22;
        // Not tip(): that helper assumes the standard 12px field row, and this row is a 22px button.
        tooltip(12, rowY - 22, 272, 22, I18n.get("gui.dmz_ragnarok.rift.edit.encounter_tip"));
        // The fallback pointer, shown only while this rift still uses one. Opening the encounter editor above gives
        // the rift a copy of its own, and from that moment this row is gone and the pointer is ignored.
        if (def.encounter == null) {
            df(I18n.get("gui.dmz_ragnarok.rift.edit.raid"),
                    list == null ? List.of() : list.raidIds(), def.raidId, v -> def.raidId = v);
            tip(I18n.get("gui.dmz_ragnarok.rift.edit.raid_tip"));
        }
        df(I18n.get("gui.dmz_ragnarok.rift.edit.theme"), THEMES, def.arenaTheme, v -> def.arenaTheme = v);
        tip(I18n.get("gui.dmz_ragnarok.rift.edit.theme_tip"));
    }

    private void timingTab() {
        tf(I18n.get("gui.dmz_ragnarok.rift.edit.min_minutes"), str(def.minSpawnMinutes),
                v -> def.minSpawnMinutes = parseI(v, def.minSpawnMinutes));
        tip(I18n.get("gui.dmz_ragnarok.rift.edit.min_minutes_tip"));
        tf(I18n.get("gui.dmz_ragnarok.rift.edit.max_minutes"), str(def.maxSpawnMinutes),
                v -> def.maxSpawnMinutes = parseI(v, def.maxSpawnMinutes));
        tip(I18n.get("gui.dmz_ragnarok.rift.edit.max_minutes_tip"));
        tf(I18n.get("gui.dmz_ragnarok.rift.edit.lifetime"), str(def.tearLifetimeSeconds),
                v -> def.tearLifetimeSeconds = parseI(v, def.tearLifetimeSeconds));
        tip(I18n.get("gui.dmz_ragnarok.rift.edit.lifetime_tip"));
    }

    private void regionsTab() {
        dfMulti(I18n.get("gui.dmz_ragnarok.rift.edit.regions"),
                list == null ? List.of() : list.regionNames(), def.regions,
                v -> replaceList(def.regions, v));
        tip(I18n.get("gui.dmz_ragnarok.rift.edit.regions_tip"));
    }

    private void messagesTab() {
        tf(I18n.get("gui.dmz_ragnarok.rift.edit.msg_opened"), def.msgOpened, v -> def.msgOpened = v);
        tip(I18n.get("gui.dmz_ragnarok.rift.edit.msg_opened_tip"));
        tf(I18n.get("gui.dmz_ragnarok.rift.edit.msg_victory"), def.msgVictory, v -> def.msgVictory = v);
        tip(I18n.get("gui.dmz_ragnarok.rift.edit.msg_victory_tip"));
        tf(I18n.get("gui.dmz_ragnarok.rift.edit.msg_failed"), def.msgFailed, v -> def.msgFailed = v);
        tip(I18n.get("gui.dmz_ragnarok.rift.edit.msg_failed_tip"));
    }

    /**
     * Open the encounter screen on this rift's OWN fight.
     *
     * <p>A rift that has none yet is given one now. If it was pointing at a stored raid, the encounter starts as a
     * COPY of that raid, so opening the editor never loses what the tear already led to; otherwise it starts from
     * the same defaults a new raid gets. Saving from that screen comes back through {@link SaveRiftPacket}, so the
     * whole encounter is stored inside the rift and the raid list is left alone.
     */
    private void openEncounter() {
        applyFields();
        if (def.encounter == null) {
            RaidBossDef source = list == null ? null : list.raidById(def.raidId);
            def.encounter = source != null
                    ? RaidBossDef.load(source.save())
                    : RaidBossDef.createDefault(def.id, def.name);
            // The encounter belongs to this rift, so it carries the rift's id: that id is what the damage tracker
            // and the announcements label the fight with, and reusing the copied raid's id would make two different
            // encounters answer to one name.
            def.encounter.id = def.id;
        }
        // The tear's OWN encounter screen, not the raid editor: a tear has no arena to draw, no sign-up window, no
        // participant range, no schedule and no host NPC, and it sends none of the raid's broadcasts.
        this.minecraft.setScreen(new RiftEncounterScreen(this, def,
                () -> RaidNet.sendToServer(new SaveRiftPacket(def.save()))));
    }

    private void save() {
        applyFields();
        // A max below the min is a range with nothing in it. The server collapses it to the min anyway, so it is
        // corrected here too rather than saved as a value the editor would show back differently.
        if (def.maxSpawnMinutes < def.minSpawnMinutes) {
            def.maxSpawnMinutes = def.minSpawnMinutes;
        }
        RaidNet.sendToServer(new SaveRiftPacket(def.save()));
        back();
    }

    private static String str(int v) {
        return String.valueOf(v);
    }
}
