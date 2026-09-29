package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.npcregion.NpcSpawnConfig;
import net.shurui.shuruisutilities.npcregion.network.PacketSaveNpcRegion;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

// NPC-region overview: vertical bounds + the list of NPCs. each row opens the per-NPC editor; Add/Remove edit
// the list client-side, pushed in one PacketSaveNpcRegion on Save. XZ footprint is fixed by the map draw.
public class NpcRegionEditScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    // Panel grown so the two action-button rows (Add/Copy/Paste, Display/Selections/Airdrop), the status line and
    // the Save/Back footer each get their own band and never overlap (the reviewer's "overlapping buttons" defect).
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_ROW_H = 15;

    // clipboard shared across editor instances: last Copy'd region's NPC list + Y bounds, for copy/paste between regions
    private static List<NpcSpawnConfig> clipboardNpcs;
    private static int clipboardMinY, clipboardMaxY;

    private final String region;
    private final List<NpcSpawnConfig> npcs;
    private String minY;
    private String maxY;
    // Per-region TP falloff (SU Feature B), held as text and parsed on Save. 0 falloff level = feature off.
    private String tpFalloffLevel;
    private String tpFalloffRange;
    private int scroll = 0;
    private String status = "";
    // NPC list top, set each init(); read by maxRows(), which addNpc() also calls to clamp scroll.
    private int listTop;

    // Pending display options, edited in the NpcRegionDisplayScreen sub-editor and sent with Save.
    String title;
    String description;
    String difficulty;
    boolean showTitle;
    boolean showHud;
    // Admin flag: an eventOnly region runs only while a timed event owns it. Loaded from and saved back to the
    // region; a normal region keeps it off and its JSON stays byte-identical.
    boolean eventOnly;
    // selections {minX,minY,minZ,maxX,maxY,maxZ}, listed in NpcRegionSelectionsScreen
    final List<int[]> boxes;
    // Airdrop crate boss + loot, edited in the NpcRegionAirdropScreen sub-editor. Null boss = apex x2.
    boolean crateBossEnabled;
    NpcSpawnConfig crateBoss;
    // region airdrop chest loot; empty = global list
    final List<net.shurui.shuruisutilities.npcregion.CustomDrop> crateLoot;
    // Per-region airdrop scheduling, edited in the NpcRegionAirdropScreen sub-editor.
    boolean airdropEnabled;
    int airdropMinIntervalMinutes;
    int airdropMaxIntervalMinutes;
    // landing announcement; blank = global default
    String airdropAnnouncement;
    // landing sound id; blank = silent
    String airdropSound;
    // map overlay fill #RRGGBB; blank = default. edited in the Display screen.
    String color;
    // Z orbs (private): whether the server installed the feature, this region's config (null = never configured, kept
    // null so the region JSON stays byte-identical until an admin opts in), and the server-wide globals. Edited in
    // NpcRegionZOrbScreen / ZOrbGlobalsScreen and sent on Save via PacketSaveZOrbConfig, separate from the region save.
    final boolean zorbsAvailable;
    net.shurui.shuruisutilities.zorb.ZOrbConfig zorbs;
    net.shurui.shuruisutilities.zorb.ZOrbGlobals zorbsGlobals;

    public NpcRegionEditScreen(String region, int minY, int maxY, List<NpcSpawnConfig> npcs,
                               String title, String description, String difficulty,
                               boolean showTitle, boolean showHud, List<int[]> boxes,
                               boolean crateBossEnabled, NpcSpawnConfig crateBoss,
                               List<net.shurui.shuruisutilities.npcregion.CustomDrop> crateLoot,
                               boolean airdropEnabled, int airdropMinIntervalMinutes,
                               int airdropMaxIntervalMinutes, String airdropAnnouncement,
                               String airdropSound, String color,
                               int tpFalloffLevel, int tpFalloffRange,
                               boolean zorbsAvailable, net.shurui.shuruisutilities.zorb.ZOrbConfig zorbs,
                               net.shurui.shuruisutilities.zorb.ZOrbGlobals zorbsGlobals, boolean eventOnly)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.npcregions"), UI_W, UI_H, null);
        this.eventOnly = eventOnly;
        this.crateLoot = crateLoot == null ? new ArrayList<>() : new ArrayList<>(crateLoot);
        this.airdropEnabled = airdropEnabled;
        this.airdropMinIntervalMinutes = airdropMinIntervalMinutes;
        this.airdropMaxIntervalMinutes = airdropMaxIntervalMinutes;
        this.airdropAnnouncement = airdropAnnouncement == null ? "" : airdropAnnouncement;
        this.airdropSound = airdropSound == null
                ? net.shurui.shuruisutilities.npcregion.NpcRegion.DEFAULT_AIRDROP_SOUND : airdropSound;
        this.color = color == null ? "" : color;
        this.region = region;
        this.npcs = npcs;
        this.minY = Integer.toString(minY);
        this.maxY = Integer.toString(maxY);
        this.title = title == null ? "" : title;
        this.description = description == null ? "" : description;
        this.difficulty = difficulty == null ? "" : difficulty;
        this.showTitle = showTitle;
        this.showHud = showHud;
        this.boxes = boxes == null ? new ArrayList<>() : new ArrayList<>(boxes);
        this.crateBossEnabled = crateBossEnabled;
        this.crateBoss = crateBoss;
        this.tpFalloffLevel = Integer.toString(tpFalloffLevel);
        this.tpFalloffRange = Integer.toString(tpFalloffRange);
        this.zorbsAvailable = zorbsAvailable;
        this.zorbs = zorbs;
        this.zorbsGlobals = zorbsGlobals == null ? new net.shurui.shuruisutilities.zorb.ZOrbGlobals() : zorbsGlobals;
    }

    // open the editor on the client (from PacketOpenNpcRegionEditor)
    public static void open(String region, int minY, int maxY, List<NpcSpawnConfig> npcs,
                            String title, String description, String difficulty,
                            boolean showTitle, boolean showHud, List<int[]> boxes,
                            boolean crateBossEnabled, NpcSpawnConfig crateBoss,
                            List<net.shurui.shuruisutilities.npcregion.CustomDrop> crateLoot,
                            boolean airdropEnabled, int airdropMinIntervalMinutes,
                            int airdropMaxIntervalMinutes, String airdropAnnouncement,
                            String airdropSound, String color,
                            int tpFalloffLevel, int tpFalloffRange,
                            boolean zorbsAvailable, net.shurui.shuruisutilities.zorb.ZOrbConfig zorbs,
                            net.shurui.shuruisutilities.zorb.ZOrbGlobals zorbsGlobals, boolean zorbsGlobalsOnly,
                            boolean eventOnly)
    {
        if (zorbsGlobalsOnly)
        {
            // Region-independent world Z orb editor (/zorbs edit): open it directly, no region.
            Minecraft.getInstance().setScreen(new ZOrbGlobalsScreen(
                    zorbsGlobals == null ? new net.shurui.shuruisutilities.zorb.ZOrbGlobals() : zorbsGlobals));
            return;
        }
        List<NpcSpawnConfig> list = npcs == null ? new ArrayList<>() : new ArrayList<>(npcs);
        Minecraft.getInstance().setScreen(new NpcRegionEditScreen(region, minY, maxY, list,
                title, description, difficulty, showTitle, showHud, boxes,
                crateBossEnabled, crateBoss, crateLoot,
                airdropEnabled, airdropMinIntervalMinutes, airdropMaxIntervalMinutes, airdropAnnouncement,
                airdropSound, color, tpFalloffLevel, tpFalloffRange, zorbsAvailable, zorbs, zorbsGlobals, eventOnly));
    }

    // Reserve holds the two action-button rows (Add/Copy/Paste, Display/Selections/Airdrop) plus the status line
    // that follow the list, so the list fills the space above them without growing over them.
    private int maxRows()
    {
        return rowsThatFit(listTop, LIST_ROW_H, 44);
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();

        headerName = tr("gui.dmz_ragnarok.core.npcregion.edit_subtitle", region);
        rowY = 30;

        // Region-wide vertical bounds (the X/Z footprint is set by the map draw).
        tf(tr("gui.dmz_ragnarok.core.npcregion.min_y"), minY, v -> minY = v);
        tip(tr("gui.dmz_ragnarok.core.npcregion.min_y_tip"));
        tf(tr("gui.dmz_ragnarok.core.npcregion.max_y"), maxY, v -> maxY = v);
        tip(tr("gui.dmz_ragnarok.core.npcregion.max_y_tip"));

        // Per-region TP anti-farm falloff (SU Feature B). Read back on Save and clamped by NpcRegion.sanitize().
        tf(tr("gui.dmz_ragnarok.core.npcregion.tp_falloff_level"), tpFalloffLevel, v -> tpFalloffLevel = v);
        tip(tr("gui.dmz_ragnarok.core.npcregion.tp_falloff_level_tip"));
        tf(tr("gui.dmz_ragnarok.core.npcregion.tp_falloff_range"), tpFalloffRange, v -> tpFalloffRange = v);
        tip(tr("gui.dmz_ragnarok.core.npcregion.tp_falloff_range_tip"));

        bf(tr("gui.dmz_ragnarok.core.npcregion.event_only"), eventOnly, () -> eventOnly = !eventOnly);
        tip(tr("gui.dmz_ragnarok.core.npcregion.event_only_tip"));

        label("§e" + tr("gui.dmz_ragnarok.core.npcregion.npc_count", npcs.size()), 14, rowY + 2, 0xFFE0E0A0);
        rowY += 14;

        this.listTop = rowY;
        int maxRows = maxRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, npcs.size() - maxRows)));
        int end = Math.min(npcs.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            NpcSpawnConfig c = npcs.get(i);
            final int index = i;
            int ry = listTop + (i - scroll) * LIST_ROW_H;
            String label = "§f#" + (i + 1) + " §7" + npcLabel(c);
            // Trailing Remove button reserves the scrollbar column so it never sits on the bar; the row fills the
            // rest of the width up to a gap before it.
            int removeW = 40;
            int removeX = rowControlRight() - removeW;
            int rowW = Math.max(1, removeX - 4 - 14);
            rowBtn(14, ry, rowW, LIST_ROW_H - 2, Component.literal(label), () -> editNpc(index))
                    .right(Component.literal("§8x" + c.maxSpawns), 0xFFB0B0B0);
            btn(removeX, ry, removeW, LIST_ROW_H - 2, Component.literal("§c").append(Component.translatable("gui.dmz_ragnarok.core.npcsel.remove")), () -> removeNpc(index));
        }
        scrollList(14, uiWidth, listTop, LIST_ROW_H, maxRows, npcs.size(), scroll,
                v -> { scroll = v; rebuildWidgets(); });

        int addY = listTop + maxRows * LIST_ROW_H + 2;
        btn(14, addY, 88, GuiTheme.BUTTON_HEIGHT, Component.literal("§a").append(Component.translatable("gui.dmz_ragnarok.core.npcregion.add_npc")), this::addNpc);
        // Copy this region's whole NPC list to the shared clipboard; paste it into any other region's editor.
        btn(106, addY, 88, GuiTheme.BUTTON_HEIGHT, Component.literal("§b").append(Component.translatable("gui.dmz_ragnarok.core.npcregion.copy_npcs")), this::copyNpcs);
        boolean canPaste = clipboardNpcs != null && !clipboardNpcs.isEmpty();
        btn(198, addY, 88, GuiTheme.BUTTON_HEIGHT,
                Component.literal(canPaste ? "§d" : "§8").append(Component.translatable("gui.dmz_ragnarok.core.npcregion.paste_npcs")), this::pasteNpcs);

        // Player-facing display options, the selection list and the airdrop crate boss, each a sub-screen.
        btn(14, addY + 16, 88, GuiTheme.BUTTON_HEIGHT, Component.literal("§e").append(Component.translatable("gui.dmz_ragnarok.core.npcregion.display")),
                () -> { applyFields(); Minecraft.getInstance().setScreen(new NpcRegionDisplayScreen(this)); });
        btn(106, addY + 16, 88, GuiTheme.BUTTON_HEIGHT, Component.literal("§b").append(Component.translatable("gui.dmz_ragnarok.core.npcregion.selections", boxes.size())),
                () -> { applyFields(); Minecraft.getInstance().setScreen(new NpcRegionSelectionsScreen(this)); });
        btn(198, addY + 16, 88, GuiTheme.BUTTON_HEIGHT, Component.literal("§6").append(Component.translatable("gui.dmz_ragnarok.core.npcregion.airdrop")),
                () -> { applyFields(); Minecraft.getInstance().setScreen(new NpcRegionAirdropScreen(this)); });

        // Z Orbs (private): only when the server installed the feature AND this client's key sync confirms it, so a
        // keyless client never even sees the button. Opening it materialises this region's config (so it saves).
        boolean showZorbs = zorbsAvailable && net.shurui.dev.sdu.api.ClientGate.feature("zorbs");
        int statusY = addY + 32;
        if (showZorbs)
        {
            btn(14, addY + 32, 88, GuiTheme.BUTTON_HEIGHT,
                    Component.literal("§d").append(Component.translatable("gui.dmz_ragnarok.core.npcregion.zorbs")),
                    () -> {
                        applyFields();
                        if (zorbs == null)
                            zorbs = new net.shurui.shuruisutilities.zorb.ZOrbConfig();
                        Minecraft.getInstance().setScreen(new NpcRegionZOrbScreen(this));
                    });
            statusY = addY + 48;
        }

        if (!status.isEmpty())
            label(status, 14, statusY, 0xFFA0FFA0);

        int by = footerY();
        btn(UI_W / 2 - 104, by, 100, footerBtnHeight(), Component.literal("§a").append(Component.translatable("gui.dmz_ragnarok.core.btn.save")), this::save);
        btn(UI_W / 2 + 4, by, 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                () -> EditorScreens.reopen("npcregions"));
    }

    String regionName()
    {
        return region;
    }

    // the edited NPC list (for the Crates screen's apex-x2 baseline)
    List<NpcSpawnConfig> npcs()
    {
        return npcs;
    }

    private void editNpc(int index)
    {
        applyFields();
        Minecraft.getInstance().setScreen(new NpcConfigEditScreen(this, npcs.get(index), index));
    }

    private void addNpc()
    {
        applyFields();
        npcs.add(new NpcSpawnConfig());
        scroll = Math.max(0, npcs.size() - maxRows());
        // jump into the new NPC so the admin can fill it in
        Minecraft.getInstance().setScreen(new NpcConfigEditScreen(this, npcs.get(npcs.size() - 1), npcs.size() - 1));
    }

    private void removeNpc(int index)
    {
        applyFields();
        if (index >= 0 && index < npcs.size())
            npcs.remove(index);
        rebuildWidgets();
    }

    // copy this region's NPC list (+ Y bounds) to the shared clipboard
    private void copyNpcs()
    {
        applyFields();
        clipboardNpcs = new ArrayList<>();
        for (NpcSpawnConfig c : npcs)
            clipboardNpcs.add(c.copy());
        clipboardMinY = parseI(minY, 0);
        clipboardMaxY = parseI(maxY, 0);
        status = "§b" + tr("gui.dmz_ragnarok.core.npcregion.status_copied", clipboardNpcs.size());
        rebuildWidgets();
    }

    // replace this region's NPC list (+ Y bounds) with a deep copy of the clipboard
    private void pasteNpcs()
    {
        if (clipboardNpcs == null || clipboardNpcs.isEmpty())
        {
            status = "§7" + tr("gui.dmz_ragnarok.core.npcregion.status_nothing");
            rebuildWidgets();
            return;
        }
        applyFields();
        npcs.clear();
        for (NpcSpawnConfig c : clipboardNpcs)
            npcs.add(c.copy());
        minY = Integer.toString(clipboardMinY);
        maxY = Integer.toString(clipboardMaxY);
        scroll = 0;
        status = "§d" + tr("gui.dmz_ragnarok.core.npcregion.status_pasted", npcs.size());
        rebuildWidgets();
    }

    private void save()
    {
        applyFields();
        int lo = parseI(minY, 0);
        int hi = parseI(maxY, 0);
        // Bad input falls back to the safe defaults; the server-side sanitize() does the final clamping.
        int falloffLevel = parseI(tpFalloffLevel, 0);
        int falloffRange = parseI(tpFalloffRange, 20);
        // Guarded send: a region with many NPC entries can exceed the 32767 byte serverbound limit; the guard names
        // the packet and declines rather than letting the server's decoder disconnect the editor.
        NetworkUtils.sendToServer(new PacketSaveNpcRegion(region, lo, hi, npcs,
                title, description, difficulty, showTitle, showHud,
                crateBossEnabled, crateBoss, crateLoot,
                airdropEnabled, airdropMinIntervalMinutes, airdropMaxIntervalMinutes, airdropAnnouncement,
                airdropSound, color, falloffLevel, falloffRange, eventOnly));
        // Z orbs (private): a SEPARATE packet, so each stays well under the serverbound size ceiling. Only sent when
        // the region actually has a config (an admin opened the Z Orbs screen); a region never configured keeps its
        // zorbs field null and the region JSON byte-identical. The server also refuses the write without the key.
        if (zorbsAvailable && zorbs != null && net.shurui.dev.sdu.api.ClientGate.feature("zorbs"))
            NetworkUtils.sendToServer(new net.shurui.shuruisutilities.zorb.network.PacketSaveZOrbConfig(
                    region, zorbs, zorbsGlobals));
        EditorScreens.reopen("npcregions");
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    private static String npcLabel(NpcSpawnConfig c)
    {
        if (c.displayName != null && !c.displayName.isBlank())
            return c.displayName;
        String id = c.entityTypeId == null ? "" : c.entityTypeId;
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }
}
