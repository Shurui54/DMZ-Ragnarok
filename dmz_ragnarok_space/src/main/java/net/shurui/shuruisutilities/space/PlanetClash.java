package net.shurui.shuruisutilities.space;

import java.util.Comparator;

import com.dragonminez.common.init.entities.ki.AbstractKiProjectile;
import com.dragonminez.common.init.entities.ki.KiWaveEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.world.space.SpaceKeys;

/**
 * One live planet-clash: the invisible {@link PlanetDefenderEntity} holder and the {@link KiWaveEntity} it casts back
 * at an incoming giant ball. This class only owns the two entities and their lifecycle; the trigger, the win/lose
 * bookkeeping and the config live in {@link PlanetBusterModule}.
 *
 * <h2>How the clash forms, using nothing but public DragonMineZ API</h2>
 * We never construct a DragonMineZ {@code BeamClash} ourselves (its constructor and detector are private). Instead we
 * put two QUALIFYING beams nose to nose and let DragonMineZ's {@code BeamClashManager} (Forge bus, on level tick END)
 * pair them on its own. The ball already qualifies as MAJOR while firing (see {@code MixinDmzGiantBallClash}). For the
 * defender side we:
 * <ol>
 *   <li>place the invisible holder on the line from the ball to the planet centre, on the PLANET side of the ball,</li>
 *   <li>aim the holder BEFORE constructing the wave so the wave's clash heading is the EXACT opposite of the ball's
 *   clash heading (see the long note in {@link #begin}). This is NOT the same as pointing the holder at the ball: a
 *   {@code KiBlast}'s clash heading is a mirrored function of its velocity, so "point at the ball" only opposed the
 *   ball for a perfectly level shot and never for a real shot at a planet above or below eye level,</li>
 *   <li>call {@code setupKiWave} (which sets the wave's render type, size, colours, ki damage = toughness, and adds it
 *   to the level itself), then flip it firing immediately with {@code setFiring(true)} so {@code isClashableBeam()}
 *   is true this instant rather than a few cast ticks later.</li>
 * </ol>
 * {@code KiWaveEntity.getClashRole()} is MAJOR unconditionally, so once firing the wave is a full clash participant.
 * DragonMineZ auto-classifies it as an NPC (its owner is not a ServerPlayer) and auto-presses the clash meter for us,
 * so we drive no input; the attacker is a real ServerPlayer, so DragonMineZ's clash HUD comes free.
 *
 * <h2>Why the wave stays alive: its own cast time, not our life extension</h2>
 * {@code setupKiWave} sets the wave's max life to {@code castTime * 2}, and an UNLOCKED firing wave keeps growing its
 * beam and can die on its own (it detonates when it hits a high-resistance block or outlives its max life). A
 * clash-LOCKED {@code KiWaveEntity} takes an early-return path in its tick that skips growth, block raytracing and the
 * max-life death check, so once a lock forms the wave is safe for the rest of the struggle.
 *
 * <p>This used to lean on that lock forming on the very next level tick, with a short cast time and {@link #keepAlive()}
 * extending the wave's life to cover the gap. Two 14 Aug timelines showed the lock never forms at all: the wave is gone
 * between t=1 and t=10 while every condition the pairing turns on reads correct (both sides MAJOR, clashable and firing,
 * clash headings exactly opposed at dot -1.000, segments overlapping, both owners resolving).
 *
 * <h2>What was actually removing the wave: a holder at zero health</h2>
 * {@code KiWaveEntity.tick()} opens by discarding itself unless {@code getOwner()} is a {@code LivingEntity} that is
 * ALIVE, and {@code LivingEntity.isAlive()} is {@code !isRemoved() && getHealth() > 0}. The holder was spawned without
 * health ever being set, so the answering wave threw itself away on its FIRST tick, one tick after spawning and long
 * before the pairing test could run on it. Three timelines all read {@code waveRemoval=DISCARDED waveTick=1} while
 * every condition the pairing turns on was correct, which is exactly the signature of a beam that is discarded rather
 * than one that fails to pair.
 *
 * <p>Nothing had damaged the holder; it simply never had health. {@code DBSagasEntity} only calls {@code setHealth}
 * from hurt, die and the transform spawn, none of which happen to an invulnerable holder with no transform chain, and
 * the holder is deliberately marked {@code dmz_stats_configured} so DragonMineZ's entity-join stat pass leaves it
 * alone. So {@link #begin} sets its health explicitly at spawn and refuses to build a clash around a holder that is
 * not alive, and {@link #keepAlive()} holds it up for the rest of the struggle.
 *
 * <p>Two earlier theories were wrong and are recorded so they are not retried: the wave was not expiring by max life
 * (raising the cast time from 5 to 60, and so its natural life from 10 ticks to 120, changed nothing), and it was not
 * being unloaded (its removal reason was DISCARDED, never UNLOADED_TO_CHUNK).
 */
