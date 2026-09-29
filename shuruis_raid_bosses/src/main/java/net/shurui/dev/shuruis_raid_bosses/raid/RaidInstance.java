package net.shurui.dev.shuruis_raid_bosses.raid;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import com.dragonminez.common.init.entities.sagas.DBSagasEntity;
import net.shurui.dev.shuruis_raid_bosses.data.RaidData;
import net.shurui.dev.shuruis_raid_bosses.dmz.DmzHooks;
import net.shurui.dev.shuruis_raid_bosses.region.Region;
import net.shurui.dev.shuruis_raid_bosses.reward.RewardManager;
import net.shurui.dev.shuruis_raid_bosses.util.Announcer;
import net.shurui.dev.shuruis_raid_bosses.util.TextUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One running or idle raid driven by its {@link RaidBossDef}: roster, spawned enemies, HUD bar, phase
 * timers, teleport/heal/scale, announcements, damage and kill tracking, rewards. Config and bounds read
 * live off the def, so GUI edits apply immediately. Handles all three {@link RaidType}s: STANDARD (one
 * scaled boss), PARALLEL_QUEST (MVP waves with an optional boss finale), BOSS_RUSH (delayed boss sequence).
 */
public class RaidInstance {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private final MinecraftServer server;
    private final String defId;

    private RaidState state = RaidState.IDLE;
    private final Set<UUID> signups = new LinkedHashSet<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final Map<UUID, ReturnPoint> signupLocations = new LinkedHashMap<>();
    private final List<UUID> participants = new ArrayList<>();
    /** how long winners are left in the arena before being sent home */
    private static final int VICTORY_HOLD_TICKS = 10 * 20;

    /** fell this raid (death-saved or died): locked out of the arena until it ends */
    private final Set<UUID> eliminated = new LinkedHashSet<>();

    /**
     * Fighters the server has told to START boss music, so the loop is edge triggered: a fighter is sent one
     * start when they first appear in the fight and one stop when they leave it. Reconciled every fighting tick
     * against who is present and alive, which is what makes a mid-fight reconnect restart the loop, a death or a
     * logout end it for that fighter alone, and one loop per client that is never stacked. Empty means nobody is
     * hearing music, e.g. the encounter has no {@code bossMusic} set.
     */
    private final Set<UUID> musicSent = new LinkedHashSet<>();

    /** every enemy/boss currently alive and tracked */
    private final Set<UUID> liveEnemies = new LinkedHashSet<>();
    /**
     * every ALLY currently alive. Its own set, not a flag on the enemy roster, because {@link #liveEnemies}
     * decides when a stage is cleared and the raid won; an ally in there would have to be killed before
     * anyone could win. Allies also earn no damage credit, wear a different glow, and target and confine on
     * their own passes.
     */
    private final Set<UUID> liveAllies = new LinkedHashSet<>();
    /**
     * enemies confirmed dead this stage, via a real {@link #onEntityDeath} or a resolvable-but-dead prune.
     * The ONLY signal that gates victory/stage-advance: an empty {@link #liveEnemies} counts as cleared only
     * when at least one removal was a real death. A roster emptied purely by unresolvable entities timing out
     * must never read as a win, see {@link #tickActive}.
     */
    private int stageDeaths;
    /**
     * per-id count of consecutive ticks {@link #enemy(UUID)} came back null (chased into an unloaded chunk,
     * changed dimension, or mid-transform before {@link #adoptTransformed} re-adds the new uuid). A null is
     * grace, not a death; only after {@link #NULL_GRACE_TICKS} do we give up, and that is a failure never a win.
     */
    private final Map<UUID, Integer> nullGraceTicks = new LinkedHashMap<>();
    /**
     * last-known position of each resolvable tracked enemy, refreshed every tick. Once a boss goes
     * unresolvable it can't report its own position, so this feeds the {@link VanishRecord} that
     * {@link #adoptTransformed}'s handoff heuristic matches against.
     */
    private final Map<UUID, Vec3> lastKnownPos = new LinkedHashMap<>();
    /**
     * recent "tracked boss vanished with NO death event" records: the fingerprint of a DMZ-native transform
     * discard (old form gone without dying, new form spawns nearby). {@link #adoptTransformed} matches a saga
     * boss within {@link #VANISH_TICK_WINDOW}/{@link #VANISH_RADIUS} and consumes the record, so one vanish
     * adopts at most one form.
     */
    private final java.util.Deque<VanishRecord> vanishRecords = new java.util.ArrayDeque<>();

    private record VanishRecord(long gameTick, Vec3 pos) {}
    private final Map<UUID, Double> accumDamage = new LinkedHashMap<>();
    private final Map<UUID, Integer> kills = new LinkedHashMap<>();

    private int hudBaselineCount;    // enemies alive right after a spawn batch (NPC-count bar denominator)
    private int phaseTimer;          // ticks remaining for COUNTDOWN / FINISHED
    /**
     * A win is held before fighters are sent home, so the FINISHED phase returns them when it ends. The
     * return used to fire the instant the boss's health hit zero, yanking a player out mid death animation,
     * before the drops and before they saw they had won, even though FINISHED already lasted ten seconds.
     */
    private boolean returnWhenFinished;
    private int activeTicks;         // ticks the encounter has been active
    private int rushIndex;           // BOSS_RUSH: index of the current/next stage
    private int rushDelayTicks;      // BOSS_RUSH: ticks until the next stage spawns (>0 = waiting)
    private int parallelWave;        // PARALLEL_QUEST: the wave currently being fought (1-based)
    private boolean parallelBossActive; // PARALLEL_QUEST: the boss finale has been spawned
    private long signupEndMillis;
    private int signupReminderTicks; // 0 = no repeat

    public RaidInstance(MinecraftServer server, String defId) {
        this(server, defId, null);
    }

    /**
     * An instance whose definition is HANDED IN rather than looked up by id: what lets a dimensional tear
     * run its own fight. It needs its own copy so two tears (or a tear and the scheduled raid of the same
     * boss) never share a roster, countdown or boss. {@code defId} becomes a synthetic key nobody collides
     * with, and {@link #def()} stops consulting {@link RaidData}, so a mid-fight edit to the stored raid
     * cannot undo the caller's arena override.
     *
     * @param defId a key unique to this run; {@link RaidDamageTracker} stamps it on every spawned enemy, so
     *              it must route back to THIS instance in {@link RaidManager}
     * @param fixedDef the definition to run, or null for the ordinary look-it-up-by-id behaviour
     */
    public RaidInstance(MinecraftServer server, String defId, RaidBossDef fixedDef) {
        this.server = server;
        this.defId = defId;
        this.fixedDef = fixedDef;
    }

    public String defId() {
        return defId;
    }

    /** non-null when this instance runs a handed-in definition (a tear), not a stored one */
    private final RaidBossDef fixedDef;

    /**
     * Told once when a run resolves: true for victory, false for any failure (timeout, wipe, unresolvable
     * boss, admin cancel). The owning tear uses it to announce, release its arena cell and unregister, which
     * it cannot do by polling {@link #state} alone because FINISHED is reached by both outcomes.
     */
    private java.util.function.Consumer<Boolean> outcomeListener;
    private boolean outcomeSent;

    /**
     * True when this run must say NOTHING of its own. A tear is one player's private trip: no sign-up,
     * nobody else can join, so raid-language announcements name an encounter no other player can act on. The
     * rift does the talking instead. Gating on the handed-in def silences every announcement, including ones
     * added later.
     */
    private boolean silent() {
        return fixedDef != null;
    }

    /**
     * True when this run is a rift (a tear), rather than an ordinary scheduled raid.
     *
     * <p>Same test as {@link #silent()} and deliberately a separate method: a rift is the only caller that hands in
     * its own definition ({@code RiftRun} deep-copies one per tear), so the two questions coincide today, but they
     * are different questions and a reader should not have to infer "is this a rift" from a method about chat.
     */
    private boolean isRift() {
        return fixedDef != null;
    }

    /** register the run-outcome listener; one per instance, a second call replaces the first */
    public void setOutcomeListener(java.util.function.Consumer<Boolean> listener) {
        this.outcomeListener = listener;
    }

    /**
     * Told each time a BOSS_RUSH stage has actually put its boss in the world. The owning tear uses it to
     * narrate phase changes to its lone fighter, which {@link #silent()} otherwise keeps it from hearing:
     * the raid-language stage announcement is suppressed for a tear because it names an encounter no other
     * player can act on, but the fighter still needs to know the fight has moved on. This fires REGARDLESS
     * of {@link #silent()} for exactly that reason, and carries no chat itself so the rift chooses its own
     * wording.
     *
     * @param stageNumber 1-based index of the stage that just spawned (matches the on-screen "%s/%s")
     * @param stageCount  total stages in the rush
     * @param bossName    the stage boss's raw display name, uncoloured, for the listener to format
     */
    @FunctionalInterface
    public interface StageListener {
        void onStage(int stageNumber, int stageCount, String bossName);
    }

    private StageListener stageListener;

    /** register the stage-advance listener; one per instance, a second call replaces the first */
    public void setStageListener(StageListener listener) {
        this.stageListener = listener;
    }

    /**
     * Fire the stage listener. Guarded because a listener throwing must never break the spawn loop it runs
     * inside: a phase notification failing is cosmetic, an unspawned boss is a stalled fight.
     */
    private void sendStage(int stageNumber, int stageCount, String bossName) {
        if (stageListener == null) return;
        try {
            stageListener.onStage(stageNumber, stageCount, bossName);
        } catch (Exception e) {
            LOGGER.error("[{}] Rift stage listener threw; the fight continues regardless.", defId, e);
        }
    }

    /**
     * Fire the outcome listener, at most once. Guarded because the failure paths can be reached twice in a
     * frame (a wipe that also times out), and a tear must not be told it lost twice.
     */
    private void sendOutcome(boolean won) {
        if (outcomeSent || outcomeListener == null) return;
        outcomeSent = true;
        try {
            outcomeListener.accept(won);
        } catch (Exception e) {
            LOGGER.error("[{}] Rift outcome listener threw; the run is resolved regardless.", defId, e);
        }
    }

    // raid drawn from this def's randomPool for the CURRENT run, or null for an ordinary raid. Chosen once
    // in begin() and cleared on reset, so every read during a run agrees on what is being fought.
    private String pickedDefId;
    private RaidBossDef pickedCache;

    private RaidBossDef def() {
        // a handed-in def wins outright, never merged: the whole point is that this run is not the stored
        // raid. It also skips the pool draw, which a tear resolves for itself before building an arena.
        if (fixedDef != null) {
            return fixedDef;
        }
        RaidBossDef host = RaidData.get(server).getDef(defId);
        if (pickedDefId == null || host == null) {
            return host;
        }
        if (pickedCache != null) {
            return pickedCache;
        }
        RaidBossDef picked = RaidData.get(server).getDef(pickedDefId);
        if (picked == null) {
            // pooled raid deleted mid-run; fall back to the host rather than dropping the fight
            pickedDefId = null;
            return host;
        }
        // deep copy through NBT, not a field merge: the drawn raid runs AS ITSELF, so every content field
        // comes along for free. Only the three things the host owns are put back: id (so lookups and
        // instance keys resolve), arena and player spawn (the whole point of a random arena).
        RaidBossDef eff = RaidBossDef.load(picked.save());
        eff.id = host.id;
        eff.arena = host.arena;
        eff.playerSpawn = host.playerSpawn;
        pickedCache = eff;
        return eff;
    }

    /** Draw a raid from the pool, if this def is a selector. Called once as a run begins. */
    private void rollRandomRaid() {
        pickedDefId = null;
        pickedCache = null;
        RaidBossDef host = RaidData.get(server).getDef(defId);
        if (host == null || host.randomPool.isEmpty()) {
            return;
        }
        // Only ids that still resolve, so a deleted or renamed pool entry cannot roll an empty raid.
        java.util.List<String> usable = new java.util.ArrayList<>();
        for (String id : host.randomPool) {
            if (id != null && !id.isBlank() && RaidData.get(server).getDef(id) != null) {
                usable.add(id);
            }
        }
        if (usable.isEmpty()) {
            return;
        }
        pickedDefId = usable.get(new java.util.Random().nextInt(usable.size()));
    }

    public RaidBossDef definition() {
        return def();
    }

    public RaidState state() {
        return state;
    }

    public boolean isIdle() {
        return state == RaidState.IDLE;
    }

    public boolean isSignupOpen() {
        return state == RaidState.SIGNUP;
    }

    public boolean isSignedUp(UUID id) {
        return signups.contains(id);
    }

    public int signupCount() {
        return signups.size();
    }

    public boolean isParticipant(UUID id) {
        return participants.contains(id);
    }

    public boolean isBossFightActive() {
        return state == RaidState.ACTIVE;
    }

    public boolean openSignups(int minutes) {
        RaidBossDef def = def();
        if (def == null || state != RaidState.IDLE) return false;
        if (!def.hasArena()) return false;
        if (!raidTypeAllowed(def)) return false;
        if (!eventRunnable(def)) return false; // an eventOnly raid opens sign-ups only inside its event
        // Draw here, not in begin(), so the sign-up announcement can name the raid players are actually being
        // called to instead of the selector that houses the pool. The gates above deliberately ran against the
        // HOST first: the arena and the raid type are the host's to own (def() puts the host's arena back on the
        // drawn raid anyway), so rolling first would only change which raid the type gate judges.
        rollRandomRaid();
        def = def();
        signups.clear();
        names.clear();
        signupLocations.clear();
        state = RaidState.SIGNUP;
        signupEndMillis = System.currentTimeMillis() + minutes * 60_000L;
        signupReminderTicks = def.signupReminderSeconds > 0 ? def.signupReminderSeconds * 20 : 0;
        announceSignupOpen(def);
        // Tell the network this server now runs this raid, so a join from anywhere routes here. A tear's private
        // fight is not a network event and records nothing.
        if (!silent()) RaidNetwork.setHost(defId, "SIGNUP");
        return true;
    }

    private void announceSignupOpen(RaidBossDef def) {
        if (!silent()) {
            Announcer.announceSignup(server, fmt(def.msgSignupOpen, def),
                    Component.translatable("announce.dmz_ragnarok.raid.title.signup_open").getString(),
                    def, joinCommand());
        }
    }

    /**
     * Command the chat Join button runs; quote the name if it has spaces.
     *
     * <p>Built from the HOST def, never from {@link #def()}. For a random raid those differ: the announcement
     * names the raid that was drawn, but {@code /rg raid join} resolves its argument by NAME and would then find
     * the pooled raid's own instance, which is idle and would refuse the join. The host name is what routes a
     * player to the instance that is actually running the fight.
     */
    private String joinCommand() {
        RaidBossDef host = RaidData.get(server).getDef(defId);
        String name = host != null ? host.name : defId;
        return "/rg raid join " + (name.contains(" ") ? "\"" + name + "\"" : name);
    }

