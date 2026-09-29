package net.shurui.shuruisutilities.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Two players dashing straight into each other meet in a clash instead of passing through one another.
 *
 * <p>The trigger is deliberately narrow: BOTH players must be mid dash, both in the straight mode, and closing on each
 * other rather than merely near each other. A clash is therefore something two people opt into by committing at the same
 * moment, not something that happens by accident when two dashes cross. Every arc mode exists partly so a player who
 * does not want the head on meeting has a way to refuse it.
 *
 * <h2>How the contest is run</h2>
 * A five second rhythm game, scored on both clients and decided here. Both fighters are held in place and dealt the
 * SAME chart from one seed (see {@link ClashRhythm}), each plays it on their own screen, and the higher score wins.
 *
 * <p>This replaced an earlier approach that spawned a pair of invisible {@code KiWaveEntity} beams nose to nose and
 * let DragonMineZ's own beam clash pair and resolve them. That worked in principle and was a constant source of
 * trouble in practice: the waves were subject to every rule real beams obey, so they detonated against nearby terrain
 * and ended the struggle on its first tick, handing someone a win they never fought for. Owning the contest outright
 * removes that whole class of failure, and a rhythm game is a better fit for a fist fight than a beam struggle was.
 *
 * <h2>What winning is worth</h2>
 * The loser is thrown back along the clash axis. The winner is released with their dash cooldown cleared and a brief
 * window in which they may dash again immediately, so a won clash flows into a follow up rather than ending the
 * exchange. The window is short on purpose: it rewards the player who won the struggle without turning one win into an
 * unbroken chain.
 */
public final class MeleeClashService
{
    private MeleeClashService() {}

    // How close two mutual dashers must be before the clash forms.
    public static final double TRIGGER_DISTANCE = 6.0D;

    // How far apart the two fighters actually stand once the clash has formed.
    //
    // The trigger fires from anywhere inside TRIGGER_DISTANCE, and both fighters used to be anchored exactly where
    // that happened to catch them, so a clash that triggered early read as two people throwing punches at each other
    // across five blocks of empty air. They are pulled onto this separation first and anchored there, so every clash
    // is fought at the same believable range regardless of how far out it triggered. Only ever closes a gap: a pair
    // that is already nearer than this is left alone rather than shoved apart.
    public static final double CLASH_SEPARATION = 1.0D;

    // How head on the two dashes must be. Both headings are unit vectors, so a dot of -1 is perfectly opposed; this
    // allows a fair cone either side while still refusing a glancing pass.
    public static final double CONVERGE_DOT = -0.82D;

    // How long the pair is held in the struggle before it is called off if DragonMineZ never resolves it.
    public static final int MAX_STRUGGLE_TICKS = 200;

    // Speed the loser is thrown back at, and how far the winner's follow up window stays open.
    public static final double LOSER_KNOCKBACK = 2.6D;
    public static final int FOLLOW_UP_TICKS = 40;

    // Punch flurry. DragonMineZ's own punch sounds, fired from the midpoint between the two fighters so both hear
    // it from the fight rather than from themselves, and so bystanders hear it from where the clash is happening.
    private static final String[] PUNCH_SOUNDS =
            { "punch1", "punch2", "punch3", "punch4", "punch5", "punch6" };
    private static final String[] CRIT_PUNCH_SOUNDS = { "critic_punch1", "critic_punch2" };

    // Gap between punches, in ticks. Fast enough to read as an exchange of blows rather than separate hits.
    private static final int PUNCH_MIN_GAP = 3;
    private static final int PUNCH_MAX_GAP = 8;

    // How often a blow in the flurry is a heavier one.
    private static final double CRIT_PUNCH_CHANCE = 0.2;

    private static final double PUNCH_VOLUME = 1.0D;

    // Shockwave burst per blow. DragonMineZ's own punch particle, scattered over each fighter's body rather than
    // pinned to one point, so a ten second exchange reads as blows landing all over instead of one repeating spot.
    private static final String PUNCH_PARTICLE = "punch_particle";
    private static final int PARTICLE_PER_PUNCH = 3;
    private static final double PARTICLE_BODY_SPREAD = 0.45D;

    // How far away a player can be and still be told about the fight's visuals.
    private static final double VISUALS_RANGE = 96.0D;

    // How long an NPC's combo flag stays raised per swing. Long enough for the clip to read, short enough that the
    // next punch is a fresh rising edge rather than a continuation.
    private static final int NPC_SWING_TICKS = 4;

    // How long past the chart's end the server waits for both clients to report before scoring a silent one as nil.
    // Generous enough to cover an ordinary round trip on a bad connection, short enough that a disconnected client
    // cannot hold the other player still.
    private static final int REPORT_GRACE_TICKS = 40;

