package net.shurui.dev.sdu.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.config.editor.ConfigEditorRegistry;
import net.shurui.dev.sdu.config.editor.ConfigTabs;
import net.shurui.dev.sdu.config.editor.OptionType;
import net.shurui.dev.sdu.config.editor.OptionView;
import net.shurui.dev.sdu.network.ConfigEditorDataPacket;
import net.shurui.dev.sdu.network.ConfigEditorRequestPacket;
import net.shurui.dev.sdu.network.DmzNet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The SDU options menu: every config option of the suite that makes sense to change in game, grouped into tabs, plus
 * a Client tab for this player's own settings and an Editors tab that links to the editors that own the rest.
 *
 * <p>Everything here is data driven. The server describes one tab at a time ({@link ConfigEditorDataPacket}); this
 * screen draws whatever it is told (a toggle, a number or text box, a dropdown, a list editor) and sends the pending
 * edits back in one {@link ConfigEditorRequestPacket}, where each is checked against its spec before it is stored.
 * Client options are described and saved locally through {@link ConfigEditorRegistry}, with no packet.
 *
 * <p>Pending edits survive switching tabs and are sent together on Save. Each row shows its default and range on
 * hover, a reset button when it differs from the default, and an amber "restart" tag when the change only applies
 * after a restart.
 */
public class OptionsOverviewScreen extends FieldEditScreen {

    private static final int UI_W = 400;
    private static final int UI_H = 260;
    private static final int TAB_X = 8;
    private static final int TAB_W = 92;
    private static final int TAB_STEP = GuiTheme.BUTTON_HEIGHT + 1;
    private static final int X0 = TAB_X + TAB_W + 6;
    private static final int LABEL_W = 132;
    private static final int CTRL_X = X0 + LABEL_W + 4;
    private static final int CTRL_W = 112;
    private static final int RESET_X = CTRL_X + CTRL_W + 4;
    private static final int ROW = 13;
    private static final int TOP = GuiTheme.CONTENT_TOP;

    private record Box(EditBox box, OptionView view) {
    }

    private ConfigEditorDataPacket data;
    private String tab;
    private String query;
    private String searchText;
    private List<OptionView> views;
    /** Edits not saved yet, by option id, across every tab (server and client). */
    private final Map<String, String> pending = new LinkedHashMap<>();
    /** Ids whose last typed text did not parse or was out of range here, with why. */
    private final Map<String, String> localErrors = new HashMap<>();
    private Map<String, String> serverErrors = new HashMap<>();
    /** Ids sent with the last save, so the reply can clear the ones that were stored. */
    private final Set<String> lastSent = new LinkedHashSet<>();
    private final List<Box> boxes = new ArrayList<>();
    private final Map<String, OptionView> clientById = new HashMap<>();
    private final Map<String, DmzDropdown> dropdownById = new HashMap<>();
    private final Map<String, Integer> rowById = new HashMap<>();
    private EditBox searchBox;
    private String status = "";
    private boolean statusBad;
    private int tabScroll;

    private OptionsOverviewScreen(ConfigEditorDataPacket data) {
        super(Component.translatable("gui.dmz_ragnarok.npc.options.title"), UI_W, UI_H, null);
        this.data = data;
        this.tab = data.tab;
        this.query = data.query;
        this.searchText = data.query;
        this.views = new ArrayList<>(data.options);
    }

