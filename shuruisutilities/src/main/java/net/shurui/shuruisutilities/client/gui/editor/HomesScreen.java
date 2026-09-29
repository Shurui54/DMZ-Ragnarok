package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// home menu: TP to your home (/home) and, if allowed, set it (/home set). meta = [hasHome, canSet] so the
// buttons reflect perms + whether a home exists.
public class HomesScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final boolean hasHome;
    private final boolean canSet;

    public HomesScreen(List<String> meta)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.menu.homes"), UI_W, UI_H, null);
        this.hasHome = meta.size() > 0 && Boolean.parseBoolean(meta.get(0));
        this.canSet = meta.size() > 1 && Boolean.parseBoolean(meta.get(1));
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = hasHome ? tr("gui.dmz_ragnarok.core.homes.set") : tr("gui.dmz_ragnarok.core.homes.none");
        int cx = UI_W / 2 - 60;
        if (hasHome)
            btn(cx, 42, 120, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.homes.go"), () -> { EditorScreens.runCommand("home"); onClose(); });
        else
            label("§7" + tr("gui.dmz_ragnarok.core.homes.no_home"), cx, 46, 0xFFB0B0B0);

        if (canSet)
            btn(cx, 64, 120, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.homes.set_here"),
                    () -> { EditorScreens.runCommand("home set"); EditorScreens.openPlayerHub(); });

        btn(UI_W / 2 - 45, footerY(), 90, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openPlayerHub);
    }
}
