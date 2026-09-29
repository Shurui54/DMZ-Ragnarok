package net.shurui.shuruisutilities.client.gui.editor;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.shuruisutilities.client.gui.SUHubScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// economy edit view: one Balance field for a player. Save sends "set uuid amount"; server applies + re-sends
// the list.
public class EconomyEditScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final String currency;
    private final String uuid;
    private final String name;
    private String balance;

    public EconomyEditScreen(String currency, String uuid, String name, String balance)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.economy"), UI_W, UI_H, null);
        this.currency = currency;
        this.uuid = uuid;
        this.name = name;
        this.balance = balance;
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        headerName = name;
        rowY = 40;
        tf(tr("gui.dmz_ragnarok.core.economy.balance_field", net.shurui.shuruisutilities.util.output.ChatOutputHandler.formatColors(currency)), balance, v -> balance = v);
        tip(tr("gui.dmz_ragnarok.core.economy.balance_tip"));

        btn(14, footerY(), 70, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.save"), () -> {
            applyFields();
            EditorScreens.act("economy", "set", uuid, balance.trim());
        });
        btn(90, footerY(), 70, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                () -> EditorScreens.reopen("economy"));
        btn(UI_W - 84, footerY(), 70, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }
}