    public JoinResult join(ServerPlayer player) {
        RaidBossDef def = def();
        if (def == null) return JoinResult.NO_SUCH_RAID;
        if (state != RaidState.SIGNUP) return JoinResult.CLOSED;
        // The event that opened this eventOnly raid may have ended mid sign-up: stop taking new fighters.
        if (!eventRunnable(def)) return JoinResult.CLOSED;
        if (signups.contains(player.getUUID())) return JoinResult.ALREADY;
        if (def.maxParticipants > 0 && signups.size() >= def.maxParticipants) return JoinResult.FULL;
        if (def.requireCharacter && !DmzHooks.hasCreatedCharacter(player)) return JoinResult.NO_CHARACTER;
        signups.add(player.getUUID());
        names.put(player.getUUID(), player.getGameProfile().getName());
        rememberLocation(player);
        // roster full: no point idling out the sign-up window, start now.
        if (def.maxParticipants > 0 && signups.size() >= def.maxParticipants) {
            if (!silent()) {
                Announcer.broadcast(server, Component.translatable(
                        "announce.dmz_ragnarok.raid.full_starting", TextUtil.color(def.name)));
            }
            begin(new LinkedHashSet<>(signups));
        }
        return JoinResult.OK;
    }

    public boolean leave(UUID id) {
        return signups.remove(id);
    }

    public boolean forceStart() {
        if (state != RaidState.SIGNUP) return false;
        if (!eventRunnable(def())) return false; // an eventOnly raid cannot be forced live outside its event
        begin(signups);
        return true;
    }

    /**
     * on-demand start for the Raid Soul item. skips sign-ups: the initiator plus anyone already signed up and
     * online gets pulled in and the fight starts now. false if mid-run or no arena.
     */
    public boolean startImmediate(ServerPlayer initiator) {
        RaidBossDef def = def();
        if (def == null || !def.hasArena()) return false;
        if (state != RaidState.IDLE && state != RaidState.SIGNUP) return false;
        if (!raidTypeAllowed(def)) return false;
        if (!eventRunnable(def)) return false; // an eventOnly raid starts immediately only inside its event
        Set<UUID> seed = new LinkedHashSet<>(signups);
        if (initiator != null) {
            seed.add(initiator.getUUID());
            names.put(initiator.getUUID(), initiator.getGameProfile().getName());
            rememberLocation(initiator);
        }
        begin(seed);
        return true;
    }

    private void begin(Collection<UUID> seed) {
        // Draw BEFORE the first def() read of the run, so everything downstream agrees on the raid being fought.
        // Sign-up now draws for itself (it has to, to announce the drawn raid by name), so only roll here when
        // nothing has been drawn yet: a start that skipped sign-up entirely, i.e. a force start or an immediate
        // Raid Soul. Re-rolling would swap the raid out from under players who signed up for the announced one.
        if (pickedDefId == null) {
            rollRandomRaid();
        }
        RaidBossDef def = def();
        outcomeSent = false; // a fresh run may report its own outcome, whatever the previous one reported
        participants.clear();
        for (UUID id : seed) {
            if (player(id) != null) participants.add(id);
        }
        if (participants.size() < Math.max(1, def.minParticipants)) {
            if (!silent()) {
                Announcer.broadcast(server, Component.translatable(
                        "announce.dmz_ragnarok.raid.not_enough", TextUtil.color(def.name)));
            }
            resetToIdle();
            return;
        }
        for (UUID id : participants) {
            ServerPlayer p = player(id);
            if (p == null) continue;
            teleport(p, def.arena);
            if (def.healOnStart) DmzHooks.fullHeal(p);
        }
        String starting = fmt(def.msgStarting, def).replace("{count}", String.valueOf(participants.size()));
        Component startingTitle =
                Component.translatable("announce.dmz_ragnarok.raid.title.starting", TextUtil.color(def.name));
        // A tear says nothing here: silent() covers it, and its own opening broadcast has already been made by the
        // rift. For an ordinary raid this is the server-wide "it is starting" line and title.
        if (!silent()) {
            Announcer.announce(server, starting, startingTitle, def);
        }
        accumDamage.clear();
        kills.clear();
        stageDeaths = 0;
        nullGraceTicks.clear();
        lastKnownPos.clear();
        vanishRecords.clear();
        rushIndex = 0;
        rushDelayTicks = 0;
        parallelWave = 0;
        parallelBossActive = false;
        activeTicks = 0;
        phaseTimer = Math.max(1, def.countdownSeconds) * 20;
        state = RaidState.COUNTDOWN;
        // A raid that started without a sign-up (Raid Soul, force start) still becomes this server's to host.
        if (!silent()) RaidNetwork.setHost(defId, "RUNNING");
    }

    /**
     * Every raid type is public as of 2.0 (owner decision): Parallel Quest and Boss Rush no longer require a key.
     * Rifts stay private, but they are gated separately in {@code RiftManager} on the Ragnarok Key, not here.
     */
    private boolean raidTypeAllowed(RaidBossDef def) {
        return true;
    }

    /**
     * Whether this def may run right now given the timed-event engine. A normal raid always may; an eventOnly raid
     * may only while an active event lists it, asked through the private key's {@code EventHooks.raidRunnable}.
     * Keyless the hook's default is false, so an eventOnly raid never runs without the key. Gated at every entry
     * point (sign-up, join, force start, immediate start) and again at spawn as a safety net.
     */
    private boolean eventRunnable(RaidBossDef def) {
        return def == null || !def.eventOnly
                || net.shurui.dev.sdu.api.key.EventHooks.get().raidRunnable(def.id);
    }

    /** fire the spawn once the countdown ends, by raid type. */
    private void startEncounter() {
        RaidBossDef def = def();
        ServerLevel level = server.getLevel(def.arena.dimension());
        if (level == null) { resetToIdle(); return; }
        if (!raidTypeAllowed(def) || !eventRunnable(def)) {
            // safety net: advanced raid reached spawn with no key (e.g. via scheduling), or an eventOnly raid
            // whose event ended between begin() and the countdown. bail cleanly.
            if (!silent()) {
                Announcer.broadcast(server, Component.translatable(
                        "announce.dmz_ragnarok.raid.requires_key", TextUtil.color(def.name), def.raidType.display()));
            }
            resetToIdle();
            return;
        }
        switch (def.raidType) {
            case PARALLEL_QUEST -> spawnParallelWave(def, level, firstWave(def));
            case BOSS_RUSH -> spawnRushStage(def, level, 0);
            default -> spawnStandard(def, level);
        }
        // The encounter's own allies, after the enemies so they have something to target on their first tick.
        // Stage 0 of a rush has already spawned its own allies by now; these are additional, not a replacement.
        spawnAllies(def, level, def.allies);
        if (liveEnemies.isEmpty()) {
            // nothing spawned (misconfigured); don't strand the raid.
            if (!silent()) {
                Announcer.broadcast(server, Component.translatable(
                        "announce.dmz_ragnarok.raid.nothing_to_fight", TextUtil.color(def.name)));
            }
            resetToIdle();
            return;
        }
        state = RaidState.ACTIVE;
        activeTicks = 0;
    }

    private void spawnStandard(RaidBossDef def, ServerLevel level) {
        stageDeaths = 0; // fresh stage needs its own real death before it can resolve as cleared
        double health = def.scaledHealth(participants.size());
        LivingEntity boss = createEnemy(def, level, def.bossEntityType, bossSpawnPos(def, level),
                health, 0, 0, def.bossScale, 0, def.kiMoves, def.bossName, def.bossModelId);
        if (boss == null) return;
        applyStandardDamageBonus(def, boss);
        stampBossTransform(boss, def);
        if (!silent()) {
            Announcer.announceTo(server, participants, fmt(def.msgBossSpawn, def).replace("{health}", String.valueOf((long) health)),
                    Component.translatable("announce.dmz_ragnarok.raid.title.boss", TextUtil.color(def.bossName)), def);
        }
        resetHudBaseline();
    }

