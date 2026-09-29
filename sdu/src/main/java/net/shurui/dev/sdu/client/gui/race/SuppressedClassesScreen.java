package net.shurui.dev.sdu.client.gui.race;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.DmzAssets;
import net.shurui.dev.sdu.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.ToggleSuppressPacket;
import net.shurui.dev.sdu.race.SuppressedDefaultsClient;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Companion editor to {@link RaceListScreen} for suppressing/restoring DMZ's default CHARACTER CLASSES
 * globally (warrior, martialartist, spiritualist, berserker, paladin, tank, cleric). A suppressed class
 * is stripped from every race's stats and from DMZ's synced maps, so it can't be seen via
 * {@code getAllRaceStats()} anymore - which is why the list unions DMZ's live class ids with the known
 * seven defaults AND the client-synced suppressed set, so a suppressed class stays visible (greyed) and
 * restorable. Each row toggles via an op-gated {@link ToggleSuppressPacket} (kind CLASS); the server is
 * authoritative and broadcasts the corrected suppressed set right back. Mirrors RaceListScreen styling.
 */
public class SuppressedClassesScreen extends SagaBaseScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    /** DMZ's seven built-in character classes, present even when one is suppressed (and thus stripped). */
    private static final List<String> DEFAULT_CLASSES = List.of(
            "warrior", "martialartist", "spiritualist", "berserker", "paladin", "tank", "cleric");

    private int scroll;

    public SuppressedClassesScreen(Screen parent) {
        super(Component.translatable("gui.dmz_ragnarok.npc.classes.title"), UI_W, UI_H, parent);
    }

    /** Every class id the editor should show: live DMZ ids + the known seven + client-suppressed ones. */
    private List<String> allClasses() {
        TreeSet<String> s = new TreeSet<>(DEFAULT_CLASSES);
        s.addAll(DmzAssets.raceClasses());
        s.addAll(SuppressedDefaultsClient.classes());
        return new ArrayList<>(s);
    }

    @Override
    protected void init() {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.npc.subtitle.editor");
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        List<String> vis = allClasses();
        int total = vis.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);
        if (total > maxRows) {
            label(tr("gui.dmz_ragnarok.npc.list.count", scroll + 1, end, total), 10, 20);
        }

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final String id = vis.get(i);
            boolean suppressed = SuppressedDefaultsClient.isClassSuppressed(id);
            String prefix = suppressed ? "§8" : "§b";
            label(prefix + id + (suppressed ? " " + tr("gui.dmz_ragnarok.npc.tag.suppressed") : ""), 10, y + 5);
            btn(240, y, 34, GuiTheme.BUTTON_HEIGHT, Component.translatable(suppressed
                    ? "gui.dmz_ragnarok.npc.btn.restore" : "gui.dmz_ragnarok.npc.btn.suppress"), () -> {
                DmzNet.sendToServer(new ToggleSuppressPacket(
                        ToggleSuppressPacket.Kind.CLASS, id, !suppressed));
                // Optimistic: the server broadcasts the authoritative suppressed set right back.
                rebuildWidgets();
            });
            y += ROW_H;
        }
        scrollList(10, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });

        btn(218, footerY(), 64, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.back"), this::back);
    }
}