public final class PlanetClash
{
    // how far in FRONT of the ball (toward the planet) the holder is placed, in blocks. Kept SMALL on purpose. With the
    // clash-space aiming (see begin) the wave's beam grows toward the planet, i.e. AWAY from the ball, so its origin
    // must start close to the ball or the two beam segments drift apart before they can be paired. DragonMineZ's overlap
    // threshold is (ballSize + waveSize) + 1.5, which a giant ball's size dominates, but the wave only needs to survive
    // one unlocked tick before it locks, so three blocks keeps the origin comfortably inside that threshold with margin.
    private static final double DEFENDER_STANDOFF = 3.0;

    // wave shape/colour. Size feeds the overlap threshold above; the colours are a cold planetary blue-white so the
    // answering beam reads as the world itself firing back rather than a player technique.
    private static final float WAVE_SPEED = 1.5F;
    private static final float WAVE_SIZE = 3.0F;
    private static final int WAVE_COLOR_MAIN = 0x66CCFF;
    private static final int WAVE_COLOR_BORDER = 0xCCF2FF;
    private static final int WAVE_COLOR_OUTLINE = 0x2277CC;

    // Sized against the 80-tick engage window rather than against how fast the wave becomes clashable. Cast time costs
    // nothing in clashability because setFiring(true) is flipped by hand at spawn rather than waiting for the cast to
    // elapse, so there is no reason to keep this short.
    //
    // Be careful what you conclude from this number. It was raised from 5 to 60 on the theory that setupKiWave derives
    // max life from castTime * 2, so a cast of 5 gave the wave only 10 ticks and that was why it died at t=10. The
    // 19:18 run disproved it: with a cast of 60 the wave STILL died between t=1 and t=10, in the same place, with the
    // same frozen beam length. So whatever removes the wave, it is not this. The value stays at 60 because a longer
    // natural life is harmless and removes one variable, NOT because it fixed anything.
    private static final int WAVE_CAST_TIME = 60;

    // Technique-id sentinel stamped on the defending wave so the client can render it invisible. TECHNIQUE_ID is a
    // synced field on AbstractKiProjectile, so unlike server-only ForgeData it reaches the client, where
    // {@code MixinKiWaveRenderer} cancels the render of any wave carrying this exact id. The value is never looked up
    // as a real technique (the wave's owner is the non-player holder, so DMZ's technique-effect paths early-return),
    // so a bogus id is inert on the server and purely a client render marker. The fiction is the PLANET resisting, so
    // the answering beam must not be seen; only the attacker's own blast or beam strains against nothing. ALIASES the
    // core-owned SpaceKeys constant (same value) so the client render mixins can read it from core.
    public static final String DEFENDER_WAVE_MARKER = SpaceKeys.DEFENDER_WAVE_MARKER;

    // how far ahead of the wave's current tick we push its max life each keepAlive(), mirroring the ball's own margin.
    private static final int WAVE_LIFE_MARGIN = 40;

