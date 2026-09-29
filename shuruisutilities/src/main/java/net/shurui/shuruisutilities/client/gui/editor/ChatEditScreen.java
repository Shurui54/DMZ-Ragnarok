package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// chat editor: tabbed. Settings edits chat format, welcome message, scoreboard toggle; Login edits per-login
// lines (up to MAX_LINES). fields persist across tabs so Save gathers both.
// meta = [chatFormat, welcomeMessage, scoreboardEnabled], rows = one login line each.
public class ChatEditScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int MAX_LINES = 6;
    private static final String[] SECTION_KEYS = {
            "gui.dmz_ragnarok.core.chat.tab_settings", "gui.dmz_ragnarok.core.chat.tab_login" };

    private String chatFormat;
    private String welcome;
    private boolean scoreboard;
    private final String[] lines = new String[MAX_LINES];
    private int section = 0;

    public ChatEditScreen(List<String> meta, List<List<String>> loginRows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.chat"), UI_W, UI_H, null);
        this.chatFormat = meta.size() > 0 ? meta.get(0) : "";
        this.welcome = meta.size() > 1 ? meta.get(1) : "";
        this.scoreboard = meta.size() > 2 && Boolean.parseBoolean(meta.get(2));
        for (int i = 0; i < MAX_LINES; i++)
            lines[i] = i < loginRows.size() && !loginRows.get(i).isEmpty() ? loginRows.get(i).get(0) : "";
    }

    private void selectSection(int s)
    {
        applyFields();
        section = s;
        rebuildWidgets();
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        // No centred "Chat" descriptor: it collided with the tab row and only restated what the tabs already show.
        rowY = buildTabHeader(null, trAll(SECTION_KEYS), section, this::selectSection);

        if (section == 0)
        {
            tf(tr("gui.dmz_ragnarok.core.chat.format"), chatFormat, v -> chatFormat = v);
            tip(tr("gui.dmz_ragnarok.core.chat.format_tip"));
            tf(tr("gui.dmz_ragnarok.core.chat.welcome"), welcome, v -> welcome = v);
            tip(tr("gui.dmz_ragnarok.core.chat.welcome_tip"));
            bf(tr("gui.dmz_ragnarok.core.chat.scoreboard"), scoreboard, () -> scoreboard = !scoreboard);
        }
        else
        {
            for (int i = 0; i < MAX_LINES; i++)
            {
                final int idx = i;
                tf(tr("gui.dmz_ragnarok.core.chat.line", i + 1), lines[i], v -> lines[idx] = v);
            }
            tip(tr("gui.dmz_ragnarok.core.chat.login_tip"));
        }

        btn(14, footerY(), 70, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.save"), this::save);
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private void save()
    {
        applyFields();
        List<String> args = new ArrayList<>();
        args.add(chatFormat);
        args.add(welcome);
        args.add(Boolean.toString(scoreboard));
        for (String l : lines)
            args.add(l == null ? "" : l);
        EditorScreens.act("chat", "save", args);
    }
}
