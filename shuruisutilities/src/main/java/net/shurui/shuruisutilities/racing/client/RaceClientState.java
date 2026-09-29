package net.shurui.shuruisutilities.racing.client;

import net.shurui.dev.sdu.api.ClientGate;
import net.shurui.shuruisutilities.racing.net.PacketRaceEffect;
import net.shurui.shuruisutilities.racing.net.PacketRaceFeatureHello;
import net.shurui.shuruisutilities.racing.net.PacketRaceItemSlot;
import net.shurui.shuruisutilities.racing.net.PacketRaceLobbyOpen;
import net.shurui.shuruisutilities.racing.net.PacketRaceRescue;
import net.shurui.shuruisutilities.racing.net.PacketRaceResults;
import net.shurui.shuruisutilities.racing.net.PacketRaceSession;
import net.shurui.shuruisutilities.racing.net.PacketRaceState;
import net.shurui.shuruisutilities.racing.net.PacketRaceTuningOpen;
import net.shurui.shuruisutilities.racing.net.PacketTrackEditorSync;

/**
 * The client's live racing state: the single sink every server-to-client racing packet (108-122) routes into on
 * the client thread. It is the ONLY client gate for the feature: every entry point returns early unless
 * {@link ClientGate#feature "racing"} is set (the private-feature login sync), so a plain client on a keyless
 * server draws no HUD, opens no screen and reacts to nothing, even if a stray packet arrives.
 *
 * <p>R0 is a STUB: it holds the last session parameters and roulette result so later batches (HUD in R5+, the
 * editor preview in R2, screens in R3/R10/R11) have a place to read them, but it renders nothing yet. Client-only
 * by design: reached exclusively through {@code DistExecutor} from the packet handlers, so it never classloads on
 * a dedicated server.
 */
public final class RaceClientState
{
    private RaceClientState() {}

    /** True between an OPEN/RUNNING session hello and its teardown; the HUD (R5+) shows only while this holds. */
    private static volatile boolean sessionActive;

    /** The shared kart-physics parameters for the current race (packet 109); null when not racing. */
    private static volatile net.shurui.shuruisutilities.racing.physics.RaceDriveParams sessionParams;
    /** The surface block set (resolved from the packet 109 registry ids), for the off-road test. */
    private static volatile java.util.Set<net.minecraft.world.level.block.Block> surfaceBlocks =
            java.util.Collections.emptySet();
    /** The track centreline (packet 109), for the minimap (R5) and the autopilot / autodrive steering. */
    private static volatile java.util.List<float[]> centreline = java.util.Collections.emptyList();

    // --- live HUD state (packet 110), read by RaceHudOverlay ---
    private static volatile int place;
    private static volatile int totalRacers;
    private static volatile int lapCurrent;
    private static volatile int lapTotal;
    private static volatile int timeTicks;
    private static volatile int countdown;
    private static volatile boolean wrongWay;
    private static volatile boolean finalLap;
    private static volatile boolean finished;
    /** Zeni carried (0..10): the HUD counter. */
    private static volatile int zeni;
    /** The dominant active self powerup (PowerupKind ordinal, -1 none) and its ticks left, for the HUD ring / badge. */
    private static volatile int selfEffect = -1;
    private static volatile int selfEffectTicks;
    private static volatile java.util.List<float[]> racerDots = java.util.Collections.emptyList();
    private static volatile java.util.List<java.util.UUID> racerUuids = java.util.Collections.emptyList();
    private static volatile java.util.UUID selfId;
    /** True once we have applied the current countdown to the ridden bike's preStart (so it freezes + rocket-starts). */
    private static volatile boolean countdownApplied;
    /** The last completed lap's time in ticks (derived client-side from the lap counter changing), 0 if none. */
    private static volatile int lastLapSplit;
    private static volatile int lapStartTime;
    private static volatile int prevLap;

    public static int lastLapSplit() { return gated() ? 0 : lastLapSplit; }

    // --- item slot + roulette (packet 111) ---
    /** The item currently held (or being rolled), NONE when the slot is empty. */
    private static volatile net.shurui.shuruisutilities.racing.physics.PowerupKind heldItem =
            net.shurui.shuruisutilities.racing.physics.PowerupKind.NONE;
    /** Charge count for a multi-use held item (Kaioken x3 = 3), else 1. */
    private static volatile int heldCharges;
    /** Wall-clock start of the current roulette spin, and its length in millis; the HUD reveals once it passes. */
    private static volatile long rouletteStartMs;
    private static volatile long rouletteDurationMs;