    // Chunk ticket that keeps the clash region LOADED and ENTITY-TICKING for the whole struggle. This is the fix for
    // "the clash never forms": the defender and its answering wave spawn on the planet surface up to
    // SpaceLayout.LABEL_DISTANCE (300) blocks from the firer, far outside any player's loaded region, so with no ticket
    // DragonMineZ's BeamClashManager (which only walks the level's LOADED entities on level-tick END) never sees the
    // wave and the pair never forms. Follows the same TicketType route GraveManager uses (a non-forced region ticket,
    // released explicitly), so unlike ServerLevel.setChunkForced it does NOT persist to level.dat: a crash mid-clash
    // simply drops it on the next start rather than leaving a permanent loaded chunk. Comparator by packed pos for dedup.
    private static final TicketType<ChunkPos> CLASH_TICKET =
            TicketType.create("su_planet_clash", Comparator.comparingLong(ChunkPos::toLong));

    // A region ticket sets its centre chunk to level (33 - radius); a chunk at Chebyshev distance d from it sits at
    // (33 - radius + d), and ENTITY_TICKING is level 31, so radius R makes every chunk within (R - 2) of the anchor
    // entity-ticking. R = 4 gives a 5x5 entity-ticking block (~72 blocks across) around the defender, which covers the
    // holder, the answering wave's whole seeded segment and the incoming ball three blocks behind it with margin, so all
    // three tick and appear in the level's entity list the detector walks.
    private static final int CLASH_TICKET_RADIUS = 4;

    // seed length, in blocks, stamped on the answering wave's clash segment at spawn. Base getClashBeamLength() reads the
    // synced BEAM_LENGTH, which starts near zero and only grows once the wave has ticked; but the pair can be tested
    // before the wave's first tick, and a zero-length segment collapses to a point with almost no overlap margin. A dozen
    // blocks gives the segment real length the instant it is detected, still well inside the entity-ticking region above.
    private static final float WAVE_SEED_BEAM_LENGTH = 12.0F;

    private final ServerLevel level;
    private final ChunkPos ticketChunk;
    private final PlanetDefenderEntity defender;
    private final KiWaveEntity wave;
    private boolean cleanedUp;
    private boolean ticketReleased;

    private PlanetClash(ServerLevel level, ChunkPos ticketChunk, PlanetDefenderEntity defender, KiWaveEntity wave)
    {
        this.level = level;
        this.ticketChunk = ticketChunk;
        this.defender = defender;
        this.wave = wave;
    }

    /**
     * Spawn the holder and fire its wave at the incoming ball. May throw ANY {@link Throwable} if DragonMineZ's clash
     * API has drifted across the version boundary; the caller catches it, logs once, and degrades to the ordinary
     * bust (no clash). On success returns a live {@link PlanetClash}.
     *
     * @param space     the space dimension level the ball is flying in.
     * @param ball       the in-flight giant ball, already steered toward the planet centre.
     * @param centre    the target planet's centre.
     * @param toughness the defender beam's ki damage (see {@link PlanetToughness}).
     */
    public static PlanetClash begin(ServerLevel space, AbstractKiProjectile ball, Vec3 centre, double toughness)
    {
        // the ball-in-flight case anchors the standoff at the ball's own position, which by trigger time sits in open
        // space just in front of the planet. The beam case (a KiWaveEntity anchored far back at the firer's eye) passes
        // an explicit anchor near the planet instead, so the defender always forms next to the target, not at the eye.
        return begin(space, ball, centre, toughness, ball.position());
    }

