package net.shurui.dev.shuruis_raid_bosses.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_raid_bosses.network.DeleteRiftPacket;
import net.shurui.dev.shuruis_raid_bosses.network.RaidNet;
import net.shurui.dev.shuruis_raid_bosses.network.SaveRiftPacket;
import net.shurui.dev.shuruis_raid_bosses.rift.RiftDef;
import net.shurui.dev.shuruis_raid_bosses.util.ColorCodes;

/**
 * The rift editor's landing screen: every configured dimensional tear with Edit / Copy / Delete, and a New button.
 *
 * <p>The row says whether the rift is enabled and whether its raid still resolves, because those are the two ways a
 * rift silently never opens, and both are invisible from the rift's own name.
 */
public class RiftListScreen extends BaseEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    private final List<RiftDef> rifts = new ArrayList<>();
    private final List<CompoundTag> raidDefs;
    private final List<String> raidIds = new ArrayList<>();
    private final List<String> regionNames;
    private int scroll;

    public RiftListScreen(List<CompoundTag> riftNbts, List<CompoundTag> raidDefs, List<String> regionNames) {
        super(Component.translatable("gui.dmz_ragnarok.rift.list.title"), UI_W, UI_H, null);
        for (CompoundTag t : riftNbts) {
            rifts.add(RiftDef.load(t));
        }
        this.raidDefs = raidDefs;
        for (CompoundTag t : raidDefs) {
            String id = t.getString("id");
            if (!id.isBlank()) {
                raidIds.add(id);
            }
        }
        this.regionNames = regionNames;
    }

    /** Ids of every stored raid, for the fallback pointer dropdown a pre-encounter rift still shows. */
    public List<String> raidIds() {
        return raidIds;
    }

    public List<String> regionNames() {
        return regionNames;
    }

    /**
     * A stored raid by id, or null. Only used when a rift that still POINTS at a raid has its encounter editor
     * opened for the first time: the encounter it is given starts as a copy of that raid rather than as defaults,
     * so nothing about the fight the tear already led to is lost.
     */
    public net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef raidById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (net.minecraft.nbt.CompoundTag t : raidDefs) {
            net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef d =
                    net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef.load(t);
            if (d.id != null && d.id.equalsIgnoreCase(id)) {
                return d;
            }
        }
        return null;
    }

    @Override
    protected void init() {
        super.init();
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        int total = rifts.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final RiftDef def = rifts.get(i);
            // Ready means there is something on the other side: the rift's own encounter, or failing that a stored
            // raid its pointer still resolves to. A rift with neither never opens, and that is invisible from here
            // without saying so.
            boolean hasEncounter = def.encounter != null
                    || (def.raidId != null && !def.raidId.isBlank() && raidIds.contains(def.raidId));
            String status = !def.enabled
                    ? I18n.get("gui.dmz_ragnarok.rift.list.status_disabled")
                    : (hasEncounter ? I18n.get("gui.dmz_ragnarok.rift.list.status_ready")
                    : I18n.get("gui.dmz_ragnarok.rift.list.status_no_raid"));
            // I18n.get(def.name) is a pass-through: an owner display name is not a key, so it comes back
            // unchanged and ColorCodes then renders its & codes.
            label(I18n.get("gui.dmz_ragnarok.rift.list.row",
                    ColorCodes.translate(I18n.get(def.name)), def.id, status), 12, y + 5);
            btn(166, y, 34, 14, Component.translatable("gui.dmz_ragnarok.raid.common.edit"),
                    () -> this.minecraft.setScreen(new RiftEditScreen(def, this)));
            btn(202, y, 34, 14, Component.translatable("gui.dmz_ragnarok.raid.list.copy"), () -> copyRift(def));
            btn(244, y, 34, 14, Component.translatable("gui.dmz_ragnarok.raid.common.delete"), () -> {
                RaidNet.sendToServer(new DeleteRiftPacket(def.id));
                rifts.remove(def);
                if (scroll > 0 && scroll >= rifts.size()) {
                    scroll--;
                }
                rebuildWidgets();
            });
            y += ROW_H;
        }
        scrollList(12, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> {
            scroll = v;
            rebuildWidgets();
        });
        tooltip(12, LIST_TOP, 240, maxRows * ROW_H, I18n.get("gui.dmz_ragnarok.rift.list.tip"));

        btn(6, footerY(), 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.rift.list.new"),
                this::createNew);
        btn(UI_W - 106, footerY(), 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.raid.common.menu"),
                () -> {
                    if (!net.shurui.dev.shuruis_raid_bosses.compat.sdu.SduHubCompat.openSduHub()) {
                        this.minecraft.setScreen(new RaidHubScreen());
                    }
                });
    }

    /** Duplicate a rift: a full NBT round trip, a fresh id, a "(copy)" name, persisted and added locally. */
    private void copyRift(RiftDef orig) {
        RiftDef copy = RiftDef.load(orig.save());
        String base = "rift_" + Long.toString(System.currentTimeMillis(), 36);
        String id = base;
        int n = 1;
        while (idExists(id)) {
            id = base + "_" + (n++);
        }
        copy.id = id;
        copy.name = orig.name + " (copy)";
        RaidNet.sendToServer(new SaveRiftPacket(copy.save()));
        rifts.add(copy);
        rebuildWidgets();
    }

    private boolean idExists(String id) {
        for (RiftDef d : rifts) {
            if (d.id != null && d.id.equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    private void createNew() {
        RiftDef def = new RiftDef("rift_" + Long.toString(System.currentTimeMillis(), 36).substring(3));
        def.name = I18n.get("gui.dmz_ragnarok.rift.list.new_name");
        // Its own encounter from the start: a brand new rift should be one Encounter click from a boss, not a dead
        // end that first has to be pointed at a raid somewhere else.
        def.encounter = net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef.createDefault(def.id, def.name);
        rifts.add(def);
        this.minecraft.setScreen(new RiftEditScreen(def, this));
    }
}
