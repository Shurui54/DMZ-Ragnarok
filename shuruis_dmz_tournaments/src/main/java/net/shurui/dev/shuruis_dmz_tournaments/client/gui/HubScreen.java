package net.shurui.dev.shuruis_dmz_tournaments.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_dmz_tournaments.network.OpenEditorRequestPacket;
import net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * The single entry point for every editor. Opened by {@code /rg tourney edit} (server sends
 * {@code OpenHubPacket}); each row asks the server (via {@link OpenEditorRequestPacket}) to gather that
 * editor's data and open it. The editors return here via their "Menu" button, so you don't have to run a
 * separate command for each one.
 */
public class HubScreen extends BaseEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private record Entry(String labelKey, String which) {
    }

    private static final Entry[] ENTRIES = {
            new Entry("gui.dmz_ragnarok.tournaments.hub.tournaments", "tournament"),
    };

    public HubScreen() {
        super(Component.translatable("gui.dmz_ragnarok.tournaments.hub.title"), UI_W, UI_H, null);
    }

    /** Open the hub on the client (invoked by OpenHubPacket). */
    public static void open() {
        Minecraft.getInstance().setScreen(new HubScreen());
    }

    @Override
    protected void init() {
        super.init();
        // Generic hub: no named entity, so no top-left name and no subtitle (suite title convention).
        int y = 32;
        for (Entry e : ENTRIES) {
            btn(UI_W / 2 - 80, y, 160, 20, Component.translatable(e.labelKey),
                    () -> TournamentNet.sendToServer(new OpenEditorRequestPacket(e.which)));
            y += 26;
        }
        btn(UI_W / 2 - 45, net.shurui.dev.sdu.client.gui.theme.GuiTheme.footerY(UI_H), 90, 14,
                Component.translatable("gui.dmz_ragnarok.tournaments.common.close"), this::onClose);
    }
}
