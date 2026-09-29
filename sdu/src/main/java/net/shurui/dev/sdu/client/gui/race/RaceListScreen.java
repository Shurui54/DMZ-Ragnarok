package net.shurui.dev.sdu.client.gui.race;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.DmzAssets;
import net.shurui.dev.sdu.client.DmzRaces;
import net.shurui.dev.sdu.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.network.DeleteRacePacket;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.ToggleSuppressPacket;
import net.shurui.dev.sdu.race.RaceData;
import net.shurui.dev.sdu.race.SuppressedDefaultsClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Top-level race editor: lists DMZ races (defaults marked); create / edit / copy / delete custom ones. */
public class RaceListScreen extends SagaBaseScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    private static RaceData clipboard;

    /**
     * DMZ's six built-in default race ids. DMZ regenerates these every boot, so they are the canonical
     * "default" set even when one is suppressed (and thus stripped from the loaded/synced maps). We union
     * this with whatever DMZ currently reports so the editor's default handling is robust either way.
     */
    private static final Set<String> DEFAULT_RACES = Set.of(
            "human", "saiyan", "namekian", "frostdemon", "bioandroid", "majin");

    private final List<RaceData> races;
    private final Set<String> defaults = defaultIds();
    private boolean showDefaults = false;
    private int scroll;
    /** Race id armed for deletion: first Del click arms it, a second confirms. Reset on screen change. */
    private String pendingDelete;

    /** Union of DMZ's reported defaults and the known built-in six, so suppressed defaults still count. */
    private static Set<String> defaultIds() {
        Set<String> s = new LinkedHashSet<>(DEFAULT_RACES);
        s.addAll(DmzRaces.defaultRaceIds());
        return s;
    }

    /**
     * The races shown given the Defaults toggle. Custom races come from the on-disk list always; DMZ
     * defaults are shown only when the Defaults toggle is on. Crucially we ALSO surface any default that
     * this client knows is suppressed but which is NOT in the on-disk list (stripped from DMZ's maps and
     * its folder possibly gone) as a synthetic placeholder row, so an admin can still Restore it - the
     * whole point of syncing the suppressed set separately from {@code getLoadedRaces()}.
     */
    private List<RaceData> visible() {
        List<RaceData> out = new ArrayList<>();
        Set<String> present = new LinkedHashSet<>();
        for (RaceData r : races) {
            present.add(r.raceId);
            if (showDefaults || !defaults.contains(r.raceId)) {
                out.add(r);
            }
        }
        if (showDefaults) {
            // Any suppressed default missing from the on-disk list: add a placeholder so it stays restorable.
            for (String id : SuppressedDefaultsClient.races()) {
                if (defaults.contains(id) && !present.contains(id)) {
                    RaceData ph = new RaceData();
                    ph.raceId = id;
                    out.add(ph);
                }
            }
        }
        return out;
    }

    public RaceListScreen(List<RaceData> races) {
        super(Component.translatable("gui.dmz_ragnarok.npc.races.title"), UI_W, UI_H, null);
        this.races = races;
    }

    public static void open(List<RaceData> races) {
        Minecraft.getInstance().setScreen(new RaceListScreen(new ArrayList<>(races)));
    }

    @Override
    protected void init() {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.npc.subtitle.editor");
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        List<RaceData> vis = visible();
        int total = vis.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);
        if (total > maxRows) {
            label(tr("gui.dmz_ragnarok.npc.list.count", scroll + 1, end, total), 10, 20);
        }

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final RaceData r = vis.get(i);
            boolean def = defaults.contains(r.raceId);
            boolean suppressed = def && SuppressedDefaultsClient.isRaceSuppressed(r.raceId);
            String name = net.shurui.dev.sdu.client.GeneratedLang.raceName(r.raceId);
            String shown = net.shurui.dev.sdu.util.ColorCodes.translate(name == null || name.isBlank() ? r.raceId : name);
            // Suppressed defaults render greyed with a marker; live defaults blue; custom cyan.
            String prefix = suppressed ? "§8" : (def ? "§9" : "§b");
            String suffix = (def ? " " + tr("gui.dmz_ragnarok.npc.tag.default") : "")
                    + (suppressed ? " " + tr("gui.dmz_ragnarok.npc.tag.suppressed") : "")
                    + " " + tr("gui.dmz_ragnarok.npc.races.classes_count", r.classes.size());
            label(prefix + shown + suffix, 10, y + 5);
            btn(168, y, 36, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.edit"), () -> minecraft.setScreen(new RaceEditScreen(this, r)));
            btn(206, y, 32, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.copy"), () -> clipboard = r.copy());
            if (def) {
                // Defaults are never deleted (DMZ regenerates them); suppression is the correct action.
                btn(240, y, 34, GuiTheme.BUTTON_HEIGHT, Component.translatable(suppressed
                        ? "gui.dmz_ragnarok.npc.btn.restore" : "gui.dmz_ragnarok.npc.btn.suppress"), () -> {
                    DmzNet.sendToServer(new ToggleSuppressPacket(
                            ToggleSuppressPacket.Kind.RACE, r.raceId, !suppressed));
                    // Optimistic: server broadcasts the authoritative set right back, correcting if refused.
                    rebuildWidgets();
                });
            } else {
                boolean armed = r.raceId.equals(pendingDelete);
                // Unified circular X delete (was a "Del" word). Two-click arm/confirm is preserved: the armed
                // state swaps the glyph to the confirm marker, matching the form-group list's existing pattern.
                iconBtnRight(rowControlRight(), y, ROW_H,
                        Component.translatable(armed ? "gui.dmz_ragnarok.npc.btn.confirm_icon" : "gui.dmz_ragnarok.npc.btn.x"), () -> {
                    if (armed) {
                        DmzNet.sendToServer(new DeleteRacePacket(r.raceId));
                        races.remove(r);
                        pendingDelete = null;
                    } else {
                        pendingDelete = r.raceId;
                    }
                    rebuildWidgets();
                });
            }
            y += ROW_H;
        }
        scrollList(10, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });

        // Open the companion screen for suppressing/restoring DMZ's default CHARACTER CLASSES globally.
        btn(210, 16, 72, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.classes"),
                () -> minecraft.setScreen(new SuppressedClassesScreen(this)));

        commitBtn(6, footerY(), 54, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.new"), this::newRace);
        btn(62, footerY(), 92, footerBtnHeight(), Component.translatable(showDefaults ? "gui.dmz_ragnarok.npc.btn.defaults_on" : "gui.dmz_ragnarok.npc.btn.defaults_off"),
                () -> { showDefaults = !showDefaults; scroll = 0; rebuildWidgets(); });
        if (clipboard != null) {
            commitBtn(156, footerY(), 60, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.paste"), this::pasteRace);
        }
        btn(218, footerY(), 64, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.menu"),
                () -> minecraft.setScreen(new net.shurui.dev.sdu.client.gui.SduHubScreen()));
    }

    private void newRace() {
        RaceData r = new RaceData();
        r.raceId = uniqueId("custom_race");
        seedClasses(r);
        races.add(r);
        minecraft.setScreen(new RaceEditScreen(this, r));
    }

    private void pasteRace() {
        RaceData r = clipboard.copy();
        r.raceId = uniqueId(r.raceId + "_copy");
        races.add(r);
        rebuildWidgets();
    }

    /** Give a fresh race a class entry for each character class DMZ knows, so it's playable. */
    private static void seedClasses(RaceData r) {
        List<String> classes = DmzAssets.raceClasses();
        if (classes.isEmpty()) {
            classes = List.of("warrior");
        }
        for (String c : classes) {
            r.classes.putIfAbsent(c, new RaceData.ClassStats());
        }
    }

    private String uniqueId(String base) {
        String id = base;
        int n = 2;
        while (exists(id)) {
            id = base + "_" + n++;
        }
        return id;
    }

    private boolean exists(String id) {
        for (RaceData r : races) {
            if (r.raceId.equals(id)) {
                return true;
            }
        }
        return defaults.contains(id);
    }
}