    // --- results (packet 113) ---
    private static volatile java.util.List<net.shurui.shuruisutilities.racing.net.PacketRaceResults.Entry> results =
            java.util.Collections.emptyList();
    /** Wall-clock time until which the results table is drawn (8s after they arrive). */
    private static volatile long resultsUntilMs;
    /** True once the RaceResultsScreen has been auto-opened for the current results, so it is not reopened each frame. */
    private static volatile boolean resultsShown;

    // --- lobby (packet 117) ---
    private static volatile String lobbyTrackId = "";
    private static volatile int lobbyLaps;
    private static volatile int lobbyMaxRacers;
    private static volatile int lobbySecondsRemaining = -1;
    /** Wall-clock stamp of the last lobby packet, so the screen can decay the countdown between the 1s updates. */
    private static volatile long lobbyStampMs;
    private static volatile java.util.List<net.shurui.shuruisutilities.racing.net.PacketRaceLobbyOpen.Member>
            lobbyMembers = java.util.Collections.emptyList();
    /** True once the RaceLobbyScreen has been auto-opened for the current lobby, so it is not reopened after a manual
     *  close. Reset when the race starts or the client resets. */
    private static volatile boolean lobbyShown;

    public static String lobbyTrackId() { return gated() ? "" : lobbyTrackId; }
    public static int lobbyLaps() { return gated() ? 0 : lobbyLaps; }
    public static int lobbyMaxRacers() { return gated() ? 0 : lobbyMaxRacers; }
    public static java.util.List<net.shurui.shuruisutilities.racing.net.PacketRaceLobbyOpen.Member> lobbyMembers()
    {
        return gated() ? java.util.Collections.emptyList() : lobbyMembers;
    }

    /** Seconds left before the lobby auto-starts, decayed locally between server updates; -1 when there is no timer. */
    public static int lobbySecondsRemaining()
    {
        if (gated() || lobbySecondsRemaining < 0)
            return lobbySecondsRemaining < 0 ? -1 : 0;
        long elapsed = (System.currentTimeMillis() - lobbyStampMs) / 1000L;
        return (int) Math.max(0, lobbySecondsRemaining - elapsed);
    }

    // --- rescue fade (packet 122) ---
    /** Wall-clock time until which the rescue fade is drawn. */
    private static volatile long rescueFadeUntilMs;

    // --- R9 screen effects (packet 112 BLIND / GRAVITY) ---
    /** Solar Flare: wall-clock start + length of the fading white blind overlay. */
    private static volatile long blindStartMs;
    private static volatile long blindDurationMs;
    /** Gravity Crush: wall-clock start + length of the red flash + camera shake. */
    private static volatile long gravityStartMs;
    private static volatile long gravityDurationMs;
    /** Kiai (R11): wall-clock start + length of the expanding kiwave ring under the user's own bike. */
    private static volatile long kiaiStartMs;
    private static volatile long kiaiDurationMs;

    public static int place() { return gated() ? 0 : place; }
    public static int totalRacers() { return gated() ? 0 : totalRacers; }
    public static int lapCurrent() { return gated() ? 0 : lapCurrent; }
    public static int lapTotal() { return gated() ? 0 : lapTotal; }
    public static int timeTicks() { return gated() ? 0 : timeTicks; }
    public static int countdown() { return gated() ? 0 : countdown; }
    public static boolean wrongWay() { return !gated() && wrongWay; }
    public static boolean finalLap() { return !gated() && finalLap; }
    public static boolean finished() { return !gated() && finished; }
    public static int zeni() { return gated() ? 0 : zeni; }
    /** The dominant active self powerup ordinal, or -1; for the HUD x20 ring and the aura badge. */
    public static int selfEffect() { return gated() ? -1 : selfEffect; }
    public static int selfEffectTicks() { return gated() ? 0 : selfEffectTicks; }
    public static java.util.List<float[]> racerDots() { return gated() ? java.util.Collections.emptyList() : racerDots; }
    public static java.util.List<java.util.UUID> racerUuids() { return gated() ? java.util.Collections.emptyList() : racerUuids; }
    public static java.util.UUID selfId() { return gated() ? null : selfId; }

    public static java.util.List<net.shurui.shuruisutilities.racing.net.PacketRaceResults.Entry> results()
    {
        return gated() ? java.util.Collections.emptyList() : results;
    }