    // How far, in blocks squared, a held fighter may drift before a position correction pulls them back to their anchor.
    // Small enough to read as frozen, and a perfectly still player trips no correction packet at all. Matches the
    // surface-gen freeze leash so the two kinds of hold feel the same.
    private static final double FREEZE_LEASH_SQ = 0.35 * 0.35;

    /**
     * Technique id that used to mark the invisible beams this clash was built on.
     *
     * <p>Kept because {@code MixinDmzDefenderWaveBlockStop} and {@code MixinKiWaveRenderer} still name it, and
     * because it costs nothing to leave the door open if a beam backed variant is ever wanted again. No wave carries
     * it today: the struggle is a rhythm game and spawns no entities at all.
     */
    public static final String CLASH_WAVE_MARKER = "su_melee_clash";

    private static final class Struggle
    {
        UUID a;
        UUID b;
        Vec3 axis;
        int ticks;
        long seed;
        // -1 means "has not reported yet". Distinguishing that from a real score of 0 is what lets a clash wait for a
        // slow client instead of instantly scoring them nil.
        int scoreA = -1;
        int scoreB = -1;
        // Side B is not a real client: a solo test clash, or later an NPC. Its score is simulated and it is never
        // held, messaged or thrown.
        boolean simulated;
        // The NPC on side B, if this is a player vs NPC clash. Null for a player pair and for a solo test clash.
        // A simulated side with an entity behind it is still held, hit with particles and thrown at the end; only its
        // SCORE is simulated, because it has no client to play the chart on.
        UUID npc;
        // Tick the NPC's current swing should be cleared on. A combo flag left permanently true plays once and
        // then sits there, so each punch is a rising edge and this is when it falls again.
        int npcSwingOffTick = -1;
        // Tick the next punch lands on. The flurry is paced rather than per-tick, and randomised so it does not
        // settle into an obvious loop over ten seconds.
        int nextPunchTick;
        // Where each fighter was pinned when the clash formed, captured after they were zeroed and faced. Held there
        // every tick with a leashed position correction, because a client-authoritative player streaming movement
        // packets cannot be stopped by zeroing its server-side motion alone. Side B's anchor is unused for a simulated
        // side, which is never a real player. Movement only: skills, ki and DMZ state are untouched.
        double anchorAX, anchorAY, anchorAZ;
        double anchorBX, anchorBY, anchorBZ;
    }

    private static final List<Struggle> ACTIVE = new ArrayList<>();

    // Players whose dash cooldown is waived, and until when. This is what a clash win buys.
    private static final Map<UUID, Integer> FOLLOW_UP = new HashMap<>();

    /**
     * How long after a struggle ends before either player may be pulled into another one.
     *
     * <p>Without this the winner resolves straight back into a clash. Winning waives their dash cooldown on purpose,
     * so they can dash again immediately, and the loser is still within {@link #TRIGGER_DISTANCE} because they are
     * mid knockback and still facing the way they were thrown from. That satisfies every condition {@link #mutual}
     * tests, so a new struggle begins on the next tick, and the exchange reads as one clash restarting over and over
     * rather than as a fight.
     *
     * <p>Deliberately shorter than {@link #FOLLOW_UP_TICKS}, so the follow up dash it exists to protect still lands as
     * an ordinary hit. What is blocked is another CLASH, not another dash.
     */
    private static final int RECLASH_LOCKOUT_TICKS = 30;

    // Players who have just come out of a struggle, and the ticks left before they may enter another.
    private static final Map<UUID, Integer> RECENT = new HashMap<>();

    /**
     * Look for a new mutual dash and run any struggle already in progress. Called once per server level tick, after
     * {@link DashService#tick}.
     */
    public static void tick(MinecraftServer server)
    {
        if (server == null)
            return;
        tickFollowUp();
        tickLockout();
        tickStruggles(server);
        // Detection is still per level, because a clash only makes sense between two players standing in the same world.
        for (ServerLevel level : server.getAllLevels())
        {
            detect(level);
        }
    }

    private static void tickFollowUp()
    {
        for (Iterator<Map.Entry<UUID, Integer>> it = FOLLOW_UP.entrySet().iterator(); it.hasNext();)
        {
            Map.Entry<UUID, Integer> e = it.next();
            int left = e.getValue() - 1;
            if (left <= 0)
                it.remove();
            else
                e.setValue(left);
        }
    }

    private static void tickLockout()
    {
        for (Iterator<Map.Entry<UUID, Integer>> it = RECENT.entrySet().iterator(); it.hasNext();)
        {
            Map.Entry<UUID, Integer> e = it.next();
            int left = e.getValue() - 1;
            if (left <= 0)
                it.remove();
            else
                e.setValue(left);
        }
    }

    /** Whether this player is inside a won clash's follow up window. */
    public static boolean hasFollowUp(ServerPlayer player)
    {
        return player != null && FOLLOW_UP.containsKey(player.getUUID());
    }