    /** A tab or a save result arrived from the server: open the screen, or refresh it in place. */
    public static void receive(ConfigEditorDataPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof OptionsOverviewScreen s) {
            s.accept(packet);
        } else if (mc.screen instanceof OptionListEditScreen l && l.owner() != null) {
            l.owner().acceptQuietly(packet);
        } else {
            mc.setScreen(new OptionsOverviewScreen(packet));
        }
    }

    private void acceptQuietly(ConfigEditorDataPacket packet) {
        data = packet;
    }

    private void accept(ConfigEditorDataPacket packet) {
        boolean moved = !packet.tab.equals(tab) || !packet.query.equals(query);
        data = packet;
        // A save sent from a local tab (Client, Editors) comes back for a server tab; stay where the player is.
        if (!(isLocalTab(tab) && packet.query.isEmpty() && query.isEmpty() && !packet.statusKey.isEmpty())) {
            tab = packet.tab;
        }
        query = packet.query;
        searchText = packet.query;
        views = new ArrayList<>(packet.options);
        if (!query.isEmpty()) {
            views.addAll(clientViews(query));
        }
        serverErrors = new HashMap<>(packet.errors);
        if (!packet.statusKey.isEmpty()) {
            for (String id : lastSent) {
                if (!serverErrors.containsKey(id)) {
                    pending.remove(id);
                }
            }
            lastSent.clear();
            status = Component.translatable(packet.statusKey, packet.statusArgs.toArray()).getString();
            statusBad = !serverErrors.isEmpty() || packet.statusKey.endsWith(".failed");
        }
        if (moved) {
            scroll = 0;
        }
        rebuildWidgets();
    }

    private static boolean isLocalTab(String t) {
        return ConfigTabs.CLIENT.equals(t) || ConfigTabs.EDITORS.equals(t);
    }

    private List<OptionView> clientViews(String q) {
        List<OptionView> list;
        try {
            list = ConfigEditorRegistry.clientViews(q);
        } catch (RuntimeException e) {
            list = List.of();
        }
        if (q.isEmpty()) {
            clientById.clear();
        }
        for (OptionView v : list) {
            clientById.put(v.id(), v);
        }
        return list;
    }

    // ---- layout ----

    private List<String[]> tabList() {
        List<String[]> out = new ArrayList<>();
        for (ConfigEditorDataPacket.TabCount t : data.tabs) {
            out.add(new String[]{t.id(), tr(ConfigTabs.langKey(t.id()))});
        }
        if (!clientViews("").isEmpty()) {
            out.add(new String[]{ConfigTabs.CLIENT, tr(ConfigTabs.langKey(ConfigTabs.CLIENT))});
        }
        out.add(new String[]{ConfigTabs.EDITORS, tr(ConfigTabs.langKey(ConfigTabs.EDITORS))});
        return out;
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        boxes.clear();
        dropdownById.clear();
        rowById.clear();
        headerSubtitle = query.isEmpty() ? tr("gui.dmz_ragnarok.npc.options.subtitle")
                : tr("gui.dmz_ragnarok.npc.options.search_results", query);

        int sy = TOP;
        int contentTop = sy + GuiTheme.BUTTON_HEIGHT + 4;
        int contentBottom = footerY() - 28;
        beginScrollBand(contentTop, contentBottom);
        rowY = contentTop - scroll;
        if (query.isEmpty() && ConfigTabs.EDITORS.equals(tab)) {
            buildDirectory();
        } else {
            if (query.isEmpty() && ConfigTabs.CLIENT.equals(tab)) {
                views = new ArrayList<>(clientViews(""));
            }
            buildOptions();
        }
        finishScrollBand(contentTop, contentBottom, rowY);

        // Built AFTER the band closes: finishScrollBand hides every widget that exists at that moment outside the
        // band, so the tab column and the search row (above the band) must be added after it.
        buildTabColumn();
        searchBox = field(X0, sy + 2, 170, searchText, 64);
        searchBox.setHint(Component.translatable("gui.dmz_ragnarok.npc.options.search_hint"));
        searchBox.setResponder(v -> searchText = v);
        btn(X0 + 174, sy, 48, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.options.find"),
                this::runSearch);
        if (!query.isEmpty()) {
            btn(X0 + 226, sy, 48, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.options.clear"),
                    this::clearSearch);
        }

        int fy = footerY();
        int bw = 80;
        commitBtn(X0, fy, bw, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.options.save_n",
                pending.size()), this::save);
        btn(X0 + bw + 6, fy, bw, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.options.revert"),
                () -> {
                    pending.clear();
                    localErrors.clear();
                    status = "";
                    rebuildWidgets();
                });
        btn(X0 + 2 * (bw + 6), fy, bw, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.menu"),
                () -> minecraft.setScreen(new SduHubScreen()));
    }

    private void buildTabColumn() {
        List<String[]> tabs = tabList();
        int top = TOP;
        int bottom = footerY() + footerBtnHeight();
        int fit = Math.max(2, (bottom - top) / TAB_STEP);
        boolean overflow = tabs.size() > fit;
        int shown = overflow ? fit - 1 : fit;
        tabScroll = Math.max(0, Math.min(tabScroll, Math.max(0, tabs.size() - shown)));
        int y = top;
        for (int i = tabScroll; i < tabs.size() && i < tabScroll + shown; i++) {
            String[] t = tabs.get(i);
            boolean on = query.isEmpty() && t[0].equals(tab);
            String text = fit(t[1], TAB_W - 8);
            // the active tab reads gold with a marker (a rect here would be hidden: it sits outside the band)
            btn(TAB_X, y, TAB_W, GuiTheme.BUTTON_HEIGHT,
                    Component.literal(on ? "§e\u25b6 " + fit(t[1], TAB_W - 18) : text), () -> selectTab(t[0]));
            y += TAB_STEP;
        }
        if (overflow) {
            int half = TAB_W / 2 - 1;
            btn(TAB_X, y, half, GuiTheme.BUTTON_HEIGHT, Component.literal("▲"), () -> {
                applyFields();
                tabScroll = Math.max(0, tabScroll - 3);
                rebuildWidgets();
            });
            btn(TAB_X + half + 2, y, half, GuiTheme.BUTTON_HEIGHT, Component.literal("▼"), () -> {
                applyFields();
                tabScroll = tabScroll + 3;
                rebuildWidgets();
            });
        }
    }

    private String fit(String s, int w) {
        if (font == null || font.width(s) <= w) {
            return s;
        }
        return font.plainSubstrByWidth(s, Math.max(0, w - font.width("..."))) + "...";
    }

    private void buildOptions() {
        if (views.isEmpty()) {
            label("§7" + tr(query.isEmpty() ? "gui.dmz_ragnarok.npc.options.empty"
                    : "gui.dmz_ragnarok.npc.options.no_results"), X0 + 2, rowY + 2);
            rowY += ROW;
            return;
        }
        String group = null;
        for (OptionView v : views) {
            String g = query.isEmpty() ? v.group() : tr(ConfigTabs.langKey(v.tab())) + "  »  " + v.group();
            if (!g.equals(group)) {
                group = g;
                rowY += 2;
                label("§6" + fit(g, UI_W - X0 - 20), X0, rowY + 2);
                rowY += ROW;
            }
            optionRow(v);
        }
        rowY += 4;
        label("§7" + tr("gui.dmz_ragnarok.npc.options.legend"), X0, rowY + 2);
        rowY += ROW;
    }

    private String labelOf(OptionView v) {
        String key = v.labelKey();
        return key != null && !key.isEmpty() && I18n.exists(key) ? I18n.get(key) : v.label();
    }

    private String shown(OptionView v) {
        return pending.getOrDefault(v.id(), v.value());
    }

    private void optionRow(OptionView v) {
        rowById.put(v.id(), rowY + scroll);
        String value = shown(v);
        String err = localErrors.containsKey(v.id()) ? localErrors.get(v.id()) : serverErrors.get(v.id());
        boolean modified = pending.containsKey(v.id()) && !pending.get(v.id()).equals(v.value());
        String restartTag = v.restart() ? " " + tr("gui.dmz_ragnarok.npc.options.restart_tag") : "";
        String color = err != null ? "§c" : modified ? "§b" : "";
        int tagW = font == null ? 0 : font.width(restartTag);
        String text = color + fit(labelOf(v), LABEL_W - tagW - 2) + (v.restart() ? "§6" + restartTag : "");
        label(text, X0 + 2, rowY + 2);
        tooltip(X0, rowY, LABEL_W, ROW, tooltipFor(v, err));

        switch (v.type()) {
            case BOOL -> {
                boolean on = Boolean.parseBoolean(value);
                btn(CTRL_X, rowY, CTRL_W, 11, Component.translatable(on ? "gui.dmz_ragnarok.npc.common.on"
                        : "gui.dmz_ragnarok.npc.common.off"), () -> {
                    applyFields();
                    setPending(v, Boolean.toString(!on));
                    rebuildWidgets();
                });
            }
            case ENUM -> {
                List<String> choices = v.choices();
                int idx = Math.max(0, choices.indexOf(value));
                dropdownById.put(v.id(), choiceAt(CTRL_X, rowY, CTRL_W, choices, idx, i -> setPending(v, choices.get(i))));
            }
            case LIST -> {
                int n = OptionView.splitList(value).size();
                btn(CTRL_X, rowY, CTRL_W, 11, Component.translatable("gui.dmz_ragnarok.npc.options.list_items", n),
                        () -> {
                            applyFields();
                            minecraft.setScreen(new OptionListEditScreen(this, v, labelOf(v), value,
                                    nv -> setPending(v, nv)));
                        }).opensScreen();
            }
            default -> {
                EditBox b = field(CTRL_X, rowY + 1, CTRL_W, value, v.type() == OptionType.STRING ? 4096 : 64);
                boxes.add(new Box(b, v));
            }
        }
        String def = v.def();
        if (!value.equals(def)) {
            btn(RESET_X, rowY, 14, 11, Component.literal("↺"), () -> {
                applyFields();
                setPending(v, def);
                localErrors.remove(v.id());
                rebuildWidgets();
            });
            tooltip(RESET_X, rowY, 14, 11, tr("gui.dmz_ragnarok.npc.options.reset_tip", display(v, def)));
        }
        rowY += ROW;
    }

    private String display(OptionView v, String value) {
        if (v.type() == OptionType.LIST) {
            List<String> parts = OptionView.splitList(value);
            return parts.isEmpty() ? tr("gui.dmz_ragnarok.npc.options.empty_list") : String.join(", ", parts);
        }
        return value.isEmpty() ? tr("gui.dmz_ragnarok.npc.options.blank") : value;
    }

    private String tooltipFor(OptionView v, String err) {
        StringBuilder sb = new StringBuilder();
        sb.append(labelOf(v)).append('\n');
        if (!v.comment().isEmpty()) {
            sb.append(v.comment()).append('\n');
        }
        sb.append(tr("gui.dmz_ragnarok.npc.options.default_is", display(v, v.def())));
        if (!v.min().isEmpty() && !v.max().isEmpty()) {
            sb.append('\n').append(tr("gui.dmz_ragnarok.npc.options.range_is", v.min(), v.max()));
        } else if (!v.min().isEmpty()) {
            sb.append('\n').append(tr("gui.dmz_ragnarok.npc.options.range_min", v.min()));
        } else if (!v.max().isEmpty()) {
            sb.append('\n').append(tr("gui.dmz_ragnarok.npc.options.range_max", v.max()));
        }
        if (v.type() == OptionType.ENUM) {
            sb.append('\n').append(tr("gui.dmz_ragnarok.npc.options.choices_are", String.join(", ", v.choices())));
        }
        if (v.restart()) {
            sb.append('\n').append(tr("gui.dmz_ragnarok.npc.options.restart_tip"));
        }
        sb.append('\n').append(tr("gui.dmz_ragnarok.npc.options.file_is", v.group(), v.id()));
        if (err != null) {
            sb.append('\n').append(tr("gui.dmz_ragnarok.npc.options.refused", err));
        }
        return sb.toString();
    }

    private void setPending(OptionView v, String value) {
        if (value.equals(v.value())) {
            pending.remove(v.id());
        } else {
            pending.put(v.id(), value);
        }
        serverErrors.remove(v.id());
    }

    @Override
    protected void applyFields() {
        super.applyFields();
        for (Box b : boxes) {
            String raw = b.box().getValue();
            OptionView v = b.view();
            String bad = check(v, raw);
            if (bad != null) {
                localErrors.put(v.id(), bad);
                if (!raw.equals(v.value())) {
                    pending.put(v.id(), raw);
                }
            } else {
                localErrors.remove(v.id());
                setPending(v, v.type() == OptionType.STRING ? raw : raw.trim());
            }
        }
    }

    /** The same type and range checks the server makes, so a bad entry is flagged before it is sent. */
    private String check(OptionView v, String raw) {
        String t = raw.trim();
        try {
            double d;
            switch (v.type()) {
                case INT -> d = Integer.parseInt(t);
                case LONG -> d = Long.parseLong(t);
                case DOUBLE -> {
                    d = Double.parseDouble(t);
                    if (Double.isNaN(d) || Double.isInfinite(d)) {
                        return tr("gui.dmz_ragnarok.npc.field.needs_number");
                    }
                }
                default -> {
                    return null;
                }
            }
            if ((!v.min().isEmpty() && d < Double.parseDouble(v.min()))
                    || (!v.max().isEmpty() && d > Double.parseDouble(v.max()))) {
                return tr("gui.dmz_ragnarok.npc.options.out_of_range", v.min(), v.max());
            }
            return null;
        } catch (NumberFormatException e) {
            return tr(v.type() == OptionType.DOUBLE ? "gui.dmz_ragnarok.npc.field.needs_number"
                    : "gui.dmz_ragnarok.npc.field.needs_whole_number");
        }
    }

    // ---- the Editors tab ----

    private void buildDirectory() {
        label("§e" + tr("gui.dmz_ragnarok.npc.options.hdr_directory"), X0, rowY + 2);
        rowY += ROW + 2;
        for (ConfigEditorDataPacket.LinkView l : data.links) {
            label("§b" + fit(tr(l.labelKey()), LABEL_W + 60), X0 + 2, rowY + 3);
            btn(RESET_X - 64, rowY, 78, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.open"),
                    () -> DmzNet.sendToServer(new ConfigEditorRequestPacket(ConfigEditorRequestPacket.LINK, l.id(), "",
                            Map.of()))).opensScreen();
            tooltip(X0, rowY, LABEL_W + 60, ROW, tr(l.tipKey()));
            rowY += GuiTheme.BUTTON_HEIGHT + 2;
        }
        // Areas edited inside another editor, listed so an admin knows where they live.
        for (String k : new String[]{"form_types", "classes", "racials"}) {
            label("§7" + fit(tr("gui.dmz_ragnarok.npc.options.dir_" + k), LABEL_W + 120), X0 + 2, rowY + 2);
            tooltip(X0, rowY, LABEL_W + 120, ROW, tr("gui.dmz_ragnarok.npc.options.t_dir_" + k));
            rowY += ROW;
        }
    }

    // ---- actions ----

    private void selectTab(String id) {
        applyFields();
        query = "";
        searchText = "";
        scroll = 0;
        status = "";
        tab = id;
        if (isLocalTab(id)) {
            rebuildWidgets();
        } else {
            DmzNet.sendToServer(new ConfigEditorRequestPacket(ConfigEditorRequestPacket.OPEN, id, "", Map.of()));
        }
    }

    private void runSearch() {
        applyFields();
        String q = searchText == null ? "" : searchText.trim();
        if (q.isEmpty()) {
            clearSearch();
            return;
        }
        DmzNet.sendToServer(new ConfigEditorRequestPacket(ConfigEditorRequestPacket.OPEN, tab, q, Map.of()));
    }

    private void clearSearch() {
        applyFields();
        searchText = "";
        query = "";
        if (isLocalTab(tab)) {
            rebuildWidgets();
        } else {
            DmzNet.sendToServer(new ConfigEditorRequestPacket(ConfigEditorRequestPacket.OPEN, tab, "", Map.of()));
        }
    }

    private void save() {
        applyFields();
        if (clientById.isEmpty()) {
            clientViews("");
        }
        Map<String, String> server = new LinkedHashMap<>();
        Map<String, String> local = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : pending.entrySet()) {
            if (localErrors.containsKey(e.getKey())) {
                continue;
            }
            (clientById.containsKey(e.getKey()) ? local : server).put(e.getKey(), e.getValue());
        }
        status = "";
        statusBad = false;
        if (!localErrors.isEmpty()) {
            status = tr("gui.dmz_ragnarok.npc.options.status.fix_first", localErrors.size());
            statusBad = true;
        }
        if (!local.isEmpty()) {
            ConfigEditorRegistry.SaveResult r = ConfigEditorRegistry.saveClient(local);
            for (String id : local.keySet()) {
                if (!r.errors().containsKey(id)) {
                    pending.remove(id);
                }
            }
            serverErrors.putAll(r.errors());
            if (server.isEmpty() && localErrors.isEmpty()) {
                status = r.errors().isEmpty()
                        ? tr(r.restartNeeded() ? "gui.dmz_ragnarok.npc.options.status.saved_restart"
                        : "gui.dmz_ragnarok.npc.options.status.saved", r.changed())
                        : tr("gui.dmz_ragnarok.npc.options.status.partial", r.changed(), r.errors().size());
                statusBad = !r.errors().isEmpty();
            }
            clientViews("");
        }
        if (!server.isEmpty()) {
            lastSent.clear();
            lastSent.addAll(server.keySet());
            String replyTab = isLocalTab(tab) ? ConfigTabs.GENERAL : tab;
            DmzNet.sendToServer(new ConfigEditorRequestPacket(ConfigEditorRequestPacket.SAVE, replyTab, query, server));
        }
        rebuildWidgets();
    }

    /**
     * Drives the screen without a mouse, for the dev visual test harness only: {@code tab <id>}, {@code search <q>},
     * {@code scrollto <option id>}, {@code dropdown <option id>}, {@code list <option id>}, {@code set <id>=<value>}
     * and {@code save}. Nothing in the game calls it.
     */
    public void debugAction(String action, String arg) {
        switch (action) {
            case "tab" -> selectTab(arg);
            case "search" -> {
                searchText = arg;
                runSearch();
            }
            case "scrollto" -> {
                Integer y = rowById.get(arg);
                if (y != null) {
                    scroll = Math.max(0, y - (TOP + GuiTheme.BUTTON_HEIGHT + 4) - ROW * 2);
                    rebuildWidgets();
                }
            }
            case "dropdown" -> {
                DmzDropdown d = dropdownById.get(arg);
                if (d != null) {
                    openDropdown = d;
                }
            }
            case "list" -> {
                for (OptionView v : views) {
                    if (v.id().equals(arg)) {
                        minecraft.setScreen(new OptionListEditScreen(this, v, labelOf(v), shown(v),
                                nv -> setPending(v, nv)));
                    }
                }
            }
            case "set" -> {
                int eq = arg.indexOf('=');
                for (OptionView v : views) {
                    if (eq > 0 && v.id().equals(arg.substring(0, eq))) {
                        setPending(v, arg.substring(eq + 1));
                    }
                }
                rebuildWidgets();
            }
            case "save" -> save();
            default -> { }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox != null && searchBox.isFocused() && (keyCode == 257 || keyCode == 335)) {
            runSearch();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (openDropdown == null && toVirtualX(mx) < X0 - 2) {
            int before = tabScroll;
            tabScroll = Math.max(0, tabScroll - (int) Math.signum(delta));
            if (tabScroll != before || delta < 0) {
                applyFields();
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    protected void renderTopOverlay(GuiGraphics g, int vmx, int vmy) {
        super.renderTopOverlay(g, vmx, vmy);
        if (!status.isEmpty() && font != null && !hasFieldWarning()) {
            var lines = font.split(Component.literal(status), UI_W - X0 - 12);
            int y = footerY() - 3 - lines.size() * (font.lineHeight + 1);
            for (var line : lines) {
                g.drawString(font, line, X0, y, statusBad ? 0xFFFF6B6B : 0xFF7CF27C, false);
                y += font.lineHeight + 1;
            }
        }
    }
}
