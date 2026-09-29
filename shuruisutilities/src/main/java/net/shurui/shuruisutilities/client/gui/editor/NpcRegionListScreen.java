package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// NPC regions list: each region + dim + what it spawns. click to open its editor; small buttons TP/delete.
// new regions come from a right-drag on Xaero's World Map (or /npcregion define). rows: [name, dim, "entity xN"].
public class NpcRegionListScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 16;

    private final List<List<String>> rows;
    private int scroll = 0;

    public NpcRegionListScreen(List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.npcregions"), UI_W, UI_H, null);
        this.rows = rows;
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.core.npcregion.subtitle", rows.size());
        // Fill the shared canvas rather than the old fixed cap; the new-region hint sits on the footer row below.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - maxRows)));
        int end = Math.min(rows.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            List<String> r = rows.get(i);
            final String name = r.get(0);
            String dim = r.size() > 1 ? shortDim(r.get(1)) : "";
            String what = r.size() > 2 ? r.get(2) : "";
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            // trailing TP + X pair right-aligned to the scrollbar-reserved column edge
            int xW = 22, tpW = 20;
            int xBtn = rowControlRight() - xW;
            int tpBtn = xBtn - 2 - tpW;
            rowBtn(14, ry, tpBtn - 4 - 14, ROW_H, Component.literal(name),
                    () -> EditorScreens.act("npcregions", "edit", name)).color(0xFF9BE0AB)
                    .right(Component.literal(dim + " §7" + what), 0xFFB0B0B0);
            btn(tpBtn, ry, tpW, ROW_H - 2, Component.literal("§bTP"),
                    () -> EditorScreens.act("npcregions", "tp", name));
            btn(xBtn, ry, xW, ROW_H - 2, Component.literal("§cX"),
                    () -> EditorScreens.act("npcregions", "delete", name));
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, rows.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        label("§7" + tr("gui.dmz_ragnarok.core.npcregion.new_hint"), 14, UI_H - 22, 0xFF888888);
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private static String shortDim(String dim)
    {
        int i = dim.indexOf(':');
        return i >= 0 ? dim.substring(i + 1) : dim;
    }
}