    /**
     * As {@link #begin(ServerLevel, AbstractKiProjectile, Vec3, double)}, but with an explicit {@code basePos} to base
     * the defender standoff from. For a travelling ball this is the ball's own position; for an anchored beam it is a
     * point on the beam near the planet (its closest approach to the centre), so the answering wave forms at the target
     * rather than back at the firer's eye where the beam entity actually sits. The aim math is identical either way: the
     * wave's clash heading is fixed to the exact negation of {@code projectile}'s clash heading, which for a wave is the
     * firer's stable look and for a ball is its live (mirrored) rotation, so the two beams read as opposed at the
     * pairing test.
     */
    public static PlanetClash begin(ServerLevel space, AbstractKiProjectile projectile, Vec3 centre, double toughness,
                                    Vec3 basePos)
    {
        AbstractKiProjectile ball = projectile;
        Vec3 ballPos = basePos;

        // WHERE to place the holder: on the line from the ball toward the planet centre, a few blocks in front of the
        // ball. This is pure POSITIONING and is deliberately kept SEPARATE from AIMING below, because they answer two
        // different questions. centre - ballPos always points at the planet, so it is a more reliable placement axis
        // than the ball's velocity (which our per-tick steering may have just re-pointed). Fall back to the beam's own
        // heading only in the degenerate case where the ball is sitting exactly on the planet centre.
        Vec3 toPlanet = centre.subtract(ballPos);
        if (toPlanet.lengthSqr() < 1.0E-6)
        {
            // degenerate: the ball sits exactly on the planet centre, so centre - ballPos gives no direction. Derive the
            // placement axis from the beam's OWN clash heading rather than a fixed +Z world axis, so the defender is still
            // pushed out along the line of the shot instead of off to an arbitrary side. Only a truly headingless beam
            // (both degenerate at once, which never happens in practice) falls back to +Z.
            Vec3 beamHeading = Vec3.directionFromRotation(ball.getClashPitch(), ball.getClashYaw());
            toPlanet = beamHeading.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : beamHeading.normalize();
        }
        else
        {
            toPlanet = toPlanet.normalize();
        }
        Vec3 defenderPos = ballPos.add(toPlanet.scale(DEFENDER_STANDOFF));

        // HOW to aim the holder, and this is the whole fix for "the clash never forms". DragonMineZ pairs two MAJOR
        // beams only when the DOT of their clash headings is below -0.3 (opposed), reading each side's heading as
        // Vec3.directionFromRotation(getClashPitch(), getClashYaw()) (see BeamClashManager.beamsClash). For our KiWave
        // that heading is exactly the OWNER's look vector: DragonMineZ stamps FIXED_YAW/FIXED_PITCH from the owner's
        // look and directionFromRotation faithfully inverts that. For the BALL, though, getClashYaw()/getClashPitch()
        // are the LIVE entity yaw/pitch that Minecraft's ProjectileUtil.rotateTowardsMovement writes every tick from the
        // ball's velocity, using yaw = atan2(vz, vx) + 90 and pitch = atan2(h, vy) - 90. Feeding THOSE back through
        // directionFromRotation does NOT return the ball's travel direction; it returns a mirrored heading, and the
        // algebra works out to dot(ballClashHeading, -normalize(velocity)) = 1 - 2 * verticalFraction^2. So aiming the
        // wave at minus the ball's VELOCITY (the intuitive "point back at the ball") opposes it only for a perfectly
        // level shot, and even a level shot yields dot = +1 (the worst possible); any real shot at a planet above or
        // below eye level pushes the dot well above -0.3 and the pair never happens. That was measured live as
        // dot=+0.856 for a roughly 16-degree downward shot. The robust cure is to aim so the wave's clash heading is the
        // EXACT negation of the ball's clash heading as DragonMineZ itself computes it, which makes the dot -1.0 for
        // every geometry, level or steep, up or down.
        Vec3 ballClashHeading = Vec3.directionFromRotation(ball.getClashPitch(), ball.getClashYaw());
        Vec3 aim = ballClashHeading.lengthSqr() < 1.0E-6
                ? toPlanet.scale(-1.0)
                : ballClashHeading.normalize().scale(-1.0);

        // convert the aim direction into the yaw/pitch the holder must carry so that its look vector (which DragonMineZ
        // turns into the wave's clash heading) equals `aim`. This is the standard Minecraft inverse of
        // directionFromRotation, so directionFromRotation(pitch, yaw) == normalize(aim) and the wave heading lands on it.
        float yaw = (float) Math.toDegrees(Math.atan2(-aim.x, aim.z));
        float pitch = (float) (-Math.toDegrees(Math.atan2(aim.y, Math.sqrt(aim.x * aim.x + aim.z * aim.z))));

        PlanetDefenderEntity defender = PlanetDefenderEntities.type().create(space);
        if (defender == null)
        {
            throw new IllegalStateException("PlanetDefenderEntity failed to instantiate");
        }
        // orient the holder BEFORE the wave is constructed: the wave stamps its fixed clash yaw/pitch from the owner's
        // rotation now, so aiming after construction would leave the wave pointing the wrong way. Set the previous-tick
        // rotations too so there is no interpolation swing on the first client frame.
        defender.moveTo(defenderPos.x, defenderPos.y, defenderPos.z, yaw, pitch);
        defender.setYRot(yaw);
        defender.setXRot(pitch);
        defender.yRotO = yaw;
        defender.xRotO = pitch;
        defender.setYHeadRot(yaw);
        defender.yHeadRotO = yaw;
        defender.setYBodyRot(yaw);

        // force-load the clash region BEFORE spawning, so the holder is added into an already entity-ticking chunk (see
        // CLASH_TICKET). Everything from here is wrapped: any failure releases the ticket and discards the half-spawned
        // holder before rethrowing, so a failed begin leaks neither a permanent chunk ticket nor an orphan holder. On
        // success the returned PlanetClash owns the ticket and releases it in cleanup().
        ChunkPos ticketChunk = new ChunkPos(BlockPos.containing(defenderPos.x, defenderPos.y, defenderPos.z));
        space.getChunkSource().addRegionTicket(CLASH_TICKET, ticketChunk, CLASH_TICKET_RADIUS, ticketChunk);
        try
        {
            if (!space.addFreshEntity(defender) || defender.isRemoved())
            {
                throw new IllegalStateException("PlanetDefenderEntity failed to spawn");
            }
            // Spawning is not the same as being FINDABLE. DragonMineZ gathers clash candidates by calling getOwner()
            // on each beam, which resolves the owner by UUID through ServerLevel.getEntity. A holder that is in the
            // world but not in that index is invisible to the pairing test, and the clash then fails in a way that
            // looks exactly like bad geometry. Checking it here turns that into a loud failure at the one moment it
            // can still be attributed.
            if (space.getEntity(defender.getUUID()) != defender)
            {
                throw new IllegalStateException(
                        "PlanetDefenderEntity spawned but is not resolvable by UUID; DragonMineZ could never pair it");
            }

            // ALIVE, not merely present. This is the one that was actually killing every clash.
            //
            // KiWaveEntity.tick() opens with, in effect:
            //     if (!(getOwner() instanceof LivingEntity o) || !o.isAlive()) { if (!level.isClientSide) discard(); }
            // and LivingEntity.isAlive() is !isRemoved() && getHealth() > 0. So a holder sitting at zero health makes
            // the answering wave throw itself away on its FIRST tick, one tick after it spawns, before the pairing test
            // ever runs on it. That is exactly what three timelines showed: waveRemoval=DISCARDED at waveTick=1 with
            // every other condition (MAJOR, clashable, firing, dot -1.000, segments overlapping) reading correct.
            //
            // The holder never took a hit, so nothing had reduced its health; it simply never had any. DBSagasEntity
            // only ever calls setHealth from hurt, die and the transform spawn, none of which run for an invulnerable
            // holder with no transform chain, and this entity is deliberately marked dmz_stats_configured so
            // DragonMineZ's own entity-join stat pass leaves it alone. Between those two, nothing was setting it.
            defender.setHealth(Math.max(1.0F, defender.getMaxHealth()));
            if (!defender.isAlive())
            {
                throw new IllegalStateException(
                        "PlanetDefenderEntity is not alive after spawn; its wave would discard itself on tick 1");
            }

            // construct and fire the answering wave. setupKiWave adds it to the level itself, so we do NOT add it again.
            KiWaveEntity wave = new KiWaveEntity(space, defender);
            float damage = (float) Math.max(1.0, toughness);
            wave.setupKiWave(defender, damage, WAVE_SPEED, WAVE_COLOR_MAIN, WAVE_COLOR_BORDER, WAVE_COLOR_OUTLINE,
                    WAVE_SIZE, WAVE_CAST_TIME);
            // belt and braces: guarantee getKiDamage() reports the toughness DragonMineZ turns into the defender's clash
            // weight, regardless of any internal scaling inside setupKiWave.
            wave.setKiDamage(damage);
            // Own the wave EXPLICITLY, and this is what the removal trace pointed at.
            //
            // KiWaveEntity.tick discards itself when getOwner() is not a living, alive entity, and the stack trace
            // showed that exact discard firing on tick 1 while our own diagnostics read the owner as a live
            // PlanetDefenderEntity at 300/300. Both can be true: the diagnostic runs later and resolves through the
            // owner UUID, so it says nothing about what tick 1 saw. setupKiWave never calls setOwner, so the wave's
            // owner rested entirely on what the constructor did with it. Setting it here removes the question.
            wave.setOwner(defender);
            // flip it firing NOW so isClashableBeam() (MAJOR and firing) is true this tick and the detector can pair it
            // immediately, rather than waiting the cast ticks for it to self-flip.
            wave.setFiring(true);
            // seed a real clash segment length so the pair has overlap margin even on the tick it is first tested, before
            // the wave has grown its own beam. Best effort: see seedClashBeamLength.
            seedClashBeamLength(wave, WAVE_SEED_BEAM_LENGTH);
            // stamp the invisibility marker LAST, after setupKiWave (which does not touch the technique id), so the client
            // renderer skips this wave entirely. See DEFENDER_WAVE_MARKER.
            wave.setTechniqueId(DEFENDER_WAVE_MARKER);

            return new PlanetClash(space, ticketChunk, defender, wave);
        }
        catch (Throwable t)
        {
            releaseTicket(space, ticketChunk);
            if (!defender.isRemoved())
            {
                try
                {
                    defender.discard();
                }
                catch (Throwable ignored)
                {
                    // the orphan sweep on the next server start reclaims it if the discard itself drifted.
                }
            }
            // preserve the caller's "catch (Throwable) and degrade to the plain bust" contract: rethrow unchecked as-is,
            // wrapping only a genuinely checked throwable (which the DMZ clash API never declares).
            if (t instanceof RuntimeException re)
            {
                throw re;
            }
            if (t instanceof Error err)
            {
                throw err;
            }
            throw new IllegalStateException("planet clash setup failed", t);
        }
    }