    public static boolean resultsActive()
    {
        return !gated() && !results.isEmpty() && System.currentTimeMillis() < resultsUntilMs;
    }

    public static boolean rescueActive()
    {
        return !gated() && System.currentTimeMillis() < rescueFadeUntilMs;
    }

    /** Solar Flare white-blind intensity in [0,1] (1 at the flash, fading to 0), 0 when not blinded. */
    public static float blindAlpha()
    {
        if (gated() || blindDurationMs <= 0)
            return 0.0F;
        long elapsed = System.currentTimeMillis() - blindStartMs;
        if (elapsed < 0 || elapsed >= blindDurationMs)
            return 0.0F;
        return 1.0F - (float) elapsed / (float) blindDurationMs;
    }

    /** Gravity Crush red-flash intensity in [0,1] (1 at the hit, fading to 0), 0 when not flashing. */
    public static float gravityAlpha()
    {
        if (gated() || gravityDurationMs <= 0)
            return 0.0F;
        long elapsed = System.currentTimeMillis() - gravityStartMs;
        if (elapsed < 0 || elapsed >= gravityDurationMs)
            return 0.0F;
        return 1.0F - (float) elapsed / (float) gravityDurationMs;
    }

    /** Kiai ring progress in [0,1] (0 at the burst, 1 fully expanded / gone), or -1 when no ring is active. The ring
     *  grows with progress and fades as {@code 1 - progress}. Read by {@code RaceBikeFx} for the local player's bike. */
    public static float kiaiRingProgress()
    {
        if (gated() || kiaiDurationMs <= 0)
            return -1.0F;
        long elapsed = System.currentTimeMillis() - kiaiStartMs;
        if (elapsed < 0 || elapsed >= kiaiDurationMs)
            return -1.0F;
        return (float) elapsed / (float) kiaiDurationMs;
    }

    /** The shared kart-physics parameters for the live race, or null. Read by the client's race branch via RaceInput. */
    public static net.shurui.shuruisutilities.racing.physics.RaceDriveParams params()
    {
        return gated() ? null : sessionParams;
    }

    /** The surface block set for the live race (never null). */
    public static java.util.Set<net.minecraft.world.level.block.Block> surfaceBlockSet()
    {
        return gated() ? java.util.Collections.emptySet() : surfaceBlocks;
    }

    /** The track centreline for the live race (never null). */
    public static java.util.List<float[]> centreline()
    {
        return gated() ? java.util.Collections.emptyList() : centreline;
    }

    /** The last track-editor preview the server pushed (packet 118), decoded, and when it goes stale. */
    private static volatile net.shurui.shuruisutilities.racing.track.TrackDef previewTrack;
    private static volatile long previewExpiryMs;
    /** The server-side editor selection node id carried by the last preview, or -1. */
    private static volatile int previewSelectedNode = -1;
    /** Bumps on every editor preview so the open editor screen can rebuild only when the track actually changed. */
    private static volatile int editorVersion;

    /** How long a preview stays live after the last push before it clears (the server re-pushes about every second). */
    private static final long PREVIEW_TTL_MS = 5000L;

    /** The live editor preview track, or null if none / stale / gated. Read by {@code TrackPreviewRenderer}. */
    public static net.shurui.shuruisutilities.racing.track.TrackDef preview()
    {
        if (gated() || previewTrack == null || System.currentTimeMillis() > previewExpiryMs)
            return null;
        return previewTrack;
    }

    /** The editor's current track for the {@code TrackEditorScreen} (no TTL: the screen keeps the last push). */
    public static net.shurui.shuruisutilities.racing.track.TrackDef editorTrack()
    {
        return gated() ? null : previewTrack;
    }

    /** The server-side selected node id from the last preview, or -1. */
    public static int editorSelectedNode()
    {
        return previewSelectedNode;
    }

    /** A version counter that bumps on each editor preview push. */
    public static int editorVersion()
    {
        return editorVersion;
    }

    private static boolean gated()
    {
        return !ClientGate.feature("racing");
    }

    public static boolean isSessionActive()
    {
        if (!ClientGate.feature("racing"))
            return false;
        // A race is over once its results table has been shown and its window has passed: the HUD then hides.
        if (!results.isEmpty() && System.currentTimeMillis() >= resultsUntilMs)
            return false;
        return sessionActive;
    }

