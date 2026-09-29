package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_raid_bosses.network.DeleteDefPacket;
import net.shurui.dev.shuruis_raid_bosses.network.RaidNet;
import net.shurui.dev.shuruis_raid_bosses.network.SaveDefPacket;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef;
import net.shurui.dev.shuruis_raid_bosses.util.ColorCodes;

import java.util.ArrayList;
import java.util.List;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * The raid editor's landing screen: the umbrella logo header over a scrollable list of every configured
 * raid with Edit / Signups / Delete actions, plus a New button. The footer "Menu" button returns to the
 * {@link RaidHubScreen}. Styled to match the sibling mods' editors.
 */
public class RaidListScreen extends BaseEditScreen {
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    private final List<RaidBossDef> defs = new ArrayList<>();
    private int scroll = 0;

    /** Every raid this client knows about, so an editor opened from here can offer them (e.g. the random pool). */
    public List<RaidBossDef> knownDefs() {
        return defs;
    }

    public RaidListScreen(List<CompoundTag> defNbts) {
        super(Component.translatable("gui.dmz_ragnarok.raid.list.title"), UI_W, UI_H, null);
        for (CompoundTag t : defNbts) defs.add(RaidBossDef.load(t));
    }

    @Override
    protected void init() {
        super.init();
        // Generic list screen: no descriptive header label (suite convention drops it); only the logo heads it.

        // Derive the row cap from the space between the list top and the footer so the list fills the panel.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        int total = defs.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final RaidBossDef def = defs.get(i);
            // I18n.get(def.name) is an intentional pass-through: an owner display name is not a key, so
            // I18n returns it unchanged; ColorCodes.translate then renders its & colour codes.
            String status = def.hasArena()
                    ? I18n.get("gui.dmz_ragnarok.raid.list.status_ready")
                    : I18n.get("gui.dmz_ragnarok.raid.list.status_no_arena");
            label(I18n.get("gui.dmz_ragnarok.raid.list.row",
                    ColorCodes.translate(I18n.get(def.name)), def.id, status), 12, y + 5);
            // Row controls pulled in so the trailing Delete ends at rowControlRight() (never under the scrollbar).
            btn(130, y, 34, 14, Component.translatable("gui.dmz_ragnarok.raid.common.edit"),
                    () -> this.minecraft.setScreen(new RaidEditScreen(def, this)));
            btn(166, y, 34, 14, Component.translatable("gui.dmz_ragnarok.raid.list.copy"), () -> copyRaid(def));
            btn(202, y, 40, 14, Component.translatable("gui.dmz_ragnarok.raid.list.signups"),
                    () -> runCommand("rg raid open " + def.id));
            btn(244, y, 34, 14, Component.translatable("gui.dmz_ragnarok.raid.common.delete"), () -> {
                RaidNet.sendToServer(new DeleteDefPacket(def.id));
                defs.remove(def);
                if (scroll > 0 && scroll >= defs.size()) scroll--;
                rebuildWidgets();
            });
            y += ROW_H;
        }
        scrollList(12, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });
        tooltip(12, LIST_TOP, 240, maxRows * ROW_H, I18n.get("gui.dmz_ragnarok.raid.list.tip"));

        btn(6, footerY(), 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.list.new"), this::createNew);
        btn(UI_W - 106, footerY(), 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.common.menu"),
                () -> {
                    // When sdu is present, "Menu" returns to the shared sdu hub; otherwise this mod's own hub.
                    if (!net.shurui.dev.shuruis_raid_bosses.compat.sdu.SduHubCompat.openSduHub()) {
                        this.minecraft.setScreen(new RaidHubScreen());
                    }
                });
    }

    /** Duplicate a raid: full deep copy via NBT round-trip, a fresh unique id, "(copy)" suffix, persisted + added locally. */
    private void copyRaid(RaidBossDef orig) {
        RaidBossDef copy = RaidBossDef.load(orig.save());
        String base = "raid_" + Long.toString(System.currentTimeMillis(), 36);
        String id = base;
        int n = 1;
        while (idExists(id)) {
            id = base + "_" + (n++);
        }
        copy.id = id;
        copy.name = orig.name + " (copy)";
        RaidNet.sendToServer(new SaveDefPacket(copy.save()));
        defs.add(copy);
        rebuildWidgets();
    }

    private boolean idExists(String id) {
        for (RaidBossDef d : defs) {
            if (d.id != null && d.id.equalsIgnoreCase(id)) return true;
        }
        return false;
    }

    private void createNew() {
        String id = "raid_" + Long.toString(System.currentTimeMillis(), 36).substring(3);
        RaidBossDef def = RaidBossDef.createDefault(id, "New Raid");
        defs.add(def);
        this.minecraft.setScreen(new RaidEditScreen(def, this));
    }

    private void runCommand(String command) {
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand(command);
        }
    }
}