    // stamp a non-zero clash segment length on the answering wave. getClashBeamLength() reads the synced BEAM_LENGTH,
    // whose setter is private DMZ internals, so this reaches it by reflection. Best effort and fully guarded: if the
    // setter has drifted across the version boundary the wave still clashes, just with the shorter segment it grows on
    // its own, so a miss here is never fatal and must not abort the begin.
    private static void seedClashBeamLength(KiWaveEntity wave, float length)
    {
        try
        {
            java.lang.reflect.Method setter = KiWaveEntity.class.getDeclaredMethod("setBeamLength", float.class);
            setter.setAccessible(true);
            setter.invoke(wave, length);
        }
        catch (Throwable ignored)
        {
            // leave the wave to grow its own segment; the standoff and size still give one tick's worth of overlap.
        }
    }

    // release this clash's chunk ticket. Idempotent at the call sites (begin's failure path runs before any instance
    // exists; cleanup guards with ticketReleased), and each call is guarded so a release can never throw into a caller.
    private static void releaseTicket(ServerLevel level, ChunkPos chunk)
    {
        try
        {
            level.getChunkSource().removeRegionTicket(CLASH_TICKET, chunk, CLASH_TICKET_RADIUS, chunk);
        }
        catch (Throwable ignored)
        {
            // a non-forced ticket that somehow outlives this drops on the next server start regardless (it never
            // persists to level.dat), so a failed release is self-healing rather than a permanent leak.
        }
    }

