package net.shurui.dev.ragnarok.visualtest;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.lwjgl.glfw.GLFW;

import com.dragonminez.client.gui.UtilityMenuScreen;
import com.dragonminez.client.gui.radial.AbstractRadialNode;
import com.dragonminez.client.gui.radial.RadialNode;
import com.dragonminez.client.gui.radial.nodes.ActionsNode;
import com.dragonminez.common.stats.StatsData;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.dev.sdu.entity.DukeSnipperjackEntity;
import net.shurui.shuruisutilities.client.radial.SenzuRadialNode;
import net.shurui.shuruisutilities.ragnarok.RgNpcFighterEntity;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticCatalog;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticDef;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticSlot;
import net.shurui.shuruisutilities.cosmetics.wardrobe.WardrobeManager;
import net.shurui.shuruisutilities.cosmetics.wardrobe.mount.CosmeticMountManager;

import org.slf4j.Logger;

/**
 * The dev-only visual test runner. Registered on the Forge event bus only by {@link VisualTestBootstrap} when both
 * activation locks are open (dev environment + {@code -Ddmzr.visualtest} set), so in a shipped jar nothing here ever
 * ticks.
 *
 * <h2>What it does, in order</h2>
 * <ol>
 *   <li>Waits for a client level. If none appears (and none is loading), it creates or loads a dedicated flat,
 *       creative, cheats-on world named {@value #WORLD} under {@code run/saves/}, so an unattended
 *       {@code runClient --quickPlaySingleplayer VisualTest} lands straight in it and the very first run creates it.
 *       It never touches any other save.</li>
 *   <li>Forces noon, clear weather, frozen day/weather cycles and no mob spawning, and completes DragonMineZ
 *       character creation on the host player (so DMZ's player renderer, through which cosmetics draw, is active),
 *       then grants the whole cosmetic catalogue so every piece can be equipped.</li>
 *   <li>Runs the requested scenarios, each: mutate the scene on the integrated-server thread, wait for the change to
 *       apply and a few render frames to settle, point the camera, and grab the framebuffer with Minecraft's own
 *       {@link Screenshot#takeScreenshot} into {@code run/screenshots/visualtest/<run-id>/}.</li>
 *   <li>Writes {@code index.txt} describing every shot and quits the client cleanly so the Gradle task exits.</li>
 * </ol>
 *
 * <p>A hard tick budget ({@link #MAX_TICKS}) guarantees it can never hang the client forever: if it is exceeded the
 * run finishes and quits regardless of progress. Every scene mutation is wrapped so a single failure logs and the
 * run carries on rather than stranding an open window on the owner's desktop.
 */
public class VisualTestRunner
{
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The dedicated test world. Never one of the owner's worlds. */
    public static final String WORLD = "VisualTest";

    /** Absolute ceiling on the whole run, in client ticks (20/s). Ten minutes. */
    private static final int MAX_TICKS = 12_000;

    /** Duke's registered id. */
    private static final String DUKE_ID = "dmz_ragnarok:duke_snipperjack";

    private enum Phase { WAIT_FOR_LEVEL, SETUP, BUILD, RUN, DONE }

    private final Set<String> scenarios;
    private final String runId;
    private final File outDir;

    private Phase phase = Phase.WAIT_FOR_LEVEL;
    private int totalTicks = 0;
    private int waitTicks = 0;
    private int levelWait = 0;
    private boolean worldRequested = false;

    /** While true, the run loop does NOT dismiss an open screen (the senzu scenario needs the utility menu open). */
    private volatile boolean holdScreen = false;
    /** Like {@link #holdScreen} but for a plain screen the harness put up itself (a tooltip card, the event editor). */
    private volatile boolean keepScreen = false;
    /** The live senzu radial node found inside DMZ's Actions ring, driven directly for the click checks. */
    private SenzuRadialNode senzuNode;
    /** The bean id the client had selected when the pull was requested, for the server-side move check. */
    private volatile String senzuPulledId = "";
    /** Server-side counts captured just before the pull, so the after-counts can prove exactly one bean moved. */
    private volatile int senzuBagBefore = -1;
    private volatile int senzuInvBefore = -1;
    /** Base coordinates captured when a spawned-scene scenario places its content, so the camera can aim at it. */
    private double sceneX;
    private double sceneY;
    private double sceneZ;

    /** Frame-time sampling (starmap): while armed, each render frame's wall-clock delta is recorded for a min/avg/max. */
    private volatile boolean frameSampling = false;
    private long frameSampleLastNanos = 0L;
    private final List<Long> frameDeltas = new CopyOnWriteArrayList<>();
    /** The live space pod entity id spawned for the pod-wake shot, so the camera can track its moving position. */
    private volatile int podEntityId = -1;

    /** The scripted actions, each run on one eligible client tick with a post-delay before the next. */
    private final Deque<Step> script = new ArrayDeque<>();
    /** index.txt lines, accumulated as shots are taken. */
    private final List<String> index = new CopyOnWriteArrayList<>();

    /** Catalogue snapshot taken on the server thread (client copy may lag on first join). */
    private final List<Cosmetic> catalogue = new CopyOnWriteArrayList<>();
    private final AtomicBoolean setupServerDone = new AtomicBoolean(false);
    private final AtomicBoolean catalogueReady = new AtomicBoolean(false);

    public VisualTestRunner(String scenarioList)
    {
        this.scenarios = parseScenarios(scenarioList);
        this.runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        this.outDir = new File(Minecraft.getInstance().gameDirectory,
                "screenshots/visualtest/" + runId);
        this.outDir.mkdirs();
        index.add("# DMZ Ragnarok visual test run " + runId);
        index.add("# scenarios: " + scenarios);
        index.add("# columns: scenario | file | what it should show");
        LOGGER.info("[VisualTest] Output directory: {}", outDir.getAbsolutePath());
    }