    // Find two players who have just dashed into each other.
    private static void detect(ServerLevel level)
    {
        List<ServerPlayer> dashers = new ArrayList<>();
        for (ServerPlayer p : level.players())
        {
            // A player mid ki beam clash is excluded here: both contests seize the same fighter, so a mutual dash by
            // two players already in a beam clash must not stack a melee clash on top of it.
            if (DashService.isDashing(p) && !inStruggle(p.getUUID()) && !RECENT.containsKey(p.getUUID())
                    && !inBeamClash(p.getUUID()))
                dashers.add(p);
        }
        if (dashers.size() < 2)
            return;
        for (int i = 0; i < dashers.size(); i++)
        {
            for (int j = i + 1; j < dashers.size(); j++)
            {
                ServerPlayer a = dashers.get(i);
                ServerPlayer b = dashers.get(j);
                if (inStruggle(a.getUUID()) || inStruggle(b.getUUID()))
                    continue;
                if (mutual(a, b))
                    begin(level, a, b);
            }
        }
    }

    // Whether these two are genuinely dashing INTO each other.
    private static boolean mutual(ServerPlayer a, ServerPlayer b)
    {
        if (a.distanceTo(b) > TRIGGER_DISTANCE)
            return false;
        Vec3 ha = DashService.heading(a);
        Vec3 hb = DashService.heading(b);
        if (ha == null || hb == null)
            return false;
        // Opposed headings. Two players dashing the same way at the same speed are not clashing, they are flying
        // together, and their headings would have a dot near +1.
        if (ha.dot(hb) > CONVERGE_DOT)
            return false;
        // And each must be aimed at the OTHER, not merely pointed the opposite way while passing.
        Vec3 aToB = b.position().subtract(a.position());
        if (aToB.lengthSqr() < 1.0E-6D)
            return false;
        aToB = aToB.normalize();
        return ha.dot(aToB) > 0.7D && hb.dot(aToB) < -0.7D;
    }

    // Seize both players and stand up the struggle.
    private static void begin(ServerLevel level, ServerPlayer a, ServerPlayer b)
    {
        try
        {
            // The dash is over the moment the clash forms. Cancelled rather than ended so neither player is charged a
            // cooldown for a dash that turned into a struggle.
            DashService.cancel(a);
            DashService.cancel(b);

            Vec3 axis = b.position().subtract(a.position());
            if (axis.lengthSqr() < 1.0E-6D)
                return;
            axis = axis.normalize();

            // Close onto CLASH_SEPARATION before anything is anchored. Both are drawn equally toward the midpoint, so
            // the fight stays where it started and neither fighter is handed ground the other had to cover. faceAlong
            // sends the position packet afterwards, which is why the move is a plain moveTo here.
            if (a.distanceTo(b) > CLASH_SEPARATION)
            {
                Vec3 mid = a.position().add(b.position()).scale(0.5D);
                Vec3 half = axis.scale(CLASH_SEPARATION * 0.5D);
                Vec3 posA = mid.subtract(half);
                Vec3 posB = mid.add(half);
                a.moveTo(posA.x, posA.y, posA.z);
                b.moveTo(posB.x, posB.y, posB.z);
            }

            faceAlong(a, axis);
            faceAlong(b, axis.scale(-1.0D));
            a.setDeltaMovement(Vec3.ZERO);
            b.setDeltaMovement(Vec3.ZERO);
            a.hurtMarked = true;
            b.hurtMarked = true;

            Struggle s = new Struggle();
            s.a = a.getUUID();
            s.b = b.getUUID();
            s.axis = axis;
            s.ticks = 0;
            // ONE seed for the pair, so both fighters play the same chart. See ClashRhythm.
            s.seed = level.getRandom().nextLong();
            // Anchor both fighters where they now stand, after the zero and the facing above, so the per-tick hold pins
            // them exactly here for the length of the clash.
            s.anchorAX = a.getX();
            s.anchorAY = a.getY();
            s.anchorAZ = a.getZ();
            s.anchorBX = b.getX();
            s.anchorBY = b.getY();
            s.anchorBZ = b.getZ();
            ACTIVE.add(s);

            NetworkUtils.sendTo(new PacketClashStart(s.seed, b.getGameProfile().getName()), a);
            NetworkUtils.sendTo(new PacketClashStart(s.seed, a.getGameProfile().getName()), b);
            announceVisuals(a, b, true);
        }
        catch (Throwable t)
        {
            // A clash is a flourish on top of a dash. It must never be able to break the dash itself.
        }
    }

    /**
     * A client has finished its chart and reported what it scored.
     *
     * <p>Clamped to the chart's own maximum, ignored for a player who is not in a clash, and ignored for a second
     * report on the same clash, so a modified client can only ever under-report. See {@link PacketClashScore}.
     */
    public static void reportScore(ServerPlayer player, int score)
    {
        if (player == null)
            return;
        UUID id = player.getUUID();
        for (Struggle s : ACTIVE)
        {
            int clamped = Math.max(0, Math.min(score, ClashRhythm.maxScore(s.seed)));
            if (s.a.equals(id) && s.scoreA < 0)
            {
                s.scoreA = clamped;
                return;
            }
            if (s.b.equals(id) && s.scoreB < 0)
            {
                s.scoreB = clamped;
                return;
            }
        }
    }

