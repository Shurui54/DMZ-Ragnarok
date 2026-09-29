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

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.dev.sdu.entity.DukeSnipperjackEntity;
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
        Set<String> all = Set.of("back", "hand", "head", "anim", "duke", "mount", "pet");
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
            pump();
        }
        catch (Throwable t)
        {
            LOGGER.error("[VisualTest] Unhandled error in phase {}; finishing to avoid a stuck client.", phase, t);
            finish();
        }
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
        if (mc.screen != null)
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