    public static void onHello(PacketRaceFeatureHello p)
    {
        if (gated())
            return;
        sessionActive = p.sessionActive;
    }

    public static void onSession(PacketRaceSession p)
    {
        if (gated())
            return;
        sessionActive = true;
        // A fresh race clears any lingering results / countdown from the previous one, and closes the lobby screen.
        results = java.util.Collections.emptyList();
        resultsUntilMs = 0L;
        resultsShown = false;
        lobbyShown = false;
        lobbySecondsRemaining = -1;
        net.minecraft.client.Minecraft smc = net.minecraft.client.Minecraft.getInstance();
        if (smc.screen instanceof RaceLobbyScreen)
            smc.setScreen(null);
        countdown = 0;
        prevLap = 0;
        lastLapSplit = 0;
        sessionParams = p.params;
        // Resolve the surface registry ids to blocks for the off-road test (BuiltInRegistries.BLOCK ids are the
        // same on the client and the server it connects to, since blocks are not datapack-driven).
        java.util.Set<net.minecraft.world.level.block.Block> set = new java.util.HashSet<>();
        for (int id : p.surfaceBlockIds)
        {
            net.minecraft.world.level.block.Block b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.byId(id);
            if (b != null)
                set.add(b);
        }
        surfaceBlocks = set;
        centreline = new java.util.ArrayList<>(p.centreline);
        // Push the params/surface onto the bike the local player is riding, so the race branch has them at once.
        pushToRiddenBike();
    }