    // Advance every struggle, and resolve the ones that have ended.
    private static void tickStruggles(MinecraftServer server)
    {
        for (Iterator<Struggle> it = ACTIVE.iterator(); it.hasNext();)
        {
            Struggle s = it.next();
            ServerPlayer a = server.getPlayerList().getPlayer(s.a);
            ServerPlayer b = s.simulated ? null : server.getPlayerList().getPlayer(s.b);
            LivingEntity npc = resolveNpc(s, a);
            s.ticks++;

            // Someone left, died, or the two are no longer in the same world. No winner, no reward, just clean up.
            // An NPC that has been killed or unloaded mid clash ends it the same way a player leaving would.
            boolean npcGone = s.npc != null && (npc == null || !npc.isAlive());
            boolean bGone = !s.simulated && (b == null || !b.isAlive());
            if (npcGone)
            {
                npcSwing(npc, false);
                cancel(a);
                announceVisuals(a, null, false);
                RECENT.put(s.a, RECLASH_LOCKOUT_TICKS);
                it.remove();
                continue;
            }
            if (a == null || !a.isAlive() || bGone || (b != null && a.level() != b.level()))
            {
                cancel(a);
                cancel(b);
                announceVisuals(a, b, false);
                it.remove();
                continue;
            }

            // Still playing: hold both in place so the struggle reads as a struggle rather than two people drifting
            // apart while their charts run.
            if (s.ticks < ClashRhythm.DURATION_TICKS)
            {
                hold(a, s.anchorAX, s.anchorAY, s.anchorAZ);
                if (b != null)
                    hold(b, s.anchorBX, s.anchorBY, s.anchorBZ);
                if (npc != null)
                {
                    holdNpc(npc);
                    if (s.npcSwingOffTick >= 0 && s.ticks >= s.npcSwingOffTick)
                    {
                        npcSwing(npc, false);
                        s.npcSwingOffTick = -1;
                    }
                }
                punchFlurry(s, a, b, npc);
                continue;
            }

            // Past the chart. Give a slow client a moment to get its score in before scoring it as nothing.
            // A simulated side never reports, so only a real one is worth waiting on.
            boolean pending = s.scoreA < 0 || (!s.simulated && s.scoreB < 0);
            boolean waiting = pending && s.ticks < ClashRhythm.DURATION_TICKS + REPORT_GRACE_TICKS;
            if (waiting)
            {
                hold(a, s.anchorAX, s.anchorAY, s.anchorAZ);
                if (b != null)
                    hold(b, s.anchorBX, s.anchorBY, s.anchorBZ);
                continue;
            }

            int scoreA = Math.max(0, s.scoreA);
            // A simulated opponent plays the same chart at an accuracy derived from its power, so a solo test still
            // produces a real result to read rather than an automatic draw against yourself.
            // A simulated opponent plays the same chart at an accuracy derived from its power, so a clash against an
            // NPC is decided by how tough it is rather than by a coin flip, and a solo test still produces a real
            // result rather than an automatic draw.
            double npcPower = npc == null ? 1.0 : Math.max(1.0, npc.getMaxHealth());
            double playerPower = Math.max(1.0, a.getMaxHealth());
            int scoreB = s.simulated ? ClashRhythm.npcScore(s.seed, npcPower, playerPower) : Math.max(0, s.scoreB);
            if (scoreA == scoreB)
            {
                // A draw pushes both apart evenly and rewards neither.
                send(a, 2, scoreA, scoreB);
                send(b, 2, scoreB, scoreA);
                push(a, s.axis.scale(-1.0D), LOSER_KNOCKBACK * 0.6D);
                if (b != null)
                    push(b, s.axis, LOSER_KNOCKBACK * 0.6D);
                else if (npc != null)
                    pushEntity(npc, s.axis, LOSER_KNOCKBACK * 0.6D);
            }
            else if (scoreA > scoreB)
            {
                send(a, 1, scoreA, scoreB);
                send(b, 0, scoreB, scoreA);
                resolve(a, b, s.axis, scoreA, scoreB);
                // The player won: the NPC is the one thrown.
                if (b == null && npc != null)
                    pushEntity(npc, s.axis, LOSER_KNOCKBACK);
            }
            else
            {
                send(b, 1, scoreB, scoreA);
                send(a, 0, scoreA, scoreB);
                resolve(b, a, s.axis.scale(-1.0D), scoreB, scoreA);
                // The NPC won: it keeps its footing and the player is thrown, which resolve() has already done for a
                // player winner but cannot do when the winner is not one.
                if (b == null && npc != null)
                    push(a, s.axis.scale(-1.0D), LOSER_KNOCKBACK);
            }

            npcSwing(npc, false);
            announceVisuals(a, b, false);
            RECENT.put(s.a, RECLASH_LOCKOUT_TICKS);
            if (!s.simulated)
                RECENT.put(s.b, RECLASH_LOCKOUT_TICKS);
            it.remove();
        }
    }

