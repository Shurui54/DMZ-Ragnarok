package net.shurui.dev.shuruis_dmz_tournaments.client.gui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import net.shurui.dev.shuruis_dmz_tournaments.network.SaveLoadoutPacket;
import net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet;
import net.shurui.dev.shuruis_dmz_tournaments.util.TextUtil;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * Tournament character creation / attack picker. Shows the ki and strike attacks the server offered this player, in
 * two groups (the server has already filtered out moves they could not legitimately hold, see TournamentMoveAccess).
 * The player equips EXACTLY {@code kiMax} ki and {@code strikeMax} strike attacks, and
 * Save only commits once both groups are full. The server re-validates the 4-and-4 split and builds the template.
 */
public class LoadoutScreen extends ScaledScreen {
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int GOLD = 0xFFF6E27A;
    private static final int HEADER_COL = 0xFFBFD4FF;
    private static final int ROW_STEP = 20;
    private static final int LIST_TOP = 44;
    // Space under the list for Save and Close: 6px gap above Save, two buttons, 22px step, bottom margin.
    private static final int TAIL = 50;
    /**
     * Row count follows the shared canvas. This screen used to size ITSELF from a fixed nine rows, so it
     * opened 200x284 while every other suite screen is 300x260 and the window jumped when a player reached
     * it. Now the canvas is fixed and the list uses whatever room is left above the buttons.
     */
    private static final int MAX_ROWS = Math.max(1, (UI_H - TAIL - LIST_TOP) / ROW_STEP);

    // a display row is either a group header (id == null) or a pickable attack
    private static final class Row {
        final String header;
        final String id;
        final boolean ki;      // for attack rows: ki (true) or strike (false)
        Row(String header, String id, boolean ki) { this.header = header; this.id = id; this.ki = ki; }
        static Row header(String h) { return new Row(h, null, false); }
        static Row attack(String id, boolean ki) { return new Row(null, id, ki); }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Set<String> kiSel = new LinkedHashSet<>();
    private final Set<String> strikeSel = new LinkedHashSet<>();
    private final int kiMax;
    private final int strikeMax;
    private final boolean creation;

    // header labels for the visible window, rebuilt each init: {y, kiSection}
    private final List<int[]> headerDraws = new ArrayList<>();
    private int scroll = 0;

    public LoadoutScreen(List<String> kiIds, List<String> strikeIds, List<String> selected,
                         int kiMax, int strikeMax, boolean creation) {
        super(Component.translatable(creation
                        ? "gui.dmz_ragnarok.tournaments.loadout.create_title"
                        : "gui.dmz_ragnarok.tournaments.loadout.title"),
                UI_W, UI_H);
        this.kiMax = kiMax;
        this.strikeMax = strikeMax;
        this.creation = creation;
        List<String> ki = kiIds == null ? List.of() : kiIds;
        List<String> strike = strikeIds == null ? List.of() : strikeIds;
        rows.add(Row.header("ki"));
        for (String id : ki) rows.add(Row.attack(id, true));
        rows.add(Row.header("strike"));
        for (String id : strike) rows.add(Row.attack(id, false));
        if (selected != null) {
            for (String s : selected) {
                if (ki.contains(s) && kiSel.size() < kiMax) kiSel.add(s);
                else if (strike.contains(s) && strikeSel.size() < strikeMax) strikeSel.add(s);
            }
        }
    }

