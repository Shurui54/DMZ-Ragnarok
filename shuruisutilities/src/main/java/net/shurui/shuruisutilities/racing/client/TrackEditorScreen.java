package net.shurui.shuruisutilities.racing.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.racing.EditorAction;
import net.shurui.shuruisutilities.racing.net.PacketTrackEditorAction;
import net.shurui.shuruisutilities.racing.track.TrackDef;
import net.shurui.shuruisutilities.racing.track.TrackNode;

/**
 * The in-world track editor screen (opened by right-clicking air with a bound track wand). A {@code FieldEditScreen}
 * with four tabs: Race (laps, width, off-road, rescue, fall depth, gate spacing, bounce), Build (grid, and Undo /
 * Validate; the editor places no blocks: tracks are drawn over roads the admins built), Surface (the on-track
 * surface block set),
 * and Node (the selected node's width, walls and checkpoint). Every action goes to the server over packet 119, which
 * the Ragnarok Key applies under the {@code su.race.admin} permission; the server pushes a fresh preview (118) back,
 * which is also what feeds the in-world {@code TrackPreviewRenderer}.
 *
 * <p>The screen loads its field values from the last preview the client holds, edits locally, and sends changes to
 * the server; it does not re-read a later preview over the operator's in-progress edits (reopening shows the saved
 * JSON, which is the verification path). Values not represented as their own control (numbers, block ids) are pushed
 * on tab switch, on Save and on close; the list edits, node edits and build actions send immediately.
 */
