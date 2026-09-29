package net.shurui.shuruisutilities.racing.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.shuruisutilities.client.gui.theme.GuiText;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.racing.EditorAction;
import net.shurui.shuruisutilities.racing.net.PacketRaceTuningSave;
import net.shurui.shuruisutilities.racing.net.PacketTrackEditorAction;
import net.shurui.shuruisutilities.racing.physics.PowerupKind;
import net.shurui.shuruisutilities.racing.physics.RaceDriveParams;
import net.shurui.shuruisutilities.racing.track.TrackDef;
import net.shurui.shuruisutilities.racing.tuning.RaceTuningDto;

/**
 * The full race tuning screen (opened by packet 120, saved by packet 121, gated on {@code su.race.admin}). Four tabs:
 * Physics (the shared kart-physics numbers: top speed, accel, steering, grip, drift, off-road, boost, mini-turbo
 * tiers, rocket start), Items (the item-box behaviour: roulette length, respawn hide, Spirit Bomb start lock), Odds
 * (the {@code [kind][bucket]} item weight grid) and Rules (max racers, laps, lobby, rescue). Save sends the whole
 * {@link RaceTuningDto} back over packet 121 (well under the 32767-byte serverbound ceiling).
 *
 * <p>The screen has two modes. GLOBAL (empty {@link #trackId}) edits the server-wide tuning; the Ragnarok Key
 * enforces the permission, persists it to {@code RaceTuningStore} and publishes it to the shard sync. PER-TRACK (a
 * non-empty {@code trackId}) edits only the two tabs a track can override, Physics and Odds, and saves those as that
 * track's override in its JSON, falling back to the global tuning for everything else. Content stays configurable in
 * game (owner rule).
 *
 * <p>Per-track mode adds a third tab, Road blocks: the track's {@code TrackDef.surfaceBlocks}, the blocks that do NOT
 * slow racers (anything else is off-road). It reads the track through the editor preview (packet 119 REFRESH answered
 * by packet 118) and edits it through the existing editor actions ADD_SURFACE / ADD_SURFACE_HELD / REMOVE_SURFACE,
 * which the server saves at once, so it needs no server logic of its own and is independent of the tuning Save.
 */
