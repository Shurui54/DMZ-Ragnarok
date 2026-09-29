package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// protection flags for one group: each flag is an allow/deny/unset toggle that cycles on click and writes
// through the zone permission system. meta = [group], rows = [label, node, state] (0 unset/1 allow/2 deny).
public class ProtectionFlagsScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 14;

    private final String group;
    private final List<List<String>> rows;
    private int scroll = 0;

    public ProtectionFlagsScreen(String group, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.protection"), UI_W, UI_H, null);
        this.group = group;
        this.rows = rows;
    }

    @Override
    protected void init()
    {
        super.init();
        headerName = tr("gui.dmz_ragnarok.core.protection.group", net.shurui.shuruisutilities.client.gui.GroupNames.display(group));
        // Fill the shared canvas rather than the old fixed cap; nothing but the footer buttons sits below the list.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - maxRows)));
        int end = Math.min(rows.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            List<String> r = rows.get(i);
            final String node = r.get(1);
            String st = r.size() > 2 ? r.get(2) : "0";
            String tag = st.equals("1") ? tr("gui.dmz_ragnarok.core.protection.allow")
                    : st.equals("2") ? tr("gui.dmz_ragnarok.core.protection.deny")
                    : tr("gui.dmz_ragnarok.core.protection.unset");
            int color = st.equals("1") ? 0xFF55FF55 : st.equals("2") ? 0xFFFF5555 : 0xFF888888;
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            rowBtn(14, ry, rowControlRight() - 14, ROW_H, Component.literal(r.get(0)),
                    () -> EditorScreens.act("protection", "cycle", group, node)).right(Component.literal("[" + tag + "]"), color);
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, rows.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        btn(14, footerY(), 60, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                () -> EditorScreens.reopen("protection"));
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }
}