    private static Set<String> parseScenarios(String list)
    {
        Set<String> all = Set.of("back", "hand", "head", "anim", "duke", "mount", "pet",
                "senzu", "items", "sagamodels", "boostpad", "halloween", "pilaf", "planetsky",
                "garrison", "avatar", "starmap", "spaceview", "wrap", "chorus");
        if (list == null || list.isBlank() || list.equalsIgnoreCase("all"))
            return all;
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String s : list.split(","))
        {
            String k = s.trim().toLowerCase(Locale.ROOT);
            if (all.contains(k))
                out.add(k);
        }
        return out.isEmpty() ? all : out;
    }

    // ---------------------------------------------------------------- tick pump

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || phase == Phase.DONE)
            return;
        totalTicks++;
        if (totalTicks > MAX_TICKS)
        {
            LOGGER.warn("[VisualTest] Tick budget exhausted at {} ticks; finishing early.", totalTicks);
            finish();
            return;
        }
        try
        {
            // The senzu scenario needs DMZ's hold-to-open utility menu to stay open even though the harness never
            // holds the key: keep it alive here every tick while holdScreen is set.
            if (holdScreen)
                keepMenuAlive();
            pump();
        }
        catch (Throwable t)
        {
            LOGGER.error("[VisualTest] Unhandled error in phase {}; finishing to avoid a stuck client.", phase, t);
            finish();
        }
    }

    /**
     * DMZ's utility menu is hold-to-open: {@code ForgeClientEvents.handleUtilityMenuHold} calls
     * {@code startClosingAnimation()} on it every tick the UTILITY_MENU key is not physically held, and the harness
     * cannot hold a real key. So while {@code holdScreen} is set, reopen the menu if it has closed and clear DMZ's
     * closing flag so it never reaches the 140 ms force-close, which keeps it fully open for the shots.
     */
    private void keepMenuAlive()
    {
        Minecraft mc = Minecraft.getInstance();
        Screen s = mc.screen;
        if (s == null)
        {
            try { mc.setScreen(new UtilityMenuScreen()); }
            catch (Throwable ignored) { }
        }
        else if (s instanceof UtilityMenuScreen)
        {
            setField(s, "closing", Boolean.FALSE);
        }
    }

    /** Record each render frame's wall-clock delta while frame sampling is armed (the starmap frame-time measurement). */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || !frameSampling)
            return;
        long now = System.nanoTime();
        if (frameSampleLastNanos != 0L)
            frameDeltas.add(now - frameSampleLastNanos);
        frameSampleLastNanos = now;
    }

    /** Clear DMZ's utility-menu closing flag immediately before it renders, so every screenshot sees it fully open. */
    @SubscribeEvent
    public void onScreenRenderPre(ScreenEvent.Render.Pre event)
    {
        if (holdScreen && event.getScreen() instanceof UtilityMenuScreen)
            setField(event.getScreen(), "closing", Boolean.FALSE);
    }

    private void pump()
    {
        Minecraft mc = Minecraft.getInstance();
        switch (phase)
        {
        case WAIT_FOR_LEVEL -> waitForLevel(mc);
        case SETUP -> setup(mc);
        case BUILD -> build();
        case RUN -> runScript();
        default -> { }
        }
    }

    // ---------------------------------------------------------------- world + level

    private void waitForLevel(Minecraft mc)
    {
        if (mc.level != null && mc.player != null && mc.getSingleplayerServer() != null)
        {
            // Give chunks and the resource-streamed assets a moment before the first mutation.
            waitTicks = 60;
            phase = Phase.SETUP;
            return;
        }
        levelWait++;
        // If quickPlay did not (or could not) put us in a world within ~10 s and nothing is loading, create/load it
        // ourselves. This makes the very first run self-sufficient (it creates VisualTest) and later runs resilient
        // if quickPlay is not passed.
        if (!worldRequested && levelWait > 200 && mc.getSingleplayerServer() == null && mc.getConnection() == null)
        {
            worldRequested = true;
            createOrLoadWorld(mc);
        }
    }

    private void createOrLoadWorld(Minecraft mc)
    {
        try
        {
            if (mc.getLevelSource().levelExists(WORLD))
            {
                LOGGER.info("[VisualTest] Loading existing world '{}'.", WORLD);
                mc.createWorldOpenFlows().loadLevel(mc.screen != null ? mc.screen : new TitleScreen(), WORLD);
                return;
            }
            LOGGER.info("[VisualTest] Creating fresh flat/creative world '{}'.", WORLD);
            GameRules rules = new GameRules();
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
            rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            rules.getRule(GameRules.RULE_DOFIRETICK).set(false, null);
            LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL,
                    true, rules, WorldDataConfiguration.DEFAULT);
            WorldOptions options = new WorldOptions(0L, false, false);
            Function<RegistryAccess, WorldDimensions> dims = ra ->
            {
                try
                {
                    return ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value()
                            .createWorldDimensions();
                }
                catch (Throwable t)
                {
                    LOGGER.warn("[VisualTest] FLAT preset unavailable; falling back to a normal world.", t);
                    return WorldPresets.createNormalWorldDimensions(ra);
                }
            };
            mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, options, dims);
        }
        catch (Throwable t)
        {
            LOGGER.error("[VisualTest] Failed to create/load the test world; finishing.", t);
            finish();
        }
    }

    // ---------------------------------------------------------------- one-time scene setup

    private void setup(Minecraft mc)
    {
        if (waitTicks > 0) { waitTicks--; return; }

        // Kick the server-side setup exactly once: fix environment, create the DMZ character, grant the catalogue,
        // and snapshot the catalogue for building the script.
        if (!setupServerDone.getAndSet(true))
        {
            mc.options.hideGui = true;
            // The harness window is usually not focused, and singleplayer auto-opens the pause menu on lost focus,
            // which would sit over every shot. Turn that off for the run.
            mc.options.pauseOnLostFocus = false;
            // Gamerules go through the vanilla command (it works and is simplest). Time, weather and positioning do
            // NOT use commands: SU overrides /tp and DMZ/SU gate /time and /weather, so those are set directly on the
            // server instead.
            sendCommand(mc, "gamerule doDaylightCycle false");
            sendCommand(mc, "gamerule doWeatherCycle false");
            sendCommand(mc, "gamerule doMobSpawning false");

            MinecraftServer server = mc.getSingleplayerServer();
            LocalPlayer lp = mc.player;
            if (server != null && lp != null)
            {
                final java.util.UUID uuid = lp.getUUID();
                server.execute(() ->
                {
                    ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                    if (sp == null) return;
                    // The VisualTest world is created PEACEFUL, and every rgnpc fighter is a DragonMineZ saga entity
                    // (a Monster), which Minecraft discards on its next tick in PEACEFUL through checkDespawn. That is
                    // why a spawned model row vanished before its shot. Move off PEACEFUL so spawned fighters persist.
                    // doMobSpawning stays off, so nothing else spawns.
                    server.setDifficulty(Difficulty.NORMAL, true);
                    // Harness hygiene: clear every non-player entity left in the test area by earlier runs (Duke,
                    // pumpkin puppets, pets, mounts, rgnpc fighters, saga NPCs, item drops) BEFORE any scenario, so a
                    // run shows only what it tests. Scenarios that need an entity (duke) spawn it themselves at run time.
                    cleanTestArea(sp);
                    // Fixed noon, clear weather, and keep the player aloft so a teleport does not drop him.
                    sp.serverLevel().setDayTime(6000L);
                    sp.serverLevel().setWeatherParameters(1_000_000, 0, false, false);
                    sp.getAbilities().mayfly = true;
                    sp.getAbilities().flying = true;
                    sp.onUpdateAbilities();
                    DmzCharacterSetup.ensureCharacter(sp);
                    grantCatalogue(sp);
                    snapshotCatalogue();
                });
            }
            waitTicks = 60; // let creation + grants sync to the client
            return;
        }

        if (catalogueReady.get())
        {
            phase = Phase.BUILD;
        }
        else if (levelWait++ > 400)
        {
            // Never block forever on the snapshot; carry on with whatever the client copy holds.
            snapshotCatalogueClient();
            phase = Phase.BUILD;
        }
    }

    private void grantCatalogue(ServerPlayer sp)
    {
        try
        {
            for (CosmeticDef d : CosmeticCatalog.all())
                if (d != null && d.enabled && d.id != null && !d.id.isBlank())
                    WardrobeManager.grant(sp.getServer(), sp.getUUID(), d.id, "visualtest");
        }
        catch (Throwable t)
        {
            LOGGER.warn("[VisualTest] Granting the catalogue failed (continuing).", t);
        }
    }

    private void snapshotCatalogue()
    {
        try
        {
            catalogue.clear();
            for (CosmeticDef d : CosmeticCatalog.all())
                if (d != null && d.enabled && d.id != null && !d.id.isBlank())
                    catalogue.add(new Cosmetic(d.id, d.slot, d.displayName));
        }
        finally
        {
            catalogueReady.set(true);
        }
    }

    /** Fallback: read whatever the client-side catalogue holds if the server snapshot never arrived. */
    private void snapshotCatalogueClient()
    {
        if (!catalogue.isEmpty()) return;
        try
        {
            for (CosmeticDef d : CosmeticCatalog.all())
                if (d != null && d.enabled && d.id != null && !d.id.isBlank())
                    catalogue.add(new Cosmetic(d.id, d.slot, d.displayName));
        }
        catch (Throwable ignored) { }
    }

    /**
     * Remove every non-player entity within a wide box around the host player. Called once on the server thread
     * during setup, before any scenario builds or runs, so a fresh run is never polluted by a previous run's Duke,
     * puppets, pets, mounts, rgnpc fighters or dropped items sitting in the persistent VisualTest world.
     */
    private void cleanTestArea(ServerPlayer sp)
    {
        try
        {
            net.minecraft.world.phys.AABB area = sp.getBoundingBox().inflate(256.0D);
            int removed = 0;
            for (Entity e : sp.serverLevel().getEntities(sp, area,
                    ent -> !(ent instanceof net.minecraft.world.entity.player.Player)))
            {
                e.discard();
                removed++;
            }
            LOGGER.info("[VisualTest] Cleared {} leftover entity(ies) from the test area before scenarios.", removed);
        }
        catch (Throwable t)
        {
            LOGGER.warn("[VisualTest] Test-area cleanup failed (continuing).", t);
        }
    }

    // ---------------------------------------------------------------- script assembly

    private void build()
    {
        if (scenarios.contains("head")) buildWearable("head", CosmeticSlot.HEAD);
        if (scenarios.contains("back")) buildWearable("back", CosmeticSlot.BACK);
        if (scenarios.contains("hand")) buildHand();
        if (scenarios.contains("pet")) buildWearable("pet", CosmeticSlot.PET);
        if (scenarios.contains("mount")) buildMount();
        if (scenarios.contains("anim")) buildAnim();
        if (scenarios.contains("duke")) buildDuke();
        if (scenarios.contains("items")) buildItems();
        if (scenarios.contains("sagamodels")) buildSagaModels();
        if (scenarios.contains("boostpad")) buildBoostPad();
        if (scenarios.contains("senzu")) buildSenzu();
        if (scenarios.contains("halloween")) buildHalloween();
        if (scenarios.contains("pilaf")) buildPilaf();
        if (scenarios.contains("planetsky")) buildPlanetSky();
        if (scenarios.contains("garrison")) buildGarrison();
        if (scenarios.contains("avatar")) buildAvatar();
        if (scenarios.contains("starmap")) buildStarmap();
        if (scenarios.contains("spaceview")) buildSpaceView();
        if (scenarios.contains("wrap")) buildWrap();
        if (scenarios.contains("chorus")) buildChorus();
        phase = Phase.RUN;
    }

    /** A worn slot: equip each catalogue piece for the slot, then shoot it from a few angles. */
    private void buildWearable(String scenario, CosmeticSlot slot)
    {
        List<Cosmetic> items = itemsFor(slot);
        if (items.isEmpty())
        {
            LOGGER.info("[VisualTest] No catalogue entries for slot {} ({} scenario).", slot.key, scenario);
            return;
        }
        for (Cosmetic c : items)
        {
            step(10, () -> serverEquip(slot, c.id));
            for (Angle a : Angle.WEARABLE)
                shootPlayer(scenario, c, a);
            step(4, () -> serverUnequip(slot));
        }
    }

    /** HAND: the ACCESSORY slot, each piece with and without a DMZ ki weapon drawn, plus a mid-swing frame. */
    private void buildHand()
    {
        List<Cosmetic> items = itemsFor(CosmeticSlot.ACCESSORY);
        if (items.isEmpty())
        {
            LOGGER.info("[VisualTest] No ACCESSORY catalogue entries (hand scenario).");
            return;
        }
        for (Cosmetic c : items)
        {
            step(10, () -> { serverKiWeapon(false); serverEquip(CosmeticSlot.ACCESSORY, c.id); });
            shootPlayer("hand", c, Angle.FRONT, "no ki weapon");
            shootPlayer("hand", c, Angle.THREE_QUARTER, "no ki weapon");
            step(10, () -> serverKiWeapon(true));
            shootPlayer("hand", c, Angle.FRONT, "ki weapon drawn");
            shootPlayer("hand", c, Angle.THREE_QUARTER, "ki weapon drawn");
            // Mid swing: swing then grab a couple of ticks in.
            step(2, () -> { if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.swing(InteractionHand.MAIN_HAND); });
            shootPlayer("hand", c, Angle.THREE_QUARTER, "mid-swing, ki weapon drawn");
            step(4, () -> { serverKiWeapon(false); serverUnequip(CosmeticSlot.ACCESSORY); });
        }
    }

    /** MOUNT: equip and summon each mount rig, then shoot it. */
    private void buildMount()
    {
        List<Cosmetic> items = itemsFor(CosmeticSlot.MOUNT);
        if (items.isEmpty())
        {
            LOGGER.info("[VisualTest] No MOUNT catalogue entries (mount scenario).");
            return;
        }
        for (Cosmetic c : items)
        {
            step(10, () -> { serverEquip(CosmeticSlot.MOUNT, c.id); serverMountSummon(true); });
            for (Angle a : Angle.WEARABLE)
                shootPlayer("mount", c, a);
            step(6, () -> { serverMountSummon(false); serverUnequip(CosmeticSlot.MOUNT); });
        }
    }

    /** ANIM: preview each triggered (JOIN_LEAVE / TELEPORT) animation and shoot a burst through its duration. */
    private void buildAnim()
    {
        List<Cosmetic> joinLeave = itemsFor(CosmeticSlot.JOIN_LEAVE);
        List<Cosmetic> teleport = itemsFor(CosmeticSlot.TELEPORT);
        List<Cosmetic> all = new ArrayList<>();
        all.addAll(joinLeave);
        all.addAll(teleport);
        if (all.isEmpty())
        {
            LOGGER.info("[VisualTest] No triggered-animation catalogue entries (anim scenario).");
            return;
        }
        for (Cosmetic c : all)
        {
            // Equip so a bare preview (no id) resolves, then preview the ARRIVING variant.
            step(6, () -> serverEquip(c.slot, c.id));
            step(2, () -> serverPreview(c.id, c.slot));
            // Burst of frames across the animation.
            for (int i = 0; i < 5; i++)
                shootPlayer("anim", c, Angle.THREE_QUARTER, "frame " + (i + 1) + " of preview");
            step(4, () -> serverUnequip(c.slot));
        }
    }

    /** DUKE: seated orbit (arms/rotation from every side), then wake, phase-1 combat, puppets and combat-trail bursts. */
    private void buildDuke()
    {
        // Keep the player alive and able to summon adds for the puppet check.
        step(2, () -> sendCommand(Minecraft.getInstance(), "gamemode creative"));
        // Spawn seated ~6 blocks from the player (the summon command works; SU does not override it).
        step(30, () -> sendCommand(Minecraft.getInstance(), "summon " + DUKE_ID + " ~ ~ ~6"));
        // Seated orbit: move the CAMERA (player) around the stationary boss at four cardinals + a three-quarter.
        for (Angle a : Angle.ORBIT)
            shootDuke("seated, " + a.label, a);
        // Wake: two provokes (first taunts, second rises). Applied as server-side hurt so no reach/packet timing.
        step(15, this::dukeProvoke);
        step(20, this::dukeProvoke);
        // Rising, every angle.
        for (int i = 0; i < 4; i++)
            shootDuke("rising, " + Angle.duke(i).label, Angle.duke(i));
        // Phase 1 combat: drop him in and let the skill machine run (SummonPuppet, SummonClone -> afterImage + SMOKE,
        // CloneSwap -> vfxTeleport + SMOKE all live here). Orbit each angle.
        step(10, () -> dukeSetHealthFraction(0.80F));
        step(40, this::dukeProvoke);
        for (int i = 0; i < 5; i++)
            shootDuke("phase1 combat, " + Angle.duke(i).label, Angle.duke(i));
        // Puppet check: place three pumpkin puppets around Duke and orbit them (isolates the black entourage case).
        step(4, () -> sendCommand(Minecraft.getInstance(), "summon dmz_ragnarok:pumpkin_puppet ~ ~ ~6"));
        step(2, () -> sendCommand(Minecraft.getInstance(), "summon dmz_ragnarok:pumpkin_puppet ~1 ~ ~5"));
        step(2, () -> sendCommand(Minecraft.getInstance(), "summon dmz_ragnarok:pumpkin_puppet ~-1 ~ ~7"));
        step(10, () -> {});
        for (Angle a : Angle.ORBIT)
            shootDuke("with puppets, " + a.label, a);
        // Combat-trail burst: from FRONT and THREE_QUARTER, grab many consecutive frames while combat VFX fire, so any
        // dark afterimage or teleport SMOKE trail shows in some frame.
        for (Angle a : new Angle[] { Angle.FRONT, Angle.THREE_QUARTER })
        {
            step(4, () -> aimAtDuke(a));
            step(1, () -> aimAtDuke(a));
            for (int i = 0; i < 12; i++)
            {
                final int f = i;
                step(2, this::dukeProvoke);
                step(1, () -> grab("duke", "duke_trail_" + a.label + "_f" + f,
                        "Duke Snipperjack: combat trail burst, " + a.label + ", frame " + f));
            }
        }
        // Best-effort deeper phase (the interlude gates on clearing adds, so this may stay in phase1); captured anyway.
        step(10, () -> dukeSetHealthFraction(0.30F));
        step(30, this::dukeProvoke);
        for (int i = 0; i < 3; i++)
            shootDuke("low health, " + Angle.duke(i).label, Angle.duke(i));
    }

    // ---------------------------------------------------------------- senzu radial node scenario

    /**
     * SENZU: give the player a senzu bean bag holding three distinct bean types, open DMZ's utility menu, drive its
     * real hover into the Actions sub-ring so the senzu node shows, then exercise the node's own right-click (cycle
     * type) and left-click (pull a bean, verified server side) paths, and finally the empty-bag (greyed) and no-bag
     * (absent) states. Every check writes a PASS/FAIL line into index.txt so the result does not rest on the shots
     * alone.
     */
    private void buildSenzu()
    {
        // 1. Equip a full bag on the server (2 senzu, 1 hp, 1 ki) and let Curios sync it to the client.
        step(50, () ->
        {
            holdScreen = true;
            onServer(sp -> index.add("senzu | (setup) | equip full bag (2 senzu, 1 hp, 1 ki): "
                    + (SenzuSetup.giveAndEquipFullBag(sp) ? "OK" : "FAILED")));
        });
        // 2. Open DMZ's utility menu; give it a few ticks to pull statsData from the player capability.
        step(20, this::openUtilityMenu);
        // 3. Prove the mixin bound: the senzu node must be a child of DMZ's ActionsNode.
        step(4, this::findSenzuNodeInActions);
        // 4. Drive the real hover so the Actions ring expands; settle a few frames, then shoot.
        step(6, this::hoverActionsSector);
        step(6, this::hoverActionsSector);
        step(10, this::hoverActionsSector);
        step(2, () ->
        {
            logActionsHover();
            grab("senzu", "senzu_actions_ring", "Actions sub-ring open, senzu bean node visible");
        });
        // 5. Right-click path: cycle the selected bean TYPE. The face icon/label/count must change.
        // First make sure all three bean types have actually reached the client (Curios sync can lag under load),
        // so the cycle and the pull below run against a stable, fully-synced bag.
        step(3, () -> beanSyncGate(3, 40));
        step(2, () -> senzuCaptureSelection("before right-click"));
        step(6, () -> { if (senzuNode != null) senzuNode.cycleSelection(); });
        step(6, this::hoverActionsSector);
        step(2, () ->
        {
            senzuCaptureSelection("after right-click");
            grab("senzu", "senzu_after_cycle", "After right-click: selected bean type cycled");
        });
        // 6. Left-click path: pull one bean of the selected type; verify on the server that it moved bag -> inventory.
        step(4, this::senzuBeforePull);
        step(10, () -> { if (senzuNode != null) senzuNode.requestPull(); });
        step(16, this::senzuAfterPull);
        step(2, () -> grab("senzu", "senzu_after_pull", "After left-click: one bean pulled into the inventory"));
        // 7. Empty bag: the node stays VISIBLE but greys (non-interactive).
        step(45, () -> onServer(SenzuSetup::equipEmptyBag));
        step(4, this::hoverActionsSector);
        step(6, () ->
        {
            senzuCheckStates("empty bag", true, false);
            grab("senzu", "senzu_empty_bag", "Empty bag: senzu node visible but greyed");
        });
        // 8. No bag: the node is ABSENT (an absent feature, never a dead entry).
        step(45, () -> onServer(SenzuSetup::removeBag));
        step(4, this::hoverActionsSector);
        step(6, () ->
        {
            senzuCheckStates("no bag", false, false);
            grab("senzu", "senzu_no_bag", "No bag: senzu node absent from the ring");
        });
        // 9. Release the menu so the run can finish (and later runs are unaffected).
        step(2, () -> { Minecraft.getInstance().setScreen(null); holdScreen = false; });
    }

    private void openUtilityMenu()
    {
        boolean ok = ensureMenu();
        index.add("senzu | (open) | utility menu open = " + ok);
    }

    /** Open DMZ's utility menu if it is not already the current screen. Its init runs synchronously here. */
    private boolean ensureMenu()
    {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof UtilityMenuScreen))
        {
            try
            {
                mc.setScreen(new UtilityMenuScreen());
            }
            catch (Throwable t)
            {
                LOGGER.warn("[VisualTest] senzu: could not open UtilityMenuScreen.", t);
            }
        }
        return mc.screen instanceof UtilityMenuScreen;
    }

    /** Find the live senzu node among DMZ's ActionsNode children (proves MixinDmzActionsNode bound). */
    private void findSenzuNodeInActions()
    {
        senzuNode = null;
        try
        {
            Minecraft mc = Minecraft.getInstance();
            ensureMenu();
            if (!(mc.screen instanceof UtilityMenuScreen scr))
            {
                index.add("senzu | (mixin) | FAIL: utility menu not open (screen=" + mc.screen + ")");
            }
            else
            {
                Object statsObj = getField(scr, "statsData");
                Object baseObj = getField(scr, "baseNodes");
                StatsData stats = statsObj instanceof StatsData s ? s : null;
                ActionsNode actions = null;
                if (baseObj instanceof List<?> baseNodes)
                    for (Object n : baseNodes)
                        if (n instanceof ActionsNode a) { actions = a; break; }
                if (actions == null)
                {
                    index.add("senzu | (mixin) | FAIL: no ActionsNode in base ring");
                }
                else if (stats == null)
                {
                    index.add("senzu | (mixin) | UNVERIFIED: statsData null, cannot enumerate ActionsNode children");
                }
                else
                {
                    boolean found = false;
                    for (RadialNode child : ((AbstractRadialNode) actions).children(stats))
                        if (child instanceof SenzuRadialNode s) { senzuNode = s; found = true; break; }
                    index.add("senzu | (mixin) | " + (found ? "PASS" : "FAIL") + ": SenzuRadialNode "
                            + (found ? "present in ActionsNode children (MixinDmzActionsNode bound)"
                                     : "NOT found among ActionsNode children"));
                }
            }
        }
        catch (Throwable t)
        {
            LOGGER.warn("[VisualTest] senzu: reflection to find the node failed.", t);
            index.add("senzu | (mixin) | ERROR: " + t);
        }
        // Fall back to a standalone node so the click/state checks can still run (they read the client bag, not stats).
        if (senzuNode == null)
            senzuNode = new SenzuRadialNode();
    }

    /** Read the screen's navigation chain to log whether the real hover actually landed on the Actions sector. */
    private void logActionsHover()
    {
        try
        {
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof UtilityMenuScreen scr)) return;
            Object chainObj = getField(scr, "chain");
            String top = "(none)";
            if (chainObj instanceof List<?> chain && !chain.isEmpty())
                top = chain.get(0).getClass().getSimpleName();
            boolean onActions = "ActionsNode".equals(top);
            index.add("senzu | (hover) | " + (onActions ? "PASS" : "NOTE") + ": base slot resolves to " + top
                    + (onActions ? " (Actions ring expanded)" : " (ring may not be expanded in the shot)"));
        }
        catch (Throwable t)
        {
            LOGGER.warn("[VisualTest] senzu: reading the chain failed.", t);
        }
    }

    /** Re-enqueue self until the client sees at least {@code wantTypes} bean types, or the tries run out. */
    private void beanSyncGate(int wantTypes, int triesLeft)
    {
        if (SenzuRadialNode.distinctTypes() >= wantTypes || triesLeft <= 0)
            return;
        script.addFirst(new Step(3, () -> beanSyncGate(wantTypes, triesLeft - 1)));
    }

    private void senzuCaptureSelection(String when)
    {
        String id = SenzuRadialNode.selectedBeanId();
        int count = SenzuRadialNode.selectedCount();
        String iconPath = "?";
        String label = "?";
        try { if (senzuNode != null && senzuNode.icon(null) != null) iconPath = senzuNode.icon(null).toString(); }
        catch (Throwable ignored) { }
        try { if (senzuNode != null && senzuNode.label(null) != null) label = senzuNode.label(null).getString(); }
        catch (Throwable ignored) { }
        index.add("senzu | (select " + when + ") | id=" + id + " count=" + count + " icon=" + iconPath
                + " label=\"" + label + "\"");
    }

    private void senzuBeforePull()
    {
        senzuPulledId = SenzuRadialNode.selectedBeanId();
        final String id = senzuPulledId;
        onServer(sp ->
        {
            senzuBagBefore = SenzuSetup.beansInBag(sp, id);
            senzuInvBefore = SenzuSetup.beansInInventory(sp, id);
            index.add("senzu | (pull before) | id=" + id + " bag=" + senzuBagBefore + " inventory=" + senzuInvBefore);
        });
    }

    private void senzuAfterPull()
    {
        final String id = senzuPulledId;
        onServer(sp ->
        {
            int bag = SenzuSetup.beansInBag(sp, id);
            int inv = SenzuSetup.beansInInventory(sp, id);
            boolean moved = senzuBagBefore >= 0 && bag == senzuBagBefore - 1 && inv == senzuInvBefore + 1;
            index.add("senzu | (pull after) | id=" + id + " bag=" + bag + " inventory=" + inv);
            index.add("senzu | (pull) | " + (moved ? "PASS" : "FAIL") + ": bag " + senzuBagBefore + "->" + bag
                    + ", inventory " + senzuInvBefore + "->" + inv + " (expected -1 / +1)");
        });
    }

    private void senzuCheckStates(String label, boolean expectVisible, boolean expectInteractive)
    {
        SenzuRadialNode node = senzuNode != null ? senzuNode : new SenzuRadialNode();
        boolean vis;
        boolean inter;
        try { vis = node.visible(null); } catch (Throwable t) { vis = false; }
        try { inter = node.interactive(null); } catch (Throwable t) { inter = false; }
        boolean pass = vis == expectVisible && inter == expectInteractive;
        index.add("senzu | (" + label + ") | " + (pass ? "PASS" : "FAIL") + ": visible=" + vis + " (want "
                + expectVisible + "), interactive=" + inter + " (want " + expectInteractive + ")");
    }

    /**
     * Place the OS cursor over DMZ's Actions base sector so the screen's own hover code (resolveHover, called every
     * render frame) expands its child ring. The Actions sector centres on -90 + 5*45 = 135 degrees; a radius in the
     * middle of the level-0 band lands squarely inside its 45 degree wedge. The UI is drawn at DMZ's own ui scale
     * over a GUI-scaled buffer, so a UI offset maps to a window-coordinate offset of offset * uiScale * screen /
     * guiScaled.
     */
    private void hoverActionsSector()
    {
        try
        {
            Minecraft mc = Minecraft.getInstance();
            ensureMenu();
            if (!(mc.screen instanceof UtilityMenuScreen scr)) return;
            Object us = invokeNoArg(scr, "getUiScale");
            double uiScale = us instanceof Float f ? f : 1.0D;
            var win = mc.getWindow();
            double gsw = Math.max(1, win.getGuiScaledWidth());
            double gsh = Math.max(1, win.getGuiScaledHeight());
            double sw = win.getScreenWidth() > 0 ? win.getScreenWidth() : win.getWidth();
            double sh = win.getScreenHeight() > 0 ? win.getScreenHeight() : win.getHeight();
            double angle = Math.toRadians(135.0D);
            double r = 63.0D;
            double dxUi = Math.cos(angle) * r;
            double dyUi = Math.sin(angle) * r;
            double cursorX = sw / 2.0D + dxUi * uiScale * sw / gsw;
            double cursorY = sh / 2.0D + dyUi * uiScale * sh / gsh;
            // glfwSetCursorPos fails silently on an unfocused window (the harness runs in the background), so also
            // write MouseHandler's cached position directly, which is what the screen's render actually reads.
            GLFW.glfwSetCursorPos(win.getWindow(), cursorX, cursorY);
            setField(mc.mouseHandler, "xpos", cursorX);
            setField(mc.mouseHandler, "ypos", cursorY);
        }
        catch (Throwable t)
        {
            LOGGER.warn("[VisualTest] senzu: hover cursor placement failed.", t);
        }
    }

    private static Object getField(Object owner, String name) throws Exception
    {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass())
        {
            try
            {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setField(Object owner, String name, Object value)
    {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass())
        {
            try
            {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(owner, value);
                return;
            }
            catch (NoSuchFieldException ignored) { }
            catch (Exception e) { return; }
        }
    }

    private static Object invokeNoArg(Object owner, String name) throws Exception
    {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass())
        {
            try
            {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(owner);
            }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }

    // ---------------------------------------------------------------- items / models / boostpad scenarios

    /** ITEMS: rupees r_1..r_5000 across the hotbar and the wallet in the offhand, with the HUD shown. */
    private void buildItems()
    {
        step(6, () -> onServer(this::giveCurrencyItems));
        step(6, () -> Minecraft.getInstance().options.hideGui = false);
        step(2, () -> grab("items", "items_hotbar", "Hotbar: rupees r_1..r_5000, wallet in the offhand"));
        step(2, () -> Minecraft.getInstance().options.hideGui = true);
    }

    private void giveCurrencyItems(ServerPlayer sp)
    {
        String[] rupees = { "r_1", "r_5", "r_10", "r_20", "r_50", "r_100", "r_500", "r_1000", "r_5000" };
        int placed = 0;
        for (int i = 0; i < rupees.length && i < 9; ++i)
        {
            Item it = itemById("dmz_ragnarok:" + rupees[i]);
            if (it != null) { sp.getInventory().items.set(i, new ItemStack(it, 1)); placed++; }
        }
        Item wallet = itemById("dmz_ragnarok:wallet");
        if (wallet != null) sp.getInventory().offhand.set(0, new ItemStack(wallet, 1));
        sp.getInventory().setChanged();
        sp.inventoryMenu.broadcastChanges();
        index.add("items | (setup) | placed " + placed + " rupees + wallet=" + (wallet != null));
    }

    // ---------------------------------------------------------------- HALLOWEEN scenario
    //
    // Gives every candy variant plus the two loot boxes, screenshots the hotbar and each item's tooltip, then proves
    // the behaviours server side (eat one candy, unwrap one chocolate, open one box), and finally puts up the event
    // editor's new Loot Boxes tab (box list, then one box's reward editor) for a screenshot. Needs the key loaded
    // (default) so the box actually rolls; the /event import seeds the built-in Halloween def with the boxes.

    private static final String TOK = "dmz_ragnarok:event_token";
    private static final String VARIANT_TAG = "Variant";

    private void buildHalloween()
    {
        // Seed the built-in Halloween event (its loot box configs) so an opened box can roll.
        step(10, () -> sendCommand(Minecraft.getInstance(), "event import builtin:halloween"));
        step(20, () -> onServer(this::giveHalloweenItems));
        step(6, () -> Minecraft.getInstance().options.hideGui = false);
        step(3, () -> grab("halloween", "hotbar",
                "Hotbar: candy, candy corn, chocolate dabura, wrapped chocolate, Halloween Box, Pumpkin Bag"));
        step(2, () -> Minecraft.getInstance().options.hideGui = true);

        // A tooltip card per item: the icon plus its full hover text (name + description line).
        tooltipShot("candy", () -> hwToken("halloween"));
        tooltipShot("candy_corn", () -> hwToken("candy_corn"));
        tooltipShot("chocolate_dabura", () -> hwToken("chocolate_dabura"));
        tooltipShot("chocolate_dabura_wrapped", () -> hwToken("chocolate_dabura_wrapped"));
        tooltipShot("halloween_box", () -> stackOf("dmz_ragnarok:halloween_box"));
        tooltipShot("pumpkin_bag", () -> stackOf("dmz_ragnarok:halloween_pumpkin_bag"));
        step(2, () -> { keepScreen = false; Minecraft.getInstance().setScreen(null); });

        // Behaviour checks, all logged to index.txt.
        step(6, () -> onServer(this::eatCandyCheck));
        step(6, () -> onServer(this::unwrapCheck));
        step(6, () -> onServer(this::openBoxCheck));
        // The box open broadcasts an announcement; show it in chat.
        step(6, () -> Minecraft.getInstance().options.hideGui = false);
        step(3, () -> grab("halloween", "box_open_chat", "Chat: the Halloween box open announcement"));
        step(2, () -> Minecraft.getInstance().options.hideGui = true);

        // The event editor's Loot Boxes tab (built client-side from a representative def so the UI renders the boxes).
        step(4, this::openEventEditorClient);
        step(4, () -> {});
        step(2, () -> showLootBoxTab(-1, false));
        step(3, () -> {});
        step(2, () -> grab("halloween", "editor_lootbox_list",
                "Loot Boxes editor tab: the box list (Halloween Box, Pumpkin Bag)"));
        step(2, () -> showLootBoxTab(0, false));
        step(3, () -> {});
        step(2, () -> grab("halloween", "editor_lootbox_settings",
                "Loot Boxes editor: the Halloween Box settings (name, announce, template, preview)"));
        step(2, () -> showLootBoxTab(0, true));
        step(3, () -> {});
        step(2, () -> grab("halloween", "editor_lootbox_rewards",
                "Loot Boxes editor: the Halloween Box reward editor (fields, helpers, reward rows)"));
        // The Theme tab (theme-key dropdown) and the Content > Regions sub (target regions + dimensions multi-selects).
        step(2, () -> showEventSection(2));
        step(3, () -> {});
        step(2, () -> grab("halloween", "editor_theme",
                "Theme tab: theme key is a dropdown (halloween selected)"));
        step(2, () -> showEventContentSub(0));
        step(3, () -> {});
        step(2, () -> grab("halloween", "editor_regions",
                "Regions sub: event regions, target regions and dimensions are all dropdowns"));
        step(2, () -> { keepScreen = false; Minecraft.getInstance().setScreen(null); });
    }

    private void giveHalloweenItems(ServerPlayer sp)
    {
        int i = 0;
        i = place(sp, i, hwToken("halloween"));
        i = place(sp, i, hwToken("candy_corn"));
        i = place(sp, i, hwToken("chocolate_dabura"));
        i = place(sp, i, hwToken("chocolate_dabura_wrapped"));
        i = place(sp, i, stackOf("dmz_ragnarok:halloween_box"));
        i = place(sp, i, stackOf("dmz_ragnarok:halloween_pumpkin_bag"));
        sp.getInventory().setChanged();
        sp.inventoryMenu.broadcastChanges();
        index.add("halloween | (setup) | placed " + i + " items in the hotbar");
    }

    private int place(ServerPlayer sp, int slot, ItemStack stack)
    {
        if (stack.isEmpty() || slot >= 9)
            return slot;
        sp.getInventory().items.set(slot, stack);
        return slot + 1;
    }

    /** An event_token stamped with a variant (client or server side; the item is registered on both). */
    private static ItemStack hwToken(String variant)
    {
        Item tok = itemById(TOK);
        if (tok == null)
            return ItemStack.EMPTY;
        ItemStack s = new ItemStack(tok, 1);
        s.getOrCreateTag().putString(VARIANT_TAG, variant);
        return s;
    }

    private static ItemStack stackOf(String id)
    {
        Item it = itemById(id);
        return it == null ? ItemStack.EMPTY : new ItemStack(it, 1);
    }

    /** Put up a tooltip card for one item, settle, screenshot it. */
    private void tooltipShot(String name, java.util.function.Supplier<ItemStack> supplier)
    {
        step(2, () -> { keepScreen = true; Minecraft.getInstance().setScreen(new TooltipShot(supplier.get())); });
        step(3, () -> { });
        step(2, () -> grab("halloween", "tooltip_" + name, "Tooltip card for " + name));
    }

    /** Eat one candy: lower hunger first, complete the eat, and log the hunger/saturation change and the consume. */
    private void eatCandyCheck(ServerPlayer sp)
    {
        // Consumption only happens outside creative (instabuild never consumes, by design), so verify in survival.
        sp.setGameMode(GameType.SURVIVAL);
        ItemStack candy = hwToken("halloween");
        candy.setCount(3);
        sp.getFoodData().setFoodLevel(6);
        sp.getFoodData().setSaturation(0.0F);
        int foodBefore = sp.getFoodData().getFoodLevel();
        float satBefore = sp.getFoodData().getSaturationLevel();
        int countBefore = candy.getCount();
        candy.getItem().finishUsingItem(candy, sp.serverLevel(), sp);
        index.add("halloween | (eat) | food " + foodBefore + "->" + sp.getFoodData().getFoodLevel()
                + " sat " + satBefore + "->" + sp.getFoodData().getSaturationLevel()
                + " count " + countBefore + "->" + candy.getCount()
                + (candy.getCount() == countBefore - 1 ? " OK" : " UNEXPECTED"));
    }

    /** Unwrap one wrapped chocolate held in the main hand: log the wrapped and plain counts before and after. */
    private void unwrapCheck(ServerPlayer sp)
    {
        ItemStack wrapped = hwToken("chocolate_dabura_wrapped");
        wrapped.setCount(2);
        sp.getInventory().items.set(0, wrapped);
        int wrappedBefore = countVariant(sp, "chocolate_dabura_wrapped");
        int plainBefore = countVariant(sp, "chocolate_dabura");
        sp.getMainHandItem().getItem().use(sp.serverLevel(), sp, InteractionHand.MAIN_HAND);
        int wrappedAfter = countVariant(sp, "chocolate_dabura_wrapped");
        int plainAfter = countVariant(sp, "chocolate_dabura");
        index.add("halloween | (unwrap) | wrapped " + wrappedBefore + "->" + wrappedAfter
                + " plain " + plainBefore + "->" + plainAfter
                + (wrappedAfter == wrappedBefore - 1 && plainAfter == plainBefore + 1 ? " OK" : " UNEXPECTED"));
    }

    /** Open one Halloween box: roll+grant directly (log the result), then consume one via the real item use. */
    private void openBoxCheck(ServerPlayer sp)
    {
        boolean rolled;
        try
        {
            rolled = net.shurui.dev.sdu.api.key.EventHooks.get().openLootBox(sp, "halloween_box");
        }
        catch (Throwable t)
        {
            rolled = false;
            index.add("halloween | (box) | openLootBox threw: " + t);
        }
        ItemStack box = stackOf("dmz_ragnarok:halloween_box");
        box.setCount(2);
        sp.getInventory().items.set(0, box);
        int boxBefore = countItem(sp, "dmz_ragnarok:halloween_box");
        sp.getMainHandItem().getItem().use(sp.serverLevel(), sp, InteractionHand.MAIN_HAND);
        int boxAfter = countItem(sp, "dmz_ragnarok:halloween_box");
        index.add("halloween | (box) | openLootBox=" + rolled + " consumed " + boxBefore + "->" + boxAfter
                + (rolled && boxAfter == boxBefore - 1 ? " OK" : " CHECK"));
    }

    private static int countVariant(ServerPlayer sp, String variant)
    {
        int n = 0;
        for (ItemStack s : sp.getInventory().items)
            if (!s.isEmpty() && itemId(s).equals(TOK) && variant.equals(variantOf(s)))
                n += s.getCount();
        return n;
    }

    private static int countItem(ServerPlayer sp, String id)
    {
        int n = 0;
        for (ItemStack s : sp.getInventory().items)
            if (!s.isEmpty() && itemId(s).equals(id))
                n += s.getCount();
        return n;
    }

    private static String itemId(ItemStack s)
    {
        return String.valueOf(ForgeRegistries.ITEMS.getKey(s.getItem()));
    }

    private static String variantOf(ItemStack s)
    {
        return s.getTag() != null && s.getTag().contains(VARIANT_TAG) ? s.getTag().getString(VARIANT_TAG) : "";
    }

    /** Build a representative Halloween def (matching the seed) and open the event editor at the Loot Boxes tab. */
    private void openEventEditorClient()
    {
        try
        {
            net.shurui.shuruisutilities.events.EventDef d =
                    new net.shurui.shuruisutilities.events.EventDef("halloween_2026");
            d.name = "&6Halloween on Ragnarok";
            d.content.tokens.variant = "halloween";
            net.shurui.shuruisutilities.events.EventDef.LootBox hb =
                    new net.shurui.shuruisutilities.events.EventDef.LootBox();
            hb.boxType = "halloween_box";
            hb.displayName = "&6Halloween Box";
            hb.announceTemplate = "&6{player} &7opened a &6Halloween Box &7and got &f{item}&7!";
            addReward(hb, 40, "common", "token:halloween:3-6");
            addReward(hb, 15, "uncommon", "token:chocolate_dabura:1-2");
            addReward(hb, 7, "rare", "cosmetic:hw_bat_hat");
            net.shurui.shuruisutilities.events.EventDef.LootBox pb =
                    new net.shurui.shuruisutilities.events.EventDef.LootBox();
            pb.boxType = "pumpkin_bag";
            pb.displayName = "&6Pumpkin Bag";
            addReward(pb, 40, "common", "token:candy_corn:3-5");
            addReward(pb, 6, "rare", "cosmetic:hw_pumpkin_head");
            d.content.lootBoxes.add(hb);
            d.content.lootBoxes.add(pb);

            // Seed a theme key, a region-mob add and a hologram so the Theme, Regions and Holograms dropdowns render
            // with representative values (the pick lists below feed those dropdowns their choices).
            d.theme.key = "halloween";
            net.shurui.shuruisutilities.events.EventDef.RegionMobAdd rm =
                    new net.shurui.shuruisutilities.events.EventDef.RegionMobAdd();
            rm.templateRegion = "haunted_forest";
            rm.targets.add("spawn_town");
            rm.targets.add("pumpkin_patch");
            rm.dims.add("minecraft:overworld");
            d.content.regionMobs.add(rm);
            d.content.eventRegions.add("pumpkin_patch");
            net.shurui.shuruisutilities.events.EventDef.EventHologram holo =
                    new net.shurui.shuruisutilities.events.EventDef.EventHologram();
            holo.dim = "minecraft:overworld";
            holo.text = "&6Happy Halloween!";
            d.content.holograms.add(holo);

            net.minecraft.nbt.CompoundTag picks = new net.minecraft.nbt.CompoundTag();
            picks.put("regions", strList("spawn_town", "haunted_forest", "pumpkin_patch"));
            picks.put("dims", strList("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end",
                    "dmz_ragnarok:namek", "dmz_ragnarok:space"));
            net.minecraft.nbt.ListTag ids = new net.minecraft.nbt.ListTag();
            net.minecraft.nbt.ListTag names = new net.minecraft.nbt.ListTag();
            int added = 0;
            for (CosmeticDef c : CosmeticCatalog.all())
            {
                if (c == null || c.id == null || c.id.isBlank() || added >= 60)
                    continue;
                ids.add(net.minecraft.nbt.StringTag.valueOf(c.id));
                names.add(net.minecraft.nbt.StringTag.valueOf(
                        c.displayName == null || c.displayName.isBlank() ? c.id : c.displayName));
                added++;
            }
            picks.put("cosmeticIds", ids);
            picks.put("cosmeticNames", names);

            keepScreen = true;
            Minecraft.getInstance().setScreen(new net.shurui.shuruisutilities.client.gui.editor.EventEditScreen(
                    d.toNbt(), picks));
        }
        catch (Throwable t)
        {
            index.add("halloween | (editor) | failed to open the editor: " + t);
        }
    }

    private static void addReward(net.shurui.shuruisutilities.events.EventDef.LootBox box, int weight, String rarity,
                                  String token)
    {
        net.shurui.shuruisutilities.events.EventDef.LootBox.Reward r =
                new net.shurui.shuruisutilities.events.EventDef.LootBox.Reward();
        r.weight = weight;
        r.rarity = rarity;
        r.tokens.add(token);
        box.rewards.add(r);
    }

    private void showLootBoxTab(int boxIndex, boolean rewards)
    {
        if (Minecraft.getInstance().screen
                instanceof net.shurui.shuruisutilities.client.gui.editor.EventEditScreen e)
            e.showLootBoxesTab(boxIndex, rewards);
    }

    private void showEventSection(int section)
    {
        if (Minecraft.getInstance().screen
                instanceof net.shurui.shuruisutilities.client.gui.editor.EventEditScreen e)
            e.showSection(section);
    }

    private void showEventContentSub(int sub)
    {
        if (Minecraft.getInstance().screen
                instanceof net.shurui.shuruisutilities.client.gui.editor.EventEditScreen e)
            e.showContentSub(sub);
    }

    private static net.minecraft.nbt.ListTag strList(String... vals)
    {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (String v : vals)
            list.add(net.minecraft.nbt.StringTag.valueOf(v));
        return list;
    }

    /** A minimal screen that draws one item icon and its full tooltip, so a screenshot shows the hover text. */
    private static final class TooltipShot extends Screen
    {
        private final ItemStack stack;

        TooltipShot(ItemStack stack)
        {
            super(net.minecraft.network.chat.Component.literal("tooltip"));
            this.stack = stack;
        }

        @Override
        public boolean isPauseScreen()
        {
            return false;
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partial)
        {
            renderBackground(g);
            Minecraft mc = Minecraft.getInstance();
            int cx = this.width / 2;
            int cy = this.height / 2;
            g.renderItem(stack, cx - 8, cy - 44);
            g.renderItemDecorations(mc.font, stack, cx - 8, cy - 44);
            g.renderTooltip(mc.font, stack, cx - 60, cy);
        }
    }

    /** SAGAMODELS: a row of rgnpc fighter models, including an old-alias id and an unmatched id, to read the fallback. */
    private void buildSagaModels()
    {
        final String[] models = { "saga_beerus", "saga_jiren", "saga_mv_cell_max", "saga_gt_omega",
                "master_whis", "beerus", "cooler", "2stars" };
        step(40, () -> onServer(sp -> spawnModelRow(sp, models)));
        // Frame the whole row from a few blocks in front, camera facing them, aimed at torso height. Two settle
        // calls plus extra frames so GeckoLib has baked and the idle pose has advanced before the shot.
        step(10, () -> lookAtScene(0.0D, 1.5D, -20.0D));
        step(4, () -> lookAtScene(0.0D, 1.5D, -20.0D));
        step(4, () -> lookAtScene(0.0D, 1.5D, -20.0D));
        step(2, () -> grab("sagamodels", "sagamodels_row",
                "Row of 8 rgnpc fighter models with name tags: " + String.join(", ", models)));
    }

    /**
     * Halloween 2026 content: a row of the six zombified Z fighter rgnpc models (so their skins and geos are seen on
     * the fighter chassis) and the Pilaf Mech raid boss (the bundled pilaf mecha combined model). Confirms the models
     * are textured, not purple/black, not floating, and at a sane scale.
     */
    private void buildPilaf()
    {
        final String[] zf = { "halloween_goku_ssj", "halloween_goku_ssj2", "halloween_krillin",
                "halloween_vegeta", "halloween_yamcha", "halloween_vegeto" };
        step(40, () -> onServer(sp -> spawnModelRow(sp, zf)));
        step(10, () -> lookAtScene(0.0D, 1.5D, -18.0D));
        step(4, () -> lookAtScene(0.0D, 1.5D, -18.0D));
        step(4, () -> lookAtScene(0.0D, 1.5D, -18.0D));
        step(2, () -> grab("pilaf", "zombified_row",
                "Row of 6 zombified Z fighters: " + String.join(", ", zf)));

        step(20, () -> onServer(this::spawnPilafMech));
        step(10, () -> lookAtScene(0.0D, 3.5D, -16.0D));
        step(4, () -> lookAtScene(0.0D, 3.5D, -16.0D));
        step(4, () -> lookAtScene(0.0D, 3.5D, -16.0D));
        step(2, () -> grab("pilaf", "pilaf_mech",
                "Pilaf Mech raid boss: owner's dedicated pilaf mech rig with Pilaf's skin, roughly 7.4 blocks tall"));
        // two idle frames a few ticks apart: if the limbs differ the idle clip is animating (not a frozen T-pose).
        step(1, () -> grab("pilaf", "pilaf_mech_idle_a", "Pilaf Mech idle frame A"));
        step(8, () -> lookAtScene(0.0D, 3.5D, -16.0D));
        step(1, () -> grab("pilaf", "pilaf_mech_idle_b", "Pilaf Mech idle frame B (~8 ticks later; pose should differ = animating)"));
        // attack clips: trigger + capture, proving the named clips exist (no GeckoLib missing-clip crash on the render thread).
        step(2, () -> pilafPlay("slam_front"));
        step(3, () -> lookAtScene(0.0D, 3.0D, -14.0D));
        step(1, () -> grab("pilaf", "pilaf_slam_front", "Pilaf Mech playing slam_front"));
        step(4, () -> pilafPlay("fire_missile"));
        step(3, () -> lookAtScene(0.0D, 3.0D, -14.0D));
        step(1, () -> grab("pilaf", "pilaf_fire_missile", "Pilaf Mech playing fire_missile"));
    }

    private void spawnPilafMech(ServerPlayer sp)
    {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("dmz_ragnarok", "pilaf_mech"));
        if (type == null)
        {
            index.add("pilaf | (setup) | FAILED: pilaf_mech entity type missing");
            return;
        }
        Entity e = type.create(sp.serverLevel());
        if (e == null)
        {
            index.add("pilaf | (setup) | FAILED: pilaf_mech create() returned null");
            return;
        }
        // Off to the side of the zombified row so the boss frames cleanly on its own.
        double x = sp.getX() + 16.0D;
        double y = sp.getY();
        double z = sp.getZ() + 6.0D;
        e.moveTo(x, y, z, 180.0F, 0.0F);
        if (e instanceof net.minecraft.world.entity.Mob m)
        {
            m.setNoAi(true);
            m.setPersistenceRequired();
        }
        e.setCustomName(net.minecraft.network.chat.Component.literal("Pilaf Mech"));
        e.setCustomNameVisible(true);
        sceneX = x;
        sceneY = y + 3.5D;
        sceneZ = z;
        boolean ok = sp.serverLevel().addFreshEntity(e);
        pilafEntityId = ok ? e.getId() : -1;
        index.add("pilaf | (setup) | spawned pilaf_mech=" + ok);
    }

    private volatile int pilafEntityId = -1;

    /** Reflectively trigger a named one-shot clip on the spawned Pilaf Mech (its play() is private), verifying the clip
     *  exists (no GeckoLib missing-clip crash) and capturing the pose. */
    private void pilafPlay(String clip)
    {
        onServer(sp ->
        {
            if (pilafEntityId < 0) { index.add("pilaf | (clip) | no pilaf entity for " + clip); return; }
            Entity e = sp.serverLevel().getEntity(pilafEntityId);
            if (e == null) { index.add("pilaf | (clip) | pilaf entity gone for " + clip); return; }
            try
            {
                Method m = e.getClass().getDeclaredMethod("play", String.class);
                m.setAccessible(true);
                m.invoke(e, clip);
                index.add("pilaf | (clip) | played " + clip);
            }
            catch (Throwable t) { index.add("pilaf | (clip) | play(" + clip + ") failed: " + t); }
        });
    }

    private void spawnModelRow(ServerPlayer sp, String[] models)
    {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("dmz_ragnarok", "rgnpc_fighter"));
        if (type == null)
        {
            index.add("sagamodels | (setup) | FAILED: rgnpc_fighter entity type missing");
            return;
        }
        double bx = sp.getX();
        double by = sp.getY();
        double bz = sp.getZ();
        double spacing = 2.5D;
        double rowZ = bz + 6.0D;
        sceneX = bx;
        sceneY = by + 1.5D;
        sceneZ = rowZ;
        int spawned = 0;
        for (int i = 0; i < models.length; ++i)
        {
            Entity e = type.create(sp.serverLevel());
            if (!(e instanceof RgNpcFighterEntity f)) continue;
            double x = bx + (i - (models.length - 1) / 2.0D) * spacing;
            // Yaw 180 faces -Z (north), toward the camera, which sits on the north side looking south at the row.
            f.moveTo(x, by, rowZ, 180.0F, 0.0F);
            // Keep them at the spawn height (the camera aims there); otherwise gravity drops them out of frame.
            f.setNoGravity(true);
            f.setNoAi(true);
            // Off PEACEFUL they no longer peaceful-despawn, but pin persistence too so a distance despawn cannot fire.
            f.setPersistenceRequired();
            try { f.setModelId(models[i]); } catch (Throwable ignored) { }
            f.setCustomName(net.minecraft.network.chat.Component.literal(models[i]));
            f.setCustomNameVisible(true);
            if (sp.serverLevel().addFreshEntity(f)) spawned++;
        }
        // The rgnpc set is BUNDLED in the jar again (assets/dmz_ragnarok/geo|textures/entity/ragnarok), loaded off the
        // classpath by RgNpcPackFinder, so this shot reads the real model art: live-or-aliased ids draw their model,
        // an unmatched id (cooler) draws the generated saiyan fallback.
        index.add("sagamodels | (setup) | spawned " + spawned + "/" + models.length + " rgnpc fighters (bundled models)");
    }

    /** BOOSTPAD: a race boost pad sat on a bottom stone slab, shot from the side. */
    private void buildBoostPad()
    {
        step(30, () -> onServer(this::placeBoostPad));
        step(10, () -> lookAtScene(4.0D, 1.0D, 0.5D));
        step(4, () -> lookAtScene(4.0D, 1.0D, 0.5D));
        step(2, () -> grab("boostpad", "boostpad_side", "Race boost pad on a bottom stone slab, side view"));
    }

    private void placeBoostPad(ServerPlayer sp)
    {
        Block pad = blockById("dmz_ragnarok:race_boost_pad");
        if (pad == null)
        {
            index.add("boostpad | (setup) | FAILED: race_boost_pad block missing");
            return;
        }
        BlockPos base = sp.blockPosition().relative(sp.getDirection(), 4).below();
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState();
        try { slab = slab.setValue(SlabBlock.TYPE, SlabType.BOTTOM); } catch (Throwable ignored) { }
        sp.serverLevel().setBlockAndUpdate(base, slab);
        sp.serverLevel().setBlockAndUpdate(base.above(), pad.defaultBlockState());
        sceneX = base.getX() + 0.5D;
        sceneY = base.getY() + 1.0D;
        sceneZ = base.getZ() + 0.5D;
        index.add("boostpad | (setup) | placed race_boost_pad on a bottom slab at " + base.above());
    }

    /** Place the camera at a scene-relative offset and aim it at the current scene point. */
    private void lookAtScene(double ox, double oy, double oz)
    {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        double cx = sceneX + ox;
        double cy = sceneY + oy;
        double cz = sceneZ + oz;
        double dx = sceneX - cx;
        double dyv = (sceneY + 0.8D) - cy;
        double dz = sceneZ - cz;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180D / Math.PI)) - 90.0F;
        float pitch = (float) (-(Mth.atan2(dyv, flat) * (180D / Math.PI)));
        p.setPos(cx, cy, cz);
        p.setDeltaMovement(0, 0, 0);
        p.setYRot(yaw);
        p.yRotO = yaw;
        p.setXRot(pitch);
        p.xRotO = pitch;
        p.setYHeadRot(yaw);
        p.setYBodyRot(yaw);
        final double fx = cx, fy = cy, fz = cz;
        final float fyaw = yaw, fpitch = pitch;
        onServer(sp -> sp.teleportTo(sp.serverLevel(), fx, fy, fz, fyaw, fpitch));
    }

    /**
     * PLANETSKY: land on a generated v2 planet surface and shoot the per-planet sky under forced weather (clear, rain,
     * storm, snow) on a wet blue-sky world, then land on a RINGED world and shoot its ring system arcing across the sky.
     * Weather is forced through the client debug hook so a scripted shot never has to wait for the deterministic clock
     * to roll a state around.
     */
    private void buildPlanetSky()
    {
        // creative + flying so the player hovers on the surface and never falls or drowns between shots.
        step(6, () -> sendCommand(Minecraft.getInstance(), "gamemode creative"));

        // land ONCE on a fresh planet that is BOTH a wet OVERWORLD (blue sky) AND ringed. A single landing serves both
        // the weather shots and the overhead-ring shots, so there is no second cross-thread teleport a lagging server
        // could reorder ahead of the shots. The space test hooks are reached reflectively: the harness is compiled into
        // the core source set too, which must not name the Space module (it resolves at runtime in the fat jar). Wait
        // generously for the teleport, the synchronous centre stamp and the sky packet.
        step(80, () -> onServer(sp -> spaceLandWetRinged(sp)));
        step(120, () -> { });

        // pin the synthetic cycle to full noon so the theme-coloured day dome and the weather darkening are visible (the
        // real cycle is driven by the world game time, which is near zero, i.e. night, in a fresh test world).
        step(4, () -> spaceForceDay(1.0F));
        planetSkyShot("CLEAR", -8.0F, "clear", "clear daytime per-planet sky (blue overworld theme dome) on a wet planet");
        // RAIN, captured twice a few ticks apart so the two frames prove the streaks fall DOWN (owner report: weather
        // flowed upward). Compare a streak's position between rain and rain_b: it must be lower in rain_b.
        step(2, () -> spaceForceWeather("RAIN"));
        step(1, () -> lookSky(0.0F, -6.0F));
        step(14, () -> lookSky(0.0F, -6.0F));
        step(1, () -> grab("planetsky", "rain", "rain: falling grey streaks near the camera, frame A"));
        step(4, () -> lookSky(0.0F, -6.0F));
        step(1, () -> grab("planetsky", "rain_b", "rain frame B, ~4 ticks after A: streaks must sit lower (falling down)"));

        // Owner report: looking up or down flips the direction the rain seems to move. Prove the streaks stay world
        // vertical and keep falling DOWN at every pitch: straight up (-90), level (0) and straight down (+90), two
        // frames a few ticks apart at each so a streak's world position must drop between the pair, never reverse.
        for (float pitch : new float[] { -90.0F, 0.0F, 90.0F })
        {
            final String tag = pitch < 0.0F ? "up" : (pitch > 0.0F ? "down" : "level");
            step(2, () -> lookSky(0.0F, pitch));
            step(12, () -> lookSky(0.0F, pitch));
            step(1, () -> grab("planetsky", "rain_pitch_" + tag + "_a",
                    "rain looking " + tag + " (pitch " + (int) pitch + "), frame A: streaks vertical in world, falling down"));
            step(4, () -> lookSky(0.0F, pitch));
            step(1, () -> grab("planetsky", "rain_pitch_" + tag + "_b",
                    "rain looking " + tag + " frame B, ~4 ticks after A: same world-down fall, direction unchanged by pitch"));
        }

        planetSkyShot("STORM", -8.0F, "storm",
                "storm: heavy rain, dark grey sky (lightning flashes on the deterministic schedule)");
        planetSkyShot("SNOW", -8.0F, "snow", "snow: drifting snowflakes over a dimmed sky");

        // ring shots on the SAME planet: a dark (near-night) sky so the lit ring arc pops, CLEAR weather so nothing
        // obscures it, and a long settle so the leftover snow particles disperse first. A few bearings looking well up
        // so the band crossing near the zenith is framed.
        step(4, () -> { spaceForceWeather("CLEAR"); spaceForceDay(0.15F); });
        step(50, () -> lookSky(0.0F, -40.0F));   // let the snow clear before the ring shots
        for (float yaw : new float[] { 0.0F, 120.0F, 240.0F })
        {
            step(2, () -> lookSky(yaw, -40.0F));
            step(4, () -> lookSky(yaw, -40.0F));
            final int y = (int) yaw;
            step(1, () -> grab("planetsky", "rings_yaw" + y, "overhead ring arc from the surface, looking up, yaw " + y));
        }
        step(2, () -> { spaceForceWeather(null); spaceForceDay(-1.0F); });
    }

    /** Force a weather state, aim the camera at the sky, let particles accumulate for a few ticks, then grab. */
    private void planetSkyShot(String stateName, float pitch, String name, String desc)
    {
        step(2, () -> spaceForceWeather(stateName));
        step(1, () -> lookSky(0.0F, pitch));
        // hold ~14 ticks so the weather subsystem spawns a full field of precipitation before the frame is grabbed.
        step(14, () -> lookSky(0.0F, pitch));
        step(1, () -> grab("planetsky", name, desc));
    }

    /**
     * Reflectively land the host player on a debug space planet that is both a wet OVERWORLD (blue sky) and ringed, so
     * one landing serves the weather shots and the overhead-ring shots. The Space module is absent from the core source
     * set, so it is reached by name at runtime (present in the fat jar).
     */
    private void spaceLandWetRinged(ServerPlayer sp)
    {
        try
        {
            Class<?> dbg = Class.forName("net.shurui.shuruisutilities.space.SpaceSurfaceDebug");
            Object id = dbg.getMethod("landWetRingedPlanet", ServerPlayer.class).invoke(null, sp);
            LOGGER.info("[VisualTest] planetsky: landed wet+ringed planet {}", id);
        }
        catch (Throwable t)
        {
            LOGGER.warn("[VisualTest] planetsky: landing failed (Space module missing?).", t);
        }
    }

    /** Reflectively force (or clear, with a null name) the client weather state for a scripted shot. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void spaceForceWeather(String stateName)
    {
        try
        {
            Class<?> swc = Class.forName("net.shurui.shuruisutilities.client.space.SurfaceWeatherClient");
            Class<?> stateCls = Class.forName("net.shurui.shuruisutilities.space.PlanetWeather$State");
            Object stateVal = stateName == null ? null : Enum.valueOf((Class<Enum>) stateCls, stateName);
            swc.getMethod("forceState", stateCls).invoke(null, stateVal);
        }
        catch (Throwable t)
        {
            LOGGER.warn("[VisualTest] planetsky: force weather '{}' failed.", stateName, t);
        }
    }

    /** Reflectively pin the synthetic day/night factor (0..1), or a negative value to follow the world clock. */
    private void spaceForceDay(float factor)
    {
        try
        {
            Class<?> swc = Class.forName("net.shurui.shuruisutilities.client.space.SurfaceWeatherClient");
            swc.getMethod("forceDayFactor", float.class).invoke(null, factor);
        }
        catch (Throwable t)
        {
            LOGGER.warn("[VisualTest] planetsky: force day factor {} failed.", factor, t);
        }
    }

    /** First-person, aim the local player's view at the sky (yaw + pitch), and hold him still. */
    private void lookSky(float yaw, float pitch)
    {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
        p.setDeltaMovement(0, 0, 0);
        p.setYRot(yaw);
        p.yRotO = yaw;
        p.setXRot(pitch);
        p.xRotO = pitch;
        p.setYHeadRot(yaw);
        p.yHeadRotO = yaw;
        p.setYBodyRot(yaw);
        p.yBodyRotO = yaw;
    }

    // ---------------------------------------------------------------- GARRISON scenario (rgnpc face rows)
    //
    // A row of the twelve OVERWORLD (Red Ribbon) garrison faces on the real PlanetGarrisonDefenderEntity + renderer, so
    // any id that does NOT resolve through RgNpcModels renders as the default 2stars (Haze Shenron) face, which is exactly
    // the "upa" bug. setModelId resolves server side, so a fallback also shows up as a getModelId mismatch logged here.

    private static final String[] GARRISON_FACES = {
            "saga_cl_red_ribbon_soldier_gunner", "saga_cl_red_ribbon_soldier_bazooka", "saga_cl_officer_black",
            "saga_cl_ninja_murasaki", "saga_cl_colonel_silver", "saga_cl_colonel_violet", "saga_cl_general_blue",
            "saga_cl_general_white", "saga_cl_major_metallitron", "saga_cl_mercenary_tao", "saga_cl_android8",
            "saga_cl_commander_red" };

    private void buildGarrison()
    {
        step(40, () -> onServer(this::spawnGarrisonRow));
        // two overlapping framings so all twelve faces are legible across two shots.
        step(10, () -> lookAtScene(0.0D, 1.2D, -24.0D));
        step(4, () -> lookAtScene(0.0D, 1.2D, -24.0D));
        step(4, () -> lookAtScene(0.0D, 1.2D, -24.0D));
        step(2, () -> grab("garrison", "overworld_faces_all",
                "Row of 12 OVERWORLD Red Ribbon garrison faces; each must be a distinct textured model, NOT the 2stars "
                        + "Haze Shenron shadow-dragon fallback"));
        step(2, () -> lookAtScene(-7.0D, 1.2D, -12.0D));
        step(3, () -> lookAtScene(-7.0D, 1.2D, -12.0D));
        step(2, () -> grab("garrison", "overworld_faces_left", "Left half of the garrison row, close up"));
        step(2, () -> lookAtScene(7.0D, 1.2D, -12.0D));
        step(3, () -> lookAtScene(7.0D, 1.2D, -12.0D));
        step(2, () -> grab("garrison", "overworld_faces_right", "Right half of the garrison row, close up"));
    }

    private void spawnGarrisonRow(ServerPlayer sp)
    {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(
                new ResourceLocation("dmz_ragnarok", "planet_garrison_defender"));
        if (type == null)
        {
            index.add("garrison | (setup) | FAILED: planet_garrison_defender entity type missing");
            return;
        }
        double bx = sp.getX();
        double by = sp.getY();
        double rowZ = sp.getZ() + 6.0D;
        double spacing = 2.5D;
        sceneX = bx;
        sceneY = by + 1.2D;
        sceneZ = rowZ;
        int spawned = 0;
        StringBuilder mism = new StringBuilder();
        for (int i = 0; i < GARRISON_FACES.length; ++i)
        {
            Entity e = type.create(sp.serverLevel());
            if (!(e instanceof net.minecraft.world.entity.Mob m))
            {
                if (e != null) e.discard();
                continue;
            }
            double x = bx + (i - (GARRISON_FACES.length - 1) / 2.0D) * spacing;
            m.moveTo(x, by, rowZ, 180.0F, 0.0F);
            m.setNoGravity(true);
            m.setNoAi(true);
            m.setPersistenceRequired();
            try
            {
                m.getClass().getMethod("setModelId", String.class).invoke(m, GARRISON_FACES[i]);
                Object got = m.getClass().getMethod("getModelId").invoke(m);
                if (!GARRISON_FACES[i].equals(String.valueOf(got)))
                    mism.append(GARRISON_FACES[i]).append("->").append(got).append(' ');
            }
            catch (Throwable t)
            {
                index.add("garrison | (setup) | reflection setModelId failed for " + GARRISON_FACES[i] + ": " + t);
            }
            m.setCustomName(net.minecraft.network.chat.Component.literal(GARRISON_FACES[i].replace("saga_cl_", "")));
            m.setCustomNameVisible(true);
            if (sp.serverLevel().addFreshEntity(m)) spawned++;
        }
        index.add("garrison | (setup) | spawned " + spawned + "/" + GARRISON_FACES.length
                + " defenders; server-side modelId fallbacks to 2stars: " + (mism.length() == 0 ? "NONE" : mism.toString()));
    }

    // ---------------------------------------------------------------- AVATAR scenario (owner-avatar planet defender)

    /**
     * AVATAR: spawn a pair of owner-avatar planet defenders ({@code dmz_ragnarok:planet_owner_avatar}) with two different
     * DragonMineZ race looks over the host player's skin, and shoot them so the render can be eyeballed: FULL SIZE, the
     * player skin textured with the race body pass, NO green outline (that is the mini clone's ally marker), and the
     * owner name plate above each. The entity is spawned and dressed reflectively so this harness (also compiled without
     * the Space module on the classpath) never names a Space class.
     */
    private void buildAvatar()
    {
        step(40, () -> onServer(this::spawnAvatarRow));
        step(10, () -> lookAtScene(0.0D, 1.2D, -7.0D));
        step(4, () -> lookAtScene(0.0D, 1.2D, -7.0D));
        step(4, () -> lookAtScene(0.0D, 1.2D, -7.0D));
        step(2, () -> grab("avatar", "pair_front",
                "Two owner-avatar defenders (namekian + frostdemon look) over the player's skin: FULL SIZE, race-tinted, "
                        + "NO outline, owner name plate above each"));
        step(2, () -> lookAtScene(-2.5D, 1.2D, -4.0D));
        step(3, () -> lookAtScene(-2.5D, 1.2D, -4.0D));
        step(2, () -> grab("avatar", "namekian_closeup", "Namekian-look avatar close up (green race body over the skin)"));
        step(2, () -> lookAtScene(2.5D, 1.2D, -4.0D));
        step(3, () -> lookAtScene(2.5D, 1.2D, -4.0D));
        step(2, () -> grab("avatar", "frostdemon_closeup", "Frost Demon-look avatar close up (layered race body over the skin)"));
    }

    private void spawnAvatarRow(ServerPlayer sp)
    {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(
                new ResourceLocation("dmz_ragnarok", "planet_owner_avatar"));
        if (type == null)
        {
            index.add("avatar | (setup) | FAILED: planet_owner_avatar entity type missing");
            return;
        }
        double bx = sp.getX();
        double by = sp.getY();
        double rowZ = sp.getZ() + 7.0D;
        sceneX = bx;
        sceneY = by + 1.2D;
        sceneZ = rowZ;
        // each row: {race, ownerName, body1, body2, body3, hair}
        String[] races = { "namekian", "frostdemon" };
        String[] owners = { "NamekOwner", "FrostOwner" };
        int[][] colors =
        {
            { 0x4CAF50, 0x2E7D32, 0xFFFFFF, 0x8D6E63 },
            { 0x7E57C2, 0xEDEDED, 0x5E35B1, 0x000000 },
        };
        int spawned = 0;
        for (int i = 0; i < races.length; ++i)
        {
            Entity e = type.create(sp.serverLevel());
            if (!(e instanceof net.minecraft.world.entity.Mob m))
            {
                if (e != null) e.discard();
                continue;
            }
            double x = bx + (i - (races.length - 1) / 2.0D) * 2.5D;
            // yaw 0 so the avatar faces the camera (which is placed on the -Z side by lookAtScene), showing the front race
            // parts (namekian antennae, frost-demon horns) rather than its back.
            m.moveTo(x, by, rowZ, 0.0F, 0.0F);
            m.setYHeadRot(0.0F);
            m.setYBodyRot(0.0F);
            m.setNoGravity(true);
            m.setNoAi(true);
            m.setPersistenceRequired();
            try
            {
                m.getClass().getMethod("setRaceAppearance", String.class, int.class, int.class, int.class, int.class,
                        int.class).invoke(m, races[i], 0, colors[i][0], colors[i][1], colors[i][2], colors[i][3]);
                // owner = the host player, so the client resolves a real skin (this dev account) under the race pass.
                m.getClass().getMethod("setOwner", java.util.UUID.class, String.class).invoke(m, sp.getUUID(), owners[i]);
            }
            catch (Throwable t)
            {
                index.add("avatar | (setup) | reflection dress failed for " + races[i] + ": " + t);
            }
            m.setCustomName(net.minecraft.network.chat.Component.literal(owners[i]));
            m.setCustomNameVisible(true);
            if (sp.serverLevel().addFreshEntity(m)) spawned++;
        }
        index.add("avatar | (setup) | spawned " + spawned + "/" + races.length + " owner-avatar defenders");
    }

    // ---------------------------------------------------------------- STARMAP scenario (open, select, zoom, frame time)

    private void buildStarmap()
    {
        // give the host player a Super dragon radar first, so the star map REVEALS the seven super bodies (holdsSuperRadar).
        // Server-side give, which syncs to the client inventory the map reads.
        step(6, () -> onServer(this::giveSuperRadar));
        step(6, this::openStarMap);
        step(24, () -> { });   // let init() + refreshBodies() populate the chart
        step(2, this::starMapRecenter);
        step(8, () -> { });
        step(2, () -> grab("starmap", "zoomed_out", "Star map zoomed out: distant systems as icons with real textures"));
        step(2, () -> grab("starmap", "super_bodies",
                "With the Super radar held: the seven super dragon balls drawn as orange orbs (labelled) near the centre"));
        step(2, this::starMapSelectFirstSystem);
        step(8, () -> { });
        step(2, () -> grab("starmap", "system_selected",
                "A system selected and framed: its planets orbiting THAT sun"));
        // (i) frame time of the SELECTED, static system view over ~5 s.
        step(2, this::startFrameSampling);
        step(100, () -> { });
        step(2, () -> stopFrameSampling("starmap (i) selected system, static"));
        step(2, () -> grab("starmap", "system_selected_after", "Selected system after the static sampling window"));
        // (ii) frame time under CONTINUOUS zoom over ~5 s (50 nudges x 2 ticks).
        step(2, this::startFrameSampling);
        for (int i = 0; i < 50; ++i)
        {
            final int k = i;
            step(2, () -> starMapNudgeZoom(k));
        }
        step(2, () -> stopFrameSampling("starmap (ii) continuous zoom"));
        step(2, () -> grab("starmap", "zoom_end", "Star map after continuous zoom in/out"));
        step(2, () -> { keepScreen = false; Minecraft.getInstance().setScreen(null); });
    }

    // Give the host player a Super dragon radar (DMZ registers it under the bare path "super_dball_radar" in its own
    // namespace, so match on the path exactly as the star map's holdsSuperRadar and RadarDimensionReport do). Reveals the
    // super bodies on the star map.
    private void giveSuperRadar(ServerPlayer sp)
    {
        net.minecraft.world.item.Item radar = null;
        for (net.minecraft.world.item.Item item : ForgeRegistries.ITEMS)
        {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id != null && "super_dball_radar".equals(id.getPath()))
            {
                radar = item;
                break;
            }
        }
        if (radar == null)
        {
            index.add("starmap | (radar) | NOTE: super_dball_radar item not found; super bodies will stay hidden");
            return;
        }
        sp.getInventory().add(new net.minecraft.world.item.ItemStack(radar));
        index.add("starmap | (radar) | gave the host player a " + ForgeRegistries.ITEMS.getKey(radar));
    }

    private void openStarMap()
    {
        try
        {
            Class<?> c = Class.forName("net.shurui.shuruisutilities.client.space.starmap.SpaceStarMapScreen");
            Screen s = (Screen) c.getDeclaredConstructor().newInstance();
            keepScreen = true;
            Minecraft.getInstance().setScreen(s);
            index.add("starmap | (open) | opened "
                    + (Minecraft.getInstance().screen == null ? "null" : Minecraft.getInstance().screen.getClass().getSimpleName()));
        }
        catch (Throwable t)
        {
            index.add("starmap | (open) | FAILED: " + t);
            LOGGER.warn("[VisualTest] starmap open failed.", t);
        }
    }

    private void starMapRecenter()
    {
        try { invokeNoArg(Minecraft.getInstance().screen, "recenter"); }
        catch (Throwable t) { LOGGER.warn("[VisualTest] starmap recenter failed.", t); }
    }

    private void starMapSelectFirstSystem()
    {
        try
        {
            Screen scr = Minecraft.getInstance().screen;
            if (scr == null) { index.add("starmap | (select) | no screen"); return; }
            Object mapObj = getField(scr, "systemsByKey");
            if (!(mapObj instanceof java.util.Map<?, ?> map) || map.isEmpty())
            {
                index.add("starmap | (select) | NOTE: no systems available to select (systemsByKey empty)");
                return;
            }
            java.util.Map.Entry<?, ?> first = map.entrySet().iterator().next();
            setField(scr, "selectedKey", String.valueOf(first.getKey()));
            try { invokeOneArg(scr, "focusSystem", first.getValue()); }
            catch (Throwable t) { LOGGER.warn("[VisualTest] starmap focusSystem failed.", t); }
            index.add("starmap | (select) | selected system key=" + first.getKey() + " of " + map.size() + " system(s)");
        }
        catch (Throwable t)
        {
            index.add("starmap | (select) | FAILED: " + t);
        }
    }

    private void starMapNudgeZoom(int k)
    {
        try
        {
            Screen scr = Minecraft.getInstance().screen;
            if (scr == null) return;
            Object z = getField(scr, "zoom");
            double zoom = z instanceof Double d ? d : 0.01;
            // oscillate: zoom in for the first half of each 20-step cycle, out for the second, so it is genuinely continuous.
            double factor = ((k % 20) < 10) ? 1.10 : (1.0 / 1.10);
            zoom *= factor;
            try
            {
                Object clamped = invokeOneArg(scr, "clampZoom", zoom);
                if (clamped instanceof Double cd) zoom = cd;
            }
            catch (Throwable ignored) { }
            setField(scr, "zoom", zoom);
        }
        catch (Throwable t) { LOGGER.warn("[VisualTest] starmap zoom nudge failed.", t); }
    }

    private void startFrameSampling()
    {
        frameDeltas.clear();
        frameSampleLastNanos = 0L;
        frameSampling = true;
    }

    private void stopFrameSampling(String label)
    {
        frameSampling = false;
        List<Long> ds = new ArrayList<>(frameDeltas);
        if (ds.isEmpty())
        {
            index.add("starmap | (frametime) | " + label + ": NO frames sampled");
            return;
        }
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        double sum = 0;
        for (long d : ds) { min = Math.min(min, d); max = Math.max(max, d); sum += d; }
        double avgMs = (sum / ds.size()) / 1.0E6;
        double minMs = min / 1.0E6;
        double maxMs = max / 1.0E6;
        double avgFps = avgMs > 0 ? 1000.0 / avgMs : 0;
        index.add(String.format(Locale.ROOT,
                "starmap | (frametime) | %s: frames=%d min=%.2fms avg=%.2fms max=%.2fms (~%.0f fps avg)",
                label, ds.size(), minMs, avgMs, maxMs, avgFps));
    }

    // ---------------------------------------------------------------- SPACEVIEW scenario (open-space suns + pod wake)

    private void buildSpaceView()
    {
        step(6, () -> sendCommand(Minecraft.getInstance(), "gamemode creative"));
        step(60, () -> onServer(this::enterSpace));
        step(40, () -> { });
        // suns shots FIRST, before mounting, from the void at (0,200,0). A dark sky so the distant system suns read.
        step(4, () -> spaceForceDay(0.05F));
        step(2, () -> lookSky(0.0F, -5.0F));
        step(8, () -> lookSky(0.0F, -5.0F));
        step(2, () -> grab("space", "open_suns_yaw0", "Open space (void dim): distant system suns near the horizon, yaw 0"));
        for (float yaw : new float[] { 90.0F, 180.0F, 270.0F })
        {
            step(2, () -> lookSky(yaw, -5.0F));
            step(5, () -> lookSky(yaw, -5.0F));
            final int y = (int) yaw;
            step(1, () -> grab("space", "open_suns_yaw" + y, "Open space distant suns, yaw " + y));
        }
        // look straight up too so a body directly overhead is caught.
        step(2, () -> lookSky(0.0F, -80.0F));
        step(4, () -> lookSky(0.0F, -80.0F));
        step(1, () -> grab("space", "open_suns_up", "Open space distant suns, looking up"));
        // VERTICAL VARIANCE shots: systems now spawn at clearly different heights, so sweep the pitch at a couple of
        // bearings to catch systems ABOVE (look up) and BELOW (look down) the player, reading as a sun with small planets
        // around it rather than a flat glowing ball. This is the "some systems higher/lower" + "systems as sun+planets" proof.
        for (float yaw : new float[] { 45.0F, 225.0F })
        {
            final int y = (int) yaw;
            // gentle pitches near the horizon: at system distances (~8-30k) the height spread (systems at Y 650..1550 vs
            // this Y 900 plane) is only a few degrees of elevation, so a near-horizon look catches the high and the low
            // systems reading as sun + planet clusters at slightly different elevations.
            step(2, () -> lookSky(yaw, -12.0F));
            step(5, () -> lookSky(yaw, -12.0F));
            step(1, () -> grab("space", "systems_high_yaw" + y,
                    "Systems just above the horizon, yaw " + y + ": distant systems as sun + planets at varied heights"));
            step(2, () -> lookSky(yaw, 12.0F));
            step(5, () -> lookSky(yaw, 12.0F));
            step(1, () -> grab("space", "systems_low_yaw" + y,
                    "Systems just below the horizon, yaw " + y + ": distant systems as sun + planets at varied heights"));
        }
        // pod wake: spawn a pod at the player, MOUNT the player (a piloted pod, so the trail resolves the pilot's aura
        // colour), drive it forward so the client wake samples a streak, and shoot it in third person from behind and side.
        step(20, () -> onServer(this::spawnMovingPod));
        step(6, () -> { });
        for (int i = 0; i < 44; ++i)
        {
            final int k = i;
            step(2, () -> onServer(sp -> pushPod(sp, k)));
            if (i == 20)
                step(1, () -> podCam(180.0F));   // camera behind the pod (third person back), looking along travel
            if (i == 26)
                step(1, () -> grab("space", "pod_wake_behind",
                        "Piloted pod under motion, camera BEHIND: exhaust straight back, aura-coloured wake ribbon trailing"));
            if (i == 32)
                step(1, () -> podCam(90.0F));     // camera to the side
            if (i == 38)
                step(1, () -> grab("space", "pod_wake_side",
                        "Piloted pod under motion, camera to the SIDE: wake ribbon and rear exhaust plume"));
        }
        step(2, () -> onServer(this::dismountAndRemovePod));
        step(2, () -> spaceForceDay(-1.0F));
    }

    /** Orient the riding player's view yaw so the third-person camera sits at the requested bearing around the pod. */
    private void podCam(float yaw)
    {
        Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK);
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        p.setYRot(yaw);
        p.yRotO = yaw;
        p.setXRot(0.0F);
        p.xRotO = 0.0F;
        p.setYHeadRot(yaw);
        p.setYBodyRot(yaw);
    }

    private void enterSpace(ServerPlayer sp)
    {
        MinecraftServer server = sp.getServer();
        ServerLevel space = server == null ? null
                : server.getLevel(net.shurui.shuruisutilities.world.space.SpaceKeys.SPACE);
        if (space == null)
        {
            index.add("space | (enter) | FAILED: space dimension not available");
            return;
        }
        // clear any planet SurfaceTravelData left by an earlier landing scenario, or the surface/space tick relocates the
        // player straight back onto that planet (which is what put an earlier open-space shot on a stone surface).
        try { net.shurui.shuruisutilities.world.space.SurfaceTravelData.clear(sp); }
        catch (Throwable ignored) { }
        sp.getAbilities().mayfly = true;
        sp.getAbilities().flying = true;
        sp.onUpdateAbilities();
        // Enter ABOVE the descend-out return altitude (SpaceTravelModule.returnAltitude, 288) so the space tick never
        // pulls the player back down (the old Y=200 sat below the return floor and was evicted at once), and CLEAR of the
        // central sun: the sun sits at (0, 900, 0) with a large 3x drawn body, so a camera near the origin column ends up
        // INSIDE the drawn sun and every shot fills with its glow. Sit on the body plane a few thousand blocks out on X,
        // well past the sun's danger radius (480) and drawn body, but still inside the reserved inner system (no body to
        // land on), so the player floats on the plane with the distant systems (which start ~12000 out) reading all around
        // and at their varied heights above/below this plane.
        double entryX = 3000.0D;
        double entryY = 900.0D;
        sp.teleportTo(space, entryX, entryY, 0.5D, sp.getYRot(), sp.getXRot());
        sceneX = entryX;
        sceneY = entryY;
        sceneZ = 0.5D;
        index.add("space | (enter) | cleared surface data, teleported to the void space dimension at (" + (int) entryX
                + ", " + (int) entryY + ", 0): above the return floor and clear of the central sun, so the player stays "
                + "and the distant systems read");
    }

    private void spawnMovingPod(ServerPlayer sp)
    {
        podEntityId = -1;
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("dragonminez", "spacepod"));
        if (type == null)
        {
            index.add("space | (pod) | NOTE: dragonminez:spacepod entity type missing; skipping pod wake shot");
            return;
        }
        Entity e = type.create(sp.serverLevel());
        if (e == null)
        {
            index.add("space | (pod) | NOTE: spacepod create() returned null");
            return;
        }
        double px = sp.getX();
        double py = sp.getY();
        double pz = sp.getZ();
        e.moveTo(px, py, pz, 90.0F, 0.0F);
        e.setNoGravity(true);
        if (e instanceof net.minecraft.world.entity.Mob m) m.setNoAi(true);
        if (sp.serverLevel().addFreshEntity(e))
        {
            podEntityId = e.getId();
            // MOUNT the player so the pod is piloted: PodTrailRenderer resolves the wake colour from the pilot's aura.
            boolean rode = sp.startRiding(e, true);
            sceneX = px;
            sceneY = py;
            sceneZ = pz;
            index.add("space | (pod) | spawned dragonminez:spacepod id=" + podEntityId + " mounted=" + rode);
        }
    }

    private void pushPod(ServerPlayer sp, int k)
    {
        if (podEntityId < 0) return;
        Entity pod = sp.serverLevel().getEntity(podEntityId);
        if (pod == null) return;
        // move +x at a steady clip so the client wake samples a straight streak; the mounted player rides along.
        pod.setNoGravity(true);
        double step = 0.8D;
        pod.setPos(pod.getX() + step, pod.getY(), pod.getZ());
        pod.setDeltaMovement(step, 0.0D, 0.0D);
        pod.setYRot(90.0F);
        pod.yRotO = 90.0F;
        pod.hasImpulse = true;
        sceneX = pod.getX();
        sceneY = pod.getY();
        sceneZ = pod.getZ();
    }

    private void dismountAndRemovePod(ServerPlayer sp)
    {
        sp.stopRiding();
        if (podEntityId < 0) return;
        Entity pod = sp.serverLevel().getEntity(podEntityId);
        if (pod != null) pod.discard();
        podEntityId = -1;
    }

    // ---------------------------------------------------------------- WRAP + MEMORY scenario (land v4, walk off edge)

    private void buildWrap()
    {
        step(6, () -> sendCommand(Minecraft.getInstance(), "gamemode creative"));
        // land on a fresh (v4) overworld planet; it stamps as it lands.
        step(80, () -> onServer(sp -> spaceDebugLand(sp, "landWetPlanet", "wrap")));
        // wait for the stamp to complete (poll the generated flag) up to ~15 s.
        step(4, () -> {});
        for (int i = 0; i < 40; ++i)
            step(6, () -> wrapWaitForStamp());
        // record the pre-move position, then teleport the player just past the +x real edge and let tickOnSurface wrap.
        step(4, () -> onServer(this::wrapMoveOffEdge));
        for (int i = 0; i < 12; ++i)
            step(2, () -> {});
        step(4, () -> onServer(this::wrapCheckRelocated));
        step(4, () -> onServer(this::wrapCheckTerrainContinues));
        // MEMORY: log heap + loaded surface chunk counts, then hold ~40 s so an external jcmd can grab a class histogram.
        step(6, () -> onServer(this::logMemoryAndChunks));
        step(2, () -> index.add("mem | (hold) | holding ~40s for external jcmd (GC.heap_info / GC.class_histogram)"));
        for (int i = 0; i < 20; ++i)
            step(40, () -> {});
    }

    private volatile String wrapPlanetId = "";
    private volatile boolean wrapStampDone = false;
    private volatile double wrapPreX = 0.0D;
    private volatile double[] wrapGeom = null;

    private void spaceDebugLand(ServerPlayer sp, String method, String scenario)
    {
        Object id = spaceDebug(method, new Class<?>[] { ServerPlayer.class }, new Object[] { sp });
        wrapPlanetId = id == null ? "" : String.valueOf(id);
        wrapStampDone = false;
        index.add(scenario + " | (land) | " + method + " -> planet id=" + (wrapPlanetId.isEmpty() ? "NONE" : wrapPlanetId));
    }

    private void wrapWaitForStamp()
    {
        if (wrapStampDone || wrapPlanetId.isEmpty()) return;
        onServer(sp ->
        {
            double[] g = (double[]) spaceDebug("planetWrapGeom",
                    new Class<?>[] { MinecraftServer.class, String.class },
                    new Object[] { sp.getServer(), wrapPlanetId });
            if (g != null && g.length >= 5 && g[4] > 0.5D)
            {
                wrapGeom = g;
                wrapStampDone = true;
                index.add(String.format(Locale.ROOT,
                        "wrap | (stamp) | complete: centre=(%.1f,%.1f) half=%.0f size=%.0f", g[0], g[1], g[2], g[3]));
            }
        });
    }

    private void wrapMoveOffEdge(ServerPlayer sp)
    {
        if (!wrapStampDone || wrapGeom == null)
        {
            index.add("wrap | (move) | SKIPPED: stamp never completed");
            return;
        }
        wrapPreX = sp.getX();
        double centreX = wrapGeom[0];
        double centreZ = wrapGeom[1];
        double half = wrapGeom[2];
        // just past the +x edge, beyond the trigger inset (6), so tickOnSurface's seam wrap fires.
        double targetX = centreX + half + 10.0D;
        sp.teleportTo(sp.serverLevel(), targetX, sp.getY(), centreZ, sp.getYRot(), sp.getXRot());
        index.add(String.format(Locale.ROOT,
                "wrap | (move) | teleported to just past +x edge x=%.1f (edge=%.1f), was x=%.1f",
                targetX, centreX + half, wrapPreX));
    }

    private void wrapCheckRelocated(ServerPlayer sp)
    {
        if (!wrapStampDone || wrapGeom == null) return;
        double centreX = wrapGeom[0];
        double half = wrapGeom[2];
        double size = wrapGeom[3];
        double x = sp.getX();
        // after the wrap the player should sit strictly inside the square near the OPPOSITE (-x) edge, roughly one full
        // planet width west of where they crossed.
        boolean insideSquare = x > centreX - half && x < centreX + half;
        boolean nearOppositeEdge = x < centreX;   // moved from the +x side to the -x half
        boolean pass = insideSquare && nearOppositeEdge;
        index.add(String.format(Locale.ROOT,
                "wrap | (relocate) | %s: x=%.1f now inside (%.1f..%.1f) and on the -x half (centre=%.1f, width=%.0f)",
                pass ? "PASS" : "FAIL", x, centreX - half, centreX + half, centreX, size));
    }

    private void wrapCheckTerrainContinues(ServerPlayer sp)
    {
        if (!wrapStampDone) return;
        Object solid = spaceDebug("marginSolidColumns",
                new Class<?>[] { MinecraftServer.class, String.class, int.class },
                new Object[] { sp.getServer(), wrapPlanetId, 64 });
        int cols = solid instanceof Integer ? (Integer) solid : -1;
        index.add("wrap | (terrain-continues) | solid columns in the 64 blocks past the +x edge: " + cols + "/64 "
                + (cols >= 60 ? "PASS" : (cols >= 1 ? "PARTIAL" : "FAIL")));
    }

    private void logMemoryAndChunks(ServerPlayer sp)
    {
        try
        {
            Runtime rt = Runtime.getRuntime();
            long used = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
            long max = rt.maxMemory() / (1024L * 1024L);
            int surfaceChunks = -1;
            try
            {
                ServerLevel surface = sp.getServer()
                        .getLevel(net.shurui.shuruisutilities.world.space.SpaceKeys.SURFACE);
                if (surface != null) surfaceChunks = surface.getChunkSource().getLoadedChunksCount();
            }
            catch (Throwable ignored) { }
            int total = 0;
            for (ServerLevel lvl : sp.getServer().getAllLevels())
                total += lvl.getChunkSource().getLoadedChunksCount();
            index.add("mem | (heap) | used=" + used + "MB / max=" + max + "MB; loaded chunks planet_surface="
                    + surfaceChunks + " all-dims=" + total + " (jvm pid=" + ProcessHandle.current().pid() + ")");
        }
        catch (Throwable t)
        {
            index.add("mem | (heap) | failed: " + t);
        }
    }

    // ---------------------------------------------------------------- CHORUS / item-spam scenario

    private void buildChorus()
    {
        step(6, () -> sendCommand(Minecraft.getInstance(), "gamemode creative"));
        step(80, () -> onServer(sp -> chorusLandAndCount(sp, "END")));
        step(30, () -> {});
        step(4, () -> onServer(sp -> chorusCount(sp, "END")));
        step(80, () -> onServer(sp -> chorusLandAndCount(sp, "NETHER")));
        step(30, () -> {});
        step(4, () -> onServer(sp -> chorusCount(sp, "NETHER")));
    }

    private volatile String chorusEndId = "";
    private volatile String chorusNetherId = "";

    private void chorusLandAndCount(ServerPlayer sp, String theme)
    {
        Object id = spaceDebug("landThemePlanet",
                new Class<?>[] { ServerPlayer.class, String.class }, new Object[] { sp, theme });
        String planet = id == null ? "" : String.valueOf(id);
        if ("END".equals(theme)) chorusEndId = planet; else chorusNetherId = planet;
        index.add("chorus | (land " + theme + ") | planet id=" + (planet.isEmpty() ? "NONE" : planet));
    }

    private void chorusCount(ServerPlayer sp, String theme)
    {
        String planet = "END".equals(theme) ? chorusEndId : chorusNetherId;
        if (planet.isEmpty()) { index.add("chorus | (count " + theme + ") | SKIPPED: no planet"); return; }
        Object n = spaceDebug("surfaceItemCount",
                new Class<?>[] { MinecraftServer.class, String.class, double.class },
                new Object[] { sp.getServer(), planet, 400.0D });
        int items = n instanceof Integer ? (Integer) n : -1;
        index.add("chorus | (items " + theme + ") | loose ItemEntity within 400 blocks of centre: " + items
                + " " + (items == 0 ? "PASS (~0)" : (items <= 5 ? "OK (<=5)" : "CHECK (item spam?)")));
    }

    // ---------------------------------------------------------------- space-module reflection bridge

    /** Call a static method on the Space module's SpaceSurfaceDebug by name (the module is absent from the core set). */
    private Object spaceDebug(String method, Class<?>[] sig, Object[] args)
    {
        try
        {
            Class<?> c = Class.forName("net.shurui.shuruisutilities.space.SpaceSurfaceDebug");
            return c.getMethod(method, sig).invoke(null, args);
        }
        catch (Throwable t)
        {
            LOGGER.warn("[VisualTest] SpaceSurfaceDebug.{} failed.", method, t);
            index.add("(space-debug) | " + method + " reflection FAILED: " + t);
            return null;
        }
    }

    private static Object invokeOneArg(Object owner, String name, Object arg) throws Exception
    {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass())
        {
            for (Method m : c.getDeclaredMethods())
            {
                if (m.getName().equals(name) && m.getParameterCount() == 1)
                {
                    m.setAccessible(true);
                    return m.invoke(owner, arg);
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Item itemById(String id)
    {
        try { return ForgeRegistries.ITEMS.getValue(new ResourceLocation(id)); }
        catch (Throwable t) { return null; }
    }

    private static Block blockById(String id)
    {
        try { return ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id)); }
        catch (Throwable t) { return null; }
    }

    // ---------------------------------------------------------------- step helpers

    private List<Cosmetic> itemsFor(CosmeticSlot slot)
    {
        List<Cosmetic> out = new ArrayList<>();
        for (Cosmetic c : catalogue)
            if (c.slot == slot)
                out.add(c);
        return out;
    }

    private void step(int waitAfter, Runnable body)
    {
        script.add(new Step(waitAfter, body));
    }

    private void shootPlayer(String scenario, Cosmetic c, Angle a)
    {
        shootPlayer(scenario, c, a, null);
    }

    /** Enqueue: orient the local player (camera decoupled from body), settle, then grab. */
    private void shootPlayer(String scenario, Cosmetic c, Angle a, String note)
    {
        // Hold the orientation for a few ticks so the model settles, then shoot.
        step(1, () -> { Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK); holdPlayer(a.bodyYaw); });
        step(1, () -> holdPlayer(a.bodyYaw));
        step(2, () -> holdPlayer(a.bodyYaw));
        String desc = c.displayName + " (" + c.id + "), " + a.label + (note == null ? "" : ", " + note);
        step(1, () -> grab(scenario, c.id + "_" + a.label + (note == null ? "" : "_" + sanitise(note)), desc));
    }

    /** Enqueue: place the camera at an orbit position looking at Duke, settle, then grab. */
    private void shootDuke(String note, Angle a)
    {
        step(4, () -> aimAtDuke(a));
        step(2, () -> aimAtDuke(a));
        step(1, () -> grab("duke", "duke_" + a.label + "_" + sanitise(note), "Duke Snipperjack: " + note + ", " + a.label));
    }

    // ---------------------------------------------------------------- runtime actions

    private void runScript()
    {
        // DragonMineZ opens its character-creation screen on join when the client still thinks the player has no
        // character; once our server-side creation has synced that is stale, but the screen does not close itself.
        // Any open screen would sit over every shot (hideGui only hides the HUD), so dismiss it each tick during the
        // run. By now the StatsSyncS2C has landed, so DMZ does not reopen it.
        Minecraft mc = Minecraft.getInstance();
        // The senzu scenario deliberately keeps DMZ's utility menu open, and the halloween scenario keeps its own
        // tooltip cards and the event editor up; only auto-dismiss screens otherwise.
        if (mc.screen != null && !holdScreen && !keepScreen)
            mc.setScreen(null);
        if (waitTicks > 0) { waitTicks--; return; }
        if (script.isEmpty()) { finish(); return; }
        Step s = script.poll();
        try
        {
            s.body.run();
        }
        catch (Throwable t)
        {
            LOGGER.warn("[VisualTest] A step threw (continuing).", t);
        }
        waitTicks = s.waitAfter;
    }

    /** Decouple the camera (look yaw) from the model (body/head yaw) so a fixed rear camera sees every side. */
    private void holdPlayer(float bodyYaw)
    {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        p.setYRot(0.0F);
        p.yRotO = 0.0F;
        p.setXRot(0.0F);
        p.xRotO = 0.0F;
        p.setYHeadRot(bodyYaw);
        p.yHeadRotO = bodyYaw;
        p.setYBodyRot(bodyYaw);
        p.yBodyRotO = bodyYaw;
    }

    private void aimAtDuke(Angle a)
    {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Entity duke = findDuke(mc);
        if (p == null || duke == null) return;
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        double radius = 5.0D;
        double rad = Math.toRadians(a.orbitDeg);
        double cx = duke.getX() + Math.sin(rad) * radius;
        double cz = duke.getZ() - Math.cos(rad) * radius;
        double cy = duke.getY() + 1.6D;
        double dx = duke.getX() - cx;
        double dy = (duke.getY() + 1.0D) - cy;
        double dz = duke.getZ() - cz;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180D / Math.PI)) - 90.0F;
        float pitch = (float) (-(Mth.atan2(dy, flat) * (180D / Math.PI)));
        // Set the client camera immediately so the frame is right, and teleport authoritatively on the server so the
        // client is not rubber-banded back before the shot. SU overrides /tp, so this uses the API, not a command.
        p.setPos(cx, cy, cz);
        p.setDeltaMovement(0, 0, 0);
        p.setYRot(yaw);
        p.yRotO = yaw;
        p.setXRot(pitch);
        p.xRotO = pitch;
        p.setYHeadRot(yaw);
        p.setYBodyRot(yaw);
        final double fx = cx, fy = cy, fz = cz;
        final float fyaw = yaw, fpitch = pitch;
        onServer(sp -> sp.teleportTo(sp.serverLevel(), fx, fy, fz, fyaw, fpitch));
    }

    /** Provoke Duke as a player hit would, server side (first hit taunts, second rises). */
    private void dukeProvoke()
    {
        dukeServer((sp, duke) -> duke.hurt(sp.damageSources().playerAttack(sp), 1.0F));
    }

    /** Drop Duke to a fraction of max health so his tick advances the phase ladder. */
    private void dukeSetHealthFraction(float fraction)
    {
        dukeServer((sp, duke) -> duke.setHealth(Math.max(1.0F, duke.getMaxHealth() * fraction)));
    }

    private void dukeServer(java.util.function.BiConsumer<ServerPlayer, DukeSnipperjackEntity> body)
    {
        onServer(sp ->
        {
            List<DukeSnipperjackEntity> found = sp.serverLevel().getEntitiesOfClass(DukeSnipperjackEntity.class,
                    sp.getBoundingBox().inflate(96.0D));
            if (!found.isEmpty())
                body.accept(sp, found.get(0));
        });
    }

    private Entity findDuke(Minecraft mc)
    {
        if (mc.level == null) return null;
        for (Entity e : mc.level.entitiesForRendering())
            if (e instanceof DukeSnipperjackEntity)
                return e;
        return null;
    }

    private void grab(String scenario, String name, String description)
    {
        try
        {
            Minecraft mc = Minecraft.getInstance();
            NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            String file = scenario + "_" + sanitise(name) + ".png";
            File target = new File(outDir, file);
            img.writeToFile(target);
            img.close();
            index.add(scenario + " | " + file + " | " + description);
            LOGGER.info("[VisualTest] shot {} -> {}", scenario, file);
        }
        catch (Throwable t)
        {
            LOGGER.warn("[VisualTest] Screenshot failed for {}/{}.", scenario, name, t);
        }
    }

    // ---------------------------------------------------------------- server-thread mutations

    private void onServer(java.util.function.Consumer<ServerPlayer> body)
    {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        LocalPlayer lp = mc.player;
        if (server == null || lp == null) return;
        java.util.UUID uuid = lp.getUUID();
        server.execute(() ->
        {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) return;
            try { body.accept(sp); }
            catch (Throwable t) { LOGGER.warn("[VisualTest] Server mutation failed.", t); }
        });
    }

    private void serverEquip(CosmeticSlot slot, String id) { onServer(sp -> WardrobeManager.equip(sp, slot, id)); }

    private void serverUnequip(CosmeticSlot slot) { onServer(sp -> WardrobeManager.unequip(sp, slot)); }

    private void serverPreview(String id, CosmeticSlot slot)
    {
        onServer(sp -> net.shurui.shuruisutilities.api.key.CosmeticHooks.get().preview(sp, id, slot.inTrigger()));
    }

    private void serverMountSummon(boolean summon)
    {
        onServer(sp ->
        {
            if (summon) CosmeticMountManager.toggle(sp);
            else CosmeticMountManager.recall(sp);
        });
    }

    private void serverKiWeapon(boolean active) { onServer(sp -> DmzCharacterSetup.setKiWeapon(sp, active)); }

    // ---------------------------------------------------------------- finish

    private void finish()
    {
        if (phase == Phase.DONE) return;
        phase = Phase.DONE;
        try
        {
            java.nio.file.Files.write(new File(outDir, "index.txt").toPath(), index);
            LOGGER.info("[VisualTest] Wrote index.txt with {} shot(s) to {}", index.size() - 3, outDir.getAbsolutePath());
        }
        catch (Throwable t)
        {
            LOGGER.warn("[VisualTest] Could not write index.txt.", t);
        }
        // Stop cleanly. Minecraft.stop() ends the run loop this frame and its shutdown path halts and saves the
        // integrated server, exactly as closing the window does, so the Gradle runClient task exits.
        LOGGER.info("[VisualTest] Complete. Quitting client.");
        Minecraft.getInstance().stop();
    }

    private static void sendCommand(Minecraft mc, String command)
    {
        if (mc.player != null && mc.player.connection != null)
            mc.player.connection.sendCommand(command);
    }

    private static String sanitise(String s)
    {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    // ---------------------------------------------------------------- small types

    private record Cosmetic(String id, CosmeticSlot slot, String displayName) { }

    private record Step(int waitAfter, Runnable body) { }

    /** A camera/model orientation. For worn scenarios only bodyYaw matters; for Duke only orbitDeg matters. */
    private record Angle(String label, float bodyYaw, double orbitDeg)
    {
        // bodyYaw is calibrated to the third-person rear camera pinned at look-yaw 0: body 0 faces the camera
        // (front), body 180 faces away (back). Verified from real shots.
        static final Angle BACK = new Angle("back", 180.0F, 0);
        static final Angle FRONT = new Angle("front", 0.0F, 180);
        static final Angle LEFT = new Angle("left", 270.0F, 90);
        static final Angle RIGHT = new Angle("right", 90.0F, 270);
        static final Angle THREE_QUARTER = new Angle("three_quarter", 45.0F, 45);

        /** The angle set for a worn cosmetic: enough to read it without a shot per item taking forever. */
        static final Angle[] WEARABLE = { BACK, THREE_QUARTER, FRONT };
        /** The full orbit around Duke. */
        static final Angle[] ORBIT = { FRONT, RIGHT, BACK, LEFT, THREE_QUARTER };

        static Angle duke(int i) { return ORBIT[Math.floorMod(i, ORBIT.length)]; }
    }
}