    private static void pushToRiddenBike()
    {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null && mc.player.getVehicle()
                instanceof net.shurui.shuruisutilities.hoverbike.HoverbikeEntity bike)
        {
            bike.setRaceParams(sessionParams);
            bike.setRaceSurface(surfaceBlocks);
        }
    }

    public static void onState(PacketRaceState p)
    {
        if (gated())
            return;
        sessionActive = true;
        place = p.place;
        totalRacers = p.totalRacers;
        lapCurrent = p.lapCurrent;
        lapTotal = p.lapTotal;
        timeTicks = p.timeTicks;
        countdown = p.countdown;
        wrongWay = p.wrongWay;
        finalLap = p.finalLap;
        finished = p.finished;
        zeni = p.zeni;
        selfEffect = p.selfEffect;
        selfEffectTicks = p.selfEffectTicks;
        racerDots = new java.util.ArrayList<>(p.racerDots);
        racerUuids = new java.util.ArrayList<>(p.racerUuids);
        selfId = p.selfId;


        // The Zeni top-speed bonus is a sustained per-bike multiplier the client physics reads (the server allows for
        // it in the anti-cheat separately): fold the synced count into the ridden race bike's KartState.
        net.minecraft.client.Minecraft zmc = net.minecraft.client.Minecraft.getInstance();
        if (zmc.player != null && zmc.player.getVehicle()
                instanceof net.shurui.shuruisutilities.hoverbike.HoverbikeEntity zbike && zbike.isRaceBike())
            zbike.raceKartState().topSpeedMult = 1.0 + 0.01 * Math.min(10, Math.max(0, p.zeni));

        // Derive the last lap split from the lap counter advancing.
        if (p.lapCurrent > prevLap && prevLap > 0)
            lastLapSplit = p.timeTicks - lapStartTime;
        if (p.lapCurrent != prevLap)
        {
            lapStartTime = p.timeTicks;
            prevLap = p.lapCurrent;
        }

        // Drive the ridden bike's rocket-start countdown from the authoritative value: set preStart ONCE when a
        // countdown begins, so the client physics freezes on the grid and can arm a rocket start; clear at GO.
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.shurui.shuruisutilities.hoverbike.HoverbikeEntity bike =
                mc.player != null && mc.player.getVehicle()
                        instanceof net.shurui.shuruisutilities.hoverbike.HoverbikeEntity b && b.isRaceBike() ? b : null;
        if (p.countdown > 0)
        {
            if (!countdownApplied && bike != null)
            {
                bike.raceKartState().preStart = p.countdown;
                countdownApplied = true;
            }
        }
        else
        {
            countdownApplied = false;
        }
    }

    public static void onItemSlot(PacketRaceItemSlot p)
    {
        if (gated())
            return;
        net.shurui.shuruisutilities.racing.physics.PowerupKind result = p.result();
        heldItem = result;
        heldCharges = Math.max(1, p.charges);
        if (result != net.shurui.shuruisutilities.racing.physics.PowerupKind.NONE && p.rouletteTicks > 0)
        {
            // Start the roulette spin: the HUD cycles icons, slowing, then reveals the decided result.
            rouletteStartMs = System.currentTimeMillis();
            rouletteDurationMs = p.rouletteTicks * 50L;
        }
        else
        {
            // Slot cleared (item used) or a no-spin grant: nothing to animate.
            rouletteStartMs = 0L;
            rouletteDurationMs = 0L;
            if (result == net.shurui.shuruisutilities.racing.physics.PowerupKind.NONE)
                heldCharges = 0;
        }
    }

    /** The item held (or being rolled) in the racer's slot; NONE when empty. */
    public static net.shurui.shuruisutilities.racing.physics.PowerupKind heldItem()
    {
        return gated() ? net.shurui.shuruisutilities.racing.physics.PowerupKind.NONE : heldItem;
    }

    /** Charges left on the held item (Kaioken x3 = 3), 0 when the slot is empty. */
    public static int heldCharges()
    {
        return gated() ? 0 : heldCharges;
    }

    /** True while the item-slot roulette is still spinning. */
    public static boolean rouletteActive()
    {
        return !gated() && rouletteDurationMs > 0
                && System.currentTimeMillis() < rouletteStartMs + rouletteDurationMs;
    }

    /** Roulette progress in {@code [0,1]}: 0 at the start of the spin, 1 at the reveal. */
    public static float rouletteProgress()
    {
        if (rouletteDurationMs <= 0)
            return 1.0F;
        float p = (System.currentTimeMillis() - rouletteStartMs) / (float) rouletteDurationMs;
        return Math.max(0.0F, Math.min(1.0F, p));
    }

    public static void onEffect(PacketRaceEffect p)
    {
        if (gated())
            return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getVehicle()
                instanceof net.shurui.shuruisutilities.hoverbike.HoverbikeEntity bike) || !bike.isRaceBike())
            return;
        net.shurui.shuruisutilities.racing.physics.KartState st = bike.raceKartState();
        int d = Math.max(0, p.durationTicks);
        switch (p.effect)
        {
            case PacketRaceEffect.SPIN_OUT -> st.spinOutTicks = d;
            case PacketRaceEffect.SQUASH -> st.squashTicks = d;
            case PacketRaceEffect.LAUNCH -> st.launchTicks = Math.max(1, d);
            case PacketRaceEffect.FROZEN -> st.frozenTicks = d;
            case PacketRaceEffect.AUTOPILOT -> st.autopilotTicks = d;
            // R7 self powerups: a boost carries its multiplier; the auras are windows on the KartState timers.
            case PacketRaceEffect.BOOST ->
            {
                st.boostTicks = Math.max(st.boostTicks, d);
                st.boostMult = Math.max(st.boostMult, Math.max(1.0, p.magnitude));
            }
            case PacketRaceEffect.DESTROYER -> st.destroyerTicks = d;
            case PacketRaceEffect.KAIOKEN_FLASH -> st.kaiokenFlashTicks = d;
            case PacketRaceEffect.KAIOKEN_X20 -> st.kaiokenX20Ticks = d;
            case PacketRaceEffect.AFTERIMAGE -> st.afterimageTicks = d;
            // R9 screen effects: purely client overlays (no KartState change), a fading white blind (Solar Flare) and
            // a red flash + shake (Gravity Crush). The squash / slow itself rides the SQUASH effect above.
            case PacketRaceEffect.BLIND ->
            {
                blindStartMs = System.currentTimeMillis();
                blindDurationMs = Math.max(1, d) * 50L;
            }
            case PacketRaceEffect.GRAVITY ->
            {
                gravityStartMs = System.currentTimeMillis();
                gravityDurationMs = Math.max(1, d) * 50L;
            }
            case PacketRaceEffect.KIAI ->
            {
                kiaiStartMs = System.currentTimeMillis();
                kiaiDurationMs = Math.max(1, d) * 50L;
            }
            case PacketRaceEffect.CLEAR -> st.clearEffects();
            default -> { }
        }
    }

    public static void onResults(PacketRaceResults p)
    {
        if (gated())
            return;
        results = new java.util.ArrayList<>(p.entries);
        resultsUntilMs = System.currentTimeMillis() + 8000L;
        // Keep the session active while the results table shows, then it clears with the next reset / disconnect.
        wrongWay = false;
        countdown = 0;
        // Open the full results screen once (best lap + records beaten); the compact HUD table is the fallback.
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (!resultsShown && !results.isEmpty() && !(mc.screen instanceof RaceResultsScreen))
        {
            mc.setScreen(new RaceResultsScreen());
            resultsShown = true;
        }
    }

    public static void onLobbyOpen(PacketRaceLobbyOpen p)
    {
        if (gated())
            return;
        lobbyTrackId = p.trackId;
        lobbyLaps = p.laps;
        lobbyMaxRacers = p.maxRacers;
        lobbySecondsRemaining = p.secondsRemaining;
        lobbyStampMs = System.currentTimeMillis();
        lobbyMembers = new java.util.ArrayList<>(p.members);
        // Open the lobby screen once; the open screen re-reads this state every frame, so later updates just refresh it.
        // Do not reopen if the player closed it (lobbyShown stays set) or has another screen up.
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof RaceLobbyScreen)
            return;
        if (!lobbyShown && mc.screen == null)
        {
            mc.setScreen(new RaceLobbyScreen());
            lobbyShown = true;
        }
    }

    public static void onEditorSync(PacketTrackEditorSync p)
    {
        if (gated())
            return;
        // The payload is the track's canonical JSON; the client rebuilds the TrackDef and derives geometry the same
        // way the server does (TrackGeometry is common), so the preview is always consistent with the stored track.
        previewSelectedNode = p.selectedNode;
        if (p.payload == null || p.payload.length == 0)
        {
            previewTrack = null;
            editorVersion++;
            return;
        }
        try
        {
            previewTrack = net.shurui.shuruisutilities.racing.track.TrackCodec.fromJson(
                    new String(p.payload, java.nio.charset.StandardCharsets.UTF_8));
            previewExpiryMs = System.currentTimeMillis() + PREVIEW_TTL_MS;
            editorVersion++;
        }
        catch (Exception e)
        {
            previewTrack = null;
        }
    }

    public static void onTuningOpen(PacketRaceTuningOpen p)
    {
        if (gated())
            return;
        RaceScreens.openTuning(p.tuning, p.trackId);
    }

    public static void onRescue(PacketRaceRescue p)
    {
        if (gated())
            return;
        // The server already moved the bike; freeze the local physics for the pause and draw the fade.
        rescueFadeUntilMs = System.currentTimeMillis() + p.pauseTicks * 50L;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null && mc.player.getVehicle()
                instanceof net.shurui.shuruisutilities.hoverbike.HoverbikeEntity bike && bike.isRaceBike())
        {
            bike.raceKartState().frozenTicks = Math.max(1, p.pauseTicks);
            bike.raceKartState().speed = 0;
        }
    }

    /** Forget everything on disconnect so no session or preview carries into the next server or the menu. */
    public static void reset()
    {
        sessionActive = false;
        previewTrack = null;
        previewExpiryMs = 0L;
        previewSelectedNode = -1;
        sessionParams = null;
        surfaceBlocks = java.util.Collections.emptySet();
        centreline = java.util.Collections.emptyList();
        place = 0;
        totalRacers = 0;
        lapCurrent = 0;
        lapTotal = 0;
        timeTicks = 0;
        countdown = 0;
        wrongWay = false;
        finalLap = false;
        finished = false;
        zeni = 0;
        selfEffect = -1;
        selfEffectTicks = 0;
        countdownApplied = false;
        lastLapSplit = 0;
        lapStartTime = 0;
        prevLap = 0;
        racerDots = java.util.Collections.emptyList();
        results = java.util.Collections.emptyList();
        resultsUntilMs = 0L;
        resultsShown = false;
        lobbyShown = false;
        lobbyTrackId = "";
        lobbyLaps = 0;
        lobbyMaxRacers = 0;
        lobbySecondsRemaining = -1;
        lobbyMembers = java.util.Collections.emptyList();
        rescueFadeUntilMs = 0L;
        blindStartMs = 0L;
        blindDurationMs = 0L;
        gravityStartMs = 0L;
        gravityDurationMs = 0L;
        kiaiStartMs = 0L;
        kiaiDurationMs = 0L;
        heldItem = net.shurui.shuruisutilities.racing.physics.PowerupKind.NONE;
        heldCharges = 0;
        rouletteStartMs = 0L;
        rouletteDurationMs = 0L;
    }
}