public final class TrackEditorScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final String[] SECTIONS = {
            "gui.dmz_ragnarok.core.race.editor.tab.race", "gui.dmz_ragnarok.core.race.editor.tab.build",
            "gui.dmz_ragnarok.core.race.editor.tab.surface", "gui.dmz_ragnarok.core.race.editor.tab.node"
    };
    // Surface list rows are tall enough for the standard round delete button.
    private static final int SURFACE_ROW_H = GuiTheme.ICON_BUTTON_SIZE + GuiTheme.UNIT;

    private static int lastTab;
    private static int surfaceScroll;

    private final String trackId;

    private int tab;
    private boolean loaded;
    private int loadedVersion = -1;

    // --- editable field values, loaded from the track ---
    private String laps = "3";
    private String defaultWidth = "8";
    private String offroad = "0.55";
    private String rescue = "6";
    private String fallDepth = "8";
    private String wallHeight = "2";
    private String headroom = "3";
    private String gateSpacing = "24";
    private String bounceMode = "BOTH";
    private String gridMode = "AUTO";
    private String gridSpacing = "3";
    private String buildSurfaceBlock = "minecraft:smooth_stone";
    private String edgeBlock = "minecraft:polished_andesite";
    private String wallBlock = "minecraft:smooth_stone";
    private final List<String> surfaceBlocks = new ArrayList<>();
    private String surfaceAddChoice = "";

    // node tab
    private int selectedNode = -1;
    private String nodeWidth = "0";

    // cached, lazily built list of every block id for the searchable block dropdowns
    private static List<String> blockOptions;

    public TrackEditorScreen(String trackId)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.race.editor.title", trackId), UI_W, UI_H, null);
        this.trackId = trackId;
        this.tab = Math.max(0, Math.min(lastTab, SECTIONS.length - 1));
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        // Ask the server for a fresh preview so a just-opened screen fills in even if none arrived yet.
        send(EditorAction.REFRESH, BlockPos.ZERO, 0, "");
        loadFromTrack();

        rowY = buildNamedTabHeader(trackId, trAll(SECTIONS), tab, this::selectTab);

        switch (tab)
        {
            case 0 -> buildRaceTab();
            case 1 -> buildBuildTab();
            case 2 -> buildSurfaceTab();
            default -> buildNodeTab();
        }

        commitBtn(14, footerY(), 60, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.save"),
                this::applyAndSend);
        btn(UI_W - 74, footerY(), 60, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.close"), () ->
        {
            applyAndSend();
            onClose();
        });
    }

    private void loadFromTrack()
    {
        TrackDef def = RaceClientState.editorTrack();
        loadedVersion = RaceClientState.editorVersion();
        if (def == null)
        {
            loaded = false;
            return;
        }
        loaded = true;
        laps = intStr(def.laps);
        defaultWidth = dbl(def.defaultWidth);
        offroad = dbl(def.offroadMult);
        rescue = dbl(def.rescueDistance);
        fallDepth = intStr(def.fallDepth);
        wallHeight = intStr(def.wallHeight);
        headroom = intStr(def.headroom);
        gateSpacing = intStr(def.autoGateSpacing);
        bounceMode = def.bounceMode == null || def.bounceMode.isBlank() ? "BOTH" : def.bounceMode;
        gridMode = def.gridMode == null || def.gridMode.isBlank() ? "AUTO" : def.gridMode;
        gridSpacing = dbl(def.gridSpacing);
        buildSurfaceBlock = def.buildSurfaceBlock;
        edgeBlock = def.edgeBlock;
        wallBlock = def.wallBlock;
        surfaceBlocks.clear();
        surfaceBlocks.addAll(def.surfaceBlocks);
        if (selectedNode < 0 || def.node(selectedNode) == null)
            selectedNode = RaceClientState.editorSelectedNode() >= 0 && def.node(RaceClientState.editorSelectedNode()) != null
                    ? RaceClientState.editorSelectedNode()
                    : (def.nodes.isEmpty() ? -1 : def.nodes.get(0).id);
        TrackNode n = selectedNode >= 0 ? def.node(selectedNode) : null;
        nodeWidth = n != null ? dbl(n.widthOr(def.defaultWidth)) : "0";
    }

    @Override
    public void tick()
    {
        super.tick();
        // Fill the screen once the first preview arrives (the initial open may precede it); do not clobber edits after.
        if (!loaded && RaceClientState.editorTrack() != null)
            rebuildWidgets();
    }

    private void selectTab(int t)
    {
        applyAndSend();
        tab = t;
        lastTab = t;
        rebuildWidgets();
    }

    // --- tabs ---

    private void buildRaceTab()
    {
        tf(tr("gui.dmz_ragnarok.core.race.editor.laps"), laps, v -> laps = v);
        tf(tr("gui.dmz_ragnarok.core.race.editor.default_width"), defaultWidth, v -> defaultWidth = v);
        tf(tr("gui.dmz_ragnarok.core.race.editor.offroad"), offroad, v -> offroad = v);
        tip(tr("gui.dmz_ragnarok.core.race.editor.offroad_tip"));
        tf(tr("gui.dmz_ragnarok.core.race.editor.rescue"), rescue, v -> rescue = v);
        tf(tr("gui.dmz_ragnarok.core.race.editor.fall_depth"), fallDepth, v -> fallDepth = v);
        tf(tr("gui.dmz_ragnarok.core.race.editor.gate_spacing"), gateSpacing, v -> gateSpacing = v);
        df(tr("gui.dmz_ragnarok.core.race.editor.bounce"), List.of(EditorAction.BOUNCE_MODES), bounceMode, v -> bounceMode = v.isBlank() ? "BOTH" : v);
    }

    private void buildBuildTab()
    {
        // No block placement from the editor (owner rule: the wand places NO blocks), so the build blocks, wall
        // height and headroom are no longer offered; a track keeps whatever values it has stored.
        df(tr("gui.dmz_ragnarok.core.race.editor.grid_mode"), List.of("AUTO", "MANUAL"), gridMode, v -> gridMode = v.isBlank() ? "AUTO" : v);
        tf(tr("gui.dmz_ragnarok.core.race.editor.grid_spacing"), gridSpacing, v -> gridSpacing = v);

        int by = rowY + 4;
        btn(14, by, 84, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.race.editor.undo"), () ->
                send(EditorAction.UNDO, BlockPos.ZERO, 0, ""));
        btn(104, by, 84, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.race.editor.validate"), () ->
                send(EditorAction.VALIDATE, BlockPos.ZERO, 0, ""));
    }

    private void buildSurfaceTab()
    {
        df(tr("gui.dmz_ragnarok.core.race.editor.add_block"), blockOptions(), surfaceAddChoice, v -> surfaceAddChoice = v);
        int ay = rowY + 2;
        commitBtn(14, ay, 84, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.race.road.add"), () ->
        {
            applyFields();
            if (!surfaceAddChoice.isBlank())
                send(EditorAction.ADD_SURFACE, BlockPos.ZERO, 0, surfaceAddChoice);
        });
        commitBtn(104, ay, 84, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.race.road.add_held"),
                () -> send(EditorAction.ADD_SURFACE_HELD, BlockPos.ZERO, 0, ""));
        tooltip(104, ay, 84, GuiTheme.BUTTON_HEIGHT, tr("gui.dmz_ragnarok.core.race.road.add_held_tip"));

        int top = ay + GuiTheme.BUTTON_HEIGHT + 4;
        int rh = SURFACE_ROW_H;
        int cap = rowsThatFit(top, rh);
        int xX = rowControlRight() - GuiTheme.ICON_BUTTON_SIZE;
        String always = tr("gui.dmz_ragnarok.core.race.road.always");
        surfaceScroll = Math.max(0, Math.min(surfaceScroll, Math.max(0, surfaceBlocks.size() - cap)));
        int end = Math.min(surfaceBlocks.size(), surfaceScroll + cap);
        for (int i = surfaceScroll; i < end; i++)
        {
            final String block = surfaceBlocks.get(i);
            int ry = top + (i - surfaceScroll) * rh;
            int textY = ry + (GuiTheme.ICON_BUTTON_SIZE - font.lineHeight) / 2 + 1;
            label(block, 14, textY, GuiTheme.COLOR_ROW);
            // The two always-surface blocks cannot be removed (the server refuses), so they get a tag, not an X.
            if (RaceTuningScreen.isAlwaysSurface(block))
                label(always, rowControlRight() - font.width(always), textY, GuiTheme.COLOR_MUTED);
            else
                btn(xX, ry, GuiTheme.ICON_BUTTON_SIZE, GuiTheme.ICON_BUTTON_SIZE, Component.literal("\u00a7cX"),
                        () -> send(EditorAction.REMOVE_SURFACE, BlockPos.ZERO, 0, block)).asIcon();
        }
        scrollList(14, uiWidth, top, rh, cap, surfaceBlocks.size(), surfaceScroll, v ->
        {
            surfaceScroll = v;
            rebuildWidgets();
        });
    }

    private void buildNodeTab()
    {
        TrackDef def = RaceClientState.editorTrack();
        List<String> ids = new ArrayList<>();
        if (def != null)
            for (TrackNode n : def.nodes)
                ids.add(Integer.toString(n.id));
        String cur = selectedNode >= 0 ? Integer.toString(selectedNode) : "";
        df(tr("gui.dmz_ragnarok.core.race.editor.node"), ids, cur, v ->
        {
            selectedNode = v.isBlank() ? -1 : parseI(v, -1);
            TrackDef d = RaceClientState.editorTrack();
            TrackNode n = d != null && selectedNode >= 0 ? d.node(selectedNode) : null;
            nodeWidth = n != null ? dbl(n.widthOr(d.defaultWidth)) : "0";
            rebuildWidgets();
        });

        if (selectedNode < 0)
            return;

        TrackNode node = def != null ? def.node(selectedNode) : null;
        tf(tr("gui.dmz_ragnarok.core.race.editor.node_width"), nodeWidth, v -> nodeWidth = v);
        tip(tr("gui.dmz_ragnarok.core.race.editor.node_width_tip"));
        int wy = rowY + 2;
        btn(14, wy, 130, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.race.editor.set_node_width"), () ->
        {
            applyFields();
            send(EditorAction.NODE_SET_WIDTH, new BlockPos(selectedNode, 0, 0), Math.max(0, parseI(nodeWidth, 0)), "");
        });
        rowY = wy + GuiTheme.BUTTON_HEIGHT + 4;

        boolean wallL = node != null && node.wallL;
        boolean wallR = node != null && node.wallR;
        boolean cp = node != null && node.checkpoint;
        bf(tr("gui.dmz_ragnarok.core.race.editor.wall_left"), wallL, () -> send(EditorAction.NODE_TOGGLE_WALL_L, new BlockPos(selectedNode, 0, 0), 0, ""));
        bf(tr("gui.dmz_ragnarok.core.race.editor.wall_right"), wallR, () -> send(EditorAction.NODE_TOGGLE_WALL_R, new BlockPos(selectedNode, 0, 0), 0, ""));
        bf(tr("gui.dmz_ragnarok.core.race.editor.checkpoint"), cp, () -> send(EditorAction.NODE_TOGGLE_CHECKPOINT, new BlockPos(selectedNode, 0, 0), 0, ""));
    }

    // --- send ---

    /** Push every numeric / block field to the server (idempotent). Called on tab switch, Save and close. */
    private void applyAndSend()
    {
        applyFields();
        send(EditorAction.SET_LAPS, BlockPos.ZERO, Math.max(1, parseI(laps, 3)), "");
        send(EditorAction.SET_WIDTH, BlockPos.ZERO, Math.max(1, (int) Math.round(parseD(defaultWidth, 8))), "");
        send(EditorAction.SET_OFFROAD, BlockPos.ZERO, clampPercent(parseD(offroad, 0.55)), "");
        send(EditorAction.SET_RESCUE, BlockPos.ZERO, Math.max(1, (int) Math.round(parseD(rescue, 6))), "");
        send(EditorAction.SET_FALLDEPTH, BlockPos.ZERO, Math.max(1, parseI(fallDepth, 8)), "");
        send(EditorAction.SET_WALLHEIGHT, BlockPos.ZERO, Math.max(0, parseI(wallHeight, 2)), "");
        send(EditorAction.SET_HEADROOM, BlockPos.ZERO, Math.max(0, parseI(headroom, 3)), "");
        send(EditorAction.SET_GATESPACING, BlockPos.ZERO, Math.max(0, parseI(gateSpacing, 24)), "");
        send(EditorAction.SET_BOUNCE, BlockPos.ZERO, bounceOrdinal(bounceMode), "");
        send(EditorAction.SET_GRIDMODE, BlockPos.ZERO, "MANUAL".equalsIgnoreCase(gridMode) ? 1 : 0, "");
        send(EditorAction.SET_GRIDSPACING, BlockPos.ZERO, Math.max(1, (int) Math.round(parseD(gridSpacing, 3))), "");
        if (buildSurfaceBlock != null && !buildSurfaceBlock.isBlank())
            send(EditorAction.SET_BUILD_SURFACE, BlockPos.ZERO, 0, buildSurfaceBlock);
        if (edgeBlock != null && !edgeBlock.isBlank())
            send(EditorAction.SET_EDGE, BlockPos.ZERO, 0, edgeBlock);
        if (wallBlock != null && !wallBlock.isBlank())
            send(EditorAction.SET_WALL, BlockPos.ZERO, 0, wallBlock);
    }

    private void send(int action, BlockPos pos, int arg, String text)
    {
        NetworkUtils.sendToServer(new PacketTrackEditorAction(trackId, action, pos, arg, text));
    }

    private static int clampPercent(double mult)
    {
        return Math.max(5, Math.min(100, (int) Math.round(mult * 100.0)));
    }

    private static int bounceOrdinal(String mode)
    {
        for (int i = 0; i < EditorAction.BOUNCE_MODES.length; i++)
            if (EditorAction.BOUNCE_MODES[i].equalsIgnoreCase(mode))
                return i;
        return 2; // BOTH
    }

    // Shared with the per-track tuning screen's road-block picker.
    static List<String> blockOptions()
    {
        if (blockOptions == null)
        {
            List<String> opts = new ArrayList<>();
            for (var block : ForgeRegistries.BLOCKS.getValues())
            {
                var key = ForgeRegistries.BLOCKS.getKey(block);
                if (key != null && block.asItem() instanceof BlockItem)
                    opts.add(key.toString());
            }
            opts.sort(String::compareTo);
            blockOptions = opts;
        }
        return blockOptions;
    }

    @Override
    public void onClose()
    {
        // Return to the world, not a parent screen (the wand opened this directly).
        Minecraft.getInstance().setScreen(null);
    }
}
