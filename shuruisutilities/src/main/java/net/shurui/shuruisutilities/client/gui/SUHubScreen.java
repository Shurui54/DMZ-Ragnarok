package net.shurui.shuruisutilities.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.api.SUHub;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.hub.PacketOpenEditor;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

// the hub menu, admin or player mode (server decides). entries come from the server already perm-filtered, so
// this just renders + dispatches. clicking asks the server to open it (re-checked server-side). other mods'
// entries appended (admin hub only) via SUHub.
public class SUHubScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 22;
    private static final int LIST_TOP = 34;

    private record Item(String labelKey, String which, Runnable external) {}

    private final boolean admin;
    private final List<Item> items = new ArrayList<>();
    private int scroll;

    public SUHubScreen(boolean admin, List<String> which, List<String> labelKeys)
    {
        super(Component.translatable(admin ? "gui.dmz_ragnarok.core.hub.title" : "gui.dmz_ragnarok.core.menu.title"),
                UI_W, UI_H, null);
        this.admin = admin;
        int n = Math.min(which.size(), labelKeys.size());
        for (int i = 0; i < n; i++)
            items.add(new Item(labelKeys.get(i), which.get(i), null));
        // cross-mod entries appear only on the admin hub
        if (admin)
            for (SUHub.Entry e : SUHub.entries())
                items.add(new Item(e.labelKey(), null, e.open()));
    }

    // open a hub on the client (from PacketOpenHub)
    public static void open(boolean admin, List<String> which, List<String> labelKeys)
    {
        Minecraft.getInstance().setScreen(new SUHubScreen(admin, which, labelKeys));
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr(admin ? "gui.dmz_ragnarok.core.hub.subtitle" : "gui.dmz_ragnarok.core.menu.subtitle");
        // Rows are derived, not fixed: on the shared canvas a hardcoded 6 stopped the list a third of the way down
        // an otherwise empty panel.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        int total = items.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);
        if (total > maxRows)
            label(tr("gui.dmz_ragnarok.core.list.count", scroll + 1, end, total), 10, 20);

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++)
        {
            final Item it = items.get(i);
            Runnable onPress = it.external() != null ? it.external()
                    : () -> NetworkUtils.INSTANCE.sendToServer(new PacketOpenEditor(it.which()));
            // Full content width, centred: the old literal 31/178 was tuned to a 240-wide panel and left the whole
            // menu sitting left of centre once every screen moved to the shared canvas.
            btn(rowBandLeft(), y, rowBandWidth(), 18, Component.translatable(it.labelKey()), onPress);
            y += ROW_H;
        }
        scrollList(rowBandLeft(), uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll,
                v -> { scroll = v; rebuildWidgets(); });

        btn(UI_W / 2 - 45, footerY(), 90, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.close"), this::onClose);
    }
}
