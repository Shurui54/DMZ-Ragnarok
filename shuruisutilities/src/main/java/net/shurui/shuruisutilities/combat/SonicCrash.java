package net.shurui.shuruisutilities.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Someone travelling fast enough to go THROUGH what is in front of them.
 *
 * <p>Two things use this and they are the same event from different causes: a maxed flier breaking the sound barrier
 * of their own accord, and the loser of a badly one-sided melee clash being thrown hard enough to be driven through a
 * wall. Both are "a body moving too fast to stop", so both are one implementation - the only difference is who paid
 * for it and how far they go.
 *
 * <h2>What it does per tick</h2>
 * Smashes the blocks the body is passing into and hits whatever living thing it passes through, while charging the
 * flier for the privilege. It does NOT steer them: a flier driving themselves keeps their own controls and simply
 * moves {@link #SELF_SPEED_MULTIPLIER} times faster (applied client side, where their movement is authoritative).
 * Only the thrown version has its velocity asserted, because that one is done TO a player along a line they had no
 * say in. Blocks go through {@code Level.destroyBlock} with the
 * crasher as the breaker, so SU's own protection guard decides what may break - a crash cannot open a claim or a
 * spawn-protected build, it simply stops there.
 *
 * <h2>Why it stops on a block it may not break</h2>
 * A crash that carried on through protected land would be a way to travel through anything by aiming at it. Hitting
 * something it cannot break ends the crash where it stands, which is also what a body hitting something immovable
 * ought to do.
 */
public final class SonicCrash
{
    private SonicCrash() {}

    /**
     * How long a crash runs, in ticks, when nothing stops it first.
     *
     * <p>The flier's own is not on a timer at all: it holds until they stop moving or run dry, so this is a safety
     * cap for a state that should never be reached - a player disconnecting mid-crash, say, or one somehow moving
     * for five solid minutes without paying for it.
     */
    private static final int SELF_TICKS = 20 * 300;
    private static final int SLAM_TICKS = 30;

    /**
     * What the flier's OWN speed is multiplied by while supersonic.
     *
     * <p>A multiplier, not a speed. The manoeuvre used to replace the player's velocity outright with a fixed
     * vector along a heading the server picked, which is why it could not be steered: whatever the player did with
     * their controls was overwritten every tick, so it flew one predetermined line and nothing else. Scaling what
     * they are ALREADY doing keeps every bit of their own flying, turns included, and only makes it faster.
     *
     * <p>Applied to their current velocity each tick rather than to a speed attribute, so it tracks whatever their
     * real maximum happens to be after races, forms and buffs, with nothing here needing to know about any of them.
     */
    // public so the client motion handler, which is the thing that actually applies it, uses this one number.
    // A compile-time constant, so referencing it there inlines the value and never loads this server-side class.
    public static final double SELF_SPEED_MULTIPLIER = 1.25D;

    /** Blocks per tick. Only the slam, which is thrown along a fixed line because it is done TO the player. */
    private static final double SELF_SPEED = 2.4D;
    private static final double SLAM_SPEED = 3.1D;

    /** How wide a hole the body punches, in blocks either side of its path. */
    private static final int BREAK_RADIUS = 1;

    /**
     * The swept tunnel, in blocks: where it starts relative to the body, how far past the body's own position it
     * reaches, and the most ground one tick may clear.
     *
     * <p>{@link #SWEEP_START} keeps the first box off the body itself, so a flier does not drop through a hole of
     * their own making. {@link #SWEEP_AHEAD} is the look-ahead that leaves cleared space in front of them; it is
     * the old fixed distance, which was right as a look-ahead and wrong as the whole tunnel.
     *
     * <p>{@link #MAX_SWEEP} bounds the work, not the manoeuvre. It only matters for a body that crossed an absurd
     * distance in one tick (a teleport, a lag spike settling, a hostile client), where sweeping the whole line
     * would mean thousands of block lookups in a single tick for no gain. Ordinary flight, boost included, is a
     * long way under it.
     */
    private static final double SWEEP_START = 0.5D;
    private static final double SWEEP_AHEAD = 2.5D;
    private static final double MAX_SWEEP = 16.0D;

    /** Blocks this tough are not going anywhere; hitting one ends the crash. See {@link #tooTough}. */
    private static final float UNBREAKABLE_RESISTANCE = 1000.0F;

    /** How much of the crasher's own strength a body hit is worth, and how hard it is thrown clear. */
    private static final float HIT_DAMAGE_SHARE = 0.5F;
    private static final double HIT_KNOCKBACK = 2.4D;

    /** How far the real body may lag its own trajectory before the server pulls it back, in blocks. */
    private static final double DRIFT_TOLERANCE = 3.0D;

    /**
     * What the flier pays per tick to keep going, as a SHARE of each maximum, and how little movement counts as
     * having stopped.
     *
     * <p>Running out of either is one of the two ways this ends, so both are drained rather than one: a manoeuvre
     * that only ever emptied ki would be free to anyone who fights with their fists.
     *
     * <h2>Why a fraction and not a points cost</h2>
     * These were flat points (20 and 35 a tick), which is what made a run end almost the instant it began. DMZ
     * derives max stamina from the RES stat off a base secondary attribute of 20, so 35 points is larger than an
     * unprogressed character's ENTIRE stamina bar: {@code spend} refused on the very first tick and the boost
     * lapsed immediately, which reads in-game as a short burst of speed rather than a manoeuvre. Even a
     * well-progressed flier only had a few hundred to spend, so the run was over in well under a second.
     *
     * <p>As a share of maximum the duration is the same for everyone and is a number that can actually be tuned: a
     * full bar buys {@code 1 / STAMINA_FRACTION_PER_TICK} ticks, so the values below are about six and a half
     * seconds of sustained crashing from full, or roughly three hundred blocks of tunnel at {@link #SELF_SPEED}.
     * Still deliberately steep: this is a late-game ability and the length of a run is meant to be the question.
     * DMZ's own refill is suppressed for the duration ({@code MixinDmzRegenSuppress}), or regen alone would decide
     * how long anyone could hold it. Same convention as {@code DashCost.COST_FRACTION}.
     */
    private static final double KI_FRACTION_PER_TICK = 0.005D;
    private static final double STAMINA_FRACTION_PER_TICK = 0.0075D;

    /**
     * Blocks per tick below which the body counts as having stopped.
     *
     * <p>Was 0.35, which was right when the manoeuvre FORCED a fixed 2.4 blocks a tick: anything under a seventh
     * of that really was a body that had stopped. It became wrong the moment the boost turned into a multiplier on
     * the flier's own speed, because a player's real flight is nowhere near 2.4 b/t, so ordinary flying sat close
     * enough to the threshold to trip it and the run kept cutting out. That is the "not continuous" report.
     *
     * <p>Now low enough to mean what it says, that the player is not going anywhere, rather than that they are
     * merely slower than a number picked for a different movement model.
     */
    private static final double STOPPED_SPEED = 0.08D;

    /** Consecutive stalled ticks before it ends, so one hitched tick does not cut a run short. */
    private static final int STOP_GRACE_TICKS = 4;

    /** Every crash currently running, by player. */
    private static final Map<UUID, Crash> RUNNING = new HashMap<>();

    /**
     * Why the run being processed right now ended.
     *
     * <p>A field rather than a return value because the two places that can refuse a tick are several frames
     * apart, and threading a reason back through both would mean changing signatures that are otherwise fine.
     * Single-threaded server tick, one crash at a time, so there is nothing here to race.
     */
    private static String lastReason = "";

    /**
     * Report how a self-driven run ended.
     *
     * <p>Deliberately one line per sonic boom rather than per tick, and only for the steered kind: a clash slam is
     * a fixed thirty ticks that nobody is trying to tune. It exists because the manoeuvre kept coming out shorter
     * than intended and every diagnosis of WHY, including two of mine, was guesswork about which of the four exits
     * it was leaving through. Now it says so.
     */
    private static void report(ServerPlayer player, Crash crash, String reason)
    {
        if (!crash.steered)
            return;
        // The bars at the MOMENT it ended, because "ran dry" at 40% ki is a different bug from "ran dry" at 0%, and
        // a run that ends for any other reason with both bars still full says the cost is not what stopped it.
        // onGround and the stall counter are here for the same reason: the three cheap exits are told apart by
        // numbers, not by watching it happen. Still one line per run, and only for a steered one.
        net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.info(
                "[SonicCrash] {} ended after {} tick(s) ({}s), {} blocks travelled ({} b/t avg): {} "
                        + "[at exit: {}, onGround={}, stalledTicks={}, sprinting={}]",
                player == null ? "?" : player.getGameProfile().getName(), crash.ticksRun,
                String.format("%.1f", crash.ticksRun / 20.0F),
                String.format("%.1f", crash.travelled),
                String.format("%.2f", crash.ticksRun == 0 ? 0.0 : crash.travelled / crash.ticksRun), reason,
                net.shurui.shuruisutilities.compat.dmz.DmzResourceDrain.bars(player),
                player == null ? "?" : String.valueOf(player.onGround()),
                crash.stalled,
                player == null ? "?" : String.valueOf(player.isSprinting()));
    }

    /** One running crash. */
    private static final class Crash
    {
        /** Where it is going. Re-read from the flier's own look each tick while they steer it. */
        Vec3 heading;
        final double speed;
        final float damage;
        int ticksLeft;
        /** Hit once each: a body passed through should not be struck twenty times on the way. */
        final Set<UUID> struck = new HashSet<>();
        /** Where the trajectory says the body should be. Only used to notice a client that is not flying it. */
        Vec3 expected;
        /**
         * Whether the crasher STEERS. A flier driving themselves goes where they look, for as long as they keep
         * going; a body thrown out of a lost clash goes where it was thrown and has no say in it.
         */
        boolean steered;
        /** Where the body was last tick, and how long it has been going nowhere. */
        Vec3 lastPos;
        /**
         * The ground the body actually covered LAST tick, as a vector.
         *
         * <p>The one honest record of where a steered run is going. The server never sets a steered flier's
         * velocity (see {@link #drive}), so {@code getDeltaMovement} on the server is whatever was last written
         * to it, which for a client-flown run is the value {@link #begin} put there when the run started. Two
         * positions a tick apart cannot be stale in that way.
         *
         * <p>Zero for a thrown body, which never reaches {@code sustain}; that one is driven along a fixed
         * heading, so its heading is already the truth.
         */
        Vec3 lastStep = Vec3.ZERO;
        int stalled;
        /** Ticks since the last time the running cost was taken. */
        int sinceCharged;
        /** How many ticks it has run and how far the body actually got, for the end-of-run diagnostic. */
        int ticksRun;
        double travelled;
        final Vec3 origin;

        Crash(Vec3 heading, double speed, float damage, int ticks, Vec3 from)
        {
            this.heading = heading;
            this.speed = speed;
            this.damage = damage;
            this.ticksLeft = ticks;
            this.expected = from;
            this.lastPos = from;
            this.origin = from;
        }
    }

    /** Is this player mid-crash? Used to keep a second one from being started on top of the first. */
    public static boolean isCrashing(ServerPlayer player)
    {
        return player != null && RUNNING.containsKey(player.getUUID());
    }

    /**
     * Break the sound barrier: the flier's own version, paid for and aimed where they are looking.
     *
     * @return false when it was refused, in which case nothing has been spent
     */
    public static boolean breakSoundBarrier(ServerPlayer player, float damage)
    {
        if (player == null || isCrashing(player))
            return false;
        if (!net.shurui.shuruisutilities.core.config.PublicContent.allows(
                net.shurui.shuruisutilities.core.config.PublicContent.FEATURE_COMBAT_LAYER))
            return false;
        Vec3 look = player.getLookAngle().normalize();
        if (look.lengthSqr() < 1.0E-4D)
            return false;
        Crash crash = begin(player, look, SELF_SPEED, damage, SELF_TICKS);
        crash.steered = true;
        // Re-announced now the kind is known, so the client steers its own body rather than flying the one
        // heading it was first handed.
        PacketSonicCrashState.broadcastStart(player, look, SELF_SPEED, SELF_TICKS, true);
        boom(player);
        return true;
    }

    /**
     * Thrown through whatever is behind them: the clash loser's version. No cost and no consent, and it travels
     * further, because it is something being done TO them.
     */
    public static void slam(ServerPlayer player, Vec3 direction, float damage)
    {
        if (player == null || direction == null || direction.lengthSqr() < 1.0E-4D)
            return;
        if (!net.shurui.shuruisutilities.core.config.PublicContent.allows(
                net.shurui.shuruisutilities.core.config.PublicContent.FEATURE_COMBAT_LAYER))
            return;
        // A running crash of their own is overwritten rather than refused: being hit that hard ends whatever they
        // were doing, and leaving the old heading in place would send them the wrong way.
        begin(player, direction.normalize(), SLAM_SPEED, damage, SLAM_TICKS);
        boom(player);
    }

    private static Crash begin(ServerPlayer player, Vec3 heading, double speed, float damage, int ticks)
    {
        Crash crash = new Crash(heading, speed, damage, ticks, player.position());
        RUNNING.put(player.getUUID(), crash);
        player.fallDistance = 0.0F;
        player.setDeltaMovement(heading.scale(speed));
        player.hurtMarked = true;
        // The client flies its own body; see drive().
        PacketSonicCrashState.broadcastStart(player, heading, speed, ticks, false);
        return crash;
    }

    /** The bang: the boom itself, the shock ring, and the impact frame that reads as a body hitting the air. */
    private static void boom(ServerPlayer player)
    {
        if (!(player.level() instanceof ServerLevel level))
            return;
        Vec3 at = player.position().add(0.0D, player.getBbHeight() * 0.5D, 0.0D);
        // THE CRACK, then the body of it. A real sonic boom is a sharp report with a low roll behind it, and no single
        // vanilla sound is both. The warden's own boom was one sample doing all of the work and read as a warden
        // rather than as a body going supersonic. Thunder pitched up is the crack; the explosion pitched well down is
        // the pressure wave that follows it. Sent as two sounds at one position so they arrive together and mix.
        level.playSound(null, player.blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS,
                1.4F, 1.7F);
        level.playSound(null, player.blockPosition(), SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS,
                1.6F, 0.5F);
        // EXPLOSION_EMITTER is the big one: a single centred burst rather than the scatter of small puffs, which is
        // what makes it read as one impact instead of a trail of smoke.
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        net.shurui.shuruisutilities.compat.dmz.DmzImpactFrame.play(player);
        // DMZ's own punch particle, filling the inside of the cone rather than leaving it hollow. Sized to sit within
        // the ring below (which throws its cloud out at 1.2), so the two read as one shape: a struck core with the
        // shockwave leaving it, instead of a circle of smoke with nothing in the middle.
        net.shurui.shuruisutilities.compat.dmz.DmzPunchBurst.play(level, at, 0.75D);
        ring(level, at, player.getLookAngle());
    }

    /** A disc of cloud thrown outward perpendicular to the heading: the cone a body drags with it through the air. */
    private static void ring(ServerLevel level, Vec3 at, Vec3 heading)
    {
        Vec3 axis = heading.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 0.0D, 1.0D) : heading.normalize();
        Vec3 side = Math.abs(axis.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 u = axis.cross(side).normalize();
        Vec3 v = axis.cross(u).normalize();
        for (int i = 0; i < 24; i++)
        {
            double angle = Math.PI * 2.0D * i / 24.0D;
            Vec3 out = u.scale(Math.cos(angle)).add(v.scale(Math.sin(angle)));
            level.sendParticles(ParticleTypes.CLOUD, at.x, at.y, at.z, 0,
                    out.x * 1.2D, out.y * 1.2D, out.z * 1.2D, 1.0D);
        }
    }

    /** Advance every running crash. Driven from {@link CombatTickHandler}. */
    public static void tick(MinecraftServer server)
    {
        if (RUNNING.isEmpty())
            return;
        for (Iterator<Map.Entry<UUID, Crash>> it = RUNNING.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry<UUID, Crash> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Crash crash = entry.getValue();
            if (player == null || !player.isAlive() || crash.ticksLeft-- <= 0)
            {
                if (player != null)
                {
                    report(player, crash, player.isAlive() ? "safety cap reached" : "died");
                    PacketSonicCrashState.broadcastEnd(player);
                }
                it.remove();
                continue;
            }
            crash.ticksRun++;
            if (!(player.level() instanceof ServerLevel level))
            {
                report(player, crash, "left the level");
                PacketSonicCrashState.broadcastEnd(player);
                it.remove();
                continue;
            }
            player.fallDistance = 0.0F;
            lastReason = "";
            if (crash.steered && !sustain(player, crash))
            {
                // Stopped moving, or the bars ran dry. Nothing violent about the ending: the boost simply lapses.
                report(player, crash, lastReason.isEmpty() ? "sustain refused" : lastReason);
                PacketSonicCrashState.broadcastEnd(player);
                it.remove();
                continue;
            }
            hitEntities(level, player, crash);
            // Blocks BEFORE the move, so the body is always advancing into space it has already cleared. Tunnelling is
            // best effort and CANNOT end the run: see smashBlocks.
            smashBlocks(level, player, crash);
            drive(player, crash);
        }
    }

    /**
     * Should a steered crash carry on for another tick?
     *
     * <p>Two ways it ends, and they are the two the manoeuvre is defined by: the flier stops moving, or the bars
     * that keep them supersonic run out. Neither is a timer, so a run lasts exactly as long as the player can
     * afford to keep it up - which is what makes holding one a decision rather than a button.
     *
     * <p>Movement is judged over several ticks. A single stalled one happens whenever a packet is late or the body
     * scrapes a corner, and cutting the boost on one of those would make it feel broken rather than costly.
     *
     * <p>The heading is re-read from where they are LOOKING, because a boost that cannot turn is a boost that can
     * only be pointed at a wall. The thrown version does not steer: see {@link Crash#steered}.
     */
    private static boolean sustain(ServerPlayer player, Crash crash)
    {
        Vec3 look = player.getLookAngle();
        if (look.lengthSqr() > 1.0E-4D)
            crash.heading = look.normalize();

        // Landing ends it. Without this the body keeps driving forward THROUGH the ground, and because the block
        // breaker aims along the direction of travel it simply tunnels, so a flier who touched down carried on
        // underground until their bars ran out. A crash is a thing that happens in the air; the floor is the end
        // of it. Only the steered kind reaches here, so a thrown body still skips along the ground as before.
        if (player.onGround())
        {
            lastReason = "landed";
            return false;
        }

        Vec3 step = player.position().subtract(crash.lastPos);
        double moved = step.length();
        crash.lastStep = step;
        crash.lastPos = player.position();
        crash.travelled += moved;
        // The first tick is always a stall: the client has not been told to move yet.
        crash.stalled = moved < STOPPED_SPEED ? crash.stalled + 1 : 0;
        if (crash.stalled > STOP_GRACE_TICKS)
        {
            lastReason = "stalled (moved " + String.format("%.2f", moved) + " b/t, under the "
                    + STOPPED_SPEED + " threshold, for " + crash.stalled + " ticks)";
            return false;
        }

        // Every tick, and as a share of each maximum so the run lasts the same length for everyone. Charging by the
        // second would let a run continue for most of a second the flier could not actually afford.
        if (!net.shurui.shuruisutilities.compat.dmz.DmzResourceDrain.spendFraction(player, KI_FRACTION_PER_TICK,
                STAMINA_FRACTION_PER_TICK))
        {
            lastReason = "ran dry (could not pay " + (KI_FRACTION_PER_TICK * 100.0) + "% ki + "
                    + (STAMINA_FRACTION_PER_TICK * 100.0) + "% stamina this tick)";
            return false;
        }
        return true;
    }

    /**
     * Carry a THROWN body along its line. A steered one is not touched here at all.
     *
     * <p>The crash is flown client side ({@code SonicCrashLocalMotion}) because a player's own client is
     * authoritative for where they are: a server that teleports them every tick is fighting that client, and the
     * client wins by cancelling whatever it was doing - which is precisely how this manoeuvre came out stopping dead
     * the instant it started.
     *
     * <h2>Why a steered run gets no velocity from here</h2>
     * It used to get one, and that is exactly why it could not be turned. Every tick this replaced the flier's
     * velocity with {@code heading * speed} and pushed it with {@code hurtMarked}, so their own steering was
     * overwritten before it could take effect and the run flew one line decided at the moment it started. A flier
     * driving themselves is supposed to be flying; the boost is a MULTIPLIER their client applies to whatever they
     * are already doing ({@code SELF_SPEED_MULTIPLIER}), so the server has nothing to impose and imposing anything
     * would only fight them again. It measures, charges and breaks blocks, and leaves the flying to the flier.
     *
     * <p>The thrown version is different in kind: it is something done TO a player, along a line they had no say in,
     * so it keeps the fixed vector and the drift correction below.
     */
    private static void drive(ServerPlayer player, Crash crash)
    {
        if (crash.steered)
        {
            // Their own flight, only faster. Nothing to assert, and asserting anything is the old bug.
            crash.expected = player.position();
            return;
        }
        Vec3 step = crash.heading.scale(crash.speed);
        player.setDeltaMovement(step);
        player.hurtMarked = true;
        crash.expected = crash.expected.add(step);
        if (player.position().distanceToSqr(crash.expected) > DRIFT_TOLERANCE * DRIFT_TOLERANCE)
        {
            player.connection.teleport(crash.expected.x, crash.expected.y, crash.expected.z,
                    player.getYRot(), player.getXRot());
        }
        else
        {
            // Their client is flying it: follow along rather than leading, so the two never disagree by much.
            crash.expected = player.position();
        }
    }

    /**
     * Smash the blocks the body is passing into. Best effort: break what it may, fly past what it may not.
     *
     * <p>This used to END the run when the block dead ahead could not be broken, and that was the whole of why a boom
     * was never continuous. The probe said so in one line, five runs running: "hit a block it may not break" after two
     * to six ticks, at three blocks a tick, with ki at 92% and stamina at 91% and the flier still sprinting in mid air.
     * Nothing was wrong with the cost, the speed or the sprint flag. The run was simply being cancelled by the tunnel.
     *
     * <p>The reason it could not break anything is worth keeping in mind, because it is not an edge case: the gate here
     * is DragonMineZ's own {@code canKiGrief}, so a world with {@code allowKiGriefingPlayers} switched OFF answers no
     * for EVERY block. That is a perfectly ordinary way to run a server, and on one the manoeuvre could not survive
     * meeting a single block of terrain. Refusing to destroy something and hitting a wall are different facts, and only
     * the second is a reason to stop flying.
     *
     * <p>So a wall now ends a run the honest way, through the flier actually stopping: their own collision halts them,
     * {@code sustain} sees the body go under {@link #STOPPED_SPEED} and ends it as stalled a quarter second later. A
     * graze, a claim, a block of obsidian or a no-grief world costs the tunnel and nothing else.
     *
     * <h2>Why the tunnel is a SWEEP and not one box</h2>
     * This used to punch a single 3x3x3 at a fixed 1.5 blocks ahead of the body, which covers the ground from
     * roughly half a block to two and a half blocks in front. That is a tunnel only for a flier who travels less
     * than two and a half blocks in a tick. Flight speed is not a constant: it comes off the player's own stats,
     * race, form and buffs, and the boost multiplies whatever that already is. So a fast flier simply outran their
     * own tunnel and the boxes stopped touching, leaving pillars of untouched terrain between them, which is
     * exactly the "it randomly stops breaking blocks, and only for some people" report: nothing random and nothing
     * per-player about the CODE, it was one number that happened to be enough for a slow character and not enough
     * for a fast one.
     *
     * <p>Sweeping the segment the body actually crossed removes the speed dependence entirely: however far they
     * got this tick, the hole covers all of it, plus a look-ahead so they are still flying into cleared space
     * rather than into the face of the next block.
     */
    private static void smashBlocks(ServerLevel level, ServerPlayer player, Crash crash)
    {
        // WHERE THEY ACTUALLY WENT, not where the server thinks they are going. A steered run is flown by the
        // client and the server never writes its velocity (see drive), so getDeltaMovement here still holds the
        // value begin() set at the very start of the run: using it aimed the tunnel along the ORIGINAL heading
        // while the flier turned away from it. The distance between two consecutive positions cannot go stale
        // that way. The remaining fallbacks are for a thrown body (driven, so its heading is the truth) and for
        // the first tick of a run, before there are two positions to subtract.
        Vec3 step = crash.lastStep;
        Vec3 facing;
        if (step.lengthSqr() > 1.0E-6D)
            facing = step.normalize();
        else if (player.getDeltaMovement().lengthSqr() > 1.0E-6D)
            facing = player.getDeltaMovement().normalize();
        else
            facing = crash.heading;

        // The body's own centre is where the sweep STARTS, not where it breaks: the first box sits a little ahead
        // so a hovering flier does not dig the floor out from under themselves, and the sweep then runs forward
        // over everything they crossed since last tick plus the look-ahead.
        Vec3 from = player.position().add(0.0D, player.getBbHeight() * 0.5D, 0.0D);
        double travelled = Math.min(step.length(), MAX_SWEEP);
        List<BlockPos> broken = new ArrayList<>();
        // Centres one block apart with a radius of one overlap by a full block on every axis, so the swept boxes
        // form a continuous bore rather than a string of beads. A Set because consecutive centres round to the
        // same block whenever the flier is slow, and re-walking a 3x3x3 for nothing is pure waste.
        Set<BlockPos> centres = new java.util.LinkedHashSet<>();
        for (double d = SWEEP_START; d <= travelled + SWEEP_AHEAD; d += 1.0D)
            centres.add(BlockPos.containing(from.add(facing.scale(d))));
        // Always include the far end, which a whole-number stride would otherwise stop just short of.
        centres.add(BlockPos.containing(from.add(facing.scale(travelled + SWEEP_AHEAD))));

        // A 3x3x3 per centre, so the hole is big enough to fly through rather than a one block bore the body clips
        // the edges of. Nothing in here decides whether the run continues; every refusal is one less block broken.
        for (BlockPos centre : centres)
            for (int dx = -BREAK_RADIUS; dx <= BREAK_RADIUS; dx++)
                for (int dy = -BREAK_RADIUS; dy <= BREAK_RADIUS; dy++)
                    for (int dz = -BREAK_RADIUS; dz <= BREAK_RADIUS; dz++)
                    {
                        BlockPos pos = centre.offset(dx, dy, dz);
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir() || !state.getFluidState().isEmpty())
                            continue;
                        if (tooTough(level, pos, state))
                            continue;
                        // THE SAME GATE KI DESTRUCTION GOES THROUGH, for two reasons at once. It is where DMZ's ki
                        // griefing gamerules, its master-structure protection and SU's guild-claim guard all live,
                        // so this obeys every rule a ki blast obeys; and SU's terrain-regen capture hangs off the
                        // same call, so a block smashed here is snapshotted and repaired exactly like one a beam
                        // took out. Deciding any of that here instead would drift from ki destruction the first
                        // time either side was retuned.
                        if (!net.shurui.shuruisutilities.compat.dmz.KiGrief.mayDestroy(level, pos, player))
                            continue;
                        // The crasher is the breaker, so SU's protection guard on destroyBlock has its say too.
                        if (level.destroyBlock(pos, false, player))
                            broken.add(pos);
                    }
        if (!broken.isEmpty())
        {
            BlockPos at = broken.get(0);
            level.sendParticles(ParticleTypes.EXPLOSION, at.getX() + 0.5D, at.getY() + 0.5D, at.getZ() + 0.5D,
                    3, 0.4D, 0.4D, 0.4D, 0.0D);
            level.playSound(null, at, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 0.7F, 1.4F);
        }
    }

    /** Bedrock and its kin. Nothing that survives a charged creeper is going to yield to a shoulder. */
    private static boolean tooTough(ServerLevel level, BlockPos pos, BlockState state)
    {
        return state.getBlock().getExplosionResistance() >= UNBREAKABLE_RESISTANCE
                || state.getDestroySpeed(level, pos) < 0.0F;
    }

    /** Hit everything the body passes through, once each. */
    private static void hitEntities(ServerLevel level, ServerPlayer player, Crash crash)
    {
        AABB box = player.getBoundingBox().inflate(1.2D);
        for (Entity entity : level.getEntities(player, box, e -> e instanceof LivingEntity))
        {
            LivingEntity victim = (LivingEntity) entity;
            if (!victim.isAlive() || !crash.struck.add(victim.getUUID()))
                continue;
            if (crash.damage > 0.0F)
                victim.hurt(victim.damageSources().playerAttack(player), crash.damage);
            // A standing quest giver or shopkeeper is passed through, not flung: knocked once it has no way back to
            // where it stood. Unlike the dash impact this shove was never gated on the hit landing, so without this a
            // crash would fling even a master that refused the damage. Real enemies stay throwable.
            if (CombatBystanders.isProtected(victim))
                continue;
            // Thrown along the crash rather than away from the crasher: they were hit by something going past, so
            // they go the way it was going.
            victim.setDeltaMovement(crash.heading.scale(HIT_KNOCKBACK).add(0.0D, 0.4D, 0.0D));
            victim.hurtMarked = true;
            level.playSound(null, victim.blockPosition(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS,
                    1.2F, 0.7F);
            level.sendParticles(ParticleTypes.EXPLOSION, victim.getX(),
                    victim.getY() + victim.getBbHeight() * 0.5D, victim.getZ(), 2, 0.3D, 0.3D, 0.3D, 0.0D);
        }
    }

    /** How hard a crash hits, from whatever the crasher's own blows are worth. */
    public static float damageFor(ServerPlayer player)
    {
        return net.shurui.shuruisutilities.compat.dmz.DragonDamage.total(player) * HIT_DAMAGE_SHARE;
    }

    /** Drop every crash. Called when the server stops, like the dash and the clash. */
    public static void clear()
    {
        RUNNING.clear();
    }

    /** Refusal feedback, on the action bar so it never fills chat with a gesture players will fumble. */
    public static void refuse(ServerPlayer player, String why)
    {
        if (player != null)
            player.displayClientMessage(Component.literal(why), true);
    }
}
