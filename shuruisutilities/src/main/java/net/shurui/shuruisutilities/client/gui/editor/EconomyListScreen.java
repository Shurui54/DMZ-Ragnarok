package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.SUHubScreen;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// economy list: each player with a balance, name + amount; click to edit. rows: [uuid, name, balance].
public class EconomyListScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 16;

    private final String currency;
    private final List<List<String>> rows;
    private int scroll = 0;

    public EconomyListScreen(String currency, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.economy"), UI_W, UI_H, null);
        this.currency = currency;
        this.rows = rows;
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.core.economy.subtitle", rows.size());
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - maxRows)));
        int end = Math.min(rows.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            List<String> r = rows.get(i);
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            rowBtn(14, ry, rowControlRight() - 14, GuiTheme.ROW_HEIGHT, Component.literal(r.get(1)),
                    () -> minecraft.setScreen(new EconomyEditScreen(currency, r.get(0), r.get(1), r.get(2))))
                    .right(Component.literal(fmt(r.get(2)) + " "
                            + net.shurui.shuruisutilities.util.output.ChatOutputHandler.formatColors(currency)), 0xFFFFFF55);
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, rows.size(), scroll, v -> { scroll = v; rebuildWidgets(); });
        btn(UI_W / 2 - 45, footerY(), 90, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                net.shurui.shuruisutilities.client.gui.EditorScreens::openAdminHub);
    }

    private static String fmt(String amount)
    {
        try
        {
            return String.format("%,d", Long.parseLong(amount));
        }
        catch (NumberFormatException e)
        {
            return amount;
        }
    }
}
