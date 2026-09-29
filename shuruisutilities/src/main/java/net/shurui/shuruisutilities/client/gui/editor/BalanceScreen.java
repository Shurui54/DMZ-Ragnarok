package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// player's own balance, read-only. meta = [amount, currency]. editing is admin-only in the Economy editor.
public class BalanceScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final String amount;
    private final String currency;

    public BalanceScreen(List<String> meta)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.menu.balance"), UI_W, UI_H, null);
        this.amount = meta.size() > 0 ? meta.get(0) : "0";
        this.currency = meta.size() > 1 ? meta.get(1) : "Zeni";
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.core.balance.subtitle");
        labelCentered("§e" + fmt(amount) + " §f" + currency, UI_W / 2, 50, 0xFFFFFFFF);
        btn(UI_W / 2 - 45, footerY(), 90, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openPlayerHub);
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