public final class RaceTuningScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final String TAB_PHYSICS = "gui.dmz_ragnarok.core.race.tuning.tab.physics";
    private static final String TAB_ITEMS = "gui.dmz_ragnarok.core.race.tuning.tab.items";
    private static final String TAB_ODDS = "gui.dmz_ragnarok.core.race.tuning.tab.odds";
    private static final String TAB_RULES = "gui.dmz_ragnarok.core.race.tuning.tab.rules";
    private static final String TAB_ROAD = "gui.dmz_ragnarok.core.race.tuning.tab.road";
    private static final String[] SECTIONS_GLOBAL = { TAB_PHYSICS, TAB_ITEMS, TAB_ODDS, TAB_RULES };
    // Per-track: the two tabs a track may override, plus its road-block list.
    private static final String[] SECTIONS_TRACK = { TAB_PHYSICS, TAB_ODDS, TAB_ROAD };
    private static int lastTab;

    // Short row labels for the odds grid, in PowerupKind ordinal order.
    private static final String[] ABBR = {
            "Destroyer", "Senzu", "Kaioken3", "Kaiokn20", "KiBlast", "Hellzone", "SpiritBmb", "KiMine",
            "Saibaman", "FakeBall", "GravCrush", "SolarFlr", "Nimbus", "Afterimg", "Kiai", "Zeni"
    };

    // Road-block list geometry: rows tall enough for the standard round delete button.
    private static final int ROAD_ROW_H = GuiTheme.ICON_BUTTON_SIZE + GuiTheme.UNIT;
    private static final int GREY = 0xFFB0B0B0;

    private final RaceTuningDto dto;
    private final String trackId; // empty = global tuning; else a per-track override
    private final boolean perTrack;
    private final String[] sections;
    private int tab;

    // Rules field text
    private String maxRacers, defaultLaps, lobbySeconds, rescuePause, rescueDistance;
    // Items field text
    private String rouletteTicks, itemRespawn, spiritLock;
    // Physics field text
    private String topSpeed, accel, brake, reverseFraction, steerLow, steerHigh, grip, driftMin, driftGrip,
            hopImpulse, offroadMult, boostMult, wallBump, rocketWindow;
    private String mtMultBlue, mtMultOrange, mtMultPurple, mtTicksBlue, mtTicksOrange, mtTicksPurple;
    // odds grid text [kind][bucket]
    private final String[][] oddsText = new String[RaceTuningDto.KINDS][RaceTuningDto.BUCKETS];

    // Road-block tab state: the block picked in the add dropdown, the list scroll, the surface set the list was
    // built from (null = not loaded yet) and whether this visit to the tab already asked the server for the track.
    private String roadAddChoice = "";
    private int roadScroll;
    private List<String> roadShown;
    private boolean roadRequested;

    public RaceTuningScreen(RaceTuningDto tuning)
    {
        this(tuning, "");
    }

    public RaceTuningScreen(RaceTuningDto tuning, String trackId)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.race.tuning.title"), UI_W, UI_H, null);
        this.dto = tuning;
        this.trackId = trackId == null ? "" : trackId;
        this.perTrack = !this.trackId.isEmpty();
        this.sections = perTrack ? SECTIONS_TRACK : SECTIONS_GLOBAL;
        this.tab = Math.max(0, Math.min(lastTab, sections.length - 1));
        loadFromDto();
    }

    private void loadFromDto()
    {
        maxRacers = intStr(dto.maxRacers);
        defaultLaps = intStr(dto.defaultLaps);
        lobbySeconds = intStr(dto.lobbySeconds);
        rescuePause = intStr(dto.rescuePauseTicks);
        rescueDistance = dbl(dto.rescueDistance);

        rouletteTicks = intStr(dto.rouletteTicks);
        itemRespawn = intStr(dto.itemRespawnTicks);
        spiritLock = intStr(dto.spiritBombLockSeconds);

        RaceDriveParams d = dto.drive != null ? dto.drive : new RaceDriveParams();
        topSpeed = dbl(d.topSpeed);
        accel = dbl(d.accel);
        brake = dbl(d.brake);
        reverseFraction = dbl(d.reverseFraction);
        steerLow = dbl(d.steerRateLow);
        steerHigh = dbl(d.steerRateHigh);
        grip = dbl(d.grip);
        driftMin = dbl(d.driftMinFraction);
        driftGrip = dbl(d.driftGripFactor);
        hopImpulse = dbl(d.hopImpulse);
        offroadMult = dbl(d.offroadMult);
        boostMult = dbl(d.boostMult);
        wallBump = dbl(d.wallBumpFactor);
        rocketWindow = intStr(d.rocketStartWindow);
        mtMultBlue = dbl(mt(d.miniTurboMult, 1, 1.12));
        mtMultOrange = dbl(mt(d.miniTurboMult, 2, 1.25));
        mtMultPurple = dbl(mt(d.miniTurboMult, 3, 1.4));
        mtTicksBlue = intStr((int) mt(d.miniTurboTicks, 1, 12));
        mtTicksOrange = intStr((int) mt(d.miniTurboTicks, 2, 22));
        mtTicksPurple = intStr((int) mt(d.miniTurboTicks, 3, 34));

        int[][] o = RaceTuningDto.copyOdds(dto.odds);
        for (int k = 0; k < RaceTuningDto.KINDS; k++)
            for (int b = 0; b < RaceTuningDto.BUCKETS; b++)
                oddsText[k][b] = intStr(o[k][b]);
    }

    private static double mt(double[] a, int i, double fallback)
    {
        return a != null && i < a.length ? a[i] : fallback;
    }

    private static double mt(int[] a, int i, double fallback)
    {
        return a != null && i < a.length ? a[i] : fallback;
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        // The screen's name sits top-left (the GuiTheme named-editor header); no subtitle line.
        String name = perTrack ? tr("gui.dmz_ragnarok.core.race.tuning.track_title", trackId)
                : tr("gui.dmz_ragnarok.core.race.tuning.title");
        rowY = buildNamedTabHeader(name, trAll(sections), tab, this::selectTab);

        switch (sections[tab])
        {
            case TAB_PHYSICS -> buildPhysicsTab();
            case TAB_ITEMS -> buildItemsTab();
            case TAB_ODDS -> buildOddsTab();
            case TAB_RULES -> buildRulesTab();
            case TAB_ROAD -> buildRoadTab();
            default -> { }
        }

        commitBtn(14, footerY(), 60, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.save"),
                this::applyAndSend);
        if (perTrack)
            btn(UI_W / 2 - 40, footerY(), 80, footerBtnHeight(),
                    Component.translatable("gui.dmz_ragnarok.core.race.tuning.use_global"), this::clearOverride);
        btn(UI_W - 74, footerY(), 60, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.close"), () ->
        {
            applyAndSend();
            onClose();
        });
    }

    private void selectTab(int t)
    {
        applyToDto();
        tab = t;
        lastTab = t;
        roadRequested = false;
        rebuildWidgets();
    }

    // --- Physics tab: two compact columns of numeric fields ---
    private void buildPhysicsTab()
    {
        int startY = rowY;
        int colAlbl = 14, colAfld = 92, colAw = 40;
        int colBlbl = 156, colBfld = 246, colBw = 40;
        int step = 12;

        int y = startY;
        y = prow(colAlbl, colAfld, colAw, y, step, "top_speed", topSpeed, v -> topSpeed = v);
        y = prow(colAlbl, colAfld, colAw, y, step, "accel", accel, v -> accel = v);
        y = prow(colAlbl, colAfld, colAw, y, step, "brake", brake, v -> brake = v);
        y = prow(colAlbl, colAfld, colAw, y, step, "reverse", reverseFraction, v -> reverseFraction = v);
        y = prow(colAlbl, colAfld, colAw, y, step, "steer_low", steerLow, v -> steerLow = v);
        y = prow(colAlbl, colAfld, colAw, y, step, "steer_high", steerHigh, v -> steerHigh = v);
        y = prow(colAlbl, colAfld, colAw, y, step, "grip", grip, v -> grip = v);
        y = prow(colAlbl, colAfld, colAw, y, step, "drift_min", driftMin, v -> driftMin = v);
        y = prow(colAlbl, colAfld, colAw, y, step, "drift_grip", driftGrip, v -> driftGrip = v);
        y = prow(colAlbl, colAfld, colAw, y, step, "hop", hopImpulse, v -> hopImpulse = v);

        int y2 = startY;
        y2 = prow(colBlbl, colBfld, colBw, y2, step, "offroad", offroadMult, v -> offroadMult = v);
        y2 = prow(colBlbl, colBfld, colBw, y2, step, "boost", boostMult, v -> boostMult = v);
        y2 = prow(colBlbl, colBfld, colBw, y2, step, "wall_bump", wallBump, v -> wallBump = v);
        y2 = prow(colBlbl, colBfld, colBw, y2, step, "rocket", rocketWindow, v -> rocketWindow = v);
        y2 = prow(colBlbl, colBfld, colBw, y2, step, "mt_blue_x", mtMultBlue, v -> mtMultBlue = v);
        y2 = prow(colBlbl, colBfld, colBw, y2, step, "mt_orange_x", mtMultOrange, v -> mtMultOrange = v);
        y2 = prow(colBlbl, colBfld, colBw, y2, step, "mt_purple_x", mtMultPurple, v -> mtMultPurple = v);
        y2 = prow(colBlbl, colBfld, colBw, y2, step, "mt_blue_t", mtTicksBlue, v -> mtTicksBlue = v);
        y2 = prow(colBlbl, colBfld, colBw, y2, step, "mt_orange_t", mtTicksOrange, v -> mtTicksOrange = v);
        y2 = prow(colBlbl, colBfld, colBw, y2, step, "mt_purple_t", mtTicksPurple, v -> mtTicksPurple = v);

        rowY = Math.max(y, y2);
    }

    // One physics row: a grey label (lang key suffix under tuning.p.) and its value field.
    private int prow(int lblX, int fldX, int fldW, int y, int step, String key, String value,
                     java.util.function.Consumer<String> setter)
    {
        label(tr("gui.dmz_ragnarok.core.race.tuning.p." + key), lblX, y + 1, GREY);
        rawField(fldX, y, fldW, value, setter);
        return y + step;
    }

    private void buildItemsTab()
    {
        tf(tr("gui.dmz_ragnarok.core.race.tuning.roulette"), rouletteTicks, v -> rouletteTicks = v);
        tip(tr("gui.dmz_ragnarok.core.race.tuning.roulette_tip"));
        tf(tr("gui.dmz_ragnarok.core.race.tuning.respawn"), itemRespawn, v -> itemRespawn = v);
        tip(tr("gui.dmz_ragnarok.core.race.tuning.respawn_tip"));
        tf(tr("gui.dmz_ragnarok.core.race.tuning.spirit_lock"), spiritLock, v -> spiritLock = v);
        tip(tr("gui.dmz_ragnarok.core.race.tuning.spirit_lock_tip"));
    }

    private void buildRulesTab()
    {
        tf(tr("gui.dmz_ragnarok.core.race.tuning.max_racers"), maxRacers, v -> maxRacers = v);
        tf(tr("gui.dmz_ragnarok.core.race.tuning.default_laps"), defaultLaps, v -> defaultLaps = v);
        tf(tr("gui.dmz_ragnarok.core.race.tuning.lobby_seconds"), lobbySeconds, v -> lobbySeconds = v);
        tf(tr("gui.dmz_ragnarok.core.race.tuning.rescue_pause"), rescuePause, v -> rescuePause = v);
        tf(tr("gui.dmz_ragnarok.core.race.tuning.rescue_distance"), rescueDistance, v -> rescueDistance = v);
    }

    // The odds grid: a header of bucket numbers (1 = front runner .. 8 = back marker), then one row per powerup with
    // a small weight field per bucket. Weights are relative; a higher weight in a bucket makes that item likelier for
    // a racer finishing in that bucket.
    private void buildOddsTab()
    {
        int labelX = 14;
        int col0 = 66;
        int step = 28;
        int fieldW = 22;

        // The corner caption is fitted so it stops short of the first bucket number.
        String corner = GuiText.ellipsize(font, tr("gui.dmz_ragnarok.core.race.tuning.odds_header"), col0 + 4 - labelX);
        label(corner, labelX, rowY, GREY);
        tooltip(labelX, rowY, col0 + RaceTuningDto.BUCKETS * step - labelX, 10,
                tr("gui.dmz_ragnarok.core.race.tuning.odds_tip"));
        for (int b = 0; b < RaceTuningDto.BUCKETS; b++)
            label(Integer.toString(b + 1), col0 + b * step + 8, rowY, GREY);
        rowY += 11;

        for (int k = 0; k < RaceTuningDto.KINDS; k++)
        {
            final int kk = k;
            label(ABBR[k], labelX, rowY + 1, RaceItemIcons.colour(PowerupKind.byId(k)));
            for (int b = 0; b < RaceTuningDto.BUCKETS; b++)
            {
                final int bb = b;
                rawField(col0 + b * step, rowY, fieldW, oddsText[k][b], v -> oddsText[kk][bb] = v);
            }
            rowY += 11;
        }
    }

    // --- Road blocks tab (per-track only): the track's no-slow surface set ---
    private void buildRoadTab()
    {
        // Ask the server for this track once per visit to the tab; the answer (and every later add/remove) arrives
        // as an editor preview, which tick() turns into a rebuild.
        if (!roadRequested)
        {
            roadRequested = true;
            sendEditor(EditorAction.REFRESH, "");
        }

        // Add row: a searchable block picker, Add (the picked block) and Add held (the block in the off-hand).
        int ay = rowY;
        dfAt(14, ay + 1, 150, TrackEditorScreen.blockOptions(), roadAddChoice, v -> roadAddChoice = v);
        commitBtn(170, ay, 50, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.race.road.add"), () ->
        {
            applyFields();
            if (!roadAddChoice.isBlank())
                sendEditor(EditorAction.ADD_SURFACE, roadAddChoice);
        });
        int heldX = 224;
        commitBtn(heldX, ay, rowControlRight() - heldX, GuiTheme.BUTTON_HEIGHT,
                Component.translatable("gui.dmz_ragnarok.core.race.road.add_held"),
                () -> sendEditor(EditorAction.ADD_SURFACE_HELD, ""));
        tooltip(heldX, ay, rowControlRight() - heldX, GuiTheme.BUTTON_HEIGHT,
                tr("gui.dmz_ragnarok.core.race.road.add_held_tip"));

        int headY = ay + GuiTheme.BUTTON_HEIGHT + 4;
        TrackDef def = editorTrackForThis();
        roadShown = def == null ? null : new ArrayList<>(def.surfaceIds());
        if (roadShown == null)
        {
            label(tr("gui.dmz_ragnarok.core.race.road.loading"), 14, headY + 2, GuiTheme.COLOR_MUTED);
            return;
        }
        String head = tr("gui.dmz_ragnarok.core.race.road.count", roadShown.size());
        label(head, 14, headY + 2, GuiTheme.COLOR_LABEL);
        tooltip(14, headY, rowControlRight() - 14, 12, tr("gui.dmz_ragnarok.core.race.road.count_tip"));

        int top = headY + 14;
        surfaceList(top, roadShown);
    }

    // The scrolling block list: the block id on the left and a round X on the right. The two always-surface blocks
    // (boost pad, finish line) cannot be removed, so they carry a muted tag instead of the X.
    private void surfaceList(int top, List<String> blocks)
    {
        int cap = rowsThatFit(top, ROAD_ROW_H);
        roadScroll = Math.max(0, Math.min(roadScroll, Math.max(0, blocks.size() - cap)));
        int end = Math.min(blocks.size(), roadScroll + cap);
        int xX = rowControlRight() - GuiTheme.ICON_BUTTON_SIZE;
        String always = tr("gui.dmz_ragnarok.core.race.road.always");
        for (int i = roadScroll; i < end; i++)
        {
            final String block = blocks.get(i);
            int ry = top + (i - roadScroll) * ROAD_ROW_H;
            int textY = ry + (GuiTheme.ICON_BUTTON_SIZE - font.lineHeight) / 2 + 1;
            label(block, 14, textY, GuiTheme.COLOR_ROW);
            if (isAlwaysSurface(block))
                label(always, rowControlRight() - font.width(always), textY, GuiTheme.COLOR_MUTED);
            else
                btn(xX, ry, GuiTheme.ICON_BUTTON_SIZE, GuiTheme.ICON_BUTTON_SIZE, Component.literal("§cX"),
                        () -> sendEditor(EditorAction.REMOVE_SURFACE, block)).asIcon();
        }
        scrollList(14, uiWidth, top, ROAD_ROW_H, cap, blocks.size(), roadScroll, v ->
        {
            roadScroll = v;
            rebuildWidgets();
        });
    }

    static boolean isAlwaysSurface(String block)
    {
        return TrackDef.ALWAYS_SURFACE_BOOST_PAD.equals(block) || TrackDef.ALWAYS_SURFACE_FINISH_LINE.equals(block);
    }

    // The editor preview, only when it is THIS screen's track (a wand held for another track also pushes previews).
    private TrackDef editorTrackForThis()
    {
        TrackDef def = RaceClientState.editorTrack();
        return def != null && trackId.equals(def.id) ? def : null;
    }

    private void sendEditor(int action, String text)
    {
        NetworkUtils.sendToServer(new PacketTrackEditorAction(trackId, action, BlockPos.ZERO, 0, text));
    }

    @Override
    public void tick()
    {
        super.tick();
        // Road tab: rebuild when the server's copy of the surface set differs from the list on screen (first load,
        // or after an add/remove). The wand's periodic preview push leaves an unchanged list alone.
        if (perTrack && TAB_ROAD.equals(sections[tab]) && openDropdown == null)
        {
            TrackDef def = editorTrackForThis();
            List<String> now = def == null ? null : new ArrayList<>(def.surfaceIds());
            if (now != null && !now.equals(roadShown))
            {
                applyFields();
                rebuildWidgets();
            }
        }
    }

    private void applyToDto()
    {
        applyFields();
        dto.maxRacers = clampI(parseI(maxRacers, dto.maxRacers), 1, 24);
        dto.defaultLaps = clampI(parseI(defaultLaps, dto.defaultLaps), 1, 50);
        dto.lobbySeconds = clampI(parseI(lobbySeconds, dto.lobbySeconds), 0, 600);
        dto.rescuePauseTicks = clampI(parseI(rescuePause, dto.rescuePauseTicks), 0, 200);
        dto.rescueDistance = Math.max(1.0, Math.min(64.0, parseD(rescueDistance, dto.rescueDistance)));

        dto.rouletteTicks = clampI(parseI(rouletteTicks, dto.rouletteTicks), 0, 200);
        dto.itemRespawnTicks = clampI(parseI(itemRespawn, dto.itemRespawnTicks), 0, 600);
        dto.spiritBombLockSeconds = clampI(parseI(spiritLock, dto.spiritBombLockSeconds), 0, 600);

        RaceDriveParams d = dto.drive != null ? dto.drive : new RaceDriveParams();
        d.topSpeed = clampD(parseD(topSpeed, d.topSpeed), 0.05, 8.0);
        d.accel = clampD(parseD(accel, d.accel), 0.001, 2.0);
        d.brake = clampD(parseD(brake, d.brake), 0.001, 2.0);
        d.reverseFraction = clampD(parseD(reverseFraction, d.reverseFraction), 0.0, 1.0);
        d.steerRateLow = clampD(parseD(steerLow, d.steerRateLow), 0.1, 30.0);
        d.steerRateHigh = clampD(parseD(steerHigh, d.steerRateHigh), 0.1, 30.0);
        d.grip = clampD(parseD(grip, d.grip), 0.01, 1.0);
        d.driftMinFraction = clampD(parseD(driftMin, d.driftMinFraction), 0.0, 1.0);
        d.driftGripFactor = clampD(parseD(driftGrip, d.driftGripFactor), 0.01, 1.0);
        d.hopImpulse = clampD(parseD(hopImpulse, d.hopImpulse), 0.0, 2.0);
        d.offroadMult = clampD(parseD(offroadMult, d.offroadMult), 0.05, 1.0);
        d.boostMult = clampD(parseD(boostMult, d.boostMult), 1.0, 4.0);
        d.wallBumpFactor = clampD(parseD(wallBump, d.wallBumpFactor), 0.0, 1.0);
        d.rocketStartWindow = clampI(parseI(rocketWindow, d.rocketStartWindow), 0, 60);
        d.miniTurboMult = new double[] { 1.0,
                clampD(parseD(mtMultBlue, 1.12), 1.0, 4.0),
                clampD(parseD(mtMultOrange, 1.25), 1.0, 4.0),
                clampD(parseD(mtMultPurple, 1.4), 1.0, 4.0) };
        d.miniTurboTicks = new int[] { 0,
                clampI(parseI(mtTicksBlue, 12), 0, 200),
                clampI(parseI(mtTicksOrange, 22), 0, 200),
                clampI(parseI(mtTicksPurple, 34), 0, 200) };
        dto.drive = d;

        int[][] o = new int[RaceTuningDto.KINDS][RaceTuningDto.BUCKETS];
        for (int k = 0; k < RaceTuningDto.KINDS; k++)
            for (int b = 0; b < RaceTuningDto.BUCKETS; b++)
                o[k][b] = clampI(parseI(oddsText[k][b], 0), 0, 100000);
        dto.odds = o;
    }

    private void applyAndSend()
    {
        applyToDto();
        NetworkUtils.sendToServer(new PacketRaceTuningSave(dto, trackId));
    }

    // Per-track "Use global": dropping the override is a destructive one-liner, so rather than add a wire bit to
    // packet 121 the button points the operator at the command that clears it (kept minimal, no extra wire state).
    private void clearOverride()
    {
        if (minecraft != null && minecraft.player != null)
            minecraft.player.displayClientMessage(Component.translatable(
                    "gui.dmz_ragnarok.core.race.tuning.use_global_msg", trackId), false);
        onClose();
    }

    private static int clampI(int v, int lo, int hi)
    {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampD(double v, double lo, double hi)
    {
        return Math.max(lo, Math.min(hi, v));
    }
}
