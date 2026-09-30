package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.config.ConfigData;
import net.shurui.shuruisutilities.core.config.ConfigLoaderBase;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule;
import net.shurui.shuruisutilities.guilds.GuildManager;
import net.shurui.shuruisutilities.guilds.model.Guild;
import net.shurui.shuruisutilities.guilds.raid.GuildRaidSpoils;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.dragonminez.common.events.DMZEvent;
import com.dragonminez.common.init.entities.ki.AbstractKiProjectile;
import com.dragonminez.common.init.entities.ki.KiBlastEntity;
import com.dragonminez.common.init.entities.ki.KiWaveEntity;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;
import com.dragonminez.common.stats.techniques.KiAttackData;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.Builder;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Phase B of planet destruction: a player in the space dimension charges a GIANT BALL ki blast, fires it at a generated
 * planet whose name is showing, and when the blast flies into that planet's derived cube the planet is destroyed. This
 * module is ONLY the trigger and the in-flight steering; it does not destroy anything itself. Every actual destruction
 * routes through the single {@link PlanetDestruction} entry point, exactly like the admin command and the (future) raid
 * path, so the destructible rule, the on-surface death and the client resync can never drift between callers.
 *
 * <p>NOTHING here is a mixin. DMZ posts {@link DMZEvent.KiAttackFireEvent} on the Forge bus AFTER a ki attack launches,
 * so a plain {@code @SubscribeEvent} handler plus a server-tick handler is all that is needed. dragonminez is a MANDATORY
 * dependency of this addon (see mods.toml), so the direct references to the DMZ event and projectile types are safe with
 * no ModList guard.
 *
 * <p>THE RANGE RULE. A planet is a legal target only when its nameplate would be showing, i.e. the player is within
 * {@link SpaceLayout#LABEL_DISTANCE} of the body's SURFACE. That constant is shared with the renderer's label gate on
 * purpose (see its note): "you may only destroy a planet from the same distance its name shows from." There is deliberately
 * NO range config; the range is tied to the label distance and must stay tied to it.
 *
 * <p>THE CLAIM RULE. An UNCLAIMED planet may be busted freely. A CLAIMED planet may be busted ONLY if the firing player's
 * guild holds an unspent raid entitlement for that exact planet ({@link GuildRaidSpoils}); the entitlement is CONSUMED at
 * the moment of impact, and that consume is the only way it is spent, so a raid win makes destruction possible but never
 * automatic.
 *
 * <p>Auto-registered on the Forge event bus by the SU module launcher (like {@link SpaceTravelModule} and {@link
 * PlanetSpawnModule}).
 */
@SUModule(name = "PlanetBuster", parentMod = ShuruisUtilities.class, version = ShuruisUtilities.CURRENT_MODULE_VERSION)
public class PlanetBusterModule extends ConfigLoaderBase
{
    private static ForgeConfigSpec PLANET_BUSTER_CONFIG;
    private static final ConfigData data =
            new ConfigData("PlanetBuster", PLANET_BUSTER_CONFIG, new ForgeConfigSpec.Builder());

    // config-backed settings (baked in bakeConfig)
    private static boolean enabled = true;
    // The DOOM SEQUENCE durations, in server ticks. A bust is not instantaneous: when the blast lands the planet first
    // RAMPS red for redRampTicks, THEN the destroy fires and the client plays a SHATTER for shatterTicks. Both are pure
    // client-visual timers on the client; the server only holds redRampTicks (to know when to destroy) and forwards
    // shatterTicks in the shatter packet. Defaults: 60 ticks (3s) of red ramp, 40 ticks (2s) of shatter.
    private static int redRampTicks = 60;
    private static int shatterTicks = 40;
    // How many points a planet's DESTROYER loses off their DMZ alignment (Resources, 0..100, higher is GOOD). Blowing
    // up a world is an evil act, so the credited destroyer's alignment drops toward EVIL. Read by the shared
    // PlanetDestruction entry point for every credited destroy (the ki blast now, a raid win later), so the penalty is
    // one rule for all destroy paths. Default 10; 0 disables it. Lives on this module because it is the destruction
    // feature's config home, even though PlanetDestruction is the class that applies it.
    private static int alignmentPenalty = 10;
    // The set of KiAttackData.KiType NAMES that may trigger a bust. Held as a Set of enum-constant name strings (not as
    // enum values) so an unknown/typo'd config entry is simply dropped at bake, never a crash, and so the list can be
    // extended later (the user plans "gods of destruction" / "destruction ki" techniques) purely by config. The event
    // gate compares event.getKiAttack().getKiType().name() against this set.
    private static volatile Set<String> allowedKiTypeNames = new HashSet<>(Arrays.asList("GIANT_BALL"));

    // Master switch for the whole clash gate. When true, a giant ball fired at a planet meets a DragonMineZ beam
    // clash: the planet fires an answering wave and the shot only busts the world if it WINS the struggle. When
    // false, the buster behaves exactly as it did before (the ball always destroys the planet).
    private static boolean clashEnabled = true;
    // Where the clash forms, expressed relative to the target's radius so it works for both small and huge bodies. The
    // trigger distance from the planet centre is max(clashTriggerMinDistance, radius * clashTriggerRadiusMultiplier),
    // chosen comfortably outside the body so the struggle happens in open space in front of the planet.
    private static double clashTriggerRadiusMultiplier = 2.0;
    private static double clashTriggerMinDistance = 50.0;
    // Toughness (defending beam ki damage) of a WILD / unowned planet: a flat, configurable number. Defaults live on
    // the KI-DAMAGE scale, not the old ~100 scale: DMZ turns toughness straight into the defender's clash weight and
    // pits it against the incoming blast's own getKiDamage(), which for a real spirit bomb runs from a few thousand
    // (a developed mid-game player) into six figures (a high-level player in a form). A 100-toughness planet lost every
    // clash by two or three orders of magnitude, which is why the struggle felt absent. 5000 makes a wild world an even
    // fight for a developed mid-game blast and a clear loss for a weak one.
    private static double clashWildToughness = 5000.0;
    // Guild-owned toughness formula: base + perMember * members + battlePower / divisor, then clamped up to a floor.
    // The floor is essential because a guild's cached battle power reads zero while every member is offline, so
    // without it an offline guild's planet would be nearly free to bust. See PlanetToughness for the full note. Like
    // the wild value these now sit on the ki-damage scale so a claimed world genuinely resists: a small guild floors at
    // 8000 (already a serious blast), a full guild with live battle power can reach into the tens of thousands, which
    // demands a high-level form blast to overcome.
    private static double clashGuildToughnessBase = 5000.0;
    private static double clashGuildToughnessPerMember = 2000.0;
    // battlePower / divisor is a toughness term. DMZ guild battle power aggregates every member's stats and reaches
    // hundreds of thousands into the millions, so the old 100000 divisor reduced even a strong guild to a handful of
    // toughness on the new scale. 100 lets a guild summing ~1,000,000 battle power contribute ~10000 toughness, keeping
    // an active guild's world meaningfully tougher than an idle one. Set to 0 to ignore battle power entirely.
    private static double clashGuildBattlePowerDivisor = 100.0;
    private static double clashGuildToughnessFloor = 8000.0;

    // The MINIMUM ki damage an incoming blast must ITSELF carry before it is allowed to destroy a planet at all. This
    // is a SEPARATE, independent gate from the clash above. The clash asks "does this blast out-power the planet's
    // toughness in a struggle?"; this asks the more basic "is this blast a real world-ender in the first place?". They
    // are deliberately not the same knob: a blast can be strong enough to win a clash against a soft wild world yet the
    // server operator may still want an absolute floor below which no blast, against any planet, ever busts anything.
    // Chosen FLAT (an absolute ki-damage number) rather than relative to toughness ON PURPOSE, so it stays independent
    // of the clash: the toughness-relative comparison already lives in the clash, and making this relative too would
    // just duplicate it and re-couple the two gates the design keeps apart. Checked EARLY, at fire time, so a player
    // firing too weak a blast is refused immediately instead of after fighting a whole clash. This is exactly the
    // user's "too little damage means the clash never even starts" rule. Default 3000, the researched floor for a
    // developed mid-game player's blast, so a genuinely weak shot cannot even open a struggle. 0 disables the gate (the
    // clash then remains the only gate).
    private static double minBlastKiDamage = 3000.0;

    // Master switch for the BEAM path. When true, a firing ki wave (a Kamehameha and the like) aimed at a planet in
    // space triggers the SAME planet clash a giant ball does: the planet fires an answering wave and the beam only
    // busts the world if it WINS the struggle. When false, no beam ever opens a clash and beams cannot destroy a
    // planet. This is a SEPARATE path from the ball allowlist above; a beam is never recovered through the fire event,
    // it is found by a per-tick raytrace of the beam segment against planet cubes, because a ki wave never travels (its
    // entity stays anchored at the firer's eye and only its beam LENGTH extends toward the target).
    private static boolean beamEnabled = true;
    // The minimum ki damage a firing BEAM must itself carry to open a planet clash. The beam twin of minBlastKiDamage,
    // and independent of the clash the same way: this asks whether the beam is a world-ender at all, the clash asks
    // whether it out-powers the planet's toughness in a struggle. A beam under this floor is ignored (it strains
    // against the world with no effect) and never opens a struggle. Default 3000, matching the blast floor. 0 disables.
    private static double minBeamKiDamage = 3000.0;

    // DIAGNOSTIC flag (JOB 2, part 2). When true, while a clash is PENDING or LOCKED the server logs one decisive-state
    // line per second (never per tick) carrying the ball's and the wave's clash role / clashable / firing / locked
    // flags, their sizes and positions, their separation, the dot product of their clash headings, and the overlap
    // threshold DragonMineZ pairs within. It exists to pinpoint WHICH link is broken when a clash announces itself but
    // never locks. Default FALSE so it is off in production; the once-per-failure timeout line (part 3) always prints
    // regardless, so an operator without this flag still gets one explanation.
    private static boolean clashDebug = false;

    // Master switch for making a PLANET clash weigh the attacker's BATTLE POWER instead of ki damage alone. When true,
    // a giant ball or beam fired at a planet contests the world with a blend of its ki damage and the firer's battle
    // power, so a melee / STR build can compete instead of always losing the ki-damage-only struggle. When false, a
    // planet clash uses the raw ki damage exactly as every other clash does, i.e. the pre-battle-power behaviour. This
    // ONLY affects planet clashes; a normal player-vs-player or player-vs-NPC clash is never touched either way.
    private static boolean clashBattlePowerEnabled = true;
    // The blend, computed on the KI-DAMAGE scale so both sides of a planet clash stay comparable (the defender wave's
    // weight IS the planet toughness, already on that scale):
    //   power = clashKiDamageWeight * kiDamage + clashBattlePowerWeight * (battlePower * clashBattlePowerScale)
    // IMPORTANT BALANCE NOTE. DMZ battle power ALREADY includes PWR (it aggregates STR, SKP, RES and PWR at full weight
    // plus VIT and ENE at half), and ki damage is PWR-driven, so counting ki damage at full weight would count PWR
    // TWICE and over-reward a ki build, the opposite of the goal. The default therefore leans HEAVILY on battle power
    // (weight 1.0) and keeps ki damage at a light 0.15, which leaves a ki build a modest edge without a runaway.
    private static double clashKiDamageWeight = 0.15;
    private static double clashBattlePowerWeight = 1.0;
    // Scales raw battle power (roughly 10^5..10^6 for a developed player) down onto the ki-damage / toughness scale
    // (thousands to tens of thousands). At 0.05 a battle power of 200,000 contributes 10,000, on the order of a wild
    // planet's toughness, so a developed player of any build can put up a real fight and a strong player can win.
    private static double clashBattlePowerScale = 0.05;

    // Multiplier on the DEFENDER's clash weight in a planet struggle. Below 1.0 the planet pushes back with less than
    // its full toughness, making the clash minigame easier to win without touching the toughness number itself.
    //
    // This dial moves TWO things inside DragonMineZ, which is why a big cut is needed to feel like a big change:
    //  1. The tug of war. Each side's per-tick push is momentum * (0.6 + 0.95 * itsShareOfTheCombinedStatPower), so
    //     the strength term only ever spans 0.6 to 1.55. Even taking the defender's share to nearly zero buys the
    //     attacker at most about 2.6x, and it is already most of the way there.
    //  2. The defender's AUTO-PRESS accuracy, which is the term that actually decides these struggles.
    //     ClashParticipant derives an NPC's press quality as min(0.92, 0.45 + 0.45 * log10(statPower + 1) / 3), and
    //     that hits the 0.92 ceiling at a statPower of only ~1358. At the old 0.375 a wild planet contested at
    //     5000 * 0.375 = 1875 and a guild floor at 8000 * 0.375 = 3000, so BOTH sat on the ceiling and the planet
    //     pressed near perfectly every sweep no matter what this number said. The curve is base 10 and logarithmic,
    //     so pulling accuracy down at all means dropping the weight by an order of magnitude, not by halves.
    //
    // At 0.05 a wild planet contests at 250 (accuracy ~0.81) and a guild floor at 400 (accuracy ~0.84), and the
    // defender's share of the combined power collapses to a few percent against any real attacker. In practice the
    // press efficiency an attacker needs to win falls from roughly 0.41 to 0.28 for a developed player, and from
    // roughly 0.60 to 0.31 for one barely over the minimum-damage gate. Deliberately NOT pushed to the 0.01 minimum:
    // a total miss on the rhythm meter scores 0.18, and the planet has to stay strong enough that missing every press
    // still loses, or the minigame stops being a minigame.
    private static double clashDefenderWeight = 0.05;

    private static ForgeConfigSpec.BooleanValue cfgEnabled;
    private static ForgeConfigSpec.ConfigValue<List<? extends String>> cfgAllowedKiTypes;
    private static ForgeConfigSpec.IntValue cfgRedRampTicks;
    private static ForgeConfigSpec.IntValue cfgShatterTicks;
    private static ForgeConfigSpec.IntValue cfgAlignmentPenalty;
    private static ForgeConfigSpec.BooleanValue cfgClashEnabled;
    private static ForgeConfigSpec.DoubleValue cfgClashTriggerRadiusMultiplier;
    private static ForgeConfigSpec.DoubleValue cfgClashTriggerMinDistance;
    private static ForgeConfigSpec.DoubleValue cfgClashWildToughness;
    private static ForgeConfigSpec.DoubleValue cfgClashGuildToughnessBase;
    private static ForgeConfigSpec.DoubleValue cfgClashGuildToughnessPerMember;
    private static ForgeConfigSpec.DoubleValue cfgClashGuildBattlePowerDivisor;
    private static ForgeConfigSpec.DoubleValue cfgClashGuildToughnessFloor;
    private static ForgeConfigSpec.DoubleValue cfgMinBlastKiDamage;
    private static ForgeConfigSpec.BooleanValue cfgBeamEnabled;
    private static ForgeConfigSpec.DoubleValue cfgMinBeamKiDamage;
    private static ForgeConfigSpec.BooleanValue cfgClashDebug;
    private static ForgeConfigSpec.BooleanValue cfgClashBattlePowerEnabled;
    private static ForgeConfigSpec.DoubleValue cfgClashKiDamageWeight;
    private static ForgeConfigSpec.DoubleValue cfgClashBattlePowerWeight;
    private static ForgeConfigSpec.DoubleValue cfgClashBattlePowerScale;
    private static ForgeConfigSpec.DoubleValue cfgClashDefenderWeight;

    // How far out from the firing player, in blocks, we scan for the just-launched projectile to recover it. A charged
    // giant ball is spawned when charging STARTS (not at release) and is reused on release, so it is already in the level
    // right next to the player when the fire event posts. 32 blocks comfortably covers the giant ball's spawn offset in
    // front of the player without pulling in a stranger's distant blast (the owner-UUID + fireTick filter would exclude it
    // anyway).
    private static final double RECOVER_SEARCH_RADIUS = 32.0;

    // How many ticks ahead of the projectile's current tick we keep pushing its max life while it is still en route, so a
    // steered blast can never time out (and self-detonate mid-air) before it reaches the target. 40 ticks (2s) is far more
    // than the gap between our per-tick impact checks, yet small enough that once we STOP extending a wedged blast it dies
    // within about 2 seconds on its own.
    private static final int LIFE_MARGIN_TICKS = 40;

    // Hard cap, in server ticks, on how long a single in-flight record may live. A blast that has not reached its target
    // within 30 seconds is wedged (the target moved out from under it, the physics stalled, ...): we drop the record so it
    // can never leak, and stop extending its life so it self-detonates shortly after. 20 ticks/s * 30 s. NOTE: this cap
    // is NOT applied while a clash is locked, so a legitimate long struggle (DMZ clashes can run up to 600 ticks) is
    // never dropped mid-fight.
    // Doubled from thirty seconds. A planet clash is the longest fight in the game and was being cut off by a budget
    // sized for an ordinary exchange.
    private static final int MAX_RECORD_TICKS = 20 * 60;

    // Grace window, in ticks, for DragonMineZ to pair our two beams into a clash after we spawn the defender. The
    // detector runs on the very next level tick, so this is generous; if no lock forms within it we assume the clash
    // failed to engage (an API drift, or geometry that did not overlap), drop the defender, and let the ball bust the
    // planet exactly as it would today.
    // Doubled alongside the record budget: the window DragonMineZ is given to pair the two beams before we give up and
    // fall back to the raw threshold.
    private static final int CLASH_ENGAGE_GRACE_TICKS = 80;

    // The KiBlastEntity render types whose DMZ onKiTick SELF-DESTRUCTS the blast on a 20-tick cadence when its speed
    // drops below 0.1 (getDeltaMovement().lengthSqr() < 0.01): 5 spirit bomb (genki) and 6 supernova. While we hold
    // one of these still (the pending-clash grace window below), we must NOT zero its velocity or it detonates itself
    // before the clash even forms. Every other ball type (2 large blast, 7 death ball) has no such path.
    private static final Set<Integer> SELF_DESTRUCT_RENDER_TYPES = new HashSet<>(Arrays.asList(5, 6));

    // The tiny non-zero speed, in blocks per tick, we hold a self-destruct-type ball at during the pending window. It
    // is above the self-destruct floor of sqrt(0.01) = 0.1 with margin (0.15^2 = 0.0225 >= 0.01); we re-pin the ball to
    // a captured anchor every tick, so this small drift never accumulates and the ball visually holds station. This is
    // the module-side twin of MixinDmzGiantBallClash's locked-phase hold, for the ticks BEFORE the clash locks (while
    // the mixin's isClashLocked() hold is not yet active).
    private static final double SELF_DESTRUCT_SAFE_HOLD_SPEED = 0.15;

    // one-shot latch so a DragonMineZ clash-API drift is logged ONCE, not every tick. A broken clash gate must degrade
    // to the current behaviour (the ball busts the planet) and must never spam the log or crash the server.
    private final java.util.concurrent.atomic.AtomicBoolean clashWarned =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // one-shot latch so a DragonMineZ ki-damage-API drift is logged ONCE, not every shot. Like the clash gate, the
    // minimum-damage gate must FAIL OPEN: if the ki-damage read ever throws we allow the blast through rather than
    // block it, because a broken gate must never make planets indestructible, and it must never spam the log or crash.
    private final java.util.concurrent.atomic.AtomicBoolean minDamageWarned =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // NBT marker written on a firing beam the instant we make a clash decision for it, so a beam whose rendered length
    // grows through a planet over many ticks is handled EXACTLY ONCE and never re-opens a clash every tick. Lives on the
    // beam's ForgeData, which is server-side and dies with the entity, so there is nothing to sweep. Only set for a
    // decision that is final for this beam's life (a clash opened, or a refusal); an out-of-range strike is left unset
    // so the beam can still trigger once the firer flies within range while holding it.
    private static final String BEAM_HANDLED_KEY = "su_planet_beam_handled";

    // Server-side timestamp (game time, in ticks) of the last "beam too weak" notice sent for a given beam, stored on the
    // beam's own ForgeData so it is per-beam and dies with the entity. A too-weak beam is left UNMARKED (see
    // detectBeamStrike) so it can still open a clash once charged, which means the gate re-runs every tick while the beam
    // is held; this timestamp throttles the notice to one per BEAM_WEAK_WARN_INTERVAL_TICKS so the player is told clearly
    // without a per-tick chat flood.
    private static final String BEAM_WEAK_WARN_KEY = "su_planet_beam_weak_warned";
    private static final int BEAM_WEAK_WARN_INTERVAL_TICKS = 40;

    // NBT marker written on a projectile the instant it becomes a tracked PLANET ATTACK (a steered giant ball or a
    // struggling beam). MixinDmzClashParticipant reads it through isPlanetAttack to decide whether a clash weight
    // should be the battle-power blend (a planet attack) or DragonMineZ's raw ki damage (every other clash, including
    // the planet's own defending wave, which is never marked). Lives on the entity's server-side ForgeData, so it dies
    // with the projectile and never needs sweeping, and it is only ever set on a projectile that has already passed
    // every buster gate, so a normal PvP / PvE clash can never carry it. See planetClashPower.
    private static final String PLANET_ATTACK_KEY = "su_planet_attack";

    // minimum rendered beam length, in blocks, before a firing beam is worth raytracing at a planet. Below this the
    // beam has barely left the muzzle and cannot have reached anything.
    private static final double BEAM_MIN_LENGTH = 0.5;

    // in-flight blasts we are steering. All access is on the server thread (both the fire event and the server tick fire
    // there), so a plain list needs no synchronisation. Cleared on server stop so a record can never outlive its world.
    private final List<InFlight> inFlight = new ArrayList<>();

    // beam clashes in progress: a player-fired ki wave has struck a planet and opened a struggle. Parallel to inFlight,
    // ticked on the server thread, cleared on server stop. Unlike a ball there is no steering or impact geometry, the
    // beam is anchored and the planet destroys the moment the beam wins the clash.
    private final List<BeamInFlight> beamInFlight = new ArrayList<>();

    // running DOOM SEQUENCES: a blast has landed and the planet is doomed, but the destroy is deferred to the END of the
    // red ramp so the client can play the ramp first. Ticked alongside inFlight on the server thread, so a plain list is
    // fine. Cleared on server stop. See DoomSequence for why a sequence outlives the firing player's disconnect.
    private final List<DoomSequence> doomSequences = new ArrayList<>();

    // one running doom sequence: the doomed planet snapshot (carries the id/position/radius/tint the shatter packet and
    // the eventual destroy both need), the credited player's id, whether the target was ENTITLEMENT-BACKED (claimed, so
    // requireUnclaimed is false at destroy time), and the tick the sequence began at (impact). The credit is held as a
    // UUID, not a ServerPlayer, ON PURPOSE: the entitlement was already spent at impact, so the planet is doomed even if
    // the firing player logs off during the ramp; at destroy time we look the id up and pass a null credit if they are
    // gone rather than aborting a destruction that has already been paid for.
    private static final class DoomSequence
    {
        final GeneratedPlanets.Generated target;
        final UUID playerId;
        final boolean claimed;
        final long startTick;

        DoomSequence(GeneratedPlanets.Generated target, UUID playerId, boolean claimed, long startTick)
        {
            this.target = target;
            this.playerId = playerId;
            this.claimed = claimed;
            this.startTick = startTick;
        }
    }

    // one steered blast: the recovered projectile, the target planet snapshot (carries the cell key PlanetDestruction
    // needs), the firing player, whether the target was CLAIMED (and therefore entitlement-backed) plus the guild id that
    // holds the entitlement, and the tick the record was created at (for the hard safety cap).
    private static final class InFlight
    {
        final AbstractKiProjectile projectile;
        final GeneratedPlanets.Generated target;
        final UUID playerId;
        final boolean claimed;
        final String guildId;
        final long createdTick;

        // clash-gate bookkeeping, all read and written on the server thread only.
        boolean clashAttempted;   // we have already decided ONCE whether to open a clash for this record
        boolean clashStarted;     // a defender + wave are live for this record
        boolean clashEverLocked;  // the ball was observed clash-locked at least once (the struggle really formed)
        int clashPendingTicks;    // ticks since clashStarted while not yet locked (the DMZ-pairing grace window)
        Vec3 clashHoldAnchor;     // the spot we pin a self-destruct-type ball to during the pending window, or null
        PlanetClash clash;        // the live clash, or null
        boolean camActiveSent;    // we have told the firer's client the planet-clash camera marker is ACTIVE (so an exit must tell it inactive)

        InFlight(AbstractKiProjectile projectile, GeneratedPlanets.Generated target, UUID playerId,
                 boolean claimed, String guildId, long createdTick)
        {
            this.projectile = projectile;
            this.target = target;
            this.playerId = playerId;
            this.claimed = claimed;
            this.guildId = guildId;
            this.createdTick = createdTick;
        }

        // discard this record's defender + wave, if any. Idempotent; safe on every exit path.
        void cleanupClash()
        {
            if (clash != null)
            {
                clash.cleanup();
            }
        }
    }

    // one struggling beam: the firing ki wave, the struck planet snapshot, the firer, whether the target was claimed
    // (entitlement-backed) plus the guild id that holds the entitlement, and the tick the record was created at. The
    // clash bookkeeping mirrors InFlight, minus the steering/hold fields a beam does not need (a wave never travels and
    // its clash heading is fixed, so there is nothing to hold still).
    private static final class BeamInFlight
    {
        final AbstractKiProjectile beam;
        final GeneratedPlanets.Generated target;
        final UUID playerId;
        final boolean claimed;
        final String guildId;
        final long createdTick;

        boolean clashEverLocked;  // the beam was observed clash-locked at least once (the struggle really formed)
        int clashPendingTicks;    // ticks since the clash was opened while not yet locked (the DMZ-pairing grace window)
        PlanetClash clash;        // the live clash, or null

        BeamInFlight(AbstractKiProjectile beam, GeneratedPlanets.Generated target, UUID playerId, boolean claimed,
                     String guildId, long createdTick)
        {
            this.beam = beam;
            this.target = target;
            this.playerId = playerId;
            this.claimed = claimed;
            this.guildId = guildId;
            this.createdTick = createdTick;
        }

        void cleanupClash()
        {
            if (clash != null)
            {
                clash.cleanup();
            }
        }
    }

    // DMZ posts this AFTER the projectile is launched, so the blast already exists in the level with isFiring() == true.
    // The gates run cheapest-first and bail silently unless a refusal message is noted, so an ordinary ki blast in space
    // costs almost nothing.
    @SubscribeEvent
    public void onKiAttackFire(DMZEvent.KiAttackFireEvent event)
    {
        if (!enabled)
        {
            return;
        }
        // gate 1: a real server player, actually in the space dimension. Cheapest possible check, and everything below
        // needs a ServerPlayer/ServerLevel, so it goes first.
        if (!(event.getPlayer() instanceof ServerPlayer player) || !SpaceDimension.isSpace(player.level()))
        {
            return;
        }
        // gate 2: the fired ki type is on the allowlist (GIANT_BALL by default). Compared by enum name so the config can
        // add future destruction techniques without a code change.
        KiAttackData.KiType firedType = event.getKiAttack().getKiType();
        if (firedType == null || !allowedKiTypeNames.contains(firedType.name()))
        {
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null)
        {
            return;
        }

        // gate 3: raytrace from the eye along the look vector for the first bustable body the shot would enter, generated
        // planet OR moon. Resolved through the SHARED PlanetInfoTarget.resolve, the one nearest-hit rule the planet-info
        // readout uses too, so "what my crosshair reads" and "what my shot busts" can never diverge. Capped at the legal
        // name-range plus one max body radius so we never scan further than a player could legally shoot (a body whose
        // centre sits just past the range can still present a near face inside it; the exact rule below decides). A moon
        // hit is adapted into the Generated shape the whole in-flight/doom pipeline consumes; a generated hit is the live
        // Generated the same walk already returns.
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double maxRay = SpaceLayout.LABEL_DISTANCE + GeneratedPlanets.maxBodyRadius();
        long gameTime = server.overworld().getGameTime();
        PlanetInfoTarget.Hit hit = PlanetInfoTarget.resolve(server, eye, look, maxRay, gameTime);
        Vec3 rayDir = look.lengthSqr() < 1.0E-9 ? null : look.normalize();
        GeneratedPlanets.Generated target = null;
        double targetEntry = Double.MAX_VALUE;
        if (hit != null && rayDir != null)
        {
            if (MoonBody.isMoon(hit.id))
            {
                target = GeneratedPlanets.forMoon(hit.id, hit.position, hit.radius, hit.surfaceSize);
            }
            else
            {
                target = GeneratedPlanets.bodyAlongRay(server, eye, look, maxRay);
            }
            if (target != null)
            {
                targetEntry = GeneratedPlanets.rayCubeEntry(eye, rayDir, target.position, target.radius, maxRay);
            }
        }
        // a SYSTEM SUN on the same ray, nearer than any planet/moon, wins: a sun is bustable too (harder, and it takes
        // the whole system with it). The star is adapted into the same Generated shape so the entire in-flight / doom /
        // destroy pipeline treats it uniformly; the destroy step detects the star id and cascades to its planets.
        if (rayDir != null)
        {
            GeneratedPlanets.Generated star = starAlongRay(server, eye, rayDir, maxRay);
            if (star != null)
            {
                double starEntry = GeneratedPlanets.rayCubeEntry(eye, rayDir, star.position, star.radius, maxRay);
                if (starEntry >= 0.0 && starEntry < targetEntry)
                {
                    target = star;
                }
            }
        }
        if (target == null)
        {
            // shot into empty space (or at a fixed body, which this feature never busts): nothing to do, silently.
            return;
        }

        // gate 4: the exact range rule, the SAME surface-distance form the renderer's label gate uses, so "its name is
        // showing" and "I can destroy it" agree exactly. Uses the same radius the client draws with (target.radius).
        double surfaceDistance = eye.distanceTo(target.position) - target.radius;
        if (surfaceDistance > SpaceLayout.LABEL_DISTANCE)
        {
            refuse(player, "planet_buster_out_of_range");
            return;
        }

        // gate 5: destructible. A fixed body is refused by the shared predicate; a generated planet or a moon both pass (a
        // ray hit here is one of those two, so this normally passes, but route the decision through the same gate too).
        if (!GeneratedPlanets.isDestructible(target.id))
        {
            refuse(player, "planet_buster_not_destructible");
            return;
        }

        // gate 6: already destroyed. A destroyed planet is not even derived by the ray (generatedFor suppresses it), so
        // this is defensive belt-and-braces against a race, refused with its own message.
        GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);
        if (claims.isDestroyed(target.id))
        {
            refuse(player, "planet_buster_already_destroyed");
            return;
        }

        // gate 7a: a PERSONALLY-claimed planet is guarded by its owner's AVATAR, not the raid entitlement. It can be
        // destroyed only after a challenger beats the avatar, and only during the explodable window (or if the feature is
        // off / has no snapshot). PlanetOwnerAvatar.explodableNow fails OPEN, so a broken gate never makes it undestroyable.
        if (claims.isPersonallyClaimed(target.id) && !PlanetOwnerAvatar.explodableNow(server, target.id))
        {
            refuse(player, "planet_buster_avatar_guarding");
            return;
        }

        // gate 7: the guild claim policy. Unclaimed proceeds. Guild-claimed proceeds ONLY if the firing player's guild
        // holds an unspent entitlement for this exact planet; the entitlement is spent at impact, not here.
        String owningGuildId = claims.owner(target.id);
        boolean claimed = owningGuildId != null;
        Guild playerGuild = GuildManager.guildOf(player.getUUID());
        String playerGuildId = playerGuild == null ? null : playerGuild.id;
        if (claimed)
        {
            if (playerGuildId == null || !GuildRaidSpoils.get(server).has(playerGuildId, target.id))
            {
                refuse(player, "planet_buster_claimed");
                return;
            }
        }

        // all gates passed: recover the just-fired projectile and start steering it. If we cannot find it (a race, or DMZ
        // changed how the blast is spawned) we do NOTHING rather than destroy a planet with no visible blast reaching it.
        AbstractKiProjectile projectile = recoverProjectile(player, firedType);
        if (projectile == null)
        {
            LoggingHandler.sulog.debug(
                    "[PlanetBuster] Could not recover the fired {} projectile for {}; not tracking a bust.",
                    firedType.name(), player.getGameProfile().getName());
            return;
        }

        // gate 8: the MINIMUM-DAMAGE gate. This is checked HERE, at fire time, the earliest point the projectile
        // exists, and NOT after a clash: a blast that is simply too weak to bust a world is told so immediately, so a
        // player is never made to fight an entire clash first only to be refused at the end. It is independent of the
        // clash (which asks whether the blast out-powers the toughness); this asks whether the blast is a world-ender
        // at all. A blast under the floor detonates like any real ki blast (so the player still sees their shot go off)
        // and is never tracked, so it can never destroy the planet. The read fails OPEN (see blastMeetsMinimumDamage).
        if (!blastMeetsMinimumDamage(projectile))
        {
            refuse(player, "planet_buster_too_weak");
            detonate(projectile);
            return;
        }

        // mark the projectile a tracked planet attack so MixinDmzClashParticipant blends the firer's battle power into
        // this shot's clash weight (and only this shot's), leaving every other clash on DragonMineZ's raw ki damage.
        projectile.getPersistentData().putBoolean(PLANET_ATTACK_KEY, true);

        inFlight.add(new InFlight(projectile, target, player.getUUID(), claimed, playerGuildId,
                server.overworld().getGameTime()));
    }

    // recover the projectile DMZ just launched for this player: an already-firing KiBlastEntity this player owns, of the
    // fired type, whose fireTick equals its own tickCount (fireHability stamps fireTick = tickCount at launch, so this
    // picks out the ONE just fired this tick and excludes an older blast from the same player still flying from before).
    // Matched by enum NAME so a future allowed technique of the same projectile class is recovered too, not just GIANT_BALL.
    // getKiType() is RELIABLE for a fired attack here: TechniqueDispatcher.executeKiAttack stamps setKiType(...) AFTER the
    // per-technique setup method runs, so a launched giant ball (and a spirit bomb, which is a GIANT_BALL ki type built at
    // render type 5) reports getKiType() == GIANT_BALL. That is why this NAME match, and the allowlist above, already pick
    // up the spirit bomb without needing to inspect the render type.
    // the nearest SYSTEM SUN the ray enters, adapted into the Generated shape, or null. Walks the system stars near the
    // eye (widened by the max star radius so a star whose centre sits just past the reach can still present a near face)
    // and picks the nearest positive entry, the same slab test bodyAlongRay uses for planets, so a sun and a planet on
    // the same ray are compared by identical maths. Pure, so it runs server-side here and could run client-side too.
    private static GeneratedPlanets.Generated starAlongRay(MinecraftServer server, Vec3 eye, Vec3 dir, double maxDistance)
    {
        GeneratedPlanets.Generated best = null;
        double bestEntry = Double.MAX_VALUE;
        for (StarPositions.Star s : StarPositions.starsNear(server, eye, maxDistance + StarPositions.MAX_RADIUS))
        {
            double entry = GeneratedPlanets.rayCubeEntry(eye, dir, s.position, s.radius, maxDistance);
            if (entry >= 0.0 && entry < bestEntry)
            {
                bestEntry = entry;
                best = GeneratedPlanets.forStar(s.key, s.position, s.radius, s.tint);
            }
        }
        return best;
    }

    private static AbstractKiProjectile recoverProjectile(ServerPlayer player, KiAttackData.KiType firedType)
    {
        ServerLevel level = player.serverLevel();
        AABB box = player.getBoundingBox().inflate(RECOVER_SEARCH_RADIUS);
        String firedName = firedType.name();
        List<AbstractKiProjectile> matches = level.getEntitiesOfClass(AbstractKiProjectile.class, box, p ->
                p instanceof KiBlastEntity
                        && p.isFiring()
                        && player.getUUID().equals(p.getOwnerUUID())
                        && p.getKiType() != null
                        && p.getKiType().name().equals(firedName)
                        && p.getFireTick() == p.tickCount);
        return matches.isEmpty() ? null : matches.get(0);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || !enabled
                || (inFlight.isEmpty() && beamInFlight.isEmpty() && doomSequences.isEmpty() && !beamEnabled))
        {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
        {
            // no server (shutting down): discard any live clash holders, then drop everything so a record can never
            // reference a dead world.
            for (InFlight record : inFlight)
            {
                record.cleanupClash();
            }
            for (BeamInFlight record : beamInFlight)
            {
                record.cleanupClash();
            }
            inFlight.clear();
            beamInFlight.clear();
            doomSequences.clear();
            return;
        }
        long now = server.overworld().getGameTime();
        GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);

        // BEAM DETECTION: find any player-fired ki wave that has grown into a planet and open a clash for it. Runs
        // before the beam step so a strike detected this tick is stepped next tick. Cheap: it only looks at the small
        // box around each space player, where a wave entity is always anchored.
        if (beamEnabled)
        {
            scanBeams(server, claims, now);
        }

        for (Iterator<InFlight> it = inFlight.iterator(); it.hasNext(); )
        {
            InFlight record = it.next();
            if (!stepRecord(server, claims, record, now))
            {
                // a finished record: on EVERY drop path (impact, clash loss, timeout, logoff, target destroyed) make
                // sure the firer's client is told the planet-clash camera marker is inactive, then clean up the
                // defender + wave so no invisible holder is ever orphaned in the space dimension. Both idempotent.
                if (record.camActiveSent)
                {
                    sendClashCam(server, record, false);
                }
                record.cleanupClash();
                it.remove();
            }
        }

        // advance the beam struggles: each resolves the moment its beam wins or loses the clash.
        for (Iterator<BeamInFlight> it = beamInFlight.iterator(); it.hasNext(); )
        {
            BeamInFlight record = it.next();
            if (!beamStep(server, claims, record, now))
            {
                record.cleanupClash();
                it.remove();
            }
        }

        // advance the doom sequences: each destroys its planet once its red ramp has elapsed.
        for (Iterator<DoomSequence> it = doomSequences.iterator(); it.hasNext(); )
        {
            DoomSequence seq = it.next();
            if (!stepDoom(server, seq, now))
            {
                it.remove();
            }
        }
    }

    // advance one in-flight blast by a tick. Returns false when the record is finished (impact fired, or dropped) so the
    // caller removes it. A dropped record never destroys anything; only a genuine impact does.
    private boolean stepRecord(MinecraftServer server, GeneratedPlanetClaims claims, InFlight record, long now)
    {
        AbstractKiProjectile projectile = record.projectile;
        // drop conditions: the blast is gone, the firing player has left, the target was destroyed by something else, or
        // the record has outlived its hard safety cap. In every case we simply stop steering; a still-living projectile
        // self-detonates on its own timeout once we stop extending its life.
        if (projectile.isRemoved())
        {
            // the ball is gone. If it was mid-clash, DragonMineZ killed it as the LOSER, so the planet WON its struggle:
            // tell the firer. Nothing is destroyed on this path, because the impact test never ran. A non-clash removal
            // (a wedged shot that timed out) says nothing, exactly as before.
            if (record.clashEverLocked)
            {
                ServerPlayer loser = server.getPlayerList().getPlayer(record.playerId);
                if (loser != null)
                {
                    refuse(loser, "planet_buster_clash_lost");
                }
            }
            return false;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(record.playerId);
        if (player == null)
        {
            return false;
        }
        if (claims.isDestroyed(record.target.id))
        {
            return false;
        }

        Vec3 centre = record.target.position;
        Vec3 pos = projectile.position();
        boolean locked = safeIsClashLocked(projectile);

        // hard safety cap, but NOT while a real clash is locked: a legitimate struggle can run up to 600 DMZ ticks and
        // must not be dropped mid-fight (which would dissolve it and hand the ball a free win).
        if (!locked && now - record.createdTick > MAX_RECORD_TICKS)
        {
            return false;
        }

        // clash gate: the first time the ball comes within the trigger distance of the planet centre, open a clash
        // ONCE. If the clash cannot be opened (disabled, or a DMZ API drift), we simply never set clashStarted and the
        // ball busts the planet as it does today.
        if (clashEnabled && !record.clashAttempted)
        {
            double trigger = clashTriggerDistance(record.target.radius);
            if (pos.distanceToSqr(centre) <= trigger * trigger)
            {
                record.clashAttempted = true;
                beginClash(server, player, record, centre);
            }
        }

        if (record.clashStarted)
        {
            // DIAGNOSTIC (JOB 2, part 2): while the two beams are live (pending OR locked), emit ONE decisive-state line
            // per second when clashDebug is on. Gated on the game time so it is at most once per 20 ticks even across
            // several in-flight records, never a per-tick flood. Every field inside clashSummary is individually
            // guarded, and the whole call is wrapped, so a diagnostic can never wedge or crash the steering loop.
            if (clashDebug && record.clash != null && now % 20L == 0L)
            {
                try
                {
                    LoggingHandler.sulog.info("[PlanetBuster] clash debug {}", clashSummary(record, locked));
                }
                catch (Throwable ignored)
                {
                    // diagnostics must never break the tick; drop this line silently and carry on.
                }
            }
            if (locked)
            {
                // struggle IN PROGRESS: stop steering and stop the impact test entirely (steering would fight the
                // giant-ball freeze mixin, and an impact must not fire while the shot is being contested). Keep BOTH
                // beams alive so neither times out and dissolves the clash by default.
                record.clashEverLocked = true;
                projectile.setMaxLife(projectile.tickCount + LIFE_MARGIN_TICKS);
                record.clash.keepAlive();
                // mark the firer's client so it reframes DMZ's clash camera onto the clash point. Sent on the first
                // locked tick and refreshed once a second (so a mid-clash relog recovers the marker within a second),
                // never every tick.
                if (!record.camActiveSent || now % 20L == 0L)
                {
                    sendClashCam(server, record, true);
                }
                return true;
            }
            if (!record.clashEverLocked)
            {
                // spawned but not yet paired. Hold the ball still for a short grace window while DragonMineZ pairs the
                // two beams; if it never locks, assume the clash failed to engage, drop the defender, and fall through
                // to ordinary steering so the planet is bustable exactly as today.
                record.clashPendingTicks++;
                if (record.clashPendingTicks <= CLASH_ENGAGE_GRACE_TICKS)
                {
                    // Hold the ball where it is while DMZ pairs the two beams. We must NOT simply zero the velocity: a
                    // spirit bomb (5) or supernova (6) self-destructs within 20 ticks once its speed drops below 0.1,
                    // so a zeroed genki would blow itself up before the clash ever locked. holdStill pins those types
                    // to an anchor with a small non-zero velocity instead, and zeroes only the safe types.
                    holdStill(record, centre);
                    projectile.setMaxLife(projectile.tickCount + LIFE_MARGIN_TICKS);
                    record.clash.keepAlive();
                    return true;
                }
                // DIAGNOSTIC (JOB 2, part 3): the grace window elapsed without DragonMineZ ever locking the two beams.
                // Log ONE INFO line, unconditionally (independent of clashDebug), so even an operator who never turned on
                // the debug flag gets a single explanation for "the planet said it was defending itself and then nothing
                // happened". Sample the summary BEFORE cleanupClash() discards the wave, so the wave-side fields are real
                // rather than "?" for an already-removed entity. Wrapped so the log can never stop the cleanup below.
                try
                {
                    LoggingHandler.sulog.info(
                            "[PlanetBuster] clash failed to engage within {} ticks; the ball busts the planet with no "
                                    + "struggle. {}", CLASH_ENGAGE_GRACE_TICKS, clashSummary(record, false));
                }
                catch (Throwable ignored)
                {
                    // never let the explanation line stop the cleanup that follows.
                }
                record.cleanupClash();
                record.clashStarted = false;
                record.clash = null;
                record.clashHoldAnchor = null;
            }
            else
            {
                // was locked, now unlocked, and the ball is still alive (a removed loser was handled at the top). The
                // ball WON: DragonMineZ cleared its lock and gave it a 60-tick breakthrough. Drop the now dead-loser
                // wave and the holder, and resume ordinary steering so the existing impact path destroys the planet.
                // Ordinary steering below re-extends the ball's life every tick, so the 60-tick breakthrough window is
                // never the limiting factor even for a slow 0.5-speed spirit bomb that could not cross the remaining
                // distance within 60 ticks.
                record.cleanupClash();
                record.clashStarted = false;
                record.clash = null;
                record.clashHoldAnchor = null;
                // the struggle is over (this ball won): drop the client camera marker so the reframe ends with the clash.
                if (record.camActiveSent)
                {
                    sendClashCam(server, record, false);
                }
            }
        }

        // impact test: the blast is inside the target's body cube. We use the body's TRUE half-extent (radius, no landing
        // margin): the player-landing check adds a skin to catch a fast player who might overshoot in a single tick, but a
        // blast is re-steered dead at the centre every tick, so it enters the actual drawn cube rather than needing that
        // skin. The willReach guard covers a blast whose per-tick step exceeds the body, so a very fast shot cannot tunnel
        // clean through the centre between checks without registering the hit.
        double half = record.target.radius;
        double dx = pos.x - centre.x;
        double dy = pos.y - centre.y;
        double dz = pos.z - centre.z;
        boolean insideCube = Math.abs(dx) <= half && Math.abs(dy) <= half && Math.abs(dz) <= half;
        double step = projectile.getKiSpeed();
        boolean willReach = pos.distanceToSqr(centre) <= step * step;
        if (insideCube || willReach)
        {
            impact(server, player, record);
            return false;
        }

        // still en route: steer dead at the centre and keep it alive. Movement is plain setDeltaMovement integration in
        // AbstractKiProjectile.tick, and DMZ's homing early-returns for GIANT_BALL (canHome() is false), so this delta is
        // not overwritten. Life is extended every tick so the blast cannot time out before it arrives.
        Vec3 heading = centre.subtract(pos);
        if (heading.lengthSqr() > 1.0E-9)
        {
            projectile.setDeltaMovement(heading.normalize().scale(step));
        }
        projectile.setMaxLife(projectile.tickCount + LIFE_MARGIN_TICKS);
        return true;
    }

    // open a clash for this record: resolve the planet's toughness, spawn the invisible defender and fire its wave at
    // the ball, and tell the firer the world is defending itself. Wrapped so ANY Throwable (a DMZ clash-API drift, a
    // spawn failure) is caught, logged ONCE, and degrades to the ordinary bust: the ball simply proceeds and destroys
    // the planet as it does today, never crashing and never spamming the log.
    private void beginClash(MinecraftServer server, ServerPlayer player, InFlight record, Vec3 centre)
    {
        try
        {
            ServerLevel space = (ServerLevel) record.projectile.level();
            double toughness = PlanetToughness.resolve(server, record.target.id);
            logClashPower("ball", player, record.projectile, toughness);
            record.clash = PlanetClash.begin(space, record.projectile, centre, defenderClashWeight(toughness));
            record.clashStarted = true;
            record.clashPendingTicks = 0;
            refuse(player, "planet_buster_clash_started");
        }
        catch (Throwable t)
        {
            record.clashStarted = false;
            record.clash = null;
            if (clashWarned.compareAndSet(false, true))
            {
                LoggingHandler.sulog.warn("[PlanetBuster] Could not open the planet clash "
                        + "(DragonMineZ clash API drift?); the ball busts the planet without a struggle.", t);
            }
        }
    }

    // tell the firing player's client whether the planet clash it is watching is one of ours, so its camera reframes onto
    // the clash point (see PlanetClashCamera). The anchor is the ball's own entity id, whose live client position IS the
    // struggle; the planet centre/radius let the client place the ball in the foreground and the planet behind it. The
    // "active" mark is refreshed once a second while locked; the matching "inactive" is sent on every exit. It is only
    // belt-and-braces: DMZ ends its own clash cinematic when the struggle stops, which already halts the client override,
    // so a missed inactive can never strand the camera. Wrapped so a marker send can never wedge the steering loop.
    private void sendClashCam(MinecraftServer server, InFlight record, boolean active)
    {
        try
        {
            ServerPlayer player = server.getPlayerList().getPlayer(record.playerId);
            if (player == null)
            {
                // no one to tell (they logged off): nothing to clear later either.
                record.camActiveSent = false;
                return;
            }
            if (active)
            {
                NetworkUtils.sendTo(PacketPlanetClashCam.active(
                        record.projectile.getId(), record.target.position, record.target.radius), player);
                record.camActiveSent = true;
            }
            else
            {
                NetworkUtils.sendTo(PacketPlanetClashCam.inactive(), player);
                record.camActiveSent = false;
            }
        }
        catch (Throwable ignored)
        {
            // the camera marker is cosmetic; never let a send failure break the clash tick.
        }
    }

    // read the ball's clash-lock flag, degrading to "not locked" on any DMZ API drift so a broken clash gate can never
    // wedge the steering loop.
    private static boolean safeIsClashLocked(AbstractKiProjectile projectile)
    {
        try
        {
            return projectile.isClashLocked();
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    // true when the blast is strong enough to be ALLOWED to destroy a planet, per the minimum-damage gate. Two ways it
    // returns true without blocking: the gate is disabled (threshold <= 0), or the DMZ ki-damage read throws (a version
    // drift), in which case we FAIL OPEN and log once. Failing open matters: a broken read must never make planets
    // indestructible. Reads getKiDamage() through the same public AbstractKiProjectile API PlanetClash uses on its
    // wave, so the number compared here is the exact one DragonMineZ would turn into the blast's own clash weight.
    private boolean blastMeetsMinimumDamage(AbstractKiProjectile projectile)
    {
        if (minBlastKiDamage <= 0.0)
        {
            return true;   // gate disabled: never blocks.
        }
        try
        {
            return projectile.getKiDamage() >= minBlastKiDamage;
        }
        catch (Throwable t)
        {
            if (minDamageWarned.compareAndSet(false, true))
            {
                LoggingHandler.sulog.warn("[PlanetBuster] Could not read the blast's ki damage "
                        + "(DragonMineZ ki API drift?); the minimum-damage gate is skipped and the blast is allowed.", t);
            }
            return true;   // fail open: allow the blast rather than ever risk making a planet indestructible.
        }
    }

    // hold a pending-clash ball still WITHOUT tripping DMZ's type 5/6 low-speed self-destruct. For a self-destruct type
    // (spirit bomb, supernova) we cannot zero the velocity, so we pin the ball to a captured anchor and leave a small
    // non-zero velocity along the ball's OWN preserved clash heading; the anchor is re-applied every tick, so the small
    // drift never accumulates and the ball holds station while its speed stays above the self-destruct floor. This runs
    // at server tick END, after the entity already moved this tick, so the re-pin lands the ball back on the anchor. For
    // a non-self-destruct type (large blast, death ball) the old zero-velocity hold is safe and needs no anchor.
    //
    // WHY the hold velocity follows the clash heading and NOT the planet centre. A ball's getClashYaw()/getClashPitch()
    // ARE its live entity rotation, and AbstractKiProjectile.tick rewrites that rotation toward its delta every tick via
    // ProjectileUtil.rotateTowardsMovement (which only acts on a non-zero delta, so a self-destruct ball's mandatory
    // non-zero hold velocity always triggers it). The defending wave was fixed against the ball's clash heading AT
    // TRIGGER TIME, so DMZ only pairs the two while that heading holds opposed (dot < -0.3). Driving the delta at the
    // planet centre made the live rotation, and therefore the ball's clash heading, drift off the fired heading; up
    // close against a large body the parallax is large, the dot climbed past -0.3, and the pair never formed. Driving
    // the delta along the ball's current clash heading rotates it toward where it already points (a no-op), so the
    // heading stays put and stays opposed to the wave. This matches MixinDmzGiantBallClash's locked-phase hold exactly,
    // so the pre-lock and post-lock holds no longer disagree. The per-tick setPos re-pin above keeps the position on the
    // anchor regardless of this direction, so there is no net drift either way.
    private static void holdStill(InFlight record, Vec3 centre)
    {
        AbstractKiProjectile projectile = record.projectile;
        int renderType = safeRenderType(projectile);
        if (!SELF_DESTRUCT_RENDER_TYPES.contains(renderType))
        {
            projectile.setDeltaMovement(Vec3.ZERO);
            return;
        }
        if (record.clashHoldAnchor == null)
        {
            record.clashHoldAnchor = projectile.position();
        }
        Vec3 anchor = record.clashHoldAnchor;
        projectile.setPos(anchor.x, anchor.y, anchor.z);
        Vec3 heading = Vec3.directionFromRotation(projectile.getClashPitch(), projectile.getClashYaw());
        if (heading.lengthSqr() < 1.0E-9)
        {
            // degenerate stored heading: fall back to the centre axis, then a fixed axis, purely so the ball keeps a
            // non-zero speed and does not self-destruct. directionFromRotation never actually returns zero length, so
            // this is defensive only.
            heading = centre.subtract(anchor);
            if (heading.lengthSqr() < 1.0E-9)
            {
                heading = new Vec3(0.0, 0.0, 1.0);
            }
        }
        projectile.setDeltaMovement(heading.normalize().scale(SELF_DESTRUCT_SAFE_HOLD_SPEED));
    }

    // read the ball's render type, degrading to -1 (which is in no promoted/self-destruct set) on any DMZ API drift so
    // the hold falls back to the plain zero-velocity path rather than throwing in the tick loop.
    private static int safeRenderType(AbstractKiProjectile projectile)
    {
        try
        {
            return projectile.getKiRenderType();
        }
        catch (Throwable ignored)
        {
            return -1;
        }
    }

    // the clash-trigger distance from the planet centre: comfortably outside the body so the struggle happens in open
    // space in front of the planet, derived relative to the body radius so it scales for small and huge planets alike.
    private static double clashTriggerDistance(double radius)
    {
        return Math.max(clashTriggerMinDistance, radius * clashTriggerRadiusMultiplier);
    }

    // scan every space player's immediate surroundings for a firing ki wave they own whose rendered length has grown
    // into a planet, and open a clash for the first such strike. A wave entity is anchored at the firer's eye, so a
    // small box around the player always contains it; the beam's REACH is its rendered length, raytraced in
    // detectBeamStrike. The defending wave is owned by the invisible holder, not a ServerPlayer, so the owner filter
    // excludes it; a beam already decided on carries the BEAM_HANDLED_KEY marker and is skipped.
    private void scanBeams(MinecraftServer server, GeneratedPlanetClaims claims, long now)
    {
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            if (!SpaceDimension.isSpace(player.level()))
            {
                continue;
            }
            ServerLevel level = player.serverLevel();
            AABB box = player.getBoundingBox().inflate(RECOVER_SEARCH_RADIUS);
            List<KiWaveEntity> waves = level.getEntitiesOfClass(KiWaveEntity.class, box, w ->
                    w.isClashableBeam()
                            && player.getUUID().equals(w.getOwnerUUID())
                            && !w.getPersistentData().getBoolean(BEAM_HANDLED_KEY));
            for (KiWaveEntity wave : waves)
            {
                if (detectBeamStrike(server, claims, player, wave, now))
                {
                    // one clash opened per player per tick is plenty (a player is not firing several world-enders at
                    // once), and it keeps a single tick bounded.
                    break;
                }
            }
        }
    }

    // decide what a single firing beam does at a planet. Returns true only when it OPENED a clash (added a record), so
    // the caller can stop scanning that player's beams this tick. Every gate mirrors the ball fire path; the geometry is
    // a segment raytrace instead of an in-flight impact, because a wave never travels.
    // Why a given beam did or did not open a clash, logged once per beam per CHANGE of outcome.
    //
    // Every rejection in detectBeamStrike used to be a bare `return false`, so a beam that was never tracked and a
    // beam that was tracked and failed to pair were indistinguishable from outside: in both cases nothing happened and
    // nothing was said. Keyed on the beam's entity id and on the reason, so a beam that is briefly too short and then
    // grows into a planet prints "short" once and then "opened", rather than either flooding or staying silent.
    private static final java.util.Map<Integer, String> BEAM_TRACE = new java.util.HashMap<>();

    private static void beamTrace(AbstractKiProjectile beam, String reason)
    {
        beamTrace(beam, null, reason);
    }

    /**
     * As above. {@code firer} is kept for the call sites and is no longer used for output.
     *
     * <p>This used to put the same sentence in the firing operator's chat, on the reasoning that someone testing why
     * their beam does nothing is standing in the game rather than reading latest.log. In practice a planet shot walks
     * through these gates many times as the beam grows, and even latched per beam per reason it filled the screen
     * with dark aqua machinery during ordinary play and on recordings. The log keeps every line; chat keeps none.
     */
    private static void beamTrace(AbstractKiProjectile beam, ServerPlayer firer, String reason)
    {
        try
        {
            // Diagnostic only, and still behind the debug flag so an ordinary shot writes nothing at all.
            if (!clashDebug)
            {
                return;
            }
            int id = beam.getId();
            String previous = BEAM_TRACE.get(id);
            if (reason.equals(previous))
            {
                return;
            }
            BEAM_TRACE.put(id, reason);
            // Bounded: space beams are rare, but a long session must not accumulate ids for entities long gone.
            if (BEAM_TRACE.size() > 256)
            {
                BEAM_TRACE.clear();
            }
            LoggingHandler.sulog.info("[PlanetBuster] beam {} -> {}", id, reason);
        }
        catch (Throwable ignored)
        {
        }
    }

    private boolean detectBeamStrike(MinecraftServer server, GeneratedPlanetClaims claims, ServerPlayer player,
                                     AbstractKiProjectile beam, long now)
    {
        Vec3 origin = beam.position();
        Vec3 dir = safeBeamHeading(beam);
        if (dir == null)
        {
            return false;
        }
        double len = safeBeamLength(beam);
        if (len < BEAM_MIN_LENGTH)
        {
            beamTrace(beam, player, "too short to reach anything (len " + String.format(java.util.Locale.ROOT, "%.1f", len)
                    + " < " + BEAM_MIN_LENGTH + ")");
            return false;   // barely fired; nothing can be in reach yet. Re-checked next tick as the beam grows.
        }
        // the first bustable body (generated planet OR moon) the beam SEGMENT (origin .. origin + dir*len) enters, through
        // the SAME shared PlanetInfoTarget.resolve the ball path uses, so both weapons agree on the nearest hit and a moon
        // is reachable by a beam too. resolve suppresses destroyed and fixed bodies and returns the nearest entry within
        // the given reach, so a hit is a live, destructible body the beam actually reaches, not merely one it is pointed
        // at. now is the current game time, which places the orbiting moon's cube where it is drawn this tick.
        PlanetInfoTarget.Hit hit = PlanetInfoTarget.resolve(server, origin, dir, len, now);
        if (hit == null)
        {
            beamTrace(beam, player, "no bustable body along the beam (len "
                    + String.format(java.util.Locale.ROOT, "%.1f", len) + ")");
            return false;   // the beam is not yet long enough to touch a body, or is aimed at empty space.
        }
        GeneratedPlanets.Generated target;
        if (MoonBody.isMoon(hit.id))
        {
            target = GeneratedPlanets.forMoon(hit.id, hit.position, hit.radius, hit.surfaceSize);
        }
        else
        {
            target = GeneratedPlanets.bodyAlongRay(server, origin, dir, len);
            if (target == null)
            {
                return false;   // a generated body was the nearest hit but is no longer derivable this instant.
            }
        }

        // the range rule, the SAME surface-distance form the ball path and the renderer's label gate use, measured from
        // the firer's eye (the beam origin). A very long held beam must still not reach past the label range. Left
        // UNMARKED on failure so the beam can still trigger once the firer flies within range while holding it.
        double surfaceDistance = origin.distanceTo(target.position) - target.radius;
        if (surfaceDistance > SpaceLayout.LABEL_DISTANCE)
        {
            return false;
        }

        // the minimum-damage gate runs BEFORE the handled stamp, and deliberately so. A beam merely under the floor is
        // NOT a final decision: the firer can charge it up while STILL holding the same beam entity. The old ordering
        // stamped the handled flag above this gate, so an under-powered beam was frozen out forever and could never open
        // a clash even after its owner powered up mid-hold. Leaving it unmarked lets the very same beam qualify the
        // instant it crosses the floor; the warn is throttled (see warnBeamTooWeak) so re-checking every tick as the beam
        // grows does not spam chat.
        if (!beamMeetsMinimumDamage(beam))
        {
            beamTrace(beam, player, "below the minimum ki damage a planet attack needs");
            warnBeamTooWeak(player, beam, now);
            return false;
        }

        // from here every outcome is FINAL for this beam: mark it handled so a still-growing beam does not re-decide
        // every tick and cannot open a second clash. The remaining refusals below (not destructible, already destroyed,
        // claimed) are stable for the beam's brief life, so marking handled ahead of them is exactly the re-trigger
        // suppression this flag exists for.
        beam.getPersistentData().putBoolean(BEAM_HANDLED_KEY, true);

        if (!GeneratedPlanets.isDestructible(target.id))
        {
            refuse(player, "planet_buster_not_destructible");
            return false;
        }
        if (claims.isDestroyed(target.id))
        {
            refuse(player, "planet_buster_already_destroyed");
            return false;
        }
        // a PERSONALLY-claimed planet is guarded by its owner's avatar, not the raid entitlement (same rule as the blast
        // gate). Refuse the beam while the avatar guards it; explodableNow fails open so a broken gate never locks a planet.
        if (claims.isPersonallyClaimed(target.id) && !PlanetOwnerAvatar.explodableNow(server, target.id))
        {
            refuse(player, "planet_buster_avatar_guarding");
            return false;
        }
        String owningGuildId = claims.owner(target.id);
        boolean claimed = owningGuildId != null;
        Guild playerGuild = GuildManager.guildOf(player.getUUID());
        String playerGuildId = playerGuild == null ? null : playerGuild.id;
        if (claimed && (playerGuildId == null || !GuildRaidSpoils.get(server).has(playerGuildId, target.id)))
        {
            refuse(player, "planet_buster_claimed");
            return false;
        }

        double toughness = PlanetToughness.resolve(server, target.id);

        // clash gate OFF: fall straight to the raw threshold (bust only if the beam out-powers the toughness), with no
        // struggle. This mirrors how a clash-disabled ball busts outright, but keeps a beam honouring the threshold
        // model the user locked in rather than becoming a free world-ender.
        if (!clashEnabled)
        {
            resolveBeamByThreshold(server, player, target, claimed, playerGuildId, beam, toughness);
            return false;
        }

        // the defender anchor: the point on the beam closest to the planet centre, clamped to the beam, which sits near
        // the struck face. Passing this as the base position makes the answering wave form at the target rather than
        // back at the firer's eye where the wave entity actually sits.
        double t = Mth.clamp((target.position.subtract(origin)).dot(dir), 0.0, len);
        Vec3 anchor = origin.add(dir.scale(t));

        PlanetClash clash;
        try
        {
            clash = PlanetClash.begin(player.serverLevel(), beam, target.position, defenderClashWeight(toughness),
                    anchor);
        }
        catch (Throwable ex)
        {
            if (clashWarned.compareAndSet(false, true))
            {
                LoggingHandler.sulog.warn("[PlanetBuster] Could not open the planet clash for a beam "
                        + "(DragonMineZ clash API drift?); falling back to the raw damage threshold.", ex);
            }
            resolveBeamByThreshold(server, player, target, claimed, playerGuildId, beam, toughness);
            return false;
        }

        // mark the beam a tracked planet attack so its clash weight is the battle-power blend, then log both sides'
        // resolved power so a launch-test can read the balance in world (see logClashPower).
        beam.getPersistentData().putBoolean(PLANET_ATTACK_KEY, true);
        logClashPower("beam", player, beam, toughness);

        BeamInFlight record = new BeamInFlight(beam, target, player.getUUID(), claimed, playerGuildId, now);
        record.clash = clash;
        beamInFlight.add(record);
        beamTrace(beam, player, "clash OPENED against " + target.id);
        refuse(player, "planet_buster_clash_started");
        return true;
    }

    // advance one beam struggle by a tick. Returns false when finished (the beam won and doomed the planet, lost, or
    // dropped) so the caller cleans up and removes it. Mirrors stepRecord's clash phases without any steering: a wave is
    // anchored and its clash heading is fixed, so there is nothing to hold still.
    private boolean beamStep(MinecraftServer server, GeneratedPlanetClaims claims, BeamInFlight record, long now)
    {
        AbstractKiProjectile beam = record.beam;
        if (beam.isRemoved())
        {
            // the beam is gone mid-struggle. If the defender's wave is still alive, DMZ killed the beam as the LOSER, so
            // the planet WON: tell the firer. If the wave is gone too, the struggle merely dissolved (the firer stopped
            // firing), so we say nothing, exactly as the ball path stays silent on a non-clash removal.
            if (record.clashEverLocked && record.clash != null && !record.clash.isWaveGone())
            {
                ServerPlayer loser = server.getPlayerList().getPlayer(record.playerId);
                if (loser != null)
                {
                    refuse(loser, "planet_buster_clash_lost");
                }
            }
            return false;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(record.playerId);
        if (player == null)
        {
            return false;
        }
        if (claims.isDestroyed(record.target.id))
        {
            return false;
        }

        boolean locked = safeIsClashLocked(beam);
        // Mirrors the ball path's per-second debug line so a beam clash can be watched the same way. Same gate, same
        // once-per-20-ticks throttle, same total guarding: a diagnostic must never wedge the beam loop.
        if (clashDebug && record.clash != null && now % 20L == 0L)
        {
            try
            {
                LoggingHandler.sulog.info("[PlanetBuster] beam clash debug {}",
                        clashSummary(beam, record.clash.debugWave(), locked));
            }
            catch (Throwable ignored)
            {
            }
        }
        if (locked)
        {
            // struggle IN PROGRESS: keep both beams alive so neither times out and dissolves the clash by default.
            record.clashEverLocked = true;
            beam.setMaxLife(beam.tickCount + LIFE_MARGIN_TICKS);
            record.clash.keepAlive();
            return true;
        }
        if (!record.clashEverLocked)
        {
            // opened but not yet paired. Wait for DMZ to pair the two beams within the grace window, keeping both alive.
            record.clashPendingTicks++;
            // Snapshot on the FIRST pending tick, while the defender is certainly still alive. The failure summary at
            // the end of the window is taken after the wave has died and the holder may already be unresolvable, which
            // makes an owner that was fine at the start look identical to one that was never there. Two lines, one at
            // each end, tell those apart.
            if (record.clashPendingTicks == 1)
            {
                String opened = dbg(() -> clashSummary(beam, record.clash == null ? null : record.clash.debugWave(),
                        false));
                LoggingHandler.sulog.info("[PlanetBuster] beam clash opened, first tick: {}", opened);
            }
            if (record.clashPendingTicks <= CLASH_ENGAGE_GRACE_TICKS)
            {
                beam.setMaxLife(beam.tickCount + LIFE_MARGIN_TICKS);
                record.clash.keepAlive();
                return true;
            }
            // the grace window elapsed without a lock (an API drift, or geometry that did not overlap). Fall back to the
            // raw threshold so the beam is not silently neutered, mirroring how the ball busts when its clash fails to
            // engage. One INFO line, like the ball's own timeout explanation.
            String summary = dbg(() -> clashSummary(beam, record.clash == null ? null : record.clash.debugWave(),
                    false));
            LoggingHandler.sulog.info(
                    "[PlanetBuster] beam clash failed to engage within {} ticks; falling back to the raw damage "
                            + "threshold. {}", CLASH_ENGAGE_GRACE_TICKS, summary);
            double toughness = PlanetToughness.resolve(server, record.target.id);
            resolveBeamByThreshold(server, player, record.target, record.claimed, record.guildId, beam, toughness);
            return false;
        }
        // was locked, now unlocked, and the beam is still alive (a removed loser was handled at the top): it WON. Route
        // the destroy through the same doom path the ball impact uses.
        beamImpact(server, player, record);
        return false;
    }

    // the beam won its clash: spend the entitlement if the target was claimed, then start the shared doom sequence. Like
    // the ball's impact, the entitlement is consumed HERE, at the win, and this is the only way it is spent on this path.
    // Unlike the ball we do NOT detonate the beam: a held player beam ends on its own, and forcing it would be cosmetic
    // noise.
    private void beamImpact(MinecraftServer server, ServerPlayer player, BeamInFlight record)
    {
        if (record.claimed && !GuildRaidSpoils.get(server).consume(record.guildId, record.target.id))
        {
            refuse(player, "planet_buster_claimed");
            return;
        }
        beginDoom(server, record.target, player.getUUID(), record.claimed);
    }

    // the NO-CLASH resolution for a beam: destroy only if the beam's ki damage strictly exceeds the planet's toughness.
    // Used when the clash gate is disabled or the clash machinery could not engage, so the user's threshold model still
    // decides the outcome. The entitlement is consumed here for a claimed target, exactly as impact does, before doom.
    // The ki-damage read FAILS OPEN (treats a broken read as overwhelming) so a DMZ API drift can never make a planet
    // indestructible, matching the minimum-damage gate's stance.
    private void resolveBeamByThreshold(MinecraftServer server, ServerPlayer player, GeneratedPlanets.Generated target,
                                        boolean claimed, String guildId, AbstractKiProjectile beam, double toughness)
    {
        double damage;
        try
        {
            damage = beam.getKiDamage();
        }
        catch (Throwable ex)
        {
            damage = Double.MAX_VALUE;
        }
        if (damage <= toughness)
        {
            refuse(player, "planet_buster_clash_lost");
            return;
        }
        if (claimed && !GuildRaidSpoils.get(server).consume(guildId, target.id))
        {
            refuse(player, "planet_buster_claimed");
            return;
        }
        beginDoom(server, target, player.getUUID(), claimed);
    }

    // the beam's clash heading (fixed for a wave), normalised, or null if the read drifts or is degenerate. Degrading to
    // null skips the beam this tick rather than throwing in the scan loop.
    private static Vec3 safeBeamHeading(AbstractKiProjectile beam)
    {
        try
        {
            Vec3 dir = Vec3.directionFromRotation(beam.getClashPitch(), beam.getClashYaw());
            return dir.lengthSqr() < 1.0E-9 ? null : dir.normalize();
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    // the beam's current rendered length, or 0 on a drifted read so it is treated as out of reach.
    private static double safeBeamLength(AbstractKiProjectile beam)
    {
        try
        {
            return beam.getClashBeamLength();
        }
        catch (Throwable ignored)
        {
            return 0.0;
        }
    }

    // true when the beam is strong enough to be ALLOWED to open a planet clash, per the minimum-damage gate. Returns
    // true without blocking when the gate is disabled or the ki-damage read throws (fail OPEN, logged once), for the
    // same reason the blast gate does: a broken read must never make a planet indestructible.
    // tell the firer, on the action bar, that their beam is under the minimum ki damage a planet clash needs, and what
    // that threshold is, so the refusal is VISIBLE instead of the beam silently doing nothing. Throttled per beam (see
    // BEAM_WEAK_WARN_KEY) because the gate re-runs every tick while the beam is held and left unmarked so it can still
    // charge into a clash. translatableWithFallback so the notice reads even if the lang entry is missing.
    private void warnBeamTooWeak(ServerPlayer player, AbstractKiProjectile beam, long now)
    {
        long last = beam.getPersistentData().getLong(BEAM_WEAK_WARN_KEY);
        if (last != 0L && now - last < BEAM_WEAK_WARN_INTERVAL_TICKS)
        {
            return;
        }
        beam.getPersistentData().putLong(BEAM_WEAK_WARN_KEY, now);
        long threshold = (long) Math.ceil(minBeamKiDamage);
        player.displayClientMessage(Component.translatableWithFallback(
                "message.dmz_ragnarok.core.planet_buster_beam_too_weak",
                "Your beam is too weak to shatter a planet. It needs at least %s ki damage; charge up and keep firing.",
                threshold), true);
    }

    private boolean beamMeetsMinimumDamage(AbstractKiProjectile beam)
    {
        if (minBeamKiDamage <= 0.0)
        {
            return true;
        }
        try
        {
            return beam.getKiDamage() >= minBeamKiDamage;
        }
        catch (Throwable t)
        {
            if (minDamageWarned.compareAndSet(false, true))
            {
                LoggingHandler.sulog.warn("[PlanetBuster] Could not read a beam's ki damage "
                        + "(DragonMineZ ki API drift?); the minimum-damage gate is skipped and the beam is allowed.", t);
            }
            return true;
        }
    }

    // a read that may touch a drifted DMZ getter. Returns the read value, or the literal "?" if the getter throws, so
    // one broken field can never take down the whole diagnostic line. Purely for logging.
    @FunctionalInterface
    private interface DbgRead
    {
        String read();
    }

    // evaluate one guarded diagnostic read: its value, or "?" on ANY throwable (including an NPE from a null wave).
    private static String dbg(DbgRead read)
    {
        try
        {
            return read.read();
        }
        catch (Throwable t)
        {
            return "?";
        }
    }

    // format a position compactly for a one-line log entry.
    private static String fmtPos(Vec3 v)
    {
        return String.format(java.util.Locale.ROOT, "(%.1f,%.1f,%.1f)", v.x, v.y, v.z);
    }

    // build the decisive-state summary shared by the per-second clash-debug line (part 2) and the one-shot
    // timeout-explanation line (part 3). Reports, for the ball and the answering wave, exactly the fields DragonMineZ's
    // BeamClashManager pairs on (role, clashable, firing, locked, size, position) plus the three geometry quantities the
    // pairing test uses: the distance between the two, the dot product of their clash headings (which must be < -0.3 to
    // pair), and the overlap threshold (ballSize + waveSize) + 1.5 their segments must fall within. Every single read is
    // wrapped in dbg(...) so a drifted getter prints "?" instead of throwing; the caller wraps the whole log call too.
    /**
     * The weight the PLANET brings to the struggle: its toughness scaled by the difficulty dial.
     *
     * <p>Applied ONLY where a clash is opened, so toughness itself is untouched everywhere else. That separation is
     * the point. {@code minBlastKiDamage} and {@code minBeamKiDamage} still decide whether an attack is a world-ender
     * at all against the real toughness, and the raw-damage fallback used when a clash cannot form still compares
     * against the real toughness. Only the minigame gets easier.
     *
     * <p>Floored at 1.0 for the same reason the wave's damage always was: a defender contesting with zero weight is
     * not an easy clash, it is no clash.
     */
    private static double defenderClashWeight(double toughness)
    {
        return Math.max(1.0, toughness * clashDefenderWeight);
    }

    private static String clashSummary(InFlight record, boolean locked)
    {
        return clashSummary(record.projectile, record.clash == null ? null : record.clash.debugWave(), locked);
    }

    /**
     * The same summary for a BEAM attack. A {@code KiWaveEntity} IS an {@code AbstractKiProjectile}, so one body serves
     * both weapons; only the label differs, and "ball" reads fine for either since both sides are ki projectiles.
     *
     * <p>This exists because a beam clash that never engages is otherwise completely silent about WHY. The three
     * quantities DragonMineZ pairs on, both clash roles, the distance and the heading dot, are all in here, so one
     * failed clash tells you whether it was the role, the geometry or the aim.
     */
    private static String clashSummary(AbstractKiProjectile attacker, KiWaveEntity defender, boolean locked)
    {
        final AbstractKiProjectile ball = attacker;
        final KiWaveEntity wave = defender;

        StringBuilder sb = new StringBuilder(256);
        sb.append("locked=").append(locked);

        // ball side.
        sb.append(" | ball rt=").append(dbg(() -> String.valueOf(ball.getKiRenderType())));
        sb.append(" role=").append(dbg(() -> String.valueOf(ball.getClashRole())));
        sb.append(" clashable=").append(dbg(() -> String.valueOf(ball.isClashableBeam())));
        sb.append(" firing=").append(dbg(() -> String.valueOf(ball.isFiring())));
        sb.append(" clashLocked=").append(dbg(() -> String.valueOf(ball.isClashLocked())));
        sb.append(" size=").append(dbg(() -> String.valueOf(ball.getSize())));
        sb.append(" pos=").append(dbg(() -> fmtPos(ball.position())));

        // wave side. A null wave is a legitimate state (never spawned / already discarded); dbg turns the NPE into "?".
        sb.append(" | wave alive=").append(dbg(() -> String.valueOf(wave != null && !wave.isRemoved() && wave.isAlive())));
        sb.append(" role=").append(dbg(() -> String.valueOf(wave.getClashRole())));
        sb.append(" clashable=").append(dbg(() -> String.valueOf(wave.isClashableBeam())));
        sb.append(" firing=").append(dbg(() -> String.valueOf(wave.isFiring())));
        sb.append(" size=").append(dbg(() -> String.valueOf(wave.getSize())));
        sb.append(" pos=").append(dbg(() -> fmtPos(wave.position())));

        // geometry: the three quantities the pairing test turns on.
        sb.append(" | dist=").append(dbg(() -> String.format(java.util.Locale.ROOT, "%.2f",
                ball.position().distanceTo(wave.position()))));
        sb.append(" dot=").append(dbg(() ->
        {
            Vec3 ballHeading = Vec3.directionFromRotation(ball.getClashPitch(), ball.getClashYaw());
            Vec3 waveHeading = Vec3.directionFromRotation(wave.getClashPitch(), wave.getClashYaw());
            return String.format(java.util.Locale.ROOT, "%.3f", ballHeading.dot(waveHeading));
        }));
        sb.append(" overlapThreshold=").append(dbg(() -> String.format(java.util.Locale.ROOT, "%.2f",
                (ball.getSize() + wave.getSize()) + 1.5)));

        // The two remaining things DragonMineZ filters on before it ever runs the geometry, and the only ones the old
        // summary could not explain a failure with. It gathers candidates by requiring getOwner() to be a LivingEntity,
        // so a defender whose holder has not resolved is silently never a candidate; and the segment it measures runs
        // for getClashBeamLength(), so a length of zero collapses a beam to a point.
        sb.append(" | beamLen ball=").append(dbg(() -> String.format(java.util.Locale.ROOT, "%.2f",
                ball.getClashBeamLength())));
        sb.append(" wave=").append(dbg(() -> String.format(java.util.Locale.ROOT, "%.2f",
                wave.getClashBeamLength())));
        sb.append(" | owner ball=").append(dbg(() -> String.valueOf(
                ball.getOwner() == null ? "null" : ball.getOwner().getClass().getSimpleName())));
        sb.append(" wave=").append(dbg(() -> String.valueOf(
                wave.getOwner() == null ? "null" : wave.getOwner().getClass().getSimpleName())));

        return sb.toString();
    }

    // the blast reached the target: spend the entitlement if the target was claimed, DETONATE the blast so the player
    // sees DMZ's real explosion, then START a timed doom sequence. The destroy itself is deferred to the END of the red
    // ramp (see stepDoom), so the client can tint the planet red before it comes apart. requireUnclaimed will be true for
    // a WILD planet and false for an ENTITLEMENT-BACKED claimed planet, but that decision is applied later, at destroy.
    //
    // ORDERING (the important part): the entitlement is consumed HERE, at impact, not at destroy. The moment the blast
    // lands is the moment the right is spent, so a player cannot start a bust and then cancel it by logging off during
    // the ramp: the destroy in stepDoom runs regardless of whether they are still online. See DoomSequence.
    private void impact(MinecraftServer server, ServerPlayer player, InFlight record)
    {
        if (record.claimed)
        {
            // consume FIRST: the right may have lapsed or been spent mid-flight, in which case we abort the whole bust.
            // This consume is the ONLY way a raid entitlement is spent on redemption. The blast still detonates (it is a
            // real ki blast that hit something), it just fizzles against an intact planet.
            if (!GuildRaidSpoils.get(server).consume(record.guildId, record.target.id))
            {
                refuse(player, "planet_buster_claimed");
                detonate(record.projectile);
                return;
            }
        }

        // detonate the landed blast (see detonate): this replaces the old discard(), which deleted the entity outright
        // and suppressed DMZ's own explosion, so the bust used to be silent and invisible.
        detonate(record.projectile);

        // start the doom sequence and announce the RED RAMP to space clients. The destroy fires when the ramp elapses.
        beginDoom(server, record.target, player.getUUID(), record.claimed);
    }

    // start a doom sequence (red ramp, then destroy) and announce the ramp to space clients. Shared by the ball impact
    // and the beam win, so both destroy paths are byte-for-byte identical from here down: same ramp, same shared
    // PlanetDestruction entry point at the end of it, same shatter.
    private void beginDoom(MinecraftServer server, GeneratedPlanets.Generated target, UUID playerId, boolean claimed)
    {
        long now = server.overworld().getGameTime();
        doomSequences.add(new DoomSequence(target, playerId, claimed, now));
        broadcastDoom(server, target, PacketPlanetDoom.PHASE_RAMP, redRampTicks);
    }

    // detonate a landed blast instead of discarding it. DMZ's KiBlastEntity.onKiTick() self-destructs by calling its own
    // (private) explodeAndDie() the moment tickCount reaches getMaxLife(), which plays the real explosion (flash, sound,
    // particles). discard() deleted the entity before that could happen, which is why the user saw nothing. We cannot
    // call explodeAndDie() directly (it is private, and the task rules out reflection and mixins for this), so we pull the
    // blast's max life down to its current tick through the PUBLIC setMaxLife API: its very next DMZ tick then sees
    // tickCount >= maxLife and runs the real detonation itself. The space dimension is void, so DMZ's explosion finds no
    // blocks to grief (and SU's ki-griefing mixin is untouched by this).
    private static void detonate(AbstractKiProjectile projectile)
    {
        projectile.setMaxLife(projectile.tickCount);
    }

    // advance one doom sequence by a tick. Returns false when the sequence is finished (its destroy fired, or it was
    // dropped) so the caller removes it; true while it is still ramping. The destroy is routed through the single
    // PlanetDestruction entry point exactly as the immediate path used to be, only now at the END of the ramp.
    private boolean stepDoom(MinecraftServer server, DoomSequence seq, long now)
    {
        if (now - seq.startTick < redRampTicks)
        {
            return true;   // still ramping: leave the sequence running, the client is tinting the planet red.
        }

        // the ramp has elapsed: destroy now. The space dimension is where the planet lives; if it is somehow unavailable
        // (shutting down) we drop the sequence rather than destroy into nothing.
        ServerLevel space = SpaceDimension.level(server);
        if (space == null)
        {
            return false;
        }

        // the credited player may have disconnected DURING the ramp. The entitlement was already spent at impact and the
        // planet was doomed the instant the blast landed, so we destroy regardless and pass a NULL credit if they are
        // gone, rather than aborting. PlanetDestruction.destroy documents a null credit as acceptable (informational).
        ServerPlayer credit = server.getPlayerList().getPlayer(seq.playerId);
        boolean requireUnclaimed = !seq.claimed;
        PlanetDestruction.Result result = PlanetDestruction.destroy(space, seq.target, credit, requireUnclaimed);

        if (result == PlanetDestruction.Result.DESTROYED)
        {
            // the destroy already marked the planet gone and resynced the layout, so every client has just STOPPED
            // drawing it. Kick off the client-side SHATTER, which is drawn from the packet's own snapshot and so outlives
            // the now-absent body. Then confirm to the firer, if they are still online.
            broadcastDoom(server, seq.target, PacketPlanetDoom.PHASE_SHATTER, shatterTicks);
            if (credit != null)
            {
                Component name = Component.literal(GeneratedPlanets.nameFor(seq.target.id));
                credit.displayClientMessage(
                        Component.translatable("message.dmz_ragnarok.core.planet_buster_destroyed", name),
                        true);
            }
        }
        // any non-DESTROYED result (a race destroyed or reclaimed it first) just ends the sequence silently, as the
        // immediate path did before: the shared entry point already applied its own guards, and there is no planet left
        // to shatter, so a missing shatter packet is correct.
        return false;
    }

    // broadcast a doom phase to every player currently IN THE SPACE DIMENSION only: the sequence is invisible anywhere
    // else, so there is no reason to send it to a client that cannot see it. One packet per phase; the client runs its
    // own local timer from receipt, so there is no per-tick animation traffic.
    private static void broadcastDoom(MinecraftServer server, GeneratedPlanets.Generated target, byte phase,
                                      int durationTicks)
    {
        PacketPlanetDoom packet =
                PacketPlanetDoom.of(target.id, target.position, target.radius, target.tint, phase, durationTicks);
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            if (SpaceDimension.isSpace(player.level()))
            {
                NetworkUtils.sendTo(packet, player);
            }
        }
    }

    // the configured alignment penalty for destroying a planet (points off the destroyer's DMZ alignment, 0 disables).
    // read by the shared PlanetDestruction entry point so every credited destroy path applies the same rule.
    public static int alignmentPenalty()
    {
        return alignmentPenalty;
    }

    // clash-toughness config accessors, read by PlanetToughness (same package, but exposed as methods so the config
    // fields stay private and the toughness formula lives in one place).
    public static double clashWildToughness()
    {
        return clashWildToughness;
    }

    // How much harder a SYSTEM SUN is than a wild planet: its clash toughness (the defender's clash weight, i.e. the ki
    // damage a blast must out-power to win the struggle) is the wild planet value times this multiplier. Owner rule
    // (2026-09-29): a system sun is exactly 10x harder than a planet, because destroying it takes out its whole system.
    // Derived from the wild config rather than a separate knob so it tracks any tuning of the base difficulty.
    private static final double STAR_TOUGHNESS_MULTIPLIER = 10.0;

    public static double clashStarToughness()
    {
        return clashWildToughness * STAR_TOUGHNESS_MULTIPLIER;
    }

    public static double clashGuildToughnessBase()
    {
        return clashGuildToughnessBase;
    }

    public static double clashGuildToughnessPerMember()
    {
        return clashGuildToughnessPerMember;
    }

    public static double clashGuildBattlePowerDivisor()
    {
        return clashGuildBattlePowerDivisor;
    }

    public static double clashGuildToughnessFloor()
    {
        return clashGuildToughnessFloor;
    }

    // true when the given projectile is a tracked PLANET ATTACK (a steered giant ball or a struggling beam). Read by
    // MixinDmzClashParticipant to decide whether a clash weight is the battle-power blend (planet attack) or
    // DragonMineZ's raw ki damage (every other clash, including the planet's own defending wave, which is never
    // marked). Cheap: a single boolean read off the entity's server-side ForgeData, wrapped so a bad read is simply
    // "not a planet attack" and never throws inside the DMZ constructor.
    public static boolean isPlanetAttack(Entity projectile)
    {
        if (projectile == null)
        {
            return false;
        }
        try
        {
            return projectile.getPersistentData().getBoolean(PLANET_ATTACK_KEY);
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    // the BP-blended clash weight for a planet attack, on the ki-damage scale so it is directly comparable to the
    // defender wave's toughness:
    //   power = clashKiDamageWeight * rawKiDamage + clashBattlePowerWeight * (battlePower * clashBattlePowerScale)
    // FAILS OPEN to the raw ki damage on every early-out (feature disabled, no owner, no stats, or a DMZ API drift), so
    // a broken read can never make a planet unbustable or trivially bustable. Called by the mixin for the live weight
    // and by logClashPower for the launch-test line, so both read one formula.
    public static double planetClashPower(LivingEntity owner, double rawKiDamage)
    {
        if (!clashBattlePowerEnabled || owner == null)
        {
            return rawKiDamage;
        }
        try
        {
            StatsData stats = StatsProvider.<StatsData>get(StatsCapability.INSTANCE, owner).resolve().orElse(null);
            if (stats == null)
            {
                return rawKiDamage;
            }
            double battlePower = stats.getBattlePowerExact();
            return clashKiDamageWeight * rawKiDamage
                    + clashBattlePowerWeight * (battlePower * clashBattlePowerScale);
        }
        catch (Throwable ignored)
        {
            return rawKiDamage;
        }
    }

    // grep-able balance line, logged once when a planet clash begins: the attacker's raw ki damage, their battle power,
    // the resolved blended attacker power, and the planet's toughness (the defender weight), so a launch-test can read
    // the balance of both sides directly from the log. Wrapped so a diagnostic can never break the clash it describes.
    private void logClashPower(String kind, ServerPlayer player, AbstractKiProjectile projectile, double toughness)
    {
        try
        {
            double rawKiDamage = projectile.getKiDamage();
            double battlePower;
            try
            {
                StatsData stats =
                        StatsProvider.<StatsData>get(StatsCapability.INSTANCE, player).resolve().orElse(null);
                battlePower = stats == null ? 0.0 : stats.getBattlePowerExact();
            }
            catch (Throwable ignored)
            {
                battlePower = 0.0;
            }
            double attackerPower = planetClashPower(player, rawKiDamage);
            LoggingHandler.sulog.debug(
                    "[PlanetBuster] clash power ({}): attacker {} kiDamage={} battlePower={} -> power={} vs planet "
                            + "toughness={}",
                    kind, player.getGameProfile().getName(), String.format(java.util.Locale.ROOT, "%.1f", rawKiDamage),
                    String.format(java.util.Locale.ROOT, "%.1f", battlePower),
                    String.format(java.util.Locale.ROOT, "%.1f", attackerPower),
                    String.format(java.util.Locale.ROOT, "%.1f", toughness));
        }
        catch (Throwable ignored)
        {
            // the balance line is diagnostics only; never let it break the clash it is describing.
        }
    }

    // action-bar refusal in the player's own language, matching the space module's feedback style.
    private static void refuse(ServerPlayer player, String key)
    {
        player.displayClientMessage(
                Component.translatable("message.dmz_ragnarok.core." + key), true);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event)
    {
        // sweep any planet-defender holder left orphaned by a crash mid-clash in a previous session, so an invisible
        // holder can never accumulate in the void of the space dimension.
        PlanetDefenderEntities.sweepOrphans(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event)
    {
        // never let an in-flight record or a pending doom sequence outlive its world: the projectile references would be
        // dead next world anyway, and a half-finished doom must not carry a destroy across a world change. Tell any
        // firer still online that the planet-clash camera is inactive, then discard any live clash holders so none are
        // orphaned across the shutdown.
        for (InFlight record : inFlight)
        {
            if (record.camActiveSent)
            {
                sendClashCam(event.getServer(), record, false);
            }
            record.cleanupClash();
        }
        for (BeamInFlight record : beamInFlight)
        {
            record.cleanupClash();
        }
        inFlight.clear();
        beamInFlight.clear();
        doomSequences.clear();
    }

    @Override
    public void load(Builder BUILDER, boolean isReload)
    {
        BUILDER.push("PlanetBuster");
        cfgEnabled = BUILDER
                .comment("Master switch for the planet-buster feature. When false, firing a ki blast at a planet never destroys it and no fire checks run.")
                .define("enabled", true);
        cfgAllowedKiTypes = BUILDER
                .comment("Ki attack types that may destroy a planet when fired at one in space. Values are DMZ KiAttackData.KiType names. Default is GIANT_BALL only. Extend this to allow future destruction techniques (e.g. a gods-of-destruction / destruction-ki attack) without a code change. An unrecognised name is logged and ignored, never a crash. Valid names: SMALL_BALL, MEDIUM_BALL, GIANT_BALL, WAVE, LASER, BEAM, DISK, EXPLOSION, SHIELD, BARRAGE, AREA.")
                .defineList("allowedKiTypes", Arrays.asList("GIANT_BALL"), o -> o instanceof String);
        cfgRedRampTicks = BUILDER
                .comment("How long, in server ticks, a doomed planet tints deeper red BEFORE it is destroyed. This is the ramp the blast's impact starts; the destroy fires when it elapses. Default 60 ticks (3 seconds). 20 ticks = 1 second. Set to 0 to destroy the instant the blast lands (no red ramp).")
                .defineInRange("redRampTicks", 60, 0, 20 * 60);
        cfgShatterTicks = BUILDER
                .comment("How long, in ticks, the client plays the planet's shatter (flying cube shards) AFTER it is destroyed. Purely a client visual that outlives the planet; the server only forwards this number. Default 40 ticks (2 seconds). 20 ticks = 1 second. Set to 0 for no shatter animation.")
                .defineInRange("shatterTicks", 40, 0, 20 * 60);
        cfgAlignmentPenalty = BUILDER
                .comment("How many DragonMineZ alignment points the player credited with destroying a planet loses (alignment runs 0..100, higher is more GOOD). Destroying a world drops the destroyer toward EVIL. Applies to every credited destroy, the ki blast now and a raid win later. Default 10. Set to 0 to disable the penalty.")
                .defineInRange("alignmentPenalty", 10, 0, 100);
        cfgClashEnabled = BUILDER
                .comment("Master switch for the planet clash gate. When true, a giant ball fired at a planet meets a DragonMineZ beam clash: the planet fires an answering ki wave and the shot only busts the world if it WINS the struggle. When false, the buster behaves exactly as before (the ball always destroys the planet). Default true.")
                .define("clashEnabled", true);
        cfgClashTriggerRadiusMultiplier = BUILDER
                .comment("Where the clash forms, as a multiple of the target planet's radius. The struggle should happen in open space in front of the planet, so this is comfortably above 1.0. The trigger distance from the planet centre is max(clashTriggerMinDistance, radius * this). Default 2.0.")
                .defineInRange("clashTriggerRadiusMultiplier", 2.0, 1.0, 100.0);
        cfgClashTriggerMinDistance = BUILDER
                .comment("A flat minimum trigger distance in blocks, so even a small planet forms its clash out in open space rather than right against its surface. The trigger distance from the planet centre is max(this, radius * clashTriggerRadiusMultiplier). Default 50.")
                .defineInRange("clashTriggerMinDistance", 50.0, 1.0, 100000.0);
        cfgClashWildToughness = BUILDER
                .comment("Toughness (the defending beam's ki damage) of an UNOWNED / wild planet. DragonMineZ turns this into the defender's clash weight and pits it against the incoming blast's own ki damage, so a higher value makes a wild planet harder to bust. This lives on the KI-DAMAGE scale: a real fired blast's ki damage runs from a few thousand (a developed mid-game player) into six figures (a high-level player in a form), so a wild planet must be in the thousands to put up any fight. Default 5000, an even contest for a developed mid-game blast.")
                .defineInRange("clashWildToughness", 5000.0, 0.0, 1.0E9);
        cfgClashGuildToughnessBase = BUILDER
                .comment("Base toughness of a GUILD-OWNED planet, before the per-member and battle-power terms are added. Toughness = base + perMember * members + battlePower / divisor, then clamped up to clashGuildToughnessFloor. On the ki-damage scale (see clashWildToughness). Default 5000.")
                .defineInRange("clashGuildToughnessBase", 5000.0, 0.0, 1.0E9);
        cfgClashGuildToughnessPerMember = BUILDER
                .comment("Toughness added to a guild-owned planet for each guild member, on the ki-damage scale. Default 2000, so each member makes a claimed world materially harder to crack.")
                .defineInRange("clashGuildToughnessPerMember", 2000.0, 0.0, 1.0E9);
        cfgClashGuildBattlePowerDivisor = BUILDER
                .comment("The owning guild's aggregate battle power divided by this becomes a toughness term (battlePower / divisor). DMZ guild battle power reaches hundreds of thousands into the millions, so a smaller divisor lets it contribute meaningful toughness on the ki-damage scale. At the default 100 a guild summing ~1,000,000 battle power adds ~10000 toughness. A larger divisor means battle power contributes less. Set to 0 to ignore battle power entirely. Default 100.")
                .defineInRange("clashGuildBattlePowerDivisor", 100.0, 0.0, 1.0E12);
        cfgClashGuildToughnessFloor = BUILDER
                .comment("Minimum toughness of a guild-owned planet, on the ki-damage scale. CRITICAL: a guild's cached battle power is only refreshed while members are ONLINE, so an entirely offline guild reports zero power and its planet would otherwise be nearly free to bust. This floor guarantees a claimed world always puts up a real fight. Default 8000, already a serious blast to overcome.")
                .defineInRange("clashGuildToughnessFloor", 8000.0, 0.0, 1.0E9);
        cfgMinBlastKiDamage = BUILDER
                .comment("Minimum ki damage an incoming blast must ITSELF carry to be allowed to destroy a planet at all. This is a SEPARATE, independent gate from the clash: the clash decides whether a blast out-powers a planet's toughness in a struggle, whereas this decides whether the blast is a real world-ender in the first place. A blast below this value is refused the INSTANT it is fired, BEFORE any clash forms, detonates harmlessly like any ki blast, and never destroys a planet (this is the 'too little damage means the clash never even starts' rule). It is a FLAT ki-damage number, NOT relative to the planet's toughness, on purpose: the toughness-relative comparison already lives in the clash, so keeping this flat keeps the two gates independent. Default 3000, the researched floor for a developed mid-game player's blast; raise it further to require genuinely huge blasts. Set to 0 to DISABLE this gate, leaving the clash as the only gate.")
                .defineInRange("minBlastKiDamage", 3000.0, 0.0, 1.0E9);
        cfgBeamEnabled = BUILDER
                .comment("Master switch for destroying planets with BEAMS (ki waves like a Kamehameha), a separate path from the giant-ball allowlist. When true, a beam whose rendered length reaches a planet in space opens the SAME clash a giant ball does and busts the world only if it WINS. When false, no beam can ever destroy a planet. A beam is only ever in reach from within its actual rendered length, so in practice the firer must be within the label range (300 blocks of the surface) AND hold the beam long enough for it to grow that far. Default true.")
                .define("beamEnabled", true);
        cfgMinBeamKiDamage = BUILDER
                .comment("Minimum ki damage a firing BEAM must itself carry to open a planet clash at all. The beam twin of minBlastKiDamage and, like it, independent of the clash: this decides whether the beam is a world-ender in the first place, the clash decides whether it out-powers the planet's toughness. A beam below this value strains against the world with no effect and never opens a struggle. Default 3000. Set to 0 to disable this gate.")
                .defineInRange("minBeamKiDamage", 3000.0, 0.0, 1.0E9);
        cfgClashDebug = BUILDER
                .comment("DIAGNOSTICS ONLY. When true, while a planet clash is pending or locked the server logs one decisive-state line per second (at most once per 20 ticks, never per tick): the ball's and the answering wave's clash role, clashable/firing/locked flags, sizes and positions, the distance between them, the dot product of their clash headings, and the overlap threshold DragonMineZ pairs within. Use this to find WHICH condition is failing when a clash announces itself but never locks. Leave FALSE in production. Default false.")
                .define("clashDebug", false);
        cfgClashBattlePowerEnabled = BUILDER
                .comment("Make a PLANET clash weigh the attacker's BATTLE POWER, not ki damage alone, so a melee / STR build can bust a world instead of always losing the ki-damage-only struggle. When true, a giant ball or beam fired at a planet contests the world with a blend of its ki damage and the firer's battle power (see the weights below). When false, a planet clash uses the raw ki damage exactly as before. This ONLY affects planet clashes: a normal player-vs-player or player-vs-NPC clash is never touched either way. Default true.")
                .define("clashBattlePowerEnabled", true);
        cfgClashKiDamageWeight = BUILDER
                .comment("Weight on the attacker's RAW KI DAMAGE in the planet-clash blend: power = clashKiDamageWeight * kiDamage + clashBattlePowerWeight * (battlePower * clashBattlePowerScale). Kept LOW on purpose. DMZ battle power ALREADY includes PWR, and ki damage is PWR-driven, so counting ki damage at full weight would count PWR twice and over-reward a ki build, the opposite of the goal. At the default 0.15 a ki build keeps only a modest edge over an equal-battle-power melee build. Default 0.15.")
                .defineInRange("clashKiDamageWeight", 0.15, 0.0, 1.0E6);
        cfgClashBattlePowerWeight = BUILDER
                .comment("Weight on the attacker's BATTLE POWER term in the planet-clash blend (see clashKiDamageWeight for the full formula). The default 1.0 makes battle power the dominant contributor, which is what lets every build compete on the strength they actually invested in. Default 1.0.")
                .defineInRange("clashBattlePowerWeight", 1.0, 0.0, 1.0E6);
        cfgClashBattlePowerScale = BUILDER
                .comment("Scales raw battle power (roughly 100,000 to 1,000,000 for a developed player) down onto the ki-damage / toughness scale (thousands to tens of thousands) before the battle-power weight is applied. At the default 0.05 a battle power of 200,000 contributes 10,000, on the order of a wild planet's toughness (5000) and a guild floor (8000), so a developed player of any build can put up a real fight and a strong player can win. Lower this to make battle power matter less, raise it to make planets easier to bust. Default 0.05.")
                .defineInRange("clashBattlePowerScale", 0.05, 0.0, 1.0E6);
        cfgClashDefenderWeight = BUILDER
                .comment("How hard the PLANET pushes back in the clash minigame, as a multiplier on the defender's clash weight. This is the difficulty dial for the struggle itself. 1.0 is the planet contesting at its full toughness; below 1.0 it contests at less and the minigame is easier to win; above 1.0 it is harder. It scales ONLY the defender's weight inside the struggle and NOT the planet's toughness, so the minimum-ki-damage gates that decide whether an attack is a world-ender at all, and the raw-damage fallback used when a clash cannot form, keep working off the unchanged toughness. Note that this number is NOT linear in difficulty: it drives the defender's auto-press accuracy through a base-10 log curve that saturates at a clash weight of about 1358, so anything above that value is the same near-perfect defender and only cuts below it are felt. Default 0.05 (was 0.375, which still sat on that ceiling), putting a wild planet at a clash weight of 250 and a guild-owned floor at 400. Raise it toward 1.0 for a serious struggle, lower it toward 0.01 to make busting a world close to a formality.")
                .defineInRange("clashDefenderWeight", 0.05, 0.01, 100.0);
        BUILDER.pop();
    }

    @Override
    public void bakeConfig(boolean reload)
    {
        enabled = cfgEnabled.get();
        redRampTicks = cfgRedRampTicks.get();
        shatterTicks = cfgShatterTicks.get();
        alignmentPenalty = cfgAlignmentPenalty.get();
        clashEnabled = cfgClashEnabled.get();
        clashTriggerRadiusMultiplier = cfgClashTriggerRadiusMultiplier.get();
        clashTriggerMinDistance = cfgClashTriggerMinDistance.get();
        clashWildToughness = cfgClashWildToughness.get();
        clashGuildToughnessBase = cfgClashGuildToughnessBase.get();
        clashGuildToughnessPerMember = cfgClashGuildToughnessPerMember.get();
        clashGuildBattlePowerDivisor = cfgClashGuildBattlePowerDivisor.get();
        clashGuildToughnessFloor = cfgClashGuildToughnessFloor.get();
        minBlastKiDamage = cfgMinBlastKiDamage.get();
        beamEnabled = cfgBeamEnabled.get();
        minBeamKiDamage = cfgMinBeamKiDamage.get();
        clashDebug = cfgClashDebug.get();
        clashBattlePowerEnabled = cfgClashBattlePowerEnabled.get();
        clashKiDamageWeight = cfgClashKiDamageWeight.get();
        clashBattlePowerWeight = cfgClashBattlePowerWeight.get();
        clashBattlePowerScale = cfgClashBattlePowerScale.get();
        clashDefenderWeight = cfgClashDefenderWeight.get();
        // parse the allowlist defensively: keep only names that resolve to a real KiAttackData.KiType, warn on the rest.
        // Building a fresh set and swapping the volatile reference keeps the event thread's read consistent.
        Set<String> parsed = new HashSet<>();
        for (String raw : cfgAllowedKiTypes.get())
        {
            if (raw == null)
            {
                continue;
            }
            String name = raw.trim().toUpperCase(java.util.Locale.ROOT);
            try
            {
                KiAttackData.KiType.valueOf(name);
                parsed.add(name);
            }
            catch (IllegalArgumentException ex)
            {
                LoggingHandler.sulog.warn(
                        "[PlanetBuster] Ignoring unknown ki type '{}' in allowedKiTypes; not a DMZ KiAttackData.KiType.",
                        raw);
            }
        }
        if (parsed.isEmpty())
        {
            // an all-invalid or empty list would silently disable the feature; fall back to the GIANT_BALL default and say so.
            LoggingHandler.sulog.warn(
                    "[PlanetBuster] allowedKiTypes resolved to nothing; falling back to GIANT_BALL only.");
            parsed.add("GIANT_BALL");
        }
        allowedKiTypeNames = parsed;
    }

    @Override
    public ConfigData returnData()
    {
        return data;
    }
}