    /**
     * The sound of the fight the rhythm game stands for.
     *
     * <p>The clash is two people trading blows, but nothing about the struggle actually swings, so without this it
     * plays out in silence while the arrows scroll. Punches are fired from the MIDPOINT between the fighters rather
     * than from either one, so both hear the exchange in front of them and anyone watching hears it from where the
     * fight is.
     *
     * <p>Paced and randomised rather than one per tick: a punch every few ticks reads as an exchange, while every
     * tick reads as static, and a fixed gap would settle into an audible loop over ten seconds.
     */
    private static void punchFlurry(Struggle s, ServerPlayer a, ServerPlayer b, LivingEntity npc)
    {
        if (s.ticks < s.nextPunchTick)
        {
            return;
        }
        try
        {
            net.minecraft.util.RandomSource random = a.level().getRandom();
            s.nextPunchTick = s.ticks + PUNCH_MIN_GAP + random.nextInt(PUNCH_MAX_GAP - PUNCH_MIN_GAP + 1);

            Entity other = b != null ? b : npc;
            Vec3 mid = other == null ? a.position() : a.position().add(other.position()).scale(0.5D);
            // Blows land ON the fighters, not at the midpoint: that is where a punch connects and it keeps a solo
            // test clash from firing every particle into empty air.
            shockwave(a, random);
            if (other != null)
            {
                shockwave(other, random);
            }
            // An NPC has no IPlayerAnimatable to drive, so its punch comes from DragonMineZ's own combo flag, which
            // is synced entity data and therefore reaches every client that can see it.
            if (npc != null)
            {
                npcSwing(npc, true);
                s.npcSwingOffTick = s.ticks + NPC_SWING_TICKS;
            }
            boolean crit = random.nextDouble() < CRIT_PUNCH_CHANCE;
            String[] pool = crit ? CRIT_PUNCH_SOUNDS : PUNCH_SOUNDS;
            SoundEvent sound = lookup(pool[random.nextInt(pool.length)]);
            if (sound == null)
            {
                return;
            }
            // Slight pitch scatter so repeats of the same sample do not read as one sound looping.
            float pitch = 0.9F + random.nextFloat() * 0.25F;
            ((ServerLevel) a.level()).playSound(null, mid.x, mid.y + 1.0D, mid.z, sound, SoundSource.PLAYERS,
                    (float) PUNCH_VOLUME, pitch);
        }
        catch (Throwable ignored)
        {
            // A missing or renamed DragonMineZ sound must never break the struggle it is decorating.
        }
    }

    /**
     * A burst of DragonMineZ's punch particle somewhere on this fighter's body.
     *
     * <p>Scattered around the torso rather than emitted from a fixed offset, so across ten seconds the blows read as
     * landing all over rather than as one repeating puff at chest height.
     */
    private static void shockwave(Entity player, net.minecraft.util.RandomSource random)
    {
        try
        {
            net.minecraft.core.particles.ParticleOptions particle = punchParticle();
            if (particle == null)
            {
                return;
            }
            for (int i = 0; i < PARTICLE_PER_PUNCH; i++)
            {
                double x = player.getX() + (random.nextDouble() - 0.5D) * PARTICLE_BODY_SPREAD * 2.0D;
                double y = player.getY() + 0.8D + (random.nextDouble() - 0.5D) * PARTICLE_BODY_SPREAD * 2.0D;
                double z = player.getZ() + (random.nextDouble() - 0.5D) * PARTICLE_BODY_SPREAD * 2.0D;
                // COUNT 0, and the three "offset" arguments are a COLOUR, not a direction.
                //
                // DragonMineZ's PunchParticle zeroes its own velocity in its super call and reads the three d
                // arguments as red, green and blue instead. Vanilla only forwards those three verbatim when the count
                // is 0; with any positive count they become a random position spread and the particle receives zeros,
                // which is why the first version of this came out black. So: one call per particle, count 0, white,
                // and the scatter is done by the positions computed above.
                ((ServerLevel) player.level()).sendParticles(particle, x, y, z, 0, 1.0D, 1.0D, 1.0D, 1.0D);
            }
        }
        catch (Throwable ignored)
        {
            // Decoration only.
        }
    }

