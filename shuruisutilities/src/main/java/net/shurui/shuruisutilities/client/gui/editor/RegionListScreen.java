package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// regions list: each protection region + dim + priority; click to edit flags. new regions come from a
// selection via /serverclaim define <name>. rows arrive as [name, dim, priority].
public class RegionListScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 16;

    private final List<List<String>> rows;
    private int scroll = 0;

    public RegionListScreen(List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.regions"), UI_W, UI_H, null);
        this.rows = rows;
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.core.region.subtitle", rows.size());
        // Fill the shared canvas rather than the old fixed cap; the new-region hint sits on the footer row below.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - maxRows)));
        int end = Math.min(rows.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            List<String> r = rows.get(i);
            final String name = r.get(0);
            String dim = r.size() > 1 ? shortDim(r.get(1)) : "";
            String prio = r.size() > 2 ? r.get(2) : "0";
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            rowBtn(14, ry, rowControlRight() - 14, ROW_H, Component.literal(name),
                    () -> EditorScreens.act("regions", "flags", name)).color(0xFF9BE0AB)
                    .right(Component.literal(dim + " §7p" + prio), 0xFFB0B0B0);
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, rows.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        label("§7" + tr("gui.dmz_ragnarok.core.region.new_hint"), 14, UI_H - 22, 0xFF888888);
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private static String shortDim(String dim)
    {
        int i = dim.indexOf(':');
        return i >= 0 ? dim.substring(i + 1) : dim;
    }
}
