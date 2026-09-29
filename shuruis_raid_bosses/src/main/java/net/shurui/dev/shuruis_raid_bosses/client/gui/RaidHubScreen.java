package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_raid_bosses.network.OpenEditorRequestPacket;
import net.shurui.dev.shuruis_raid_bosses.network.RaidNet;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * The single entry point for every editor. Opened by {@code /rg raid edit} (server sends
 * {@code OpenHubPacket}); each row asks the server (via {@link OpenEditorRequestPacket}) to gather that
 * editor's data and open it. The editors return here via their "Menu" button, so you don't have to run a
 * separate command for each one.
 */
public class RaidHubScreen extends BaseEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private record Entry(String labelKey, String which) {
    }

    private static final Entry[] ENTRIES = {
            new Entry("gui.dmz_ragnarok.raid.hub.raids", "raid"),
            new Entry("gui.dmz_ragnarok.raid.hub.rifts", "rift"),
    };

    public RaidHubScreen() {
        super(Component.translatable("gui.dmz_ragnarok.raid.hub.title"), UI_W, UI_H, null);
    }

    /** Open the hub on the client (invoked by OpenHubPacket). */
    public static void open() {
        Minecraft.getInstance().setScreen(new RaidHubScreen());
    }

    @Override
    protected void init() {
        super.init();
        // Generic hub menu: no descriptive header label (suite convention drops it); only the logo heads it.
        int y = 40;
        for (Entry e : ENTRIES) {
            btn(UI_W / 2 - 80, y, 160, 20, Component.translatable(e.labelKey),
                    () -> RaidNet.sendToServer(new OpenEditorRequestPacket(e.which)));
            y += 26;
        }
        btn(UI_W / 2 - 45, footerY(), 90, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.common.close"), this::onClose);
    }
}