    // Looked up by registry name and only used if it is a plain particle with no extra data to encode.
    private static net.minecraft.core.particles.ParticleOptions punchParticle()
    {
        try
        {
            net.minecraft.core.particles.ParticleType<?> type =
                    ForgeRegistries.PARTICLE_TYPES.getValue(new ResourceLocation("dragonminez", PUNCH_PARTICLE));
            return type instanceof net.minecraft.core.particles.ParticleOptions options ? options : null;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /** Tell everyone nearby that these two are locked together, so onlookers see the punches too. */
    private static void announceVisuals(ServerPlayer a, ServerPlayer b, boolean active)
    {
        try
        {
            if (a == null)
            {
                return;
            }
            int idA = a.getId();
            int idB = b == null ? a.getId() : b.getId();
            PacketClashVisuals packet = new PacketClashVisuals(idA, idB, active);
            for (ServerPlayer viewer : ((ServerLevel) a.level()).players())
            {
                if (viewer.distanceToSqr(a) <= VISUALS_RANGE * VISUALS_RANGE)
                {
                    NetworkUtils.sendTo(packet, viewer);
                }
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    // Resolved by registry name rather than by class, so a DragonMineZ reshuffle costs the sound and not the clash.
    private static SoundEvent lookup(String name)
    {
        try
        {
            return ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("dragonminez", name));
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /**
     * The NPC on side B, if there is one and it is still around.
     */
    private static LivingEntity resolveNpc(Struggle s, ServerPlayer near)
    {
        if (s.npc == null || near == null || !(near.level() instanceof ServerLevel level))
        {
            return null;
        }
        Entity entity = level.getEntity(s.npc);
        return entity instanceof LivingEntity living ? living : null;
    }

    /**
     * Pin an NPC for the length of the struggle, the same way a player is pinned.
     *
     * <p>Its AI is left alone rather than disabled: turning AI off and back on around a ten second clash risks
     * stranding a mob with no goals if the clash ends by an unusual path, and zeroing its motion each tick achieves
     * the same standstill without touching state that has to be restored.
     */
    private static void holdNpc(LivingEntity npc)
    {
        try
        {
            npc.setDeltaMovement(Vec3.ZERO);
            npc.hurtMarked = true;
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * Swing an NPC's fists, using DragonMineZ's own combo flag.
     *
     * <p>Players are animated through {@code IPlayerAnimatable}, which is mixed into {@code AbstractClientPlayer} and
     * so has no NPC equivalent. Saga entities animate from synced entity data instead: {@code setComboing} writes to
     * {@code IS_COMBOING}, which every client watching the mob receives and its GeckoLib attack controller turns into
     * the melee clip. So the same punch reaches viewers by a completely different route depending on who threw it.
     *
     * <p>Raised and lowered rather than held, because the clip plays off the flag going up. Left permanently true it
     * would play once and then stand still for the rest of the clash.
     */
    private static void npcSwing(LivingEntity npc, boolean swinging)
    {
        try
        {
            if (npc instanceof com.dragonminez.common.init.entities.sagas.DBSagasEntity saga)
            {
                saga.setComboing(swinging);
            }
        }
        catch (Throwable ignored)
        {
            // Not every NPC in a clash is a saga entity, and a DragonMineZ reshape must not cost the clash.
        }
    }

    // Knockback for a non-player fighter.
    private static void pushEntity(LivingEntity entity, Vec3 direction, double speed)
    {
        try
        {
            entity.setDeltaMovement(direction.normalize().scale(speed).add(0.0D, 0.42D, 0.0D));
            entity.hurtMarked = true;
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * Start a clash between a player and an NPC that have just closed on each other.
     *
     * <p>The NPC's side is simulated: it has no client to play the chart on, so its score comes from
     * {@link ClashRhythm#npcScore} against the two fighters' relative power. Everything else about it is real. It is
     * held in place, blows land on it, and it is thrown if it loses.
     */
    public static boolean beginNpcClash(ServerPlayer player, LivingEntity npc)
    {
        if (player == null || npc == null || !npc.isAlive() || !(player.level() instanceof ServerLevel level))
        {
            return false;
        }
        // Suppressed while the player is in a ki beam clash, for the same reason the mutual dash trigger is: a beam
        // clash already holds the player, so an NPC melee clash would seize them a second time. The NPC has no beams
        // of its own to check, only the player can be the one in a DMZ clash here.
        if (inStruggle(player.getUUID()) || RECENT.containsKey(player.getUUID())
                || RECENT.containsKey(npc.getUUID()) || npcInStruggle(npc.getUUID())
                || inBeamClash(player.getUUID()))
        {
            return false;
        }
        try
        {
            DashService.cancel(player);
            Vec3 axis = npc.position().subtract(player.position());
            if (axis.lengthSqr() < 1.0E-6D)
            {
                return false;
            }
            axis = axis.normalize();

            // Same closing as the player-vs-player clash: both sides come onto CLASH_SEPARATION so the exchange is
            // fought at punching range rather than wherever the trigger caught them.
            if (player.distanceTo(npc) > CLASH_SEPARATION)
            {
                Vec3 mid = player.position().add(npc.position()).scale(0.5D);
                Vec3 half = axis.scale(CLASH_SEPARATION * 0.5D);
                Vec3 posPlayer = mid.subtract(half);
                Vec3 posNpc = mid.add(half);
                player.moveTo(posPlayer.x, posPlayer.y, posPlayer.z);
                npc.teleportTo(posNpc.x, posNpc.y, posNpc.z);
            }

            faceAlong(player, axis);
            player.setDeltaMovement(Vec3.ZERO);
            player.hurtMarked = true;
            holdNpc(npc);

            Struggle s = new Struggle();
            s.a = player.getUUID();
            s.b = player.getUUID();
            s.simulated = true;
            s.npc = npc.getUUID();
            s.axis = axis;
            s.ticks = 0;
            s.seed = level.getRandom().nextLong();
            // Only the player side is a real client and needs pinning; the NPC is server-authoritative and holdNpc's
            // zeroing is enough.
            s.anchorAX = player.getX();
            s.anchorAY = player.getY();
            s.anchorAZ = player.getZ();
            ACTIVE.add(s);

            NetworkUtils.sendTo(new PacketClashStart(s.seed, npc.getDisplayName().getString()), player);
            announceVisuals(player, null, true);
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static boolean npcInStruggle(UUID id)
    {
        for (Struggle s : ACTIVE)
        {
            if (id.equals(s.npc))
                return true;
        }
        return false;
    }

    // Pin a fighter at their anchor for the length of the struggle. Zeroing the server-side motion is not enough on its
    // own: a client-authoritative player keeps streaming movement packets and walks off regardless, which is exactly
    // what the rhythm keys were doing. The leashed correction below sends a position packet the client honours whenever
    // they drift past the leash, pulling them back while a still player trips nothing. The look angle is preserved on
    // purpose, so the pin never yanks their camera. Movement only: skills, ki, attributes and DMZ state are untouched.
    private static void hold(ServerPlayer player, double anchorX, double anchorY, double anchorZ)
    {
        player.setDeltaMovement(Vec3.ZERO);
        player.hasImpulse = true;
        player.hurtMarked = true;
        player.resetFallDistance();
        double dx = player.getX() - anchorX;
        double dy = player.getY() - anchorY;
        double dz = player.getZ() - anchorZ;
        if (dx * dx + dy * dy + dz * dz > FREEZE_LEASH_SQ)
        {
            player.connection.teleport(anchorX, anchorY, anchorZ, player.getYRot(), player.getXRot());
        }
    }

    private static void send(ServerPlayer player, int outcome, int own, int other)
    {
        try
        {
            if (player != null)
                NetworkUtils.sendTo(new PacketClashEnd(outcome, own, other), player);
        }
        catch (Throwable ignored)
        {
        }
    }

    // Close a fighter's overlay with no result, for a clash that ended without being played out.
    private static void cancel(ServerPlayer player)
    {
        send(player, 3, 0, 0);
    }

    // Winner keeps their footing and their momentum; loser is thrown back along the axis.
    private static void resolve(ServerPlayer winner, ServerPlayer loser, Vec3 awayFromWinner,
                               int winnerScore, int loserScore)
    {
        if (loser != null)
        {
            if (isRout(winnerScore, loserScore))
                rout(loser, awayFromWinner);
            else
                push(loser, awayFromWinner, LOSER_KNOCKBACK);
        }
        if (winner == null)
            return;
        // The follow up: cooldown waived and a window in which the winner may dash again straight away, so a won clash
        // continues the exchange instead of ending it.
        DashService.startCooldown(winner, 0);
        FOLLOW_UP.put(winner.getUUID(), FOLLOW_UP_TICKS);
    }

    /**
     * Twice the hits or better: not a win, a rout.
     *
     * <p>A clash decided by one or two beats is two fighters who were close, and the ordinary shove is the right
     * answer. Landing double someone's hits is a different result and now reads as one.
     *
     * <p>Zero is treated as a rout only when the winner actually scored, so two players who both miss everything are
     * a draw rather than a rout by the one who fluked a single beat.
     */
    private static boolean isRout(int winnerScore, int loserScore)
    {
        return winnerScore > 0 && winnerScore >= loserScore * 2;
    }

    /**
     * Thrown hard enough to go through what is behind them.
     *
     * <p>The same crash a flier makes of their own accord, done TO the loser: they travel much further than a shove,
     * they take whatever they hit with them, and terrain they are driven through obeys the same ki-destruction and
     * terrain-repair rules as everything else. No cost, because it is not their doing.
     */
    private static void rout(ServerPlayer loser, Vec3 awayFromWinner)
    {
        try
        {
            // Up along the throw so they leave the floor, or a rout along flat ground just scrapes.
            SonicCrash.slam(loser, awayFromWinner.normalize().add(0.0D, 0.25D, 0.0D).normalize(), 0.0F);
        }
        catch (Throwable ignored)
        {
            push(loser, awayFromWinner, LOSER_KNOCKBACK);
        }
    }

    private static void push(ServerPlayer player, Vec3 direction, double speed)
    {
        try
        {
            // A little upward lift so the throw arcs instead of scraping along the ground.
            player.setDeltaMovement(direction.normalize().scale(speed).add(0.0D, 0.42D, 0.0D));
            player.hurtMarked = true;
        }
        catch (Throwable ignored)
        {
        }
    }

    private static void faceAlong(ServerPlayer player, Vec3 direction)
    {
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) (-Math.toDegrees(
                Math.atan2(direction.y, Math.sqrt(direction.x * direction.x + direction.z * direction.z))));
        player.setYRot(yaw);
        player.setXRot(pitch);
        // The previous tick rotation matters too: a wave reads the owner's rotation, and leaving the old value behind
        // can have it interpolate from where the player used to be looking.
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), yaw, pitch);
    }

    /**
     * Start a clash directly, bypassing the mutual dash trigger. For {@code /meleeclash}.
     *
     * <p>Passing the same player twice is a SOLO test: side B becomes simulated, so one person with one client can
     * play the chart through and get a real result. Returns false if either side is already clashing or still inside
     * the post clash lockout.
     *
     * <p>Deliberately NOT suppressed for a ki beam clash, unlike the mutual dash and NPC triggers. This path only runs
     * from {@code /meleeclash}, an admin command, where starting a clash is an explicit request rather than an
     * accident of two dashes crossing. An admin who runs it on a player mid beam clash meant to, so it is honoured.
     */
    public static boolean forceClash(ServerPlayer a, ServerPlayer b)
    {
        if (a == null || b == null || !(a.level() instanceof ServerLevel level))
            return false;
        boolean solo = a.getUUID().equals(b.getUUID());
        if (inStruggle(a.getUUID()) || RECENT.containsKey(a.getUUID()))
            return false;
        if (!solo && (inStruggle(b.getUUID()) || RECENT.containsKey(b.getUUID())))
            return false;
        if (solo)
        {
            Struggle s = new Struggle();
            s.a = a.getUUID();
            s.b = a.getUUID();
            s.simulated = true;
            // No second body to face, so the axis is simply where they are looking: the knockback still reads right.
            Vec3 look = a.getLookAngle();
            s.axis = look.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : look.normalize();
            s.ticks = 0;
            s.seed = level.getRandom().nextLong();
            // Pin the lone fighter where they stand: the simulated side B is never a real player, so only side A holds.
            s.anchorAX = a.getX();
            s.anchorAY = a.getY();
            s.anchorAZ = a.getZ();
            ACTIVE.add(s);
            NetworkUtils.sendTo(new PacketClashStart(s.seed, "Training"), a);
            announceVisuals(a, null, true);
            return true;
        }
        begin(level, a, b);
        return inStruggle(a.getUUID());
    }

    private static boolean inStruggle(UUID id)
    {
        for (Struggle s : ACTIVE)
        {
            if (s.a.equals(id) || s.b.equals(id))
                return true;
        }
        return false;
    }

    /**
     * Whether this player is already locked in a DragonMineZ ki beam clash.
     *
     * <p>A melee clash is a rhythm duel that seizes both fighters, and DMZ's beam clash seizes them too, so a melee
     * clash that forms on top of a beam clash puts two contests on one pair of players: the reported "meleeclashes
     * happen on ki clash". Two dashers who are ALSO mid beam clash are dropped from the melee trigger so the beam
     * clash they are already in plays out alone.
     *
     * <p>DMZ is a mandatory dependency, so this reads its state directly. {@code BeamClashManager.isClashing} returns
     * whether the UUID owns a beam in an active clash: DMZ keeps a {@code CLASHING_OWNERS} set and rebuilds it from
     * {@code ACTIVE_CLASHES} inside {@code onLevelTick} every level tick, so the answer is current. Confirmed against
     * the 2.1.3 jar with javap, not inferred.
     *
     * <p>Fails OPEN on purpose: if that lookup ever throws or the method is gone after a DMZ change, this returns
     * false and the melee clash is allowed. A missed suppression is a cosmetic overlap, whereas swallowing the
     * trigger here would take the whole melee clash feature down.
     */
    private static boolean inBeamClash(UUID id)
    {
        if (id == null)
            return false;
        try
        {
            return com.dragonminez.common.combat.clash.BeamClashManager.isClashing(id);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** Whether this player is currently locked in a melee clash, for code outside this service that needs the predicate. */
    public static boolean isInClash(UUID id)
    {
        return id != null && inStruggle(id);
    }

    /** Whether this player is currently locked in a melee clash. */
    public static boolean isInClash(ServerPlayer player)
    {
        return player != null && inStruggle(player.getUUID());
    }

    /** Drop all clash state, for a server stop or a reload. Spawns nothing, so there is nothing to discard. */
    public static void clear()
    {
        ACTIVE.clear();
        FOLLOW_UP.clear();
        RECENT.clear();
    }
}