    /** Push the wave's max life forward so a short cast time cannot expire it mid-clash. Safe to call every tick. */
    public void keepAlive()
    {
        if (cleanedUp)
        {
            return;
        }
        // The HOLDER first, and independently of the wave. DragonMineZ gathers clash candidates by requiring
        // getOwner() to be a LivingEntity, so the moment this holder stops resolving, the defending wave is no longer
        // a candidate at all and the pairing test never runs on it again. That failure is completely silent: the wave
        // is still MAJOR, still firing, still aimed correctly, and simply never pairs. Keeping the holder alive is
        // therefore not tidiness, it is the difference between a clash and a planet quietly exploding.
        try
        {
            if (defender != null && !defender.isRemoved())
            {
                defender.setPersistenceRequired();
                defender.setNoAi(true);
                // Vanilla despawn ignores persistence for some paths and age for others; zeroing the age keeps the
                // holder out of every one of them for as long as the struggle runs.
                defender.tickCount = 0;
                // Hold its health up for the same reason it is set at spawn: the answering wave discards itself on any
                // tick where its owner is not alive, so a holder that ever reaches zero takes the clash with it.
                if (defender.getHealth() <= 0.0F)
                {
                    defender.setHealth(Math.max(1.0F, defender.getMaxHealth()));
                }
            }
        }
        catch (Throwable ignored)
        {
        }
        if (wave == null || wave.isRemoved())
        {
            return;
        }
        try
        {
            // Only ever RAISE it. This used to assign tickCount + margin unconditionally, which on the first call
            // (tickCount 0) cut the wave's max life from the 120 its cast time had given it down to 40. That was not
            // what killed the clash, but shortening the life of the thing we are trying to keep alive is the exact
            // opposite of the method's job, and the timeline showed it happening on every run.
            wave.setMaxLife(Math.max(wave.getMaxLife(), wave.tickCount + WAVE_LIFE_MARGIN));
        }
        catch (Throwable ignored)
        {
            // never let a life-extension failure break the tick loop; if it stops, the clash simply dissolves.
        }
    }

