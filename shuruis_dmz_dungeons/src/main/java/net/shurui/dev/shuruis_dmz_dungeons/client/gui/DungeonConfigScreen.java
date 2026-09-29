package net.shurui.dev.shuruis_dmz_dungeons.client.gui;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloorConfig;
import net.shurui.dev.shuruis_dmz_dungeons.network.SaveDungeonConfigChunkPacket;
import net.shurui.dev.shuruis_dmz_dungeons.network.SaveDungeonConfigPacket;
import net.shurui.dev.shuruis_dmz_dungeons.network.SddNet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

// the /rg dungeon config editor, on the shared DMZ toolkit (FieldEditScreen) so it matches the raid boss
// editor and the spawner editor. one tabbed screen for the whole dungeon: a Settings tab (dungeon-wide rules)
// and a Floors tab that lists every floor as its own entry (Edit / Go / Regen / remove) with an Add button.
//
// the whole editable model lives on this screen: the four rule fields plus a mutable list of DungeonFloorConfig
// (edited in place by the per-floor sub-editor). Save sends it all in one server-authoritative packet. Go and
// Regen run the existing /rg dungeon floor subcommands (server-validated) against the floor NUMBER.
//
// keyless server: procedural floors need Shurui's Key, so when keyUnlocked is false the Floors tab is not shown
// at all and only the Settings tab (time limit, cooldown, pvp, ki block destruction) is present, since those
// rules work without the key.
public class DungeonConfigScreen extends FieldEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final boolean keyUnlocked;
    private boolean pvp;
    private boolean kiBlockDestruction;
    private boolean blockEditing;
    private int timeLimitSeconds;
    private int cooldownSeconds;
    private final List<DungeonFloorConfig> floors;

    private int tab = 0;
    private int floorScroll = 0;
    // the floor a Regen button is currently confirming (1-based), or -1. a first click arms it, a second runs it,
    // so a destructive regen needs a deliberate confirm at the point of action without any standing warning text.
    private int regenConfirm = -1;

    public DungeonConfigScreen(boolean keyUnlocked, boolean pvp, boolean kiBlockDestruction, boolean blockEditing,
                               int timeLimitSeconds, int cooldownSeconds, List<DungeonFloorConfig> floors) {
        super(Component.translatable("gui.dmz_ragnarok.dungeons.config.title"), UI_W, UI_H, null);
        this.keyUnlocked = keyUnlocked;
        this.pvp = pvp;
        this.kiBlockDestruction = kiBlockDestruction;
        this.blockEditing = blockEditing;
        this.timeLimitSeconds = timeLimitSeconds;
        this.cooldownSeconds = cooldownSeconds;
        this.floors = floors;
    }

    public static void open(boolean keyUnlocked, boolean pvp, boolean kiBlockDestruction, boolean blockEditing,
                            int timeLimitSeconds, int cooldownSeconds, List<DungeonFloorConfig> floors) {
        Minecraft.getInstance().setScreen(new DungeonConfigScreen(
                keyUnlocked, pvp, kiBlockDestruction, blockEditing, timeLimitSeconds, cooldownSeconds, floors));
    }

    // tab captions: Floors + Settings when the key unlocks floors, else just Settings.
    private String[] sections() {
        String settings = I18n.get("gui.dmz_ragnarok.dungeons.config.tab_settings");
        if (!keyUnlocked) {
            return new String[]{settings};
        }
        return new String[]{I18n.get("gui.dmz_ragnarok.dungeons.config.tab_floors"), settings};
    }

    private boolean floorsTabActive() {
        return keyUnlocked && tab == 0;
    }

    @Override
    protected void init() {
        super.init();
        clearFields();

        rowY = buildTabHeader(null, sections(), tab, this::selectSection);

        int contentTop = rowY;
        int contentBottom = UI_H - 26;
        beginScrollBand(contentTop, contentBottom);
        rowY = contentTop - scroll;
        if (floorsTabActive()) {
            floorsTab();
        } else {
            settingsTab();
        }
        finishScrollBand(contentTop, contentBottom);

        int by = footerY();
        btn(UI_W / 2 - 104, by, 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.dungeons.common.save"), this::save);
        btn(UI_W / 2 + 4, by, 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.dungeons.config.close"), this::onClose);
    }

    private void selectSection(int index) {
        if (tab != index) {
            applyFields();
            tab = index;
            scroll = 0;
            regenConfirm = -1;
            rebuildWidgets();
        }
    }

    private void settingsTab() {
        tf(I18n.get("gui.dmz_ragnarok.dungeons.config.time_limit"), intStr(timeLimitSeconds),
                v -> timeLimitSeconds = clampSeconds(parseI(v, timeLimitSeconds)));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.config.time_limit_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.config.cooldown"), intStr(cooldownSeconds),
                v -> cooldownSeconds = clampSeconds(parseI(v, cooldownSeconds)));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.config.cooldown_tip"));
        bf(I18n.get("gui.dmz_ragnarok.dungeons.config.pvp"), pvp, () -> pvp = !pvp);
        tip(I18n.get("gui.dmz_ragnarok.dungeons.config.pvp_tip"));
        bf(I18n.get("gui.dmz_ragnarok.dungeons.config.ki_block"), kiBlockDestruction,
                () -> kiBlockDestruction = !kiBlockDestruction);
        tip(I18n.get("gui.dmz_ragnarok.dungeons.config.ki_block_tip"));
        bf(I18n.get("gui.dmz_ragnarok.dungeons.config.block_editing"), blockEditing,
                () -> blockEditing = !blockEditing);
        tip(I18n.get("gui.dmz_ragnarok.dungeons.config.block_editing_tip"));
    }

    private void floorsTab() {
        int listTop = rowY;
        // Reserve 18px so the list clears the Add button below it and stops within the scroll band bottom (234).
        int maxRows = rowsThatFit(listTop, ROW_H, 18);
        int count = floors.size();
        floorScroll = Math.max(0, Math.min(floorScroll, Math.max(0, count - maxRows)));
        int end = Math.min(count, floorScroll + maxRows);
        for (int i = floorScroll; i < end; i++) {
            floorRow(i);
        }
        scrollList(12, uiWidth, listTop, ROW_H, maxRows, count, floorScroll,
                v -> { floorScroll = v; rebuildWidgets(); });

        int addY = listTop + maxRows * ROW_H + 2;
        btn(14, addY, 120, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.dungeons.config.add_floor"), () -> {
            floors.add(DungeonFloorConfig.defaultFor(floors.size() + 1));
            floorScroll = Math.max(0, floors.size() - maxRows);
            regenConfirm = -1;
            rebuildWidgets();
        });
    }

    private void floorRow(int index) {
        DungeonFloorConfig c = floors.get(index);
        int n = index + 1;
        int y = rowY;
        String rowKey = c.isBoss()
                ? "gui.dmz_ragnarok.dungeons.config.floor_row_boss"
                : "gui.dmz_ragnarok.dungeons.config.floor_row";
        label(I18n.get(rowKey, n, themeDisplay(c.theme), c.size, c.depth), 14, y + 2);
        btn(150, y, 32, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.dungeons.common.edit"),
                () -> this.minecraft.setScreen(new DungeonFloorEditScreen(this, c, n)));
        btn(184, y, 24, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.dungeons.config.floor_go"),
                () -> runCommand("rg dungeon floor tp " + n));
        tooltip(184, y, 24, 11, I18n.get("gui.dmz_ragnarok.dungeons.config.floor_go_tip"));
        boolean confirming = regenConfirm == n;
        btn(210, y, 42, GuiTheme.BUTTON_HEIGHT, Component.translatable(confirming
                ? "gui.dmz_ragnarok.dungeons.config.floor_regen_confirm"
                : "gui.dmz_ragnarok.dungeons.config.floor_regen"), () -> {
            if (confirming) {
                regenConfirm = -1;
                runCommand("rg dungeon floor regen " + n);
            } else {
                regenConfirm = n;
                rebuildWidgets();
            }
        });
        tooltip(210, y, 42, 11, I18n.get("gui.dmz_ragnarok.dungeons.config.floor_regen_tip"));
        btn(256, y, GuiTheme.ICON_BUTTON_SIZE, GuiTheme.ICON_BUTTON_SIZE, Component.translatable("gui.dmz_ragnarok.dungeons.config.floor_remove"), () -> {
            floors.remove(index);
            if (floorScroll > 0 && floorScroll >= floors.size()) {
                floorScroll--;
            }
            regenConfirm = -1;
            rebuildWidgets();
        });
        // hover rect tracks the button it explains: both are the shared icon-button size, so the help does not
        // stop a few px short of the button's edge.
        tooltip(256, y, GuiTheme.ICON_BUTTON_SIZE, GuiTheme.ICON_BUTTON_SIZE,
                I18n.get("gui.dmz_ragnarok.dungeons.config.floor_remove_tip"));
        rowY += ROW_H;
    }

    private void save() {
        applyFields();
        List<CompoundTag> nbts = new ArrayList<>();
        for (DungeonFloorConfig c : floors) {
            nbts.add(c.save(new CompoundTag()));
        }
        // Serialise the whole save with the existing wire format, then send it as chunks: the full floor list can
        // exceed the 32767-byte serverbound custom-payload ceiling (which kicks the sender on the server's decode),
        // so it must never travel as one packet. The server reassembles the frames and applies them identically.
        // See SaveDungeonConfigChunkPacket.
        SaveDungeonConfigPacket body = new SaveDungeonConfigPacket(pvp, kiBlockDestruction, blockEditing,
                timeLimitSeconds, cooldownSeconds, nbts);
        FriendlyByteBuf scratch = new FriendlyByteBuf(Unpooled.buffer());
        try {
            body.encode(scratch);
            byte[] bytes = new byte[scratch.readableBytes()];
            scratch.readBytes(bytes);
            int total = bytes.length;
            int chunks = Math.max(1, (total + SaveDungeonConfigChunkPacket.CHUNK_BYTES - 1)
                    / SaveDungeonConfigChunkPacket.CHUNK_BYTES);
            for (int i = 0; i < chunks; i++) {
                int off = i * SaveDungeonConfigChunkPacket.CHUNK_BYTES;
                int len = Math.min(SaveDungeonConfigChunkPacket.CHUNK_BYTES, total - off);
                byte[] part = Arrays.copyOfRange(bytes, off, off + len);
                SddNet.sendToServer(new SaveDungeonConfigChunkPacket(i, chunks, total, part));
            }
        } finally {
            scratch.release();
        }
        onClose();
    }

    private void runCommand(String command) {
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand(command);
        }
    }

    private static String themeDisplay(String theme) {
        String key = "gui.dmz_ragnarok.dungeons.opt.theme."
                + (theme == null ? "overworld" : theme.toLowerCase(Locale.ROOT));
        return I18n.get(key);
    }

    private static int clampSeconds(int v) {
        return Math.max(0, Math.min(86400, v));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