    @Override
    protected void init() {
        super.init();
        headerDraws.clear();
        int w = UI_W - 80;
        int x = (UI_W - w) / 2;

        boolean scrollable = rows.size() > MAX_ROWS;
        int maxScroll = Math.max(0, rows.size() - MAX_ROWS);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        int shown = Math.min(MAX_ROWS, rows.size());
        int listBtnW = scrollable ? w - 14 : w;
        int y = LIST_TOP;
        for (int r = 0; r < shown; r++) {
            Row row = rows.get(scroll + r);
            if (row.header != null) {
                headerDraws.add(new int[] { y, "ki".equals(row.header) ? 1 : 0 });
            } else {
                boolean on = (row.ki ? kiSel : strikeSel).contains(row.id);
                String label = (on ? "&6✔ " : "&7") + pretty(row.id);
                addRenderableWidget(button(x, y, listBtnW, TextUtil.color(label), () -> toggle(row.id, row.ki)));
            }
            y += ROW_STEP;
        }
        if (scrollable) {
            int ax = x + w - 12;
            DmzTextureButton up = button(ax, LIST_TOP, 12, Component.literal("▲"), () -> scrollBy(-1));
            up.active = scroll > 0;
            addRenderableWidget(up);
            DmzTextureButton down = button(ax, LIST_TOP + ROW_STEP * (MAX_ROWS - 1), 12, Component.literal("▼"), () -> scrollBy(1));
            down.active = scroll < maxScroll;
            addRenderableWidget(down);
        }
        y = LIST_TOP + ROW_STEP * MAX_ROWS + 6;
        boolean complete = kiSel.size() == kiMax && strikeSel.size() == strikeMax;
        DmzTextureButton save = button(x, y, w, Component.translatable(
                "gui.dmz_ragnarok.tournaments.loadout.save2", kiSel.size(), kiMax, strikeSel.size(), strikeMax), this::save);
        save.active = complete;
        addRenderableWidget(save);
        y += 22;
        addRenderableWidget(button(x, y, w, Component.translatable("gui.dmz_ragnarok.tournaments.common.close"), this::onClose));
    }

    private void toggle(String id, boolean ki) {
        Set<String> set = ki ? kiSel : strikeSel;
        int max = ki ? kiMax : strikeMax;
        if (set.contains(id)) set.remove(id);
        else if (set.size() < max) set.add(id);
        rebuildWidgets();
    }

    private void scrollBy(int delta) {
        int maxScroll = Math.max(0, rows.size() - MAX_ROWS);
        int ns = Math.max(0, Math.min(maxScroll, scroll + delta));
        if (ns != scroll) { scroll = ns; rebuildWidgets(); }
    }

    private void save() {
        if (kiSel.size() != kiMax || strikeSel.size() != strikeMax) return;
        List<String> out = new ArrayList<>(kiSel);
        out.addAll(strikeSel);
        TournamentNet.sendToServer(new SaveLoadoutPacket(out));
        onClose();
    }

    private DmzTextureButton button(int x, int y, int w, Component label, Runnable onPress) {
        return new DmzTextureButton(x, y, w, 18, label,
                DmzTextures.MENU_BIG, DmzTextures.BUTTON_U, DmzTextures.BUTTON_V,
                DmzTextures.BUTTON_W, DmzTextures.BUTTON_H, DmzTextures.ATLAS, onPress);
    }

    // registry id -> readable label ("super_god_fist" -> "Super God Fist")
    private static String pretty(String id) {
        String[] parts = id.replace('.', ' ').replace('_', ' ').trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (rows.size() > MAX_ROWS) {
            double vy = toVirtualY(mouseY);
            if (vy >= LIST_TOP && vy < LIST_TOP + MAX_ROWS * ROW_STEP) {
                scrollBy(delta > 0 ? -1 : 1);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(originX(), originY(), 0);
        pose.scale((float) guiScale, (float) guiScale, 1.0f);

        DmzTextures.panel(g, 0, 0, uiWidth, uiHeight);
        net.shurui.dev.shuruis_dmz_tournaments.client.gui.theme.ThemeRender.header(g, 0, 0, uiWidth);
        Component title = getTitle();
        g.drawString(this.font, title, UI_W / 2 - this.font.width(title) / 2, 30, GOLD, false);

        // group headers as plain labels above their rows; counts live on the rows and Save, not here (no-chrome rule)
        int x = (UI_W - (UI_W - 80)) / 2;
        for (int[] h : headerDraws) {
            Component label = Component.translatable(h[1] == 1
                    ? "gui.dmz_ragnarok.tournaments.loadout.section_ki"
                    : "gui.dmz_ragnarok.tournaments.loadout.section_strike");
            g.drawString(this.font, label, x + 2, h[0] + 5, HEADER_COL, false);
        }

        super.render(g, (int) Math.round(toVirtualX(mouseX)), (int) Math.round(toVirtualY(mouseY)), partialTick);
        pose.popPose();
    }
}
