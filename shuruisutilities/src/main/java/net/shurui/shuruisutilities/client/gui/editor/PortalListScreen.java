package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// portals list: each portal + fill type; click to edit config, delete per row. new portals need a block
// selection (/portal), so this only edits existing ones. rows: [name, fillType].
public class PortalListScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 16;

    private final List<List<String>> rows;
    private int scroll = 0;

    public PortalListScreen(List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.portals"), UI_W, UI_H, null);
        this.rows = rows;
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.core.portal.subtitle", rows.size());
        // Fill the shared canvas rather than the old fixed cap; the new-portal hint sits on the footer row below.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - maxRows)));
        int end = Math.min(rows.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            final String name = rows.get(i).get(0);
            String fill = rows.get(i).size() > 1 ? rows.get(i).get(1) : "";
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            int delW = 52;
            int delX = rowControlRight() - delW;
            rowBtn(14, ry, delX - 4 - 14, ROW_H, Component.literal(name), () -> EditorScreens.act("portals", "open", name))
                    .right(Component.literal(fill), 0xFFB0B0B0);
            btn(delX, ry, delW, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.delete"),
                    () -> EditorScreens.act("portals", "delete", name));
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, rows.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        label("§7" + tr("gui.dmz_ragnarok.core.portal.new_hint"), 14, UI_H - 22, 0xFF888888);
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }
}
