package net.shurui.dev.shuruis_dmz_tournaments.client.gui;

import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_dmz_tournaments.network.OpenBrowserPacket;
import net.shurui.dev.shuruis_dmz_tournaments.network.OpenSignupRequestPacket;
import net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet;
import net.shurui.dev.shuruis_dmz_tournaments.util.ColorCodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * The sign-up NPC's tournament browser. Lists every tournament the NPC exposes under collapsible group
 * dropdowns (click a group header to expand/collapse). Selecting a tournament asks the server to open its
 * sign-up screen ({@link OpenSignupRequestPacket}).
 */
public class BrowserScreen extends BaseEditScreen {
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 30;

    private final Map<String, List<OpenBrowserPacket.Entry>> groups = new TreeMap<>();
    private final Set<String> expanded = new LinkedHashSet<>();
    private int scroll = 0;

    /** A visible row: either a group header (toggles expansion) or a tournament entry (opens sign-up). */
    private record Row(String group, OpenBrowserPacket.Entry entry) {
        boolean isHeader() { return entry == null; }
    }

    public BrowserScreen(List<OpenBrowserPacket.Entry> entries) {
        super(Component.translatable("gui.dmz_ragnarok.tournaments.browser.title"), UI_W, UI_H, null);
        for (OpenBrowserPacket.Entry e : entries) {
            String g = (e.group() == null || e.group().isBlank()) ? "Ungrouped" : e.group();
            groups.computeIfAbsent(g, k -> new ArrayList<>()).add(e);
        }
        expanded.addAll(groups.keySet()); // start with everything expanded
    }

    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<OpenBrowserPacket.Entry>> g : groups.entrySet()) {
            rows.add(new Row(g.getKey(), null));
            if (expanded.contains(g.getKey())) {
                for (OpenBrowserPacket.Entry e : g.getValue()) rows.add(new Row(g.getKey(), e));
            }
        }
        return rows;
    }

    @Override
    protected void init() {
        super.init();
        // Generic browser: no named entity, so no top-left name and no subtitle (suite title convention).
        int footerY = net.shurui.dev.sdu.client.gui.theme.GuiTheme.footerY(UI_H);

        if (groups.isEmpty()) {
            label(net.minecraft.client.resources.language.I18n.get("gui.dmz_ragnarok.tournaments.browser.empty"), 12, LIST_TOP + 6);
            btn(UI_W / 2 - 45, footerY, 90, 14, Component.translatable("gui.dmz_ragnarok.tournaments.common.close"), this::onClose);
            return;
        }

        // row cap from the gap between list top and footer, so the list fills the panel
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        List<Row> rows = buildRows();
        int total = rows.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            Row row = rows.get(i);
            if (row.isHeader()) {
                int size = groups.get(row.group()).size();
                boolean open = expanded.contains(row.group());
                String arrow = open ? "▾ " : "▸ ";
                // stop at rowControlRight() so headers clear the scrollbar column when the list overflows
                btn(10, y, rowControlRight() - 10, 14, Component.translatable("gui.dmz_ragnarok.tournaments.browser.group",
                                arrow, ColorCodes.translate(row.group()), size),
                        () -> { toggle(row.group()); rebuildWidgets(); });
            } else {
                OpenBrowserPacket.Entry e = row.entry();
                Component state = e.signupOpen()
                        ? Component.translatable("gui.dmz_ragnarok.tournaments.browser.state_open")
                        : Component.translatable("gui.dmz_ragnarok.tournaments.browser.state_closed");
                btn(20, y, rowControlRight() - 20, 14, Component.translatable("gui.dmz_ragnarok.tournaments.browser.entry",
                                ColorCodes.translate(e.name()), e.formatLabel(), state, e.count()),
                        () -> { TournamentNet.sendToServer(new OpenSignupRequestPacket(e.defId())); });
            }
            y += ROW_H;
        }
        scrollList(10, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });

        btn(UI_W / 2 - 45, footerY, 90, 14, Component.translatable("gui.dmz_ragnarok.tournaments.common.close"), this::onClose);
    }

    private void toggle(String group) {
        if (!expanded.remove(group)) expanded.add(group);
    }
}