    /**
     * DIAGNOSTICS ONLY: the answering wave entity, or null before/after its lifetime. Package-private and read-only so
     * {@link PlanetBusterModule}'s clash-debug line can sample the wave's live clash state alongside the ball's. It does
     * NOT expose the wave for mutation and has no effect on the clash itself.
     */
    KiWaveEntity debugWave()
    {
        return wave;
    }

    /** True once at least one of the two entities has gone (the ball killed the wave on a win, or a dissolve). */
    public boolean isDefenderGone()
    {
        return defender == null || defender.isRemoved() || !defender.isAlive();
    }

    /**
     * True once the answering WAVE (the defender's beam, not the holder) has gone. Used by the beam path to tell a
     * player loss from a mutual dissolve: on a clash the loser's beam is killed by DMZ's resolve, so if the attacker's
     * beam vanished while this wave is still alive the planet won, whereas if both are gone the struggle just dissolved
     * (the player released) and nothing should be announced. The holder entity outlives both until we clean it up, so
     * {@link #isDefenderGone()} cannot answer this.
     */
    public boolean isWaveGone()
    {
        return wave == null || wave.isRemoved() || !wave.isAlive();
    }

    /**
     * Discard the holder and its wave. Idempotent and total: called on EVERY exit path for the owning record (win,
     * loss, dissolve, player logoff, target already destroyed, timeout, server stop) so no invisible holder is ever
     * orphaned in the space dimension. Never throws.
     */
    public void cleanup()
    {
        if (cleanedUp)
        {
            return;
        }
        cleanedUp = true;
        try
        {
            if (wave != null && !wave.isRemoved())
            {
                wave.discard();
            }
        }
        catch (Throwable ignored)
        {
            // ignore: fall through to the holder.
        }
        try
        {
            if (defender != null && !defender.isRemoved())
            {
                defender.discard();
            }
        }
        catch (Throwable ignored)
        {
            // give up on this one; it will be swept on the next server start if it somehow survived.
        }
        // release the chunk ticket LAST, after both entities are discarded (so their removal happens while the region is
        // still loaded). Guarded by ticketReleased so a double cleanup can never double-release, and reached on EVERY
        // exit path because cleanupClash() funnels every win/loss/dissolve/logoff/timeout/server-stop here.
        if (!ticketReleased)
        {
            ticketReleased = true;
            releaseTicket(level, ticketChunk);
        }
    }
}