    /**
     * Where the main boss spawns. Normally the arena floor centre, but when the encounter anchors on a block (its
     * {@code spawnOnObsidian} toggle, or a Snipperjack boss whose encounter predates that field) the boss is seated
     * on that block inside the arena so a builder can place it to choose his spot. Deterministic when several exist
     * (nearest to the arena centre, then lowest Y, X, Z). If none is found, a warning names the encounter and the
     * normal floor-centre spawn is used. {@code createEnemy} still surface-snaps the returned X/Z, so an anchor
     * block sitting as the top block at its column lands the boss right on top of it.
     */
    private Vec3 bossSpawnPos(RaidBossDef def, ServerLevel level) {
        Vec3 fallback = def.arena.floorCenter();
        boolean anchor = def.spawnOnObsidian
                || RaidBossDef.SNIPPERJACK_ENTITY.equals(def.bossEntityType);
        if (!anchor || def.arena == null) {
            return fallback;
        }
        String blockId = (def.spawnAnchorBlock == null || def.spawnAnchorBlock.isBlank())
                ? "minecraft:obsidian" : def.spawnAnchorBlock;
        net.minecraft.world.level.block.Block want =
                ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(blockId));
        if (want == null) {
            LOGGER.warn("[{}] spawn anchor block '{}' is not a registered block; using the normal spawn.",
                    def.name, blockId);
            return fallback;
        }
        net.minecraft.core.BlockPos min = def.arena.min();
        net.minecraft.core.BlockPos max = def.arena.max();
        Vec3 centre = def.arena.center();
        net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
        net.minecraft.core.BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    m.set(x, y, z);
                    if (!level.getBlockState(m).is(want)) {
                        continue;
                    }
                    // nearest to centre, deterministic tie-break by lowest Y then X then Z
                    double dx = (x + 0.5) - centre.x, dz = (z + 0.5) - centre.z;
                    double score = dx * dx + dz * dz;
                    if (score < bestScore - 1.0e-6
                            || (Math.abs(score - bestScore) <= 1.0e-6 && best != null
                                && (y < best.getY()
                                    || (y == best.getY() && (x < best.getX()
                                        || (x == best.getX() && z < best.getZ())))))) {
                        bestScore = score;
                        best = new net.minecraft.core.BlockPos(x, y, z);
                    }
                }
            }
        }
        if (best == null) {
            LOGGER.warn("[{}] spawn-on-{} is set but no such block is in the arena; using the normal spawn.",
                    def.name, blockId);
            return fallback;
        }
        return new Vec3(best.getX() + 0.5, best.getY() + 1.0, best.getZ() + 0.5);
    }

    /**
     * write the boss's configured transform chain onto persistentData for the sdu engine. pure NBT, no sdu
     * classes referenced, so it's valid whether or not sdu is loaded (the consumer only exists with sdu).
     * only the main boss gets stamped.
     * <ul>
     *   <li>non-empty custom chain ("forms" list): write "sdu_tf" and force "dmz_quest_no_transform" so the
     *       custom chain overrides DMZ's native transforms.</li>
     *   <li>no custom chain but useDefaultTransform=false: just kill DMZ's native transforms.</li>
     *   <li>otherwise leave it alone (DMZ defaults apply).</li>
     * </ul>
     */
    private void stampBossTransform(LivingEntity boss, RaidBossDef def) {
        net.minecraft.nbt.CompoundTag chain = def.bossTransform;
        boolean hasChain = chain != null
                && !chain.getList("forms", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty();
        if (hasChain) {
            boss.getPersistentData().put("sdu_tf", chain.copy());
            boss.getPersistentData().putBoolean("dmz_quest_no_transform", true);
        } else if (!def.useDefaultTransform) {
            boss.getPersistentData().putBoolean("dmz_quest_no_transform", true);
        }
    }

    /** sorted distinct wave numbers in a Parallel Quest's enemy list. */
    private static java.util.List<Integer> waveNumbers(RaidBossDef def) {
        java.util.TreeSet<Integer> waves = new java.util.TreeSet<>();
        for (String token : def.enemies)
            waves.add(Math.max(1, EnemyWave.fromToken(token).wave));
        return new ArrayList<>(waves);
    }

    private static int firstWave(RaidBossDef def) {
        java.util.List<Integer> waves = waveNumbers(def);
        return waves.isEmpty() ? 1 : waves.get(0);
    }

    /** spawn every enemy in {@code wave}. waves run in order, boss finale last. */
    private void spawnParallelWave(RaidBossDef def, ServerLevel level, int wave) {
        stageDeaths = 0; // require this wave's own real deaths before it advances
        parallelWave = wave;
        int total = 0;
        for (String token : def.enemies) {
            EnemyWave w = EnemyWave.fromToken(token);
            if (Math.max(1, w.wave) != wave)
                continue;
            for (int i = 0; i < Math.max(1, w.count); i++) {
                Vec3 pos = def.arena.randomInside(level.getRandom());
                LivingEntity e = createEnemy(def, level, w.entityType, pos, w.health, w.meleeDamage, w.kiPower,
                        w.scale, w.defense, java.util.List.of(), null, w.rgModelId);
                if (e != null) total++;
            }
        }
        if (total == 0) {
            // Unlike a boss rush this cannot stall (nextParallelStep reads liveEnemies and resolves the run when a
            // wave comes up empty), but it was just as invisible: with no enemy and no announcement there was
            // nothing to distinguish "wave 3 is misconfigured" from "wave 3 was never meant to exist".
            LOGGER.error("[{}] Parallel-quest wave {} spawned no enemies; check its entity tokens.",
                    def.name, wave);
        }
        if (total > 0) {
            java.util.List<Integer> waves = waveNumbers(def);
            Component title = waves.size() > 1
                    ? Component.translatable("announce.dmz_ragnarok.raid.title.wave", TextUtil.color(def.name),
                            waves.indexOf(wave) + 1, waves.size())
                    : Component.translatable("announce.dmz_ragnarok.raid.title.boss", TextUtil.color(def.name));
            if (!silent()) {
                Announcer.announceTo(server, participants, fmt(def.msgBossSpawn, def).replace("{health}", String.valueOf(total)),
                        title, def);
            }
        }
        resetHudBaseline();
    }

    /** PARALLEL_QUEST finale: after the last wave, spawn the def's configured boss. */
    private void spawnParallelBoss(RaidBossDef def, ServerLevel level) {
        parallelBossActive = true;
        spawnStandard(def, level);
    }

    /**
     * Spawn boss-rush stage {@code index}, SKIPPING FORWARD over any stage that cannot put a boss in the world.
     *
     * <h2>Why skipping, and not just returning</h2>
     * This used to be {@code if (boss == null) return;}. That left the instance in the one state it can never get
     * out of: {@link #liveEnemies} empty and {@code stageDeaths} zero, which is exactly the pair
     * {@link #tryResolveClearedStage} refuses to resolve on. The fight then sat there with nothing in the arena
     * until it timed out, and the only two paths that could report why were both behind {@code !silent()}, which
     * is false for every rift run. So a rush whose second stage named a missing entity looked from the outside
     * like "the later levels never start", with nothing in the log at all.
     *
     * <p>A stage that cannot spawn is a configuration error, not a fight outcome, so it is logged and stepped over
     * rather than being allowed to decide the run. If NO remaining stage can spawn, the rush has nothing left to
     * present and resolves as a victory: every enemy that actually existed has been killed, and failing the player
     * for the operator's broken token would be the wrong half of the mistake to punish.
     */
    private void spawnRushStage(RaidBossDef def, ServerLevel level, int index) {
        for (int i = Math.max(0, index); i < def.rushStages.size(); i++) {
            stageDeaths = 0; // require this boss's real death before the next stage
            rushIndex = i;
            BossStage s = BossStage.fromToken(def.rushStages.get(i));
            // ORDER MATTERS, see BossStage.swapSides: swap what is already alive FIRST, so this stage's own boss and
            // its own allies are not caught by the swap that announced them.
            if (s.swapSides) swapSides();
            double health = s.health > 0 ? s.health : def.scaledHealth(participants.size());
            LivingEntity boss = createEnemy(def, level, s.entityType, def.arena.floorCenter(),
                    health, s.meleeDamage, s.kiPower, s.scale, s.defense, s.kiMoves,
                    s.bossName != null && !s.bossName.isBlank() ? s.bossName : def.bossName, s.rgModelId);
            if (boss == null) {
                LOGGER.error("[{}] Boss-rush stage {}/{} ('{}') spawned nothing and was skipped; the run would"
                                + " otherwise have stalled with an empty arena.",
                        def.name, i + 1, def.rushStages.size(), def.rushStages.get(i));
                continue;
            }
            String stageBoss = s.bossName != null && !s.bossName.isBlank() ? s.bossName : def.bossName;
            Component label = Component.translatable("announce.dmz_ragnarok.raid.title.rush",
                    i + 1, def.rushStages.size(), TextUtil.color(stageBoss));
            if (!silent()) {
                Announcer.announceTo(server, participants, fmt(def.msgBossSpawn, def)
                        .replace("{boss}", stageBoss)
                        .replace("{health}", String.valueOf((long) health)), label, def);
            }
            // Outside the silent() gate on purpose: a tear suppresses the raid-language announcement above but
            // still owns a lone fighter who must be told the fight moved on. The rift listens here and speaks
            // in its own words; a non-silent raid has no listener registered, so this is inert for it.
            sendStage(i + 1, def.rushStages.size(), stageBoss);
            spawnAllies(def, level, s.allies);
            resetHudBaseline();
            return;
        }
        if (index >= 0 && index < def.rushStages.size()) {
            LOGGER.error("[{}] No boss-rush stage from {} onward could spawn; resolving the run rather than"
                    + " leaving it stalled in an empty arena.", def.name, index + 1);
            resolveVictory(def);
        }
    }

    /**
     * build, stat, register and spawn one enemy/boss. null if the type is invalid or not a LivingEntity.
     * null {@code visibleName} leaves it unnamed.
     *
     * <p>{@code rgModelId} is the ragnarok NPC character to wear, blank for none. It is a parameter and not read
     * off the def because all three callers mean a different one: the raid's own boss, a parallel-quest wave's
     * enemy, and a boss-rush stage each carry their own. Every non-rgnpc entity type ignores it.
     */
    private LivingEntity createEnemy(RaidBossDef def, ServerLevel level, String entityId, Vec3 pos,
                                     double health, double meleeDamage, double kiPower, double scale,
                                     double defense, List<String> kiTokens, String visibleName,
                                     String rgModelId) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(entityId));
        if (type == null) {
            // silent() suppresses PLAYER-FACING announcements, never operator diagnostics. A rift run is always
            // silent (its fixedDef is what makes it one), so before this log line a rift whose stage named a
            // missing entity produced no announcement, no log, and no enemy: the stage simply never started and
            // there was nothing anywhere to say why. See the note on spawnRushStage.
            LOGGER.warn("[{}] Stage entity '{}' is not a registered entity type; nothing spawned for it.",
                    def.name, entityId);
            if (!silent()) {
                Announcer.broadcast(server, Component.translatable(
                        "announce.dmz_ragnarok.raid.invalid_entity", TextUtil.color(def.name), entityId));
            }
            return null;
        }
        Entity entity = type.create(level);
        if (!(entity instanceof LivingEntity enemy)) {
            LOGGER.warn("[{}] Stage entity '{}' is not a LivingEntity; nothing spawned for it.",
                    def.name, entityId);
            if (!silent()) {
                Announcer.broadcast(server, Component.translatable(
                        "announce.dmz_ragnarok.raid.not_living", TextUtil.color(def.name), entityId));
            }
            return null;
        }
        // arena is an X/Z area, so take Y off the terrain surface, not the box floor (below terrain buried NPCs,
        // above it left them floating). force-load + validate ground/headroom so a boss can never spawn at the
        // world floor (-64); if the caller's X/Z is unsafe, re-roll inside the arena, else guarded arena centre.
        Vec3 snapped = surfaceSnap(level, pos);
        if (!isSafeStand(level, snapped.x, snapped.y, snapped.z))
            snapped = nearestSafeSpot(level, def, pos);
        enemy.moveTo(snapped.x, snapped.y, snapped.z, 0f, 0f);
        // lift out of any embedding blocks so it doesn't suffocate the instant the raid starts (classic "boss
        // dies as soon as it spawns"). runs after the surface snap.
        nudgeClear(level, enemy, def);

        applyConfiguredStats(enemy, def, health, meleeDamage, kiPower, scale, defense, kiTokens, visibleName);
        // The chosen ragnarok NPC character, BEFORE the entity enters the world: the model id rides the entity's
        // synched data, so setting it first means the very first packet clients see already carries the right
        // character. Set afterwards it works too, but every viewer renders the default model for a tick first.
        net.shurui.shuruisutilities.ragnarok.RgNpcLook.apply(enemy, rgModelId);
        // opt out of DMZ's onEntityJoinWorld config-stat overwrite so our stats survive (DMZ 2.1.3).
        enemy.getPersistentData().putBoolean("dmz_stats_configured", true);
        // In a rift, DragonMineZ's NATIVE transformations are off unless the operator configured a chain.
        //
        // A saga fighter transforms at a health fraction (default 0.5) by constructing a new entity of the next form
        // and discarding itself. The run tracks its enemies by UUID (liveEnemies / RaidDamageTracker), and a boss
        // rush advances on its configured stages, so an unconfigured native transform takes the tracked boss out of
        // the world and replaces it with something the run never spawned: the stage cannot complete and the tear
        // fails. Observed live on 2026-09-10, where a rush of frieza_first then frieza_second had DMZ turn second
        // into frieza_third on its own and the rift failed.
        //
        // Only rifts are changed. An ordinary raid keeps whatever useDefaultTransform says, because its arena-based
        // adoption path (RaidManager.tryAdoptTransformedBoss) is what native transforms were built around.
        //
        // A manually configured chain still works and is the supported way to have forms in a rift: stampBossTransform
        // writes "sdu_tf" for the boss and sets this same key itself, so the forms are driven by sdu, which is what
        // carries the configured stats across (see the "Saga transform: carried configured stats" log line).
        if (isRift())
            enemy.getPersistentData().putBoolean("dmz_quest_no_transform", true);
        if (!def.vanillaDrops)
            enemy.getPersistentData().putBoolean(
                    net.shurui.dev.shuruis_raid_bosses.event.ForgeEventHandler.NO_VANILLA_DROPS_TAG, true);
        if (enemy instanceof Mob mob) mob.setPersistenceRequired();
        // register tracking BEFORE it enters the world so the EntityJoinLevelEvent sees an already-tracked boss
        // and doesn't mistake the initial spawn for a transform to adopt.
        liveEnemies.add(enemy.getUUID());
        RaidDamageTracker.register(enemy.getUUID(), defId);
        boolean added = level.addFreshEntity(enemy);
        if (!added || enemy.isRemoved() || !enemy.isAlive()) {
            // never actually entered the world (blocked/duplicate/removed). don't leave a phantom in liveEnemies
            // or next tick sees "no enemies" and resolves the raid instantly.
            liveEnemies.remove(enemy.getUUID());
            RaidDamageTracker.clear(enemy.getUUID());
            LOGGER.warn("[{}] Boss '{}' failed to spawn at {} (added={}, alive={}).",
                    def.name, entityId, pos, added, enemy.isAlive());
            if (!silent()) {
                Announcer.broadcast(server, Component.translatable(
                        "announce.dmz_ragnarok.raid.spawn_failed", TextUtil.color(def.name)));
            }
            return null;
        }
        // red glow outline so players can see which entities are the raid targets.
        applyGlow(enemy);
        LOGGER.info("[{}] Spawned '{}' hp={}/{} at {}.", def.name, entityId,
                enemy.getHealth(), enemy.getMaxHealth(), enemy.blockPosition());
        return enemy;
    }

    /**
     * snap X/Z onto the terrain surface Y. arena box vertical position is irrelevant now; only its X/Z footprint
     * matters. MOTION_BLOCKING_NO_LEAVES gives the first empty block above the top solid one, i.e. where feet go.
     * ChunkAccess.getHeight returns that value minus one, so we +1 to match the old Level.getHeight feet result.
     * level.getHeight does NOT force-load: on an unloaded chunk it returns getMinBuildHeight() (-64),
     * dropping the target to the world floor. so we force-load the exact chunk to FULL first, then read the
     * heightmap off the loaded chunk. runs on the server thread during raid start; getChunk(int,int) here is
     * getChunk(x, z, ChunkStatus.FULL, true), which adds a ticket and drives generation via managedBlock (pumps
     * the main-thread queue, no lock), so it will not deadlock.
     */
    private Vec3 surfaceSnap(ServerLevel level, Vec3 pos) {
        int bx = net.minecraft.util.Mth.floor(pos.x);
        int bz = net.minecraft.util.Mth.floor(pos.z);
        net.minecraft.world.level.chunk.ChunkAccess chunk = level.getChunk(
                net.minecraft.core.SectionPos.blockToSectionCoord(bx),
                net.minecraft.core.SectionPos.blockToSectionCoord(bz));
        // ceilinged dimensions (Nether-style) have a solid bedrock roof, so MOTION_BLOCKING_NO_LEAVES returns
        // the top of the ceiling and a snap would put a player ON the roof. an admin can define a raid arena in
        // any dimension (arena is a WorldEdit selection with an arbitrary dimension), so this is reachable.
        // in that case scan DOWN the column for the first real standing spot instead of trusting the heightmap.
        if (level.dimensionType().hasCeiling()) {
            return ceilingSurfaceSnap(level, pos, bx, bz);
        }
        // chunk-local X/Z into the heightmap; result is absolute world Y.
        int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx & 15, bz & 15) + 1;
        // A ROOF OVER THE ARENA is not a ceilinged dimension, and the heightmap cannot tell the difference: it
        // reports the top of whatever is highest in the column, so an arena inside a building, under an overhang,
        // or (as a tear arena always is) inside a sealed shell would put the boss on TOP of the roof, outside the
        // box the fight is confined to. When the heightmap answer is above the arena, the column is roofed, so use
        // the same downward scan the ceilinged dimensions use.
        RaidBossDef def = def();
        if (def != null && def.arena != null && surfaceY > def.arena.max().getY()) {
            return ceilingSurfaceSnap(level, pos, bx, bz);
        }
        return new Vec3(pos.x, surfaceY, pos.z);
    }

    /**
     * ceilinged-dimension fallback for {@link #surfaceSnap}: the heightmap is useless under a solid roof, so scan
     * downward from the arena's own top Y for the first spot with solid ground and two blocks of headroom. the
     * chunk is already force-loaded by the caller. returns feet Y just above the ground on the first hit, or the
     * arena floor if the whole column is unsuitable (so we never punch through the roof).
     */
    private Vec3 ceilingSurfaceSnap(ServerLevel level, Vec3 pos, int bx, int bz) {
        RaidBossDef def = def();
        int top = (def != null && def.arena != null) ? def.arena.max().getY() : (level.getMaxBuildHeight() - 2);
        int floor = (def != null && def.arena != null) ? def.arena.min().getY() : level.getMinBuildHeight();
        // clamp the scan window to the world so a mis-set arena can't read out of bounds.
        top = Math.min(top, level.getMaxBuildHeight() - 1);
        floor = Math.max(floor, level.getMinBuildHeight() + 1);
        for (int y = top; y > floor; y--) {
            if (isSafeStand(level, pos.x, y, pos.z)) {
                return new Vec3(pos.x, y, pos.z);
            }
        }
        return new Vec3(pos.x, floor + 1, pos.z);
    }

    /**
     * is (x, feetY, z) a safe standing spot: solid block directly below the feet, and at least 2 blocks of
     * headroom (feet + head empty). rejects feet at or below the world floor. assumes the chunk is loaded.
     */
    private boolean isSafeStand(ServerLevel level, double x, double feetY, double z) {
        if (feetY <= level.getMinBuildHeight()) return false;
        int bx = net.minecraft.util.Mth.floor(x);
        int by = net.minecraft.util.Mth.floor(feetY);
        int bz = net.minecraft.util.Mth.floor(z);
        net.minecraft.core.BlockPos feet = new net.minecraft.core.BlockPos(bx, by, bz);
        net.minecraft.core.BlockPos head = feet.above();
        net.minecraft.core.BlockPos below = feet.below();
        // A BARRIER is never a floor. A tear arena is wrapped in one and capped by one, and an arena built by an
        // earlier version can have an old cap still standing inside it, so accepting a barrier here is what put a
        // boss on the roof of its own arena. No raid wants anything stood on a barrier in any case: it is the
        // material arenas are BOUNDED with, never floored with.
        boolean groundSolid = !level.getBlockState(below).is(net.minecraft.world.level.block.Blocks.BARRIER)
                && !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
        boolean feetClear = level.getBlockState(feet).getCollisionShape(level, feet).isEmpty();
        boolean headClear = level.getBlockState(head).getCollisionShape(level, head).isEmpty();
        return groundSolid && feetClear && headClear;
    }

    /** max re-rolls when a scattered X/Z snaps to an unsafe spot before we fall back to a known-good position. */
    private static final int SNAP_RETRIES = 8;

    /** how far out from a requested X/Z {@link #nearestSafeSpot} will look, in blocks, before giving up. */
    private static final int NEAREST_SEARCH_RADIUS = 24;

    /**
     * The safe standing spot CLOSEST to a requested X/Z, searched as square rings growing outward from it.
     *
     * <p>This is what places a boss: every boss and stage boss is spawned at {@link Region#floorCenter()}, and an
     * admin wants it in the middle of the arena, so when the exact centre happens to be inside a block the answer
     * should be the nearest clear spot to the centre, not somewhere else entirely. The previous fallback re-rolled
     * a RANDOM position anywhere in the arena, which for a CYLINDER arena could hand back a corner of the bounding
     * box that is outside the ring the fight is confined to, and for a plain box threw away the caller's intent.
     *
     * <p>Candidates outside the arena footprint are skipped rather than clamped, so a cylinder arena never yields a
     * spot beyond its wall. Rings are walked in increasing radius and the first hit wins, so the result is the
     * closest safe spot rather than merely a safe one. Y comes from the same guarded {@link #surfaceSnap} every
     * other placement uses. On total failure this falls back to the old scatter, which has its own last-resort
     * arena-centre guard, so the boss still cannot be left at the world floor.
     */
    private Vec3 nearestSafeSpot(ServerLevel level, RaidBossDef def, Vec3 want) {
        // Region.contains ignores Y (its box spans the whole column), so one in-range Y serves every candidate.
        double probeY = def.arena.floorCenter().y;
        for (int r = 0; r <= NEAREST_SEARCH_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    // Only the perimeter of this ring: the interior was covered by a smaller r already.
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    double x = want.x + dx;
                    double z = want.z + dz;
                    // Vertical-agnostic containment, so a boss is never nudged out through the arena wall.
                    if (!def.arena.contains(level, new Vec3(x, probeY, z))) continue;
                    Vec3 snapped = surfaceSnap(level, new Vec3(x, want.y, z));
                    if (isSafeStand(level, snapped.x, snapped.y, snapped.z)) return snapped;
                }
            }
        }
        LOGGER.warn("[{}] No safe spot within {} blocks of {}; falling back to an arena scatter.",
                def.name, NEAREST_SEARCH_RADIUS, want);
        return safeSurfaceSpot(level, def, level.getRandom(), null);
    }

    /**
     * scatter inside the arena and snap to a SAFE surface spot: force-loads the chunk, validates solid ground and
     * headroom, and re-rolls a bounded number of times. on repeated failure returns the guarded arena centre as a
     * last-resort fallback (logged), so nothing is ever left at the world floor (-64). {@code fallback}, when
     * non-null, overrides that centre fallback (e.g. the live boss position). never returns a spot at/below the
     * world floor unless the arena centre itself is void, which is logged.
     */
    private Vec3 safeSurfaceSpot(ServerLevel level, RaidBossDef def, net.minecraft.util.RandomSource random, Vec3 fallback) {
        for (int i = 0; i <= SNAP_RETRIES; i++) {
            Vec3 rolled = def.arena.randomInside(random);
            Vec3 snapped = surfaceSnap(level, rolled);
            if (isSafeStand(level, snapped.x, snapped.y, snapped.z)) return snapped;
        }
        if (fallback != null && isSafeStand(level, fallback.x, fallback.y, fallback.z)) {
            LOGGER.warn("[{}] No safe surface spot after {} rolls; using boss/fallback position {}.",
                    def.name, SNAP_RETRIES + 1, fallback);
            return fallback;
        }
        // last resort: the arena centre, snapped through the same guarded path.
        Vec3 centre = surfaceSnap(level, def.arena.floorCenter());
        LOGGER.warn("[{}] No safe surface spot after {} rolls; falling back to guarded arena centre {}.",
                def.name, SNAP_RETRIES + 1, centre);
        if (centre.y <= level.getMinBuildHeight()) {
            LOGGER.warn("[{}] Arena centre also resolved to the world floor (void/ocean arena?). Placement at {} "
                    + "is unsafe; check the arena bounds.", def.name, centre);
        }
        return centre;
    }

    /** step straight up until the bounding box clears blocks. */
    private void nudgeClear(ServerLevel level, LivingEntity enemy, RaidBossDef def) {
        // already surface-snapped, so allow fixed headroom above current Y (arena is X/Z-only, terrain can sit
        // above the box top).
        double ceiling = enemy.getY() + 16;
        int guard = 0;
        while (!level.noCollision(enemy) && enemy.getY() < ceiling && guard++ < 48) {
            enemy.setPos(enemy.getX(), enemy.getY() + 1, enemy.getZ());
        }
    }

    /**
     * re-track a boss that a DMZ transformation just spawned in place of one of ours. saga bosses transform by
     * spawning a new form and discarding the old one with no death event, which would otherwise look like the
     * boss vanished and end the raid early. only adopts entities inside the arena, while active, not already
     * tracked.
     */
    public boolean adoptTransformed(LivingEntity entity) {
        if (state != RaidState.ACTIVE) return false;
        RaidBossDef def = def();
        // bosses fly and a transform can fire just past the strict bounds, so allow a margin or the new form is
        // never adopted and the raid instantly "wins". this X/Z containment is only an OUTER guard: on its own it
        // would pull in ANY saga boss wandering/naturally-spawning in range, so a positive transform signal below
        // is also required.
        if (def == null || def.arena == null || !def.arena.containsWithMargin(entity, ADOPT_MARGIN)) return false;
        UUID id = entity.getUUID();
        if (liveEnemies.contains(id)) return false;

        // two-signal gate: adopt ONLY a real transformed form of one of THIS raid's bosses.
        net.minecraft.nbt.CompoundTag pd = entity.getPersistentData();
        //   (a) our sdu engine stamps every form it makes with "sdu_tf_active" (remaining chain under "sdu_tf").
        boolean sduForm = pd.contains("sdu_tf_active") || pd.contains("sdu_tf");
        //   (b) handoff heuristic: a tracked boss vanished with no death event very recently and very close to
        //       where this one appeared. consumeVanishNear returns true and burns the matched record.
        boolean nativeHandoff = !sduForm && consumeVanishNear(entity.position());
        if (!sduForm && !nativeHandoff) return false; // neither signal, ignore

        liveEnemies.add(id);
        RaidDamageTracker.register(id, defId);
        lastKnownPos.put(id, entity.position());
        applyGlow(entity);
        // our sdu-engine forms already carry the right multiplied stats + buffs; re-applying the raid config here
        // would clobber them, so adopt-for-tracking only. DMZ-native forms (no such key) still get re-statted.
        if (!sduForm) reapplyConfiguredStats(entity, def);
        if (!def.vanillaDrops)
            entity.getPersistentData().putBoolean(
                    net.shurui.dev.shuruis_raid_bosses.event.ForgeEventHandler.NO_VANILLA_DROPS_TAG, true);
        resetHudBaseline();
        return true;
    }

    /** blocks around the arena still counted as inside when adopting a transformed boss. */
    private static final double ADOPT_MARGIN = 32.0;

    /**
     * handoff window (ticks): how recently a tracked boss must have vanished-without-dying for an incoming saga
     * boss to count as its transformed form. ~40t (2s) covers a DMZ discard-then-respawn while staying well
     * short of {@link #NULL_GRACE_TICKS}.
     */
    private static final long VANISH_TICK_WINDOW = 40L;
    /**
     * handoff radius (blocks): DMZ spawns the new form right where the old one was, so a tight 12 blocks rejects
     * unrelated saga bosses elsewhere in the footprint.
     */
    private static final double VANISH_RADIUS = 12.0;

    /** drop vanish records past the window so a stale vanish can't adopt a later saga boss. */
    private void expireVanishRecords() {
        long now = server.getTickCount();
        vanishRecords.removeIf(r -> now - r.gameTick() > VANISH_TICK_WINDOW);
    }

    /**
     * true if a tracked boss vanished-without-dying within the window+radius of {@code candidatePos}; consumes
     * the matched record so one vanish adopts at most one form. expires stale records first.
     */
    private boolean consumeVanishNear(Vec3 candidatePos) {
        expireVanishRecords();
        long now = server.getTickCount();
        double radiusSq = VANISH_RADIUS * VANISH_RADIUS;
        for (java.util.Iterator<VanishRecord> it = vanishRecords.iterator(); it.hasNext(); ) {
            VanishRecord r = it.next();
            if (now - r.gameTick() > VANISH_TICK_WINDOW) continue;
            if (r.pos().distanceToSqr(candidatePos) <= radiusSq) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    /**
     * raw persistentData key for the suite-wide "NPC defense" system (a double). a mob with it gets DMZ's
     * resistance-mitigation curve applied by an sdu Forge handler (scale = DMZ getDefense; higher = tankier;
     * 0/absent = none). written raw so raid_bosses never classloads sdu; without sdu the key just sits unread.
     */
    private static final String NPC_DEFENSE_KEY = "dmz_npc_defense";

    /**
     * how many consecutive unresolvable ({@link #enemy(UUID)} == null) ticks before we give up on a tracked
     * enemy. generous (5s) so a normal DMZ transform handoff rides it out instead of counting as gone.
     * exceeding it resolves as FAILURE, never a win.
     */
    private static final int NULL_GRACE_TICKS = 100;

    /**
     * a DMZ transform spawned this form carrying DMZ's own scaling (old stats x multiplier, refilled hp) and
     * DROPPING everything the raid set: ki-move pool, melee/ki damage, speed, scale, battle power, name. re-apply
     * the def stats so every form honours the raid config; this is what makes edited HP stick on transforming
     * bosses and keeps forms hitting with the configured defense-piercing damage instead of weak type defaults.
     *
     * <p>parallel-quest waves stay on DMZ scaling: by the time an enemy transforms we don't know which wave it
     * came from, and per-wave stats are the exception there anyway.</p>
     */
    private void reapplyConfiguredStats(LivingEntity entity, RaidBossDef def) {
        switch (def.raidType) {
            case STANDARD -> {
                applyConfiguredStats(entity, def, def.scaledHealth(participants.size()), 0, 0,
                        def.bossScale, 0, def.kiMoves, def.bossName);
                applyStandardDamageBonus(def, entity);
            }
            case BOSS_RUSH -> {
                if (rushIndex < 0 || rushIndex >= def.rushStages.size()) return;
                BossStage s = BossStage.fromToken(def.rushStages.get(rushIndex));
                double health = s.health > 0 ? s.health : def.scaledHealth(participants.size());
                String name = s.bossName != null && !s.bossName.isBlank() ? s.bossName : def.bossName;
                applyConfiguredStats(entity, def, health, s.meleeDamage, s.kiPower, s.scale, s.defense, s.kiMoves, name);
            }
            default -> { }
        }
        LOGGER.info("[{}] Adopted transformed boss '{}' and re-applied raid stats (hp={}/{}).",
                def.name, entity.getType().getDescriptionId(), entity.getHealth(), entity.getMaxHealth());
    }

    /**
     * shared by initial spawn and transformed-form adoption: DMZ stats/ki pool for saga entities, then the
     * health override, then the name.
     */
    private void applyConfiguredStats(LivingEntity enemy, RaidBossDef def, double health, double meleeDamage,
                                      double kiPower, double scale, double defense, List<String> kiTokens,
                                      String visibleName) {
        // saga bosses: stats/ki first, health override wins after.
        if (enemy instanceof DBSagasEntity sagas) applyDmzStats(sagas, def, meleeDamage, kiPower, scale, defense, kiTokens);

        if (health > 0) {
            AttributeInstance maxHp = enemy.getAttribute(Attributes.MAX_HEALTH);
            if (maxHp != null) maxHp.setBaseValue(health);
            enemy.setHealth((float) health);
        }
        if (visibleName != null && !visibleName.isBlank()) {
            enemy.setCustomName(TextUtil.color(visibleName));
            enemy.setCustomNameVisible(true);
        }
    }

    /** STANDARD: extra melee per participant past the first. */
    private void applyStandardDamageBonus(RaidBossDef def, LivingEntity boss) {
        if (def.scaleDamage && participants.size() > 1) {
            AttributeInstance atk = boss.getAttribute(Attributes.ATTACK_DAMAGE);
            if (atk != null) atk.setBaseValue(atk.getBaseValue() + def.damagePerPlayer * (participants.size() - 1));
        }
    }

    /**
     * apply the def's DMZ stats + ki moves to a saga enemy. per-enemy melee/ki/scale override the def defaults
     * when non-zero; a non-empty ki-move list replaces the default pool. battle power is DERIVED as
     * round(melee + ki), mirroring how a player's scouter BP sums scaled stats (melee ~ STR, ki ~ PWR) so boss
     * readings stay comparable to player readings.
     */
    private void applyDmzStats(DBSagasEntity boss, RaidBossDef def, double meleeDamage, double kiPower,
                               double scale, double defense, List<String> kiTokens) {
        double melee = meleeDamage > 0 ? meleeDamage : def.baseMeleeDamage;
        double ki = kiPower > 0 ? kiPower : def.kiBlastDamage;
        double sc = scale > 0 ? scale : def.bossScale;
        double def_ = defense > 0 ? defense : def.baseDefense;
        if (ki > 0) boss.setKiBlastDamage((float) ki);
        if (melee > 0) {
            var atk = boss.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            if (atk != null) atk.setBaseValue(melee);
        }
        if (melee > 0 || ki > 0) boss.setBattlePower((int) Math.round(melee + ki));
        // NPC defense: write the raw key (never import sdu; without sdu it just sits unread). an sdu handler
        // reads it and applies DMZ's mitigation curve. do NOT also set ARMOR/ARMOR_TOUGHNESS or damage gets
        // double-mitigated. 0/absent = none.
        if (def_ > 0) boss.getPersistentData().putDouble(NPC_DEFENSE_KEY, def_);
        else boss.getPersistentData().remove(NPC_DEFENSE_KEY);
        if (def.moveSpeed > 0) {
            var spd = boss.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            if (spd != null) spd.setBaseValue(def.moveSpeed);
        }
        if (sc > 0) boss.setScaleVal((float) sc);
        // aiTier is DMZ's 1-based id (1=SIMPLE 2=TACTICAL 3=ADVANCED); 0/-1 = keep default. setAiTierById already
        // ignores out-of-range, but gate here so "unset" never touches the tier.
        if (def.aiTier >= 1 && def.aiTier <= 3) boss.setAiTierById(def.aiTier);

        List<String> moves = (kiTokens != null && !kiTokens.isEmpty()) ? kiTokens : def.kiMoves;
        if (!moves.isEmpty()) {
            boss.getSkillPool().clear();
            for (String token : moves) {
                KiMove m = KiMove.fromToken(token);
                DBSagasEntity.KiSkillType t;
                try {
                    t = DBSagasEntity.KiSkillType.valueOf(m.type);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                boss.addKiSkill(t, Math.max(1, m.cooldown), (float) m.size);
            }
        }
        markRangedBias(boss);
    }

    /**
     * Probability that a raid NPC beyond melee range fires a ki attack instead of closing the distance.
     *
     * <p>Filling a skill pool does not make an NPC use it: DMZ's combat brain decides that per tick and, out past
     * melee range, overwhelmingly chooses to approach. A boss given four ki attacks therefore flew at the player and
     * punched. This is the weight of the override that fixes it (see MixinDmzSagaRangedBias). Below 0.5 on purpose,
     * so an NPC still closes, repositions and uses combos rather than standing off and turning every fight into a
     * ranged duel.
     */
    private static final double RANGED_BIAS = 0.45;

    /** Persistent-data key read by MixinDmzSagaRangedBias. Raw string, so no mixin class is ever loaded here. */
    private static final String RANGED_BIAS_KEY = "dmz_ragnarok_ranged_bias";

    /**
     * Tag an NPC we spawned as one whose ranged behaviour we steer.
     *
     * <p>Written per entity rather than applied globally so DMZ's own wild saga NPCs keep DMZ's behaviour exactly:
     * the only fighters affected are the ones an encounter put in the world.
     */
    private void markRangedBias(LivingEntity entity) {
        if (entity == null) return;
        entity.getPersistentData().putDouble(RANGED_BIAS_KEY, RANGED_BIAS);
    }

    /** scoreboard team whose color drives the red glow outline on tracked enemies. */
    private static final String GLOW_TEAM = "srb_glow";

    /**
     * get or lazily make the shared team that colors the glow outline red. one team, reused by every raid, never
     * deleted on end. setSeeFriendlyInvisibles(false) keeps it a pure color carrier.
     */
    private net.minecraft.world.scores.PlayerTeam glowTeam() {
        net.minecraft.world.scores.Scoreboard sb = server.getScoreboard();
        net.minecraft.world.scores.PlayerTeam team = sb.getPlayerTeam(GLOW_TEAM);
        if (team == null) {
            team = sb.addPlayerTeam(GLOW_TEAM);
            team.setColor(net.minecraft.ChatFormatting.RED);
            team.setSeeFriendlyInvisibles(false);
        }
        return team;
    }

    /** glowing tag + shared red team. cheap, idempotent. */
    private void applyGlow(LivingEntity enemy) {
        if (enemy == null) return;
        enemy.setGlowingTag(true);
        server.getScoreboard().addPlayerToTeam(enemy.getScoreboardName(), glowTeam());
    }

    /** pull an enemy off the glow team (team itself stays for future raids). */
    private void removeGlow(LivingEntity enemy) {
        if (enemy == null) return;
        net.minecraft.world.scores.Scoreboard sb = server.getScoreboard();
        net.minecraft.world.scores.PlayerTeam team = sb.getPlayerTeam(GLOW_TEAM);
        if (team != null) sb.removePlayerFromTeam(enemy.getScoreboardName(), team);
    }

    /**
     * Entity tag marking an NPC as fighting on the players' side.
     *
     * <p>Written to persistentData rather than kept only in {@link #liveAllies} because the damage handler that
     * refuses friendly fire runs on the Forge bus with nothing but the two entities in hand: it has no raid, no
     * instance and no way to ask one. A tag on the entity is the only thing available at that point. The set is
     * still the authority for everything the instance itself does; the tag is its shadow, and
     * {@link #setSide} is the one place that keeps the two in step.
     */
    public static final String ALLY_TAG = "srb_raid_ally";

    /** scoreboard team whose colour drives the GREEN glow outline on allies. */
    private static final String ALLY_GLOW_TEAM = "srb_ally_glow";

    /**
     * The ally counterpart of {@link #glowTeam()}. A separate team purely to carry a different colour: an ally and
     * an enemy standing side by side have to be told apart at a glance, and after a side swap the outline
     * changing colour is the only signal the player gets that the fight just turned over.
     */
    private net.minecraft.world.scores.PlayerTeam allyGlowTeam() {
        net.minecraft.world.scores.Scoreboard sb = server.getScoreboard();
        net.minecraft.world.scores.PlayerTeam team = sb.getPlayerTeam(ALLY_GLOW_TEAM);
        if (team == null) {
            team = sb.addPlayerTeam(ALLY_GLOW_TEAM);
            team.setColor(net.minecraft.ChatFormatting.GREEN);
            team.setSeeFriendlyInvisibles(false);
        }
        return team;
    }

    /**
     * Put {@code entity} on one side, moving it off the other.
     *
     * <p>The single choke point for side membership, and it must stay that way. Six things have to agree about
     * which side an NPC is on (the two roster sets, the two scoreboard teams, the persistent tag and the damage
     * tracker) and a swap flips all of them at once; doing it in more than one place is how they drift apart and
     * an entity ends up glowing green while still counting toward victory.
     *
     * <p>Damage-tracker registration follows side because the tracker decides who gets credit for a kill.
     * Registering an ally would let a player farm credit off their own partner.
     */
    private void setSide(LivingEntity entity, boolean ally) {
        if (entity == null) return;
        UUID id = entity.getUUID();
        net.minecraft.world.scores.Scoreboard sb = server.getScoreboard();
        if (ally) {
            liveEnemies.remove(id);
            liveAllies.add(id);
            RaidDamageTracker.clear(id);
            sb.removePlayerFromTeam(entity.getScoreboardName(), glowTeam());
            sb.addPlayerToTeam(entity.getScoreboardName(), allyGlowTeam());
            entity.getPersistentData().putBoolean(ALLY_TAG, true);
        } else {
            liveAllies.remove(id);
            liveEnemies.add(id);
            RaidDamageTracker.register(id, defId);
            sb.removePlayerFromTeam(entity.getScoreboardName(), allyGlowTeam());
            sb.addPlayerToTeam(entity.getScoreboardName(), glowTeam());
            entity.getPersistentData().remove(ALLY_TAG);
        }
        entity.setGlowingTag(true);
        // A side change invalidates whatever it was chasing: an ally must stop hitting the player it was just
        // fighting, and a former enemy must stop hitting the player it was just fighting too.
        if (entity instanceof Mob mob) mob.setTarget(null);
    }

    /** Spawn every ally in {@code tokens}. Safe with an empty or null list, which is the normal case. */
    private void spawnAllies(RaidBossDef def, ServerLevel level, List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return;
        for (String token : tokens) {
            AllySpawn spec = AllySpawn.fromToken(token);
            for (int i = 0; i < Math.max(1, spec.count); i++) {
                createAlly(def, level, spec);
            }
        }
    }

    /**
     * Build one ally through the same pipeline an enemy uses, then put it on the players' side.
     *
     * <p>Reusing {@link #createEnemy} rather than writing a parallel spawner is deliberate: the surface snap, the
     * suffocation nudge, the DMZ stat opt-out, the rgnpc look and the "did it actually enter the world" check are
     * all things an ally needs exactly as much as an enemy does, and a second copy of that code would drift.
     * The cost is that the ally is briefly registered as an enemy, which {@link #setSide} immediately undoes
     * before anything can tick.
     */
    private LivingEntity createAlly(RaidBossDef def, ServerLevel level, AllySpawn spec) {
        LivingEntity ally = createEnemy(def, level, spec.entityType, def.arena.floorCenter(),
                spec.health, spec.meleeDamage, spec.kiPower, spec.scale, spec.defense,
                java.util.List.of(),
                spec.name != null && !spec.name.isBlank() ? spec.name : null,
                spec.rgModelId);
        if (ally == null) return null;
        setSide(ally, true);
        applyAllyKi(ally, spec);
        return ally;
    }

    /**
     * Replace whatever ki loadout the shared spawn pipeline left on an ally with the ally's OWN list.
     *
     * <p>{@link #createAlly} goes through {@link #createEnemy}, and that path treats an empty move list as "unset"
     * and falls back to the encounter's {@code kiMoves}. For an enemy wave that is the intended inheritance; for an
     * ally it meant the player's partner fought using the BOSS's loadout, throwing the boss's own signature attacks.
     * So the pool is rewritten here, after the shared pipeline has run: the ally's list if it has one, and an empty
     * pool if it does not. Never the boss's.
     *
     * <p>Done here rather than by changing the fallback in {@code applyConfiguredStats}, because that fallback is
     * load bearing for enemy waves, which are supposed to inherit.
     */
    private void applyAllyKi(LivingEntity ally, AllySpawn spec) {
        if (!(ally instanceof DBSagasEntity saga)) return;
        saga.getSkillPool().clear();
        // An ally with no loadout keeps the tag harmlessly: the ranged override needs a ready skill to pick, so an
        // empty pool means it never fires, and giving the ally moves later needs no second change here.
        markRangedBias(ally);
        if (spec.kiMoves.isEmpty()) return;
        for (String token : spec.kiMoves) {
            KiMove m = KiMove.fromToken(token);
            DBSagasEntity.KiSkillType type;
            try {
                type = DBSagasEntity.KiSkillType.valueOf(m.type);
            } catch (IllegalArgumentException e) {
                continue;
            }
            saga.addKiSkill(type, Math.max(1, m.cooldown), (float) m.size);
        }
    }

    /**
     * Every tick of an active encounter: keep allies pointed at the enemy and never at a player.
     *
     * <p>Left alone, a hostile mob spawned as an ally does what its own AI says and attacks the nearest player,
     * which is the person it is supposed to be helping. Rather than rebuilding goal lists per entity type (which
     * would have to know about vanilla mobs, DMZ saga entities and rgnpc fighters separately), this simply
     * overrides the target: any ally whose target is a player, or is dead, or is another ally, is re-pointed at
     * the nearest live enemy.
     */
    private void tickAllies() {
        if (liveAllies.isEmpty()) return;
        for (UUID id : new java.util.ArrayList<>(liveAllies)) {
            LivingEntity a = enemy(id); // same resolver: it is a lookup by uuid in the arena level, not a side test
            if (a == null || !a.isAlive()) {
                if (a != null && !a.isAlive()) liveAllies.remove(id);
                continue;
            }
            if (!(a instanceof Mob mob)) continue;
            LivingEntity current = mob.getTarget();
            boolean badTarget = current == null
                    || !current.isAlive()
                    || current instanceof net.minecraft.world.entity.player.Player
                    || liveAllies.contains(current.getUUID());
            if (badTarget) mob.setTarget(nearestEnemyTo(a));
        }
    }

    /** Share of enemy retargets that pick an ally instead of a player. The rest go to the player. */
    private static final double ALLY_FOCUS_CHANCE = 0.25;

    /** How often an enemy re-rolls who it is focusing, in ticks. */
    private static final int FOCUS_REROLL_TICKS = 100;

    /**
     * Keep the pressure on the PLAYER, with the ally taking a real but minority share of it.
     *
     * <p>Nothing steered enemies before this: their targeting was left to DMZ's own AI, which takes whatever is
     * nearest. An ally is a fighter that runs at the boss and stays in its face, so it was reliably nearer than the
     * player and soaked most of the fight, which is the opposite of what an ally is for. This re-points each enemy
     * on a weighted roll instead: {@value #ALLY_FOCUS_CHANCE} to the ally, the rest to the player.
     *
     * <p>The roll happens when an enemy has no usable target, and again every {@link #FOCUS_REROLL_TICKS}, rather
     * than every tick. Re-rolling per tick would average out to the same split across the fight but read as an
     * enemy that flickers between two victims and commits to neither; on a re-roll interval it commits to one, then
     * switches, which is what "focus" means. The periodic roll is also what pulls an enemy off an ally it latched
     * onto, so the split holds over a long fight rather than only at first contact.
     *
     * <p>An enemy currently targeting another enemy counts as having no usable target: that happens after a phase
     * swap, when the thing it was fighting changed sides under it.
     */
    private void tickEnemyFocus() {
        if (liveEnemies.isEmpty()) return;
        boolean reroll = activeTicks % FOCUS_REROLL_TICKS == 0;
        for (UUID id : new java.util.ArrayList<>(liveEnemies)) {
            LivingEntity e = enemy(id);
            if (e == null || !e.isAlive() || !(e instanceof Mob mob)) continue;
            LivingEntity current = mob.getTarget();
            boolean unusable = current == null
                    || !current.isAlive()
                    || liveEnemies.contains(current.getUUID());
            if (!unusable && !reroll) continue;
            LivingEntity pick = pickFocus(e);
            if (pick != null) mob.setTarget(pick);
        }
    }

    /**
     * Weighted pick between the nearest player and the nearest ally. Falls back to whichever exists when the other
     * does not, so an encounter with no allies behaves exactly as it did before this pass existed.
     */
    private LivingEntity pickFocus(LivingEntity from) {
        LivingEntity ally = nearestAllyTo(from);
        LivingEntity player = nearestParticipantTo(from);
        if (player == null) return ally;
        if (ally == null) return player;
        return from.getRandom().nextDouble() < ALLY_FOCUS_CHANCE ? ally : player;
    }

    /** The closest live ally to {@code from} in the same level, or null when none is resolvable. */
    private LivingEntity nearestAllyTo(LivingEntity from) {
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (UUID id : liveAllies) {
            LivingEntity a = enemy(id);
            if (a == null || !a.isAlive() || a.level() != from.level()) continue;
            double d = a.distanceToSqr(from);
            if (d < bestSq) {
                bestSq = d;
                best = a;
            }
        }
        return best;
    }

    /**
     * The closest live, non-eliminated participant to {@code from} in the same level. Eliminated players are
     * skipped for the same reason {@link #confineParticipants} skips them: they have been sent home on purpose and
     * are no longer in the fight.
     */
    private LivingEntity nearestParticipantTo(LivingEntity from) {
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (UUID id : participants) {
            if (eliminated.contains(id)) continue;
            ServerPlayer p = player(id);
            if (p == null || !p.isAlive() || p.level() != from.level()) continue;
            double d = p.distanceToSqr(from);
            if (d < bestSq) {
                bestSq = d;
                best = p;
            }
        }
        return best;
    }

    /** The closest live enemy to {@code from} in the same level, or null when none is resolvable. */
    private LivingEntity nearestEnemyTo(LivingEntity from) {
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (UUID id : liveEnemies) {
            LivingEntity e = enemy(id);
            if (e == null || !e.isAlive() || e.level() != from.level()) continue;
            double d = e.distanceToSqr(from);
            if (d < bestSq) {
                bestSq = d;
                best = e;
            }
        }
        return best;
    }

    /**
     * The phase swap: every live ally becomes an enemy and every live enemy becomes an ally.
     *
     * <p>Done by moving the SAME entities between sides, never by respawning: a respawn would hand the boss back
     * its full health and throw away every point of damage the player had done to it, which turns a dramatic
     * betrayal into a punishment.
     *
     * <p>{@code stageDeaths} is reset because the swap begins a new stage in every sense that matters. Without
     * it, a death from before the swap would still be sitting in the counter, and the moment the swap emptied
     * {@link #liveEnemies} the encounter would read as cleared and hand out a victory mid-phase.
     */
    private void swapSides() {
        List<LivingEntity> wereEnemies = new java.util.ArrayList<>();
        List<LivingEntity> wereAllies = new java.util.ArrayList<>();
        for (UUID id : liveEnemies) {
            LivingEntity e = enemy(id);
            if (e != null && e.isAlive()) wereEnemies.add(e);
        }
        for (UUID id : liveAllies) {
            LivingEntity a = enemy(id);
            if (a != null && a.isAlive()) wereAllies.add(a);
        }
        for (LivingEntity e : wereEnemies) setSide(e, true);
        for (LivingEntity a : wereAllies) setSide(a, false);
        stageDeaths = 0;
        LOGGER.info("[{}] Phase swap: {} enemies became allies, {} allies became enemies.",
                defId, wereEnemies.size(), wereAllies.size());
    }

    /**
     * Keep allies inside the arena, the same way {@link #confineEnemies} keeps enemies inside it.
     *
     * <p>Needed for the same reason and one more: an ally that wanders out is not just untidy, it is a fighter
     * the player is relying on that has left the fight, and because an ally's target is re-pointed every tick it
     * would otherwise chase an enemy through the wall the moment the enemy got confined and it did not.
     */
    /**
     * Put a fighter who has left the arena back into it.
     *
     * <p>The barrier shell is what normally keeps them in, and it is not enough on its own: a player can end up
     * outside it by being placed on top of it, by a teleport of their own, by a knockback through a gap, or by an
     * admin. Outside it they are in an empty landscape a hundred thousand blocks from anything with no way back,
     * and the fight they were in carries on without them until it times out. This is the safety net for all of
     * those, and it is why it tests the ARENA rather than any particular way of leaving.
     *
     * <p>Only live participants are moved. An eliminated player has already been sent home on purpose, and dragging
     * them back would undo that; {@link #enforceEliminatedLockout} is the pass that keeps them out.
     */
    private void confineParticipants(RaidBossDef def) {
        if (def == null || def.arena == null) {
            return;
        }
        for (UUID id : participants) {
            if (eliminated.contains(id)) {
                continue;
            }
            ServerPlayer p = player(id);
            if (p == null) {
                continue;
            }
            boolean inArenaLevel = p.level().dimension().equals(def.arena.dimension());
            if (inArenaLevel && def.arena.contains(p)) {
                continue;
            }
            // The same safe placement the fight started them at, so they land on real ground rather than at the
            // clamped edge of the box.
            teleport(p, def.arena);
            p.sendSystemMessage(Component.translatable("announce.dmz_ragnarok.raid.returned_to_arena"));
        }
    }

    private void confineAllies(RaidBossDef def) {
        if (def == null || def.arena == null || liveAllies.isEmpty()) return;
        for (UUID id : liveAllies) {
            LivingEntity a = enemy(id);
            if (a == null || !a.isAlive()) continue;
            if (a.level().dimension().equals(def.arena.dimension())
                    && def.arena.containsWithMargin(a, -CONFINE_MARGIN)) {
                continue;
            }
            Vec3 clamped = def.arena.clampInside(a.position(), CONFINE_MARGIN);
            a.teleportTo(clamped.x, clamped.y, clamped.z);
            a.setDeltaMovement(Vec3.ZERO);
            a.hasImpulse = true;
        }
    }

    /** Discard every ally and forget them. Mirrors {@link #cleanupEnemies()}. */
    private void cleanupAllies() {
        net.minecraft.world.scores.Scoreboard sb = server.getScoreboard();
        net.minecraft.world.scores.PlayerTeam team = sb.getPlayerTeam(ALLY_GLOW_TEAM);
        for (UUID id : liveAllies) {
            LivingEntity a = enemy(id);
            if (a != null) {
                if (team != null) sb.removePlayerFromTeam(a.getScoreboardName(), team);
                a.discard();
            }
        }
        liveAllies.clear();
    }

    /** live count right after a spawn batch, the NPC-count bar denominator. */
    private void resetHudBaseline() {
        hudBaselineCount = Math.max(1, liveEnemies.size());
    }

    /** single boss (HP drives the bar) vs a wave of NPCs. */
    private boolean bossFight(RaidBossDef def) {
        return switch (def.raidType) {
            case PARALLEL_QUEST -> parallelBossActive;
            default -> true; // STANDARD and each BOSS_RUSH stage are single bosses
        };
    }

    /** push the bar state (count or boss HP) to every participant. */
    private void updateHud() {
        RaidBossDef def = def();
        if (def == null) return;
        net.shurui.dev.shuruis_raid_bosses.network.RaidHudPacket packet;
        if (bossFight(def) && liveEnemies.size() == 1) {
            LivingEntity boss = enemy(liveEnemies.iterator().next());
            if (boss == null) return;
            String label = boss.hasCustomName() ? boss.getCustomName().getString()
                    : TextUtil.color(def.bossName != null && !def.bossName.isBlank() ? def.bossName : def.name).getString();
            packet = new net.shurui.dev.shuruis_raid_bosses.network.RaidHudPacket(true,
                    net.shurui.dev.shuruis_raid_bosses.network.RaidHudPacket.MODE_BOSS_HP,
                    label, boss.getHealth(), boss.getMaxHealth());
        } else {
            String label = TextUtil.color("&c" + def.name).getString();
            if (def.raidType == RaidType.PARALLEL_QUEST) {
                java.util.List<Integer> waves = waveNumbers(def);
                if (waves.size() > 1 && !parallelBossActive)
                    label += Component.translatable("hud.dmz_ragnarok.raid.wave_suffix",
                            waves.indexOf(parallelWave) + 1, waves.size()).getString();
            }
            packet = new net.shurui.dev.shuruis_raid_bosses.network.RaidHudPacket(true,
                    net.shurui.dev.shuruis_raid_bosses.network.RaidHudPacket.MODE_NPC_COUNT,
                    label, liveEnemies.size(), Math.max(hudBaselineCount, liveEnemies.size()));
        }
        sendHudToParticipants(packet);
    }

    private void clearHud() {
        sendHudToParticipants(net.shurui.dev.shuruis_raid_bosses.network.RaidHudPacket.clear());
        // clearHud() is the choke point every teardown funnels through (victory, timeout, wipe, cancel,
        // abandon, resetToIdle via cleanupEnemies), so the boss loop is cut here for everyone still hearing
        // it: the boss dying, the fight ending, or the tear closing all stop the music the same way.
        stopBossMusicAll();
    }

    private void sendHudToParticipants(net.shurui.dev.shuruis_raid_bosses.network.RaidHudPacket packet) {
        for (UUID id : participants) {
            ServerPlayer p = player(id);
            if (p != null) net.shurui.dev.shuruis_raid_bosses.network.RaidNet.sendToPlayer(packet, p);
        }
    }

    /**
     * Bring the boss music each present fighter hears into line with the fight: start it for a fighter who has
     * appeared and is not eliminated, stop it for one who has died, left, or logged out. Edge triggered off
     * {@link #musicSent}, so a start reaches a client once (a reconnecting fighter is re-sent one because they
     * dropped out of the set when they went offline) and never stacks. A no-op when the encounter sets no music.
     */
    private void reconcileBossMusic() {
        RaidBossDef def = def();
        String track = effectiveBossMusic(def);
        if (track == null || track.isBlank()) {
            stopBossMusicAll();
            return;
        }
        // stop anyone the roster no longer counts as a live fighter (eliminated, left, or logged out)
        musicSent.removeIf(id -> {
            boolean live = participants.contains(id) && !eliminated.contains(id) && player(id) != null;
            if (!live) {
                ServerPlayer p = player(id);
                if (p != null) {
                    net.shurui.dev.shuruis_raid_bosses.network.RaidNet.sendToPlayer(
                            net.shurui.dev.shuruis_raid_bosses.network.BossMusicPacket.stop(), p);
                }
                return true;
            }
            return false;
        });
        // start it for a live fighter who is not already hearing it (fresh entry or a reconnect)
        for (UUID id : participants) {
            if (eliminated.contains(id) || musicSent.contains(id)) continue;
            ServerPlayer p = player(id);
            if (p == null) continue;
            net.shurui.dev.shuruis_raid_bosses.network.RaidNet.sendToPlayer(
                    net.shurui.dev.shuruis_raid_bosses.network.BossMusicPacket.start(track), p);
            musicSent.add(id);
        }
    }

    /**
     * The track an encounter should actually loop. Normally the operator-set {@link RaidBossDef#bossMusic}, but a
     * blank field resolves to the boss entity's own default so a Snipperjack encounter built BEFORE the music field
     * existed (its blank field never got the editor's seed) still plays his track without needing a re-save. Any
     * other boss with a blank field stays silent, the intended default: nothing else maps a boss to a track.
     */
    private static String effectiveBossMusic(RaidBossDef def) {
        if (def == null) return null;
        if (def.bossMusic != null && !def.bossMusic.isBlank()) return def.bossMusic;
        if (RaidBossDef.SNIPPERJACK_ENTITY.equals(def.bossEntityType)) return RaidBossDef.SNIPPERJACK_MUSIC;
        return null;
    }

    /** Cut the boss loop for every fighter the server believes is still hearing it. Idempotent. */
    private void stopBossMusicAll() {
        if (musicSent.isEmpty()) return;
        for (UUID id : musicSent) {
            ServerPlayer p = player(id);
            if (p != null) {
                net.shurui.dev.shuruis_raid_bosses.network.RaidNet.sendToPlayer(
                        net.shurui.dev.shuruis_raid_bosses.network.BossMusicPacket.stop(), p);
            }
        }
        musicSent.clear();
    }

    public void tick() {
        switch (state) {
            case IDLE -> { }
            case SIGNUP -> {
                if (System.currentTimeMillis() >= signupEndMillis) {
                    begin(signups);
                } else if (signupReminderTicks > 0 && --signupReminderTicks <= 0) {
                    RaidBossDef def = def();
                    if (def != null) {
                        announceSignupOpen(def);
                        signupReminderTicks = def.signupReminderSeconds > 0 ? def.signupReminderSeconds * 20 : 0;
                    }
                }
            }
            case COUNTDOWN -> {
                // Music starts the instant fighters are in the arena (COUNTDOWN, before the boss spawns), which
                // for a rift is the moment they enter the tear, and keeps reconciling through the fight so a
                // reconnect restarts it and a death or a logout ends it for that fighter alone.
                reconcileBossMusic();
                tickCountdown();
            }
            case ACTIVE -> {
                reconcileBossMusic();
                tickActive();
            }
            case FINISHED -> {
                if (--phaseTimer <= 0) {
                    if (returnWhenFinished) {
                        returnAllParticipants();
                    }
                    resetToIdle();
                }
            }
        }
    }

    private void tickCountdown() {
        if (phaseTimer % 20 == 0) {
            int secs = phaseTimer / 20;
            // The fighter always gets their own countdown. The op fan-out that comes with a participant-scoped
            // broadcast is right for a public raid, where staff may want to watch, and wrong for a tear: that is one
            // player in a sealed arena nobody else can enter, so a line a second about it reaches every op online for
            // a fight none of them can join. This is the same reasoning as the silent() guard on the boss-rush timer
            // just below, which this call was simply missing.
            if (secs > 0) Announcer.broadcastTo(server, participants, Component.translatable(
                    "announce.dmz_ragnarok.raid.countdown", secs), !silent());
        }
        if (--phaseTimer <= 0) {
            startEncounter();
        }
    }

    private void tickActive() {
        RaidBossDef def = def();
        activeTicks++;

        // BOSS_RUSH: counting down to the next stage, not fighting right now.
        if (rushDelayTicks > 0) {
            if (rushDelayTicks % 20 == 0) {
                if (!silent()) {
                    Announcer.broadcastTo(server, participants, Component.translatable(
                            "announce.dmz_ragnarok.raid.next_boss", TextUtil.formatDuration(rushDelayTicks / 20)));
                }
            }
            if (--rushDelayTicks <= 0) {
                ServerLevel level = server.getLevel(def.arena.dimension());
                if (level != null) spawnRushStage(def, level, rushIndex);
            }
            return;
        }

        // prune liveEnemies. two cases that MUST be told apart:
        //   * resolvable-but-dead (e != null && !e.isAlive()): a real death (e.g. /kill with no event, or a frame
        //     the handler missed). prune now and COUNT it as a stage death.
        //   * unresolvable (e == null): just not resolvable THIS tick (chased into an unloaded chunk, changed
        //     dimension, or mid-transform before adoptTransformed re-adds the new uuid). NOT a death: hold under
        //     a per-id grace window, drop only after NULL_GRACE_TICKS, and even then flag it a failure not a kill.
        expireVanishRecords();
        boolean graceFailure = false;
        for (java.util.Iterator<UUID> it = liveEnemies.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            LivingEntity e = enemy(id);
            if (e != null && e.isAlive()) {
                nullGraceTicks.remove(id); // resolvable + alive, reset any grace
                lastKnownPos.put(id, e.position()); // in case it vanishes into a transform next tick
                // DMZ saga bosses can clear the GLOWING flag each tick, so re-pin it. cheap: near no-ops when set.
                applyGlow(e);
                continue;
            }
            if (e != null) {
                // resolvable AND dead: real defeat, prune and credit as a stage death.
                nullGraceTicks.remove(id);
                lastKnownPos.remove(id);
                removeGlow(e);
                drain(id);
                RaidDamageTracker.clear(id);
                stageDeaths++;
                it.remove();
                continue;
            }
            // e == null: unresolvable this tick, the fingerprint of a DMZ-native transform discard (old form gone
            // with NO death event, new form about to spawn nearby). record the vanish on the FIRST null tick using
            // the last resolvable position so adoptTransformed can match the incoming form. onEntityDeath removes
            // the id before this on a real kill, so only genuine no-death disappearances reach here.
            if (!nullGraceTicks.containsKey(id)) {
                Vec3 pos = lastKnownPos.get(id);
                if (pos != null) vanishRecords.addLast(new VanishRecord(server.getTickCount(), pos));
            }
            // age the grace window; keep it in liveEnemies until it expires so a transient-null / mid-transform
            // boss isn't counted as gone.
            int ticks = nullGraceTicks.merge(id, 1, Integer::sum);
            if (ticks >= NULL_GRACE_TICKS) {
                nullGraceTicks.remove(id);
                lastKnownPos.remove(id);
                RaidDamageTracker.clear(id);
                it.remove();
                graceFailure = true; // never resolved this boss, resolve as failure not a win
            }
        }

        // VICTORY BEFORE WIPE: check the cleared-stage gate FIRST, ahead of every failure below. a stage that
        // emptied on a REAL death (stageDeaths > 0) is always a win/advance and must never lose a same-tick race
        // to the grace-failure or party wipe. fixes a legit boss kill being beaten by the death-save -> eliminated
        // -> allParticipantsEliminated wipe, or by a stale ghost timing out.
        if (tryResolveClearedStage(def)) return;

        // boss stuck unresolvable past its grace window = lost/broken raid, never a kill. only reached when the
        // stage did NOT clear on a real death above.
        if (graceFailure && liveEnemies.isEmpty()) {
            wipe();
            return;
        }

        // confine every enemy to the arena EVERY tick. DMZ saga AI teleports to its target's position with no
        // bounds check, so a bounced-out player drags the boss out. that teleport fires per entity-tick, so clamp
        // per-tick (not every 10) right before an enemy crosses a wall.
        confineEnemies(def);
        // Allies are steered AFTER the confine pass, so an ally that was just teleported back inside the arena
        // picks its target from where it actually is rather than from where it was about to leave.
        confineAllies(def);
        tickAllies();
        // After tickAllies, so an ally that was just re-pointed is already on its enemy when the enemy picks who
        // to focus back.
        tickEnemyFocus();

        if (activeTicks % 10 == 0) {
            updateHud();
            enforceEliminatedLockout(def);
            confineParticipants(def);
        }

        // WIPE: everyone eliminated with the boss still alive is a failure. the victory gate ran above, so an
        // empty-with-real-death stage never reaches this.
        if (!liveEnemies.isEmpty() && allParticipantsEliminated()) {
            wipe();
            return;
        }

        int limit = def.fightTimeLimit;
        if (limit > 0 && activeTicks >= limit * 20L) {
            timeout();
        }
    }

    /**
     * cleared-stage / victory gate, split out of {@link #tickActive} so it runs BEFORE any wipe. gated on REAL
     * DEATHS clearing the stage, not just an empty liveEnemies: stageDeaths only moves on {@link #onEntityDeath},
     * a resolvable-dead prune, or a credited untracked kill ({@link #creditUntrackedBossKill}), never the
     * null/grace path, so a roster drained purely by unresolvable entities can't resolve here.
     *
     * <p>true = stage resolved (caller must return now): rush advances, parallel rolls its next wave/boss,
     * everything else is a victory. false = not cleared, caller falls through to its wipe checks.
     */
    private boolean tryResolveClearedStage(RaidBossDef def) {
        if (!(liveEnemies.isEmpty() && stageDeaths > 0)) return false;
        if (def.raidType == RaidType.BOSS_RUSH && rushIndex + 1 < def.rushStages.size()) {
            advanceRush(def);
        } else if (def.raidType == RaidType.PARALLEL_QUEST && nextParallelStep(def)) {
            return true;
        } else {
            resolveVictory(def);
        }
        return true;
    }

    /**
     * late-adoption-on-death credit path. a DMZ saga boss can do its FINAL transform with no death event and end
     * up untracked (adoptTransformed missed it: >32 blocks out, or the handoff fell outside the vanish
     * radius/window). the player kills that untracked final form, onLivingDeath ignores it (isBoss == false), so
     * stageDeaths never moves and the vanished ghost times out into a false FAILURE. fix: when such a boss dies
     * INSIDE this ACTIVE arena while a tracked boss recently vanished, credit a stage death so the next tick's
     * victory gate fires.
     *
     * <p>false-win guard: needs (a) ACTIVE, (b) death inside the arena (same adopt margin), (c) a matching recent
     * VanishRecord near the death, the same handoff signal adoptTransformed uses. a random saga boss dying nearby
     * with no vanished tracked boss is rejected. double-count guard: a normal TRACKED death goes through
     * onEntityDeath and is filtered by the caller (!isBoss), so this only runs for UNtracked entities.
     */
    public boolean creditUntrackedBossKill(LivingEntity dead) {
        if (state != RaidState.ACTIVE || dead == null) return false;
        RaidBossDef def = def();
        if (def == null || def.arena == null) return false;
        // death must be inside the arena (same margin adoptTransformed uses).
        if (!def.arena.containsWithMargin(dead, ADOPT_MARGIN)) return false;
        // need the handoff fingerprint: a tracked boss vanished-without-dying recently and near this death.
        // consumeVanishNear burns the record so one vanish credits at most one kill. no match = unrelated saga
        // boss, reject.
        if (!consumeVanishNear(dead.position())) return false;

        // drop any stale ghost id lingering near the death so it can't time out into a wipe after we credit the
        // win: the null-grace ids whose last-known position sits within the handoff radius.
        double radiusSq = VANISH_RADIUS * VANISH_RADIUS;
        for (java.util.Iterator<UUID> it = liveEnemies.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (enemy(id) != null) continue; // only prune ghosts, never a live tracked enemy
            Vec3 pos = lastKnownPos.get(id);
            if (pos == null || pos.distanceToSqr(dead.position()) > radiusSq) continue;
            nullGraceTicks.remove(id);
            lastKnownPos.remove(id);
            RaidDamageTracker.clear(id);
            it.remove();
        }
        stageDeaths++; // credit the untracked final-form kill so next tick's victory gate fires
        LOGGER.info("[{}] Credited an untracked boss kill inside the arena (final-form transform with no death event).",
                def.name);
        return true;
    }

    /** PARALLEL_QUEST: next wave, or the boss finale, once the batch is cleared. true if something new spawned. */
    private boolean nextParallelStep(RaidBossDef def) {
        ServerLevel level = server.getLevel(def.arena.dimension());
        if (level == null) return false;
        java.util.List<Integer> waves = waveNumbers(def);
        int at = waves.indexOf(parallelWave);
        if (at >= 0 && at + 1 < waves.size()) {
            int next = waves.get(at + 1);
            if (!silent()) {
                Announcer.broadcastTo(server, participants, Component.translatable("announce.dmz_ragnarok.raid.wave_incoming",
                        TextUtil.color(def.name), at + 2, waves.size()));
            }
            spawnParallelWave(def, level, next);
            return !liveEnemies.isEmpty();
        }
        if (def.parallelBoss && !parallelBossActive) {
            if (!silent()) {
                Announcer.broadcastTo(server, participants, Component.translatable("announce.dmz_ragnarok.raid.boss_appeared",
                        TextUtil.color(def.name)));
            }
            spawnParallelBoss(def, level);
            return !liveEnemies.isEmpty();
        }
        return false;
    }

    private void advanceRush(RaidBossDef def) {
        rushIndex++;
        BossStage next = BossStage.fromToken(def.rushStages.get(rushIndex));
        rushDelayTicks = Math.max(0, next.delaySeconds) * 20;
        if (rushDelayTicks == 0) {
            ServerLevel level = server.getLevel(def.arena.dimension());
            if (level != null) spawnRushStage(def, level, rushIndex);
        }
    }

    /** from the death handler when one of our tracked entities dies. */
    public void onEntityDeath(UUID deadId, UUID killerId) {
        if (!liveEnemies.contains(deadId)) {
            RaidDamageTracker.clear(deadId);
            return;
        }
        if (killerId != null) kills.merge(killerId, 1, Integer::sum);
        removeGlow(enemy(deadId));
        drain(deadId);
        RaidDamageTracker.clear(deadId);
        liveEnemies.remove(deadId);
        nullGraceTicks.remove(deadId);
        lastKnownPos.remove(deadId); // real kill, no handoff vanish record for this id
        stageDeaths++; // a real LivingDeathEvent is the only trusted "actually died" signal
        // resolution waits for next tick so multi-death frames settle first.
    }

    /** roll a dying enemy's per-player damage into the raid-wide tally. */
    private void drain(UUID enemyId) {
        for (Map.Entry<UUID, Double> e : RaidDamageTracker.snapshot(enemyId).entrySet()) {
            accumDamage.merge(e.getKey(), e.getValue(), Double::sum);
        }
    }

    private void drainAll() {
        for (UUID id : liveEnemies) drain(id);
    }

    private void resolveVictory(RaidBossDef def) {
        drainAll();
        Map<UUID, Double> damage = new LinkedHashMap<>(accumDamage);
        double total = damage.values().stream().mapToDouble(Double::doubleValue).sum();

        // everyone who dealt damage or scored a kill.
        Set<UUID> everyone = new LinkedHashSet<>(damage.keySet());
        everyone.addAll(kills.keySet());

        boolean mvpMode = def.raidType == RaidType.PARALLEL_QUEST;
        List<UUID> ranked = new ArrayList<>(everyone);
        ranked.sort(Comparator.comparingDouble((UUID id) -> score(def, damage, id, mvpMode)).reversed());

        String topName = ranked.isEmpty()
                ? Component.translatable("raid.dmz_ragnarok.raid.nobody").getString() : name(ranked.get(0));
        double topDmg = ranked.isEmpty() ? 0 : damage.getOrDefault(ranked.get(0), 0.0);
        double topPercent = (total > 0) ? topDmg / total * 100.0 : 0.0;
        int topKills = ranked.isEmpty() ? 0 : kills.getOrDefault(ranked.get(0), 0);

        for (int i = 0; i < ranked.size(); i++) {
            UUID id = ranked.get(i);
            ServerPlayer p = player(id);
            if (p == null) continue;
            Map<String, String> ph = placeholders(def, damage.getOrDefault(id, 0.0), total,
                    kills.getOrDefault(id, 0), i + 1);
            RewardManager.giveAll(server, p, def.participantRewards, ph);
            if (i == 0) RewardManager.giveAll(server, p, def.topRewards, ph);
            else if (i <= 3) RewardManager.giveAll(server, p, def.runnerUpRewards, ph);
            // Event overlay: an active timed event (via the core sdu hook, never the key/SU directly) may add bonus
            // reward tokens for this rank. Keyless / no event: the hook returns none and nothing changes.
            java.util.List<String> eventBonus = net.shurui.dev.sdu.api.key.EventHooks.get()
                    .raidBonusTokens(def.id, def.raidType.name(), i + 1);
            if (!eventBonus.isEmpty()) RewardManager.giveAll(server, p, eventBonus, ph);
        }

        String victory = fmt(def.msgVictory, def)
                .replace("{top}", topName)
                .replace("{top_percent}", fmtPercent(topPercent))
                .replace("{mvp}", topName)
                .replace("{kills}", String.valueOf(topKills));
        Component victoryTitle = mvpMode
                ? Component.translatable("announce.dmz_ragnarok.raid.title.mvp", topName)
                : Component.translatable("announce.dmz_ragnarok.raid.title.victory");
        if (!silent()) {
            Announcer.announceTo(server, participants, victory, victoryTitle, def);
        }
        // The fighters STAY in the arena for the FINISHED phase and are sent home when it ends: ten seconds to watch
        // the thing fall over and pick up what it dropped, instead of being yanked out the instant its health hit
        // zero. The phase already lasted ten seconds; the players simply were not in the arena for any of it.
        returnWhenFinished = true;
        cleanupEnemies();
        phaseTimer = VICTORY_HOLD_TICKS;
        state = RaidState.FINISHED;
        // The win is banked NOW rather than when they are returned: the rewards have already been handed out, and a
        // logout or a server stop inside those ten seconds must not turn a win that happened into a loss.
        sendOutcome(true);
    }

    private double score(RaidBossDef def, Map<UUID, Double> damage, UUID id, boolean mvpMode) {
        double dmg = damage.getOrDefault(id, 0.0);
        if (!mvpMode) return dmg;
        return dmg + def.mvpKillWeight * kills.getOrDefault(id, 0);
    }

    private Map<String, String> placeholders(RaidBossDef def, double dmg, double total, int killCount, int rank) {
        Map<String, String> ph = new LinkedHashMap<>();
        double percent = total > 0 ? dmg / total * 100.0 : 0.0;
        ph.put("%damage%", String.valueOf((long) dmg));
        ph.put("%damage_percent%", fmtPercent(percent));
        ph.put("%kills%", String.valueOf(killCount));
        ph.put("%rank%", String.valueOf(rank));
        ph.put("%boss%", def.bossName);
        ph.put("%total_damage%", String.valueOf((long) total));
        return ph;
    }

    private void timeout() {
        RaidBossDef def = def();
        if (!silent()) {
            Announcer.announceTo(server, participants, fmt(def.msgTimeout, def),
                    Component.translatable("announce.dmz_ragnarok.raid.title.failed"), def);
        }
        returnAllParticipants();
        cleanupEnemies();
        phaseTimer = 5 * 20;
        state = RaidState.FINISHED;
        sendOutcome(false);
    }

    private boolean allParticipantsEliminated() {
        return !participants.isEmpty() && eliminated.containsAll(participants);
    }

    /**
     * failure teardown shared by a full party wipe and a boss stuck unresolvable past its grace window. mirrors
     * {@link #timeout()}: NOT a victory, no rewards or MVP, just the raid-failed message and cleanup.
     */
    private void wipe() {
        RaidBossDef def = def();
        Component title = Component.translatable("announce.dmz_ragnarok.raid.title.failed");
        if (def != null) {
            if (!silent()) {
                Announcer.announceTo(server, participants, fmt(def.msgTimeout, def), title, def);
            }
        } else {
            if (!silent()) {
                Announcer.announceTo(server, participants, Component.translatable("announce.dmz_ragnarok.raid.raid_failed"), title, null);
            }
        }
        returnAllParticipants();
        cleanupEnemies();
        phaseTimer = 5 * 20;
        state = RaidState.FINISHED;
        sendOutcome(false);
    }

    public void cancel() {
        if (state == RaidState.IDLE) return;
        RaidBossDef def = def();
        if (!silent()) {
            Announcer.broadcast(server, Component.translatable("announce.dmz_ragnarok.raid.cancelled_admin",
                    TextUtil.color(def != null ? def.name : defId)));
        }
        returnAllParticipants();
        cleanupEnemies();
        // A cancelled run is a FAILED one as far as a tear is concerned: nobody won it, and the arena cell has to be
        // released either way. Sent before the reset so the listener still sees the run it belongs to.
        sendOutcome(false);
        resetToIdle();
    }

    /**
     * End a run with no broadcast: the same teardown as {@link #cancel()} without telling the server an admin did
     * it. For a dimensional tear whose fighter has gone, where the only person who could care is the one who left,
     * and announcing an admin cancellation to everybody else would simply be untrue.
     */
    public void abandon() {
        if (state == RaidState.IDLE) return;
        returnAllParticipants();
        cleanupEnemies();
        sendOutcome(false);
        resetToIdle();
    }

    private void cleanupEnemies() {
        for (UUID id : liveEnemies) {
            LivingEntity e = enemy(id);
            if (e != null) {
                removeGlow(e); // unteam before discard so no ghost entries linger on the shared team
                e.discard();
            }
            RaidDamageTracker.clear(id);
        }
        liveEnemies.clear();
        cleanupAllies();
        lastKnownPos.clear();
        vanishRecords.clear();
        clearHud();
    }

    private void resetToIdle() {
        // Catch-all for the resolutions that reach idle without passing through victory or wipe: too few
        // participants to begin, a missing arena level, a raid type the key does not allow. Guarded by
        // outcomeSent, so a run that already reported its result is not re-reported here as a loss.
        sendOutcome(false);
        // The raid is over on this server; drop it from the network host directory so nobody is routed to a raid
        // that has ended. A tear never recorded itself, so it clears nothing.
        if (!silent()) RaidNetwork.clearHost(defId);
        cleanupEnemies();
        state = RaidState.IDLE;
        pickedDefId = null;
        pickedCache = null;
        signups.clear();
        signupLocations.clear();
        participants.clear();
        eliminated.clear();
        liveAllies.clear();
        accumDamage.clear();
        kills.clear();
        stageDeaths = 0;
        nullGraceTicks.clear();
        lastKnownPos.clear();
        vanishRecords.clear();
        activeTicks = 0;
        rushIndex = 0;
        rushDelayTicks = 0;
        returnWhenFinished = false;
    }

    private LivingEntity enemy(UUID id) {
        if (id == null) return null;
        RaidBossDef def = def();
        if (def == null || def.arena == null) return null;
        ServerLevel level = server.getLevel(def.arena.dimension());
        if (level == null) return null;
        Entity e = level.getEntity(id);
        return e instanceof LivingEntity le ? le : null;
    }

    /** teleport one signed-up player into the arena (sign-up GUI/command). */
    public boolean teleportToArena(ServerPlayer p) {
        RaidBossDef def = def();
        if (def == null || def.arena == null) return false;
        teleport(p, def.arena);
        return true;
    }

    /** True once the fight itself is under way, i.e. {@link #begin} has run and not yet finished. */
    public boolean started() {
        return state == RaidState.COUNTDOWN || state == RaidState.ACTIVE;
    }

    /**
     * safely place a player into this raid's arena, reusing the same scatter/surface-snap path as sign-up
     * and re-entry. used by {@code /raid join} so that command never drops players onto the raw NPC
     * coordinates (which could be underground). true if the raid has an arena and the player was moved.
     *
     * <p>Signing up does NOT move anyone: this declines while the raid is still in sign-up, so a player who
     * joins early keeps playing where they are until the raid actually begins. {@link #begin} is the one
     * place that gathers the roster into the arena. The gate belongs here rather than at the call sites
     * because every join-placement path (the command, and a cross-shard arrival) goes through this method,
     * while the sign-up screen's deliberate "teleport to arena" button calls {@link #teleportToArena}
     * directly and is meant to keep working during sign-up.
     *
     * <p>Note that {@link #join} only succeeds in {@link RaidState#SIGNUP}, and a join that fills the roster
     * calls {@link #begin} inline, which teleports the whole roster itself. So the started() case here is a
     * player arriving into a fight already running, not a second teleport for one begin() just handled.
     */
    public boolean placeInArena(ServerPlayer p) {
        if (!started()) return false;
        return teleportToArena(p);
    }

    private void teleport(ServerPlayer p, Region region) {
        RaidBossDef def = def();
        // A configured player spawn wins over the arena. It is a place an admin deliberately picked, so players are
        // put inside it as drawn rather than scattered across the whole arena and surface-snapped.
        if (def != null && def.playerSpawn != null) {
            ServerLevel spawnLevel = server.getLevel(def.playerSpawn.dimension());
            if (spawnLevel != null) {
                // Standable, not the raw box floor: an admin-drawn spawn whose lower corner sits below the
                // ground would otherwise put arrivals inside the terrain. Every other path into an arena already
                // surface-snaps; this one did not.
                Vec3 at = def.playerSpawn.randomStandableInside(spawnLevel, p.getRandom());
                p.teleportTo(spawnLevel, at.x, at.y, at.z, p.getYRot(), p.getXRot());
                stopMomentum(p);
                return;
            }
        }
        ServerLevel level = server.getLevel(region.dimension());
        if (level == null) return;
        // scatter + snap to a SAFE surface spot (force-loads the chunk, validates ground/headroom, re-rolls,
        // falls back to the live boss or guarded arena centre). never drops the player to the world floor.
        // prefer the live boss position as the fallback if one is up, else the guarded centre.
        Vec3 bossPos = firstLiveEnemyPos();
        Vec3 snapped = (def != null && def.arena != null)
                ? safeSurfaceSpot(level, def, p.getRandom(), bossPos)
                : surfaceSnap(level, region.randomInside(p.getRandom()));
        p.teleportTo(level, snapped.x, snapped.y, snapped.z, p.getYRot(), p.getXRot());
        stopMomentum(p);
    }

    /**
     * Kill a placed player's carried velocity. A fighter who walked into a tear while flying fast keeps that
     * momentum through the teleport (vanilla teleportTo does not clear it), so they would arrive already moving
     * and could fly straight back out of the arena, tripping confineParticipants every pass. Arriving at a stand
     * is what every placement here intends, so this is unconditional.
     */
    private static void stopMomentum(ServerPlayer p) {
        p.setDeltaMovement(Vec3.ZERO);
        p.hasImpulse = true;
        p.fallDistance = 0.0F;
    }

    /** actual position of the first live tracked enemy (a validated spawned point), or null if none up. */
    private Vec3 firstLiveEnemyPos() {
        for (UUID id : liveEnemies) {
            LivingEntity e = enemy(id);
            if (e != null && e.isAlive()) return e.position();
        }
        return null;
    }

    private void rememberLocation(ServerPlayer player) {
        signupLocations.put(player.getUUID(), new ReturnPoint(player.level().dimension(),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
    }

    /**
     * death-save: instead of a lethal blow, send the player back to where they signed up and fully restore them.
     * true if rescued.
     */
    public boolean rescueFromLethal(ServerPlayer p) {
        if (state != RaidState.ACTIVE) return false;
        if (!participants.contains(p.getUUID())) return false;
        if (!returnToStart(p)) return false;
        DmzHooks.fullHeal(p);
        DmzHooks.clearCombatLocks(p); // fullHeal leaves STUN/strikeLocked behind, drop the lockout too
        eliminated.add(p.getUUID()); // falling once is final, no re-entry this raid
        return true;
    }

    /** participant actually died mid-raid (no death-save): out for the rest of it. */
    public void onParticipantDeath(ServerPlayer p) {
        if (state == RaidState.ACTIVE && participants.contains(p.getUUID())) {
            eliminated.add(p.getUUID());
        }
    }

    /** Blocks each enemy is kept away from the arena walls when it is clamped back inside. */
    private static final double CONFINE_MARGIN = 1.0;

    /**
     * Per-tick confinement pass: keep every live enemy inside the arena. DMZ's saga-boss AI teleports to
     * its target's current position with no bounds check, so a defeated player bounced outside the arena
     * would otherwise drag the boss out and back. When an enemy reaches/crosses a (slightly-shrunk) wall we
     * reposition it just inside, kill its momentum, and drop any out-of-bounds AI target so it doesn't
     * immediately re-teleport toward an out-of-arena player. Additive only, no other behaviour changes.
     */
    private void confineEnemies(RaidBossDef def) {
        if (def == null || def.arena == null) return;
        for (UUID id : liveEnemies) {
            LivingEntity e = enemy(id);
            if (e == null || !e.isAlive()) continue;
            // Negative margin shrinks the box (containsWithMargin uses aabb().inflate(margin)): correct the
            // entity just before it actually crosses the wall.
            if (def.arena.containsWithMargin(e, -CONFINE_MARGIN)) continue;
            Vec3 clamped = def.arena.clampInside(e.position(), CONFINE_MARGIN);
            e.teleportTo(clamped.x, clamped.y, clamped.z);
            e.setDeltaMovement(Vec3.ZERO);
            e.hasImpulse = true;
            if (e instanceof Mob mob && mob.getTarget() != null
                    && !def.arena.containsWithMargin(mob.getTarget(), 0.0)) {
                // Don't just drop the target to null: a confine-drop immediately followed by an sdu transform
                // swap would hand the next form a null target and leave it permanently inert. Re-target to the
                // nearest in-arena active participant instead; only fall back to null if the arena is empty.
                ServerPlayer inArena = nearestParticipantInArena(def, e);
                mob.setTarget(inArena); // may be null only when no valid participant remains inside
            }
        }
    }

    /**
     * Nearest active (signed-up, not-eliminated, alive, in-arena) participant to {@code from}, or null if
     * none remain inside the arena. Used to re-target a confined enemy instead of dropping its target to null.
     */
    private ServerPlayer nearestParticipantInArena(RaidBossDef def, LivingEntity from) {
        ServerPlayer best = null;
        double bestSq = Double.MAX_VALUE;
        for (UUID id : participants) {
            if (eliminated.contains(id)) continue;
            ServerPlayer p = player(id);
            if (p == null || !p.isAlive() || p.isSpectator()) continue;
            if (!p.level().dimension().equals(def.arena.dimension())) continue;
            if (!def.arena.containsWithMargin(p, 0.0)) continue;
            double d = p.distanceToSqr(from);
            if (d < bestSq) {
                bestSq = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * Eliminated players may not return to the fight: anyone who fell this raid and walks back into the
     * arena is bounced to the world spawn with a reminder. Checked twice a second while the raid runs.
     *
     * <p>When {@code def.preventReentry} is ON this widens to bounce EVERY player inside the arena who is
     * not a current active participant (a participant who has not been eliminated): so eliminated fighters
     * stay locked out AND any non-participant who wanders in is ejected. The default (OFF) path is unchanged:
     * only eliminated players inside the arena are bounced.</p>
     */
    private void enforceEliminatedLockout(RaidBossDef def) {
        if (def == null || def.arena == null) return;

        if (def.preventReentry) {
            ServerLevel arenaLevel = server.getLevel(def.arena.dimension());
            if (arenaLevel == null) return;
            for (ServerPlayer p : arenaLevel.players()) {
                if (p.isSpectator()) continue;
                // Defensive dimension guard: iterating arenaLevel.players() already guarantees this, but keep
                // the explicit check so the intent stays correct if the iteration source ever changes.
                if (!p.level().dimension().equals(def.arena.dimension())) continue;
                if (!def.arena.containsWithMargin(p, 8.0)) continue;
                UUID id = p.getUUID();
                // Active participant = signed-up participant who has not been eliminated. Everyone else is bounced.
                boolean activeParticipant = participants.contains(id) && !eliminated.contains(id);
                if (activeParticipant) continue;
                bounceOut(p);
            }
            return;
        }

        if (eliminated.isEmpty()) return;
        for (UUID id : eliminated) {
            ServerPlayer p = player(id);
            if (p == null || p.isSpectator()) continue;
            if (!def.arena.containsWithMargin(p, 8.0)) continue;
            bounceOut(p);
        }
    }

    /** Teleport a player out of the arena to the world spawn with the re-entry reminder. */
    private void bounceOut(ServerPlayer p) {
        ServerLevel overworld = server.overworld();
        var spawn = overworld.getSharedSpawnPos();
        p.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, p.getYRot(), p.getXRot());
        DmzHooks.clearCombatLocks(p); // don't strand a bounced player with a lingering DMZ combat lockout
        p.sendSystemMessage(Component.translatable("raid.dmz_ragnarok.raid.already_fought"));
    }

    /**
     * Teleport a player back to where they stood when they joined this raid, as if they ran {@code /back}.
     * Returns false if we never recorded a return point for them or the target dimension is gone.
     */
    private boolean returnToStart(ServerPlayer p) {
        ReturnPoint rp = signupLocations.get(p.getUUID());
        if (rp == null) return false;
        ServerLevel level = server.getLevel(rp.dimension());
        if (level == null) return false;
        p.teleportTo(level, rp.x(), rp.y(), rp.z(), rp.yaw(), rp.pitch());
        return true;
    }

    /** Send every participant back to where they teleported in from. Called when the raid ends. */
    private void returnAllParticipants() {
        for (UUID id : participants) {
            ServerPlayer p = player(id);
            if (p != null) {
                returnToStart(p);
                DmzHooks.clearCombatLocks(p); // ensure nobody leaves the raid stuck in a DMZ stun/knockdown lockout
            }
        }
    }

    /** Where a player stood when they signed up, so a lethal hit can return them there. */
    private record ReturnPoint(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                               double x, double y, double z, float yaw, float pitch) {}

    private ServerPlayer player(UUID id) {
        return id == null ? null : server.getPlayerList().getPlayer(id);
    }

    private String name(UUID id) {
        if (id == null) return Component.translatable("raid.dmz_ragnarok.raid.unknown").getString();
        ServerPlayer p = player(id);
        if (p != null) return p.getGameProfile().getName();
        return names.getOrDefault(id, id.toString().substring(0, 8));
    }

    private String fmt(String template, RaidBossDef def) {
        return template.replace("{name}", def.name).replace("{id}", def.id)
                .replace("{npc}", def.npcName).replace("{boss}", def.bossName);
    }

    private static String fmtPercent(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    public Component statusSummary() {
        RaidBossDef def = def();
        Component typeName = def != null ? def.raidType.display() : Component.literal("?");
        net.minecraft.network.chat.MutableComponent out = Component.translatable(
                "status.dmz_ragnarok.raid.header",
                TextUtil.color(def != null ? def.name : defId), typeName, state.name());
        if (state == RaidState.IDLE && def != null && def.scheduleEnabled) {
            long next = Scheduler.nextRunMillis(def);
            if (next > 0) {
                long secs = Math.max(0, (next - System.currentTimeMillis()) / 1000);
                out.append(Component.translatable("status.dmz_ragnarok.raid.opens_in", TextUtil.formatDuration(secs)));
            }
        } else if (state == RaidState.SIGNUP) {
            long secs = Math.max(0, (signupEndMillis - System.currentTimeMillis()) / 1000);
            out.append(Component.translatable("status.dmz_ragnarok.raid.signup",
                    signups.size(), TextUtil.formatDuration(secs)));
        } else if (state == RaidState.ACTIVE) {
            out.append(Component.translatable("status.dmz_ragnarok.raid.active",
                    participants.size(), liveEnemies.size()));
        }
        return out;
    }
}
