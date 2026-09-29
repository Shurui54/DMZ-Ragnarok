package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// protection group picker: pick a group, then toggle its flags. DENY on _ALL_ protects everyone; ALLOW it
// back for a trusted group/zone to grant it. rows: [name].
public class ProtectionListScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 16;

    private final List<List<String>> rows;
    private int scroll = 0;

    public ProtectionListScreen(List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.protection"), UI_W, UI_H, null);
        this.rows = rows;
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.core.protection.subtitle", rows.size());
        // Fill the shared canvas rather than the old fixed cap; the hint sits on the footer row below.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - maxRows)));
        int end = Math.min(rows.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            final String name = rows.get(i).get(0);
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            rowBtn(14, ry, rowControlRight() - 14, ROW_H,
                    Component.literal(net.shurui.shuruisutilities.client.gui.GroupNames.display(name)),
                    () -> EditorScreens.act("protection", "flags", name)).color(0xFFFFFF55);
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, rows.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        label("§7" + tr("gui.dmz_ragnarok.core.protection.hint"), 14, UI_H - 22, 0xFF888888);
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }
}
