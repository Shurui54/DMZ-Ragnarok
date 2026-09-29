package net.shurui.dev.shuruis_dmz_tournaments.client.gui;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_dmz_tournaments.network.DeleteDefPacket;
import net.shurui.dev.shuruis_dmz_tournaments.network.SaveDefPacket;
import net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentDef;
import net.shurui.dev.shuruis_dmz_tournaments.util.ColorCodes;

import java.util.ArrayList;
import java.util.List;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * The tournament editor's landing screen: a scrollable list of every configured tournament with
 * Edit / Signups / Delete actions, plus New + Menu in the footer. Menu returns to the {@link HubScreen}.
 * Styled to match the sibling {@code sdu} mod's list editors.
 */
public class TournamentListScreen extends BaseEditScreen {
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    private final List<TournamentDef> defs = new ArrayList<>();
    private int scroll = 0;

    public TournamentListScreen(List<CompoundTag> defNbts) {
        super(Component.translatable("gui.dmz_ragnarok.tournaments.list.title"), UI_W, UI_H, null);
        for (CompoundTag t : defNbts) defs.add(TournamentDef.load(t));
    }

    @Override
    protected void init() {
        super.init();
        // Generic list screen: no named entity, so no top-left name and no subtitle (suite title convention).
        // row cap from the gap between list top and footer, so the list fills the panel
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        int total = defs.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final TournamentDef def = defs.get(i);
            String status = def.hasAllRegions()
                    ? " " + I18n.get("gui.dmz_ragnarok.tournaments.list.status_ready")
                    : " " + I18n.get("gui.dmz_ragnarok.tournaments.list.status_no_bounds");
            label(I18n.get("gui.dmz_ragnarok.tournaments.list.row",
                    ColorCodes.translate(I18n.get(def.name)), def.id, status), 10, y + 5);
            // stop at rowControlRight() so the last button never sits under the scrollbar
            btn(122, y, 34, 14, Component.translatable("gui.dmz_ragnarok.tournaments.common.edit"),
                    () -> this.minecraft.setScreen(new TournamentEditScreen(def, this)));
            btn(158, y, 34, 14, Component.translatable("gui.dmz_ragnarok.tournaments.list.copy"), () -> copyDef(def));
            btn(194, y, 44, 14, Component.translatable("gui.dmz_ragnarok.tournaments.list.signups"),
                    () -> runCommand("rg tourney open " + def.id));
            btn(240, y, rowControlRight() - 240, 14, Component.translatable("gui.dmz_ragnarok.tournaments.common.delete"), () -> {
                TournamentNet.sendToServer(new DeleteDefPacket(def.id));
                defs.remove(def);
                if (scroll > 0 && scroll >= defs.size()) scroll--;
                rebuildWidgets();
            });
            y += ROW_H;
        }
        scrollList(10, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });

        int footerY = net.shurui.dev.sdu.client.gui.theme.GuiTheme.footerY(UI_H);
        btn(6, footerY, 120, 14, Component.translatable("gui.dmz_ragnarok.tournaments.list.new"), this::createNew);
        btn(UI_W - 126, footerY, 120, 14, Component.translatable("gui.dmz_ragnarok.tournaments.common.menu"),
                () -> {
                    // sdu present: "Menu" returns to the shared sdu hub, else this mod's own hub
                    if (!net.shurui.dev.shuruis_dmz_tournaments.compat.sdu.SduHubCompat.openSduHub()) {
                        this.minecraft.setScreen(new HubScreen());
                    }
                });
    }

    private void createNew() {
        String id = "tourney_" + Long.toString(System.currentTimeMillis(), 36).substring(3);
        TournamentDef def = TournamentDef.createDefault(id, "New Tournament");
        defs.add(def);
        this.minecraft.setScreen(new TournamentEditScreen(def, this));
    }

    private void copyDef(TournamentDef orig) {
        TournamentDef copy = TournamentDef.load(orig.save());
        String base = "tourney_" + Long.toString(System.currentTimeMillis(), 36).substring(3);
        String id = base;
        int suffix = 1;
        while (idExists(id)) {
            id = base + "_" + suffix++;
        }
        copy.id = id;
        copy.name = orig.name + " (copy)";
        TournamentNet.sendToServer(new SaveDefPacket(copy.save()));
        defs.add(copy);
        rebuildWidgets();
    }

    private boolean idExists(String id) {
        for (TournamentDef d : defs) {
            if (id.equals(d.id)) return true;
        }
        return false;
    }

    private void runCommand(String command) {
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand(command);
        }
    }
}
