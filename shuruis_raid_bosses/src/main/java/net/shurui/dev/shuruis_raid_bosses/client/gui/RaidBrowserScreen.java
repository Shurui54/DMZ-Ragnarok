package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_raid_bosses.network.OpenBrowserPacket;
import net.shurui.dev.shuruis_raid_bosses.network.RaidNet;
import net.shurui.dev.shuruis_raid_bosses.network.RequestSignupPacket;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidType;
import net.shurui.dev.shuruis_raid_bosses.util.ColorCodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * The raid-NPC menu: every joinable raid grouped into collapsible category "dropdowns". Clicking a
 * category header expands/collapses it; clicking a raid asks the server to open that raid's sign-up
 * screen. Built on {@link BaseEditScreen} for the shared DMZ styling and scroll handling.
 */
public class RaidBrowserScreen extends BaseEditScreen {
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 30;

    private final Map<String, List<OpenBrowserPacket.Entry>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> collapsed = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private int scroll = 0;

    private record Row(boolean header, String category, OpenBrowserPacket.Entry entry) {}

    public RaidBrowserScreen(List<OpenBrowserPacket.Entry> entries) {
        super(Component.translatable("gui.dmz_ragnarok.raid.browser.title"), UI_W, UI_H, null);
        for (OpenBrowserPacket.Entry e : entries) {
            groups.computeIfAbsent(e.category(), k -> new ArrayList<>()).add(e);
        }
        for (List<OpenBrowserPacket.Entry> list : groups.values()) {
            list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        }
    }

    /** Flatten the category groups into visible rows, honouring which categories are collapsed. */
    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        for (Map.Entry<String, List<OpenBrowserPacket.Entry>> g : groups.entrySet()) {
            out.add(new Row(true, g.getKey(), null));
            if (!collapsed.contains(g.getKey())) {
                for (OpenBrowserPacket.Entry e : g.getValue()) out.add(new Row(false, g.getKey(), e));
            }
        }
        return out;
    }

    @Override
    protected void init() {
        super.init();
        // Generic browser screen: no descriptive header label (suite convention drops it); only the logo heads it.

        if (groups.isEmpty()) {
            label(I18n.get("gui.dmz_ragnarok.raid.browser.empty"), 16, LIST_TOP + 8);
            btn(UI_W / 2 - 45, footerY(), 90, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.common.close"), this::onClose);
            return;
        }

        // Derive the row cap from the space between the list top and the footer so the list fills the panel.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        List<Row> rows = rows();
        int total = rows.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            Row r = rows.get(i);
            if (r.header()) {
                int count = groups.get(r.category()).size();
                boolean open = !collapsed.contains(r.category());
                String arrow = open ? I18n.get("gui.dmz_ragnarok.raid.browser.arrow_open")
                        : I18n.get("gui.dmz_ragnarok.raid.browser.arrow_closed");
                // Header spans from the left inset to rowControlRight() so it never reaches under the scrollbar.
                btn(12, y, rowControlRight() - 12, 14, Component.literal(
                        I18n.get("gui.dmz_ragnarok.raid.browser.group", arrow, ColorCodes.translate(r.category()), count)),
                        () -> { toggle(r.category()); rebuildWidgets(); });
            } else {
                OpenBrowserPacket.Entry e = r.entry();
                // I18n.get(e.name()) is a pass-through for the owner display name (see RaidListScreen).
                label(I18n.get("gui.dmz_ragnarok.raid.browser.entry",
                        ColorCodes.translate(I18n.get(e.name())),
                        RaidType.byOrdinal(e.typeOrdinal()).display().getString()), 24, y + 4);
                label(stateLabel(e.stateOrdinal()), UI_W - 118, y + 4);
                // Join right-aligned to rowControlRight() so it stays clear of the scrollbar column.
                btn(rowControlRight() - 58, y, 58, 14, Component.translatable("gui.dmz_ragnarok.raid.browser.join"), () -> {
                    RaidNet.sendToServer(new RequestSignupPacket(e.id()));
                });
            }
            y += ROW_H;
        }
        scrollList(12, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });

        btn(UI_W / 2 - 45, footerY(), 90, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.common.close"), this::onClose);
    }

    private void toggle(String category) {
        if (!collapsed.remove(category)) collapsed.add(category);
    }

    private static String stateLabel(int ordinal) {
        return switch (ordinal) {
            case 1 -> I18n.get("gui.dmz_ragnarok.raid.browser.state_open");
            case 2 -> I18n.get("gui.dmz_ragnarok.raid.browser.state_starting");
            case 3 -> I18n.get("gui.dmz_ragnarok.raid.browser.state_progress");
            case 4 -> I18n.get("gui.dmz_ragnarok.raid.browser.state_finishing");
            default -> I18n.get("gui.dmz_ragnarok.raid.browser.state_idle");
        };
    }
}
