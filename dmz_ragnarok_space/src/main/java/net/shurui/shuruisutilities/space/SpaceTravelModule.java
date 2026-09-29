package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.shurui.shuruisutilities.commands.player.VanishStorage;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.config.ConfigData;
import net.shurui.shuruisutilities.core.config.ConfigLoaderBase;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule;
import net.shurui.shuruisutilities.spacepod.PodDeployData;
import net.shurui.shuruisutilities.spacepod.SpacePodDeploy;
import net.shurui.shuruisutilities.util.DynamicLevels;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.commons.selections.WarpPoint;

import com.dragonminez.common.init.MainEntities;
import com.dragonminez.common.init.entities.BlackNimbusEntity;
import com.dragonminez.common.init.entities.FlyingNimbusEntity;
import com.dragonminez.common.init.entities.SpacePodEntity;
import com.dragonminez.common.spacepod.SpacePodDestinationDefinition;
import com.dragonminez.common.spacepod.SpacePodDestinationRegistry;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.Builder;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.shard.ShardConfig;
import net.shurui.shuruisutilities.shard.ShardDimensions;
import net.shurui.shuruisutilities.shard.ShardRouter;
import net.shurui.shuruisutilities.shard.ShardSync;
import net.shurui.shuruisutilities.shard.ShardTransfer;
import net.shurui.shuruisutilities.world.space.InhabitedPlanets;
import net.shurui.shuruisutilities.world.space.SpaceHandoff;
import net.shurui.shuruisutilities.world.space.SurfaceTravelData;
import net.shurui.shuruisutilities.world.space.SurfaceSnap;

/**
 * Space-travel entry/return (phase 1a). Flying up past a configurable altitude in an eligible dimension moves a
 * player into the datapack space dimension ({@link SpaceDimension}); flying down out of space past a
 * configurable altitude returns them to the exact spot they left from. No planets, rendering, claiming or
 * generated worlds live here; later phases own those.
 *
 * <p>The whole thing is a server-side player-tick handler on the Forge bus (auto-registered by the SU module
 * launcher). Origin is persisted on the player (see {@link SpaceTravelData}) so a relog mid-trip never strands
 * anyone. Config lives in SpaceTravel.toml through the standard SU {@link ConfigLoaderBase} path.
 *
 * <p>HAZARD: a cross-dimension {@code ServerPlayer.teleportTo} DISMOUNTS the rider and leaves the vehicle behind,
 * and DMZ's rideable space pod ({@code SpacePodEntity}) is persistence-required, so naively teleporting a pod
 * pilot into space would strand an immortal pod in the origin dimension and drop the pilot in on foot. The pod is
 * therefore a first-class travel vehicle handled at exactly two points, both of which mirror DMZ's own
 * TravelToPlanetC2S (which discards the ridden pod and constructs a fresh one at the destination): ENTERING space
 * spawns a FRESH pod in the space dimension and reseats the pilot (see {@link #enterSpaceAboardPod}), and every
 * LANDING/RETURN rebuilds the pod under the player on arrival (see {@link #carryPodThroughTeleport}), so the pod
 * travels WITH its pilot in both directions and no trip can cost anyone a ship. DMZ's flying nimbus (and its black
 * variant) also climbs freely in any dimension but is NOT a launch vehicle: a nimbus rider is held out of space
 * entirely in {@link #tickEligible}, which fixes a live bug where an altitude-crossing nimbus rider was dumped
 * into space on foot with the cloud left behind. dragonminez is a mandatory dependency of this addon (see
 * mods.toml), so the direct type references are safe with no ModList guard needed; the runtime pod touch points
 * are still Throwable-guarded against a DMZ 2.1.3 shape shift.
 *
 * <p>POD AUTOPILOT (Job 2). Selecting a planet in the pod menu arms an autopilot ({@link SpaceAutopilot}, set from
 * {@link PlanetCourse}); this tick handler then FLIES the pod for the player. A launch from a surface dimension is a
 * POWERED ASCENT, not an instant blink: {@link #driveAscent} drives the pod straight up under power inside the origin
 * dimension until it passes {@code entryAltitude}, and only THEN performs the one unavoidable cross-dimension teleport
 * into space ({@link #enterSpace}). Once in space {@link #driveAutopilot} flies it horizontally into the target body,
 * where the ordinary fly-into landing fires.
 *
 * <p>The pod is driven CLIENT-SIDE, which is the fix for the old per-tick judder. A scripted server-side pod teleport
 * plus a {@code ClientboundMoveVehiclePacket} used to force the client into the {@code absMoveTo} branch every tick,
 * which sets {@code xo = x} and leaves nothing to interpolate, so the pod advanced in 20 discrete hops a second. Now the
 * server only SYNCS the target and a constant per-tick step to the controlling client ({@link PacketSpaceAutopilotSync},
 * sent on start / target change / stop, never per tick) and the client's own {@code SpacePodEntity.travel} integrates
 * the pod toward it (see {@code core.mixin.client.MixinDmzSpacePodTravel}), reporting position through the ordinary
 * {@code ServerboundMoveVehiclePacket} path exactly like manual flight, which is smooth precisely because it never hits
 * {@code absMoveTo}. The server runs the IDENTICAL integration ({@link #integratePod}) with plain
 * {@code setDeltaMovement} + {@code move} (never {@code teleportTo}) so other players see honest interpolated motion and
 * arrival detection reads a real position; setting the pod's delta to the step also keeps the reported move inside the
 * vehicle "moved too quickly" tolerance. A rolling window of chunks ahead is force-loaded along the route
 * ({@link SpaceRouteChunks}) so the client never stalls waiting for a chunk. The drive logs a start and an end line
 * ({@link #logAutopilotStart} / {@link #stopAutopilot}) so a future failure is visible instead of silent.
 */
@SUModule(name = "SpaceTravel", parentMod = ShuruisUtilities.class, version = ShuruisUtilities.CURRENT_MODULE_VERSION)
public class SpaceTravelModule extends ConfigLoaderBase
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static ForgeConfigSpec SPACE_CONFIG;
    private static final ConfigData data = new ConfigData("SpaceTravel", SPACE_CONFIG, new ForgeConfigSpec.Builder());

    // Dimensions that may NEVER be eligible, enforced in code so an admin adding them to the config list can't
    // turn them on. The Nether/End have their own vertical structure and DMZ's otherworld is the afterlife dim;
    // launching to space from any of them makes no sense and would be a trap. Package-visible so the planet
    // registry (PlanetRegistry) reuses the exact same set rather than declaring a second, drift-prone copy: a
    // forbidden launch source is also never a planet body.
    static final Set<String> FORBIDDEN_DIMENSIONS = new HashSet<>(Arrays.asList(
            "minecraft:the_nether",
            "minecraft:the_end",
            "dragonminez:otherworld"));

    // config-backed settings (baked in bakeConfig)
    private static boolean enabled = true;
    private static double entryAltitude = 1000.0;
    private static double returnAltitude = 288.0;
    private static double nearestPlanetRange = 6144.0;
    private static List<String> eligibleDimensions = Arrays.asList("minecraft:overworld");

    private static ForgeConfigSpec.BooleanValue cfgEnabled;
    private static ForgeConfigSpec.DoubleValue cfgEntryAltitude;
    private static ForgeConfigSpec.DoubleValue cfgReturnAltitude;
    private static ForgeConfigSpec.DoubleValue cfgNearestPlanetRange;
    private static ForgeConfigSpec.ConfigValue<List<? extends String>> cfgEligibleDimensions;

    // Y a player arrives at when entering space: near the bottom of the play band, with the whole 2048-block
    // space column above them to climb. Kept above the return altitude so arriving never instantly re-triggers
    // the descend-out check.
    private static final double SPACE_ARRIVAL_Y = 320.0;

    // How far back from a planet body's surface a player materialises when they launch from that body's dimension
    // (phase 1b). Added to the body's radius so the standoff is measured from the surface, not the centre: a
    // comfortable gap so nobody spawns inside the cube and the whole body reads in view on arrival.
    private static final double PLANET_STANDOFF = 40.0;

    // Per-player re-trigger cooldown (ms). Stops a player hovering exactly at the threshold from ping-ponging
    // between dimensions every tick. Keyed on the persistent tag so it also covers a fast relog. The SAME cooldown
    // also guards the planet landing below, so landing on a body cannot immediately re-trigger.
    private static final long COOLDOWN_MS = 3000L;
    private static final String COOLDOWN_TAG = "su_space_cooldown";

    // Per-player memo of a surface CELL we already scanned and found NO stamped planet for. tickOnSurface resolves a
    // missing SurfaceTravelData record by scanning the (tiny) stamped-surface set once; if that scan finds nothing (an
    // admin teleported to a genuinely empty coordinate), we record the cell here so the scan does not repeat every tick
    // for a player sitting there. A genuinely empty cell never gains a surface just by being stood on, so one miss
    // stands until the player moves to a different cell (a different packed key) or a resolve later succeeds. Server
    // thread only (the player tick), so a plain HashMap needs no synchronisation; entries are dropped on logout.
    private final Map<UUID, Long> surfaceResolveMiss = new HashMap<>();

    // Per-player game-tick we last sent the "reached the edge" action bar, so the boundary clamp shows it at most
    // once every EDGE_MESSAGE_INTERVAL ticks instead of spamming it every tick the clamp fires. Server thread only
    // (the player tick), so a plain HashMap needs no synchronisation; entries are dropped on logout alongside
    // surfaceResolveMiss.
    private final Map<UUID, Long> lastEdgeMessageTick = new HashMap<>();

    // Per-player game-tick we last swept the player's surroundings for unpopulated saiyan settlements on an inhabited
    // planet, so the (cheap) structure lookup runs at most once per SETTLEMENT_CHECK_INTERVAL ticks per player instead of
    // every tick. Server thread only (the player tick), so a plain HashMap needs no synchronisation; entries are dropped
    // on logout alongside the other per-player memos.
    private final Map<UUID, Long> lastSettlementCheckTick = new HashMap<>();

    // Players whose autopilot is currently in its DRIVE phase (ascending or flying toward a body), so the runtime
    // "started driving" log fires exactly once per trip and the matching "ended" log fires exactly once. This is
    // transient session state only: the SpaceAutopilot persistent tag is the real source of truth for whether an
    // autopilot exists, and this set merely dedupes the log lines. Server thread only (the player tick), so a plain
    // HashSet needs no synchronisation; entries are dropped on logout alongside the other per-player memos.
    private final Set<UUID> autopilotAnnounced = new HashSet<>();

    // Powered-ascent progress, per player. ascentTick is the index within the fixed ASCENT_TICKS schedule; ascentStartY
    // is the Y the climb began from, captured once on the first ascent tick so the eased height curve spans exactly this
    // climb. Both are transient session state: the SpaceAutopilot persistent tag is the real source of truth for whether
    // a trip exists, and a relog inside the sub-3-second ascent window simply re-captures from the current Y and eases
    // again, which is graceful. Server thread only (the player tick), so plain HashMaps need no synchronisation; entries
    // are dropped on logout and whenever an autopilot ends (see stopAutopilot).
    private final Map<UUID, Integer> ascentTick = new HashMap<>();
    private final Map<UUID, Double> ascentStartY = new HashMap<>();

    // The fixed ascent target column per player: (launch X, entryAltitude, launch Z), captured once on the first ascent
    // tick so both the server integration and the client's synced target climb straight up the same line. Cleared on the
    // cross-into-space teleport, on autopilot end and on logout, alongside the other transient ascent state.
    private final Map<UUID, Vec3> ascentTarget = new HashMap<>();

    // Per-player count of consecutive DRIVE ticks the autopilot has HELD because the chunk the pod is about to enter is
    // not yet loaded server-side (see SpaceRouteChunks.chunkReady). While this is non-zero the pod is not advanced, so a
    // resumed tick simply takes one normal constant-length step with no accumulated burst to lurch. Cleared the instant a
    // tick advances, on stop and on logout. Server thread only (the player tick), so a plain HashMap needs no sync.
    private final Map<UUID, Integer> autopilotHoldTicks = new HashMap<>();

    // Hard bound on the hold. If the pilot's next chunk still has not loaded after this many consecutive held ticks the
    // pod advances ANYWAY (accepting a possible hitch) rather than trapping the player on a stalled trip forever. At 20
    // TPS this is 5 seconds. When it trips we log once and take a single step; if the chunk is still missing next tick the
    // hold begins afresh, so a genuinely unloadable chunk yields slow creep-forward progress instead of a permanent hang.
    private static final int MAX_HOLD_TICKS = 100;

    // The last autopilot sync sent to each player: [targetX, targetY, targetZ, step], plus its dimension in lastSyncDim.
    // We push a PacketSpaceAutopilotSync only when this changes (start, target/step change, cross-into-space), never per
    // tick, which is the whole point of the client-integrated rewrite. Cleared on stop and logout.
    private final Map<UUID, double[]> lastSync = new HashMap<>();
    private final Map<UUID, ResourceLocation> lastSyncDim = new HashMap<>();

    // Powered-ascent duration in ticks: the climb to entryAltitude always spans roughly this many ticks regardless of
    // where it started or how fast the pod is, so a launch reads as roughly the same ~3 seconds every time. 60 ticks = 3s
    // at 20 TPS. The per-tick step is a CONSTANT derived from this budget ((entryAltitude - startY) / ASCENT_TICKS), NOT
    // from the pod's flight speed. Constant (not eased) on purpose now that the client integrates the motion itself: a
    // constant step keeps the client and server integrations in lockstep and keeps the reported move inside the vehicle
    // "moved too quickly" tolerance, which is what actually eliminates the judder.
    private static final int ASCENT_TICKS = 60;

    // A generous safety cap on the ascent tick count. The real exit is the pod crossing entryAltitude; this only guards
    // against a pod that somehow never climbs (e.g. the client travel mixin failed to bind), so the launch still resolves
    // into space rather than hanging forever. Well above ASCENT_TICKS so a slightly slow client is never cut short.
    private static final int ASCENT_TICK_CAP = ASCENT_TICKS * 4;

    // How often, in ticks, the boundary edge action bar may repeat for one player. 40 ticks = 2 seconds: often
    // enough that a player pressed against the wall keeps seeing why they are stuck, rare enough that it no longer
    // rewrites the action bar every single tick.
    private static final long EDGE_MESSAGE_INTERVAL = 40L;

    // How often, in ticks, a player on an inhabited planet is swept for nearby unpopulated saiyan settlements. 20 ticks
    // (1 second) is frequent enough that a settlement fills almost the instant a player nears it, yet rare enough that
    // the (already cheap) getStructureAt sampling is a non-event on the tick loop. The first sweep after arrival runs
    // immediately (no prior entry), so a player who lands inside a settlement sees it populate at once.
    private static final long SETTLEMENT_CHECK_INTERVAL = 20L;

    // Extra reach, in blocks, added to a body's half-extent (PlanetEntity.getVisualRadius(), which is the visual
    // half-size) when testing whether a player has flown into it. A player lands when all three axis distances to
    // the body centre are within the half-extent plus this margin, i.e. inside the body's cube plus a small skin,
    // so brushing the visible surface counts as a landing rather than needing to reach the exact centre.
    private static final double LANDING_MARGIN = 8.0;

    // How far apart the landing checks are sampled along a player's path through space this tick, and the cap on how
    // many samples one tick may cost. 24 is comfortably under the smallest landing half-extent there is (a minimum
    // generated body is 32 + LANDING_MARGIN = 40), so no body can slip between two samples; the cap keeps a single
    // enormous jump (a teleport, not a flight) from turning into an unbounded loop.
    private static final double PATH_SAMPLE_STEP = 24.0;
    private static final int MAX_PATH_SAMPLES = 64;

    // Where each player was in space at the end of the previous tick, so the landing checks can cover the whole path
    // they travelled rather than the single point they ended on. Transient session state: dropped on logout, on
    // leaving space and whenever the cooldown is stamped, so a fresh arrival never sweeps a line back to wherever the
    // player happened to be before.
    private final Map<UUID, Vec3> lastSpacePos = new HashMap<>();

    // Y a player arrives at when they land on a generated planet's surface is resolved by SurfaceStamp (top of the
    // stamped cap). Leaving the surface works like leaving space: fly UP past this altitude and you return to space
    // beside the planet's body. Kept well above the surface play band (SurfaceDimension.SURFACE_Y = 96, cap at most
    // +3) so ordinary movement on the planet never triggers it, and comfortably below the dimension ceiling (320).
    private static final double SURFACE_LEAVE_ALTITUDE = 260.0;

    // How far inside the boundary a bounced player is placed, in blocks, so a single fast tick cannot leave them
    // straddling the edge and re-bouncing every tick. Comfortably larger than one tick of even fast ki flight so the
    // clamp always lands them cleanly inside. Applied per-axis: a violated X or Z is pulled to (half + margin) minus
    // this value, back toward the cell centre.
    private static final double SURFACE_BOUNDARY_PUSHBACK = 3.0;

    // How far outside the planet's half-extent the square boundary sits, in blocks. It is placed a few blocks beyond the
    // terrain rim on purpose, so a player standing on the outermost real column (or a rim column that wobbled outward) is
    // never grabbed by the clamp. The cost of sitting outside the wobbled rim is spelt out on enforceHorizontalBoundary:
    // near an inward-dented part of the rim this margin can sit over open void.
    private static final double SURFACE_BOUNDARY_MARGIN = 6.0;

    // Beerus is now a fixed body whose authored build lives IN the shared generated-planet surface dimension
    // (shuruisutilities:planet_surface), seeded there by PlanetRegionSeeder at its authored absolute coordinates. It is
    // NOT a stamped procedural surface and NOT a claimable sugen planet: it keeps its fixed dimension-id key (below), so
    // it is automatically indestructible (GeneratedPlanets.isDestructible refuses any non-sugen/sumoon id) and the
    // planet-buster, which only ever targets generated bodies, never sees it. The old glass dome and its bespoke leave
    // plane are gone: the dome has been stripped from the build data, so on the surface Beerus is confined by the
    // standard planet boundary (its footprint AABB below) and left by flying up past SURFACE_LEAVE_ALTITUDE, exactly
    // like a generated planet, routed through the same leave path so pod and nimbus handling are reused unchanged.
    static final String BEERUS_KEY = "dmz_ragnarok:beerus_planet";

    // Fixed landing spot inside the Beerus build, matching the coordinates on the DMZ space-pod destination
    // (data/shuruisutilities/spacepod/beerus_planet.json). Held here too so the surface fall-catch can rescue a player
    // back onto solid ground without reparsing the destination. MUST stay in sync with that JSON.
    private static final double BEERUS_LANDING_X = -449.0;
    private static final double BEERUS_LANDING_Y = 8.0;
    private static final double BEERUS_LANDING_Z = -14.0;

    // Y below which a player standing on Beerus is treated as having fallen off the build into the surrounding void and
    // is rescued back to the landing spot. The build sits around Y8; anyone this far below it has left the solid extent.
    private static final double BEERUS_FALL_RESCUE_Y = -32.0;

    // Horizontal AABB of the Beerus build, measured from the seeded region data (solid-block extent). This IS the
    // standard planet boundary for Beerus: the player is confined to it, and it also arms the fly-up leave (only over
    // the build, never above the open void beside it) and gates the surface handling. A cheap axis-aligned test is
    // enough: the exact solid extent covers every part of the build, and just outside it is void.
    private static final double BEERUS_MIN_X = -924.0;
    private static final double BEERUS_MAX_X = 4.0;
    private static final double BEERUS_MIN_Z = -464.0;
    private static final double BEERUS_MAX_Z = 464.0;

    // Y below which a player on the surface is treated as having fallen off the planet and is rescued to the centre
    // column. Set from the real terrain floor (SurfaceStamp.lowestTerrainY()) minus a clearance so a player standing on
    // the lowest legitimate block never trips it, yet anyone who has stepped off the rim or dug straight down through the
    // whole column stack is caught before the dimension floor at -64. Computed LIVE (not captured once) because the
    // column depth is config-driven now: at the default depth (128) the floor is Y -34, so this is Y -50, and a deeper
    // config lowers both together so a player on the lowest block is never falsely rescued.
    private static double surfaceFallRescueY()
    {
        return SurfaceStamp.lowestTerrainY() - 16;
    }

    // A datapack (re)load can change DMZ's space-pod destinations, which is exactly what PlanetRegistry.bodies()
    // reads, so the cached body-key set behind launch eligibility must be dropped when one happens. AddReloadListenerEvent
    // fires on the Forge bus for the initial server-resource load and every /reload, so this keeps eligibility in
    // step with the destinations without polling. Config bake handles the other invalidation path (see PlanetSpawnModule).
    @SubscribeEvent
    public void onAddReloadListeners(net.minecraftforge.event.AddReloadListenerEvent event)
    {
        PlanetRegistry.invalidate();
    }

    // Populate an inhabited planet (Planet Vegeta) AT ITS SETTLEMENTS as a player roams it. An inhabited planet is a REAL
    // datapack dimension, not a stamped cell on the shared surface dimension, so the garrison's SurfaceStamp.whenStamped
    // trigger never fires for it, and a single on-arrival one-shot near spawn is wrong now that the crowd lives out at the
    // saiyan_settlement structures scattered across the planet. Instead this runs on a THROTTLED player tick: while a
    // player is on an inhabited planet it asks the garrison to populate any settlement footprint at or near the player
    // that has not been filled yet. The lookup is cheap (getStructureAt reads only loaded chunk structure references, no
    // forced generation, no wide search), and each settlement is filled exactly once (tracked per settlement position in
    // PlanetGarrisonData), so killed inhabitants never respawn and the cost is spread across the planet as players
    // explore. Gated to the small InhabitedPlanets set (Vegeta today) rather than a hardcoded id check.
    private void maybePopulateNeutralSettlements(ServerPlayer player)
    {
        String dim = player.level().dimension().location().toString();
        if (!InhabitedPlanets.isInhabited(dim))
        {
            return;
        }
        long now = player.level().getGameTime();
        Long last = lastSettlementCheckTick.get(player.getUUID());
        if (last != null && now - last < SETTLEMENT_CHECK_INTERVAL)
        {
            return; // throttle: this runs at most once per SETTLEMENT_CHECK_INTERVAL ticks per player.
        }
        lastSettlementCheckTick.put(player.getUUID(), now);
        MinecraftServer server = player.getServer();
        if (server == null || !(player.level() instanceof ServerLevel level))
        {
            return;
        }
        PlanetGarrison.ensureNeutralSettlements(server, level, dim, player.blockPosition());
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player))
        {
            return;
        }
        if (!enabled || player.isSpectator())
        {
            return;
        }
        // A player already being handed to another shard must not have the space tick run again. Once a cross-server
        // handoff is dispatched (handOffToPlanet, handOffOutOfSpace, handOffToSpace all go through
        // ShardTransfer.connect -> ShardSync.handOff, which marks them TRANSFERRING), the player still sits in the
        // world for a few hundred ms until the proxy disconnects them, and the NEXT tick would re-run tickInSpace and
        // dispatch a SECOND, conflicting handoff. That is exactly the double handoff seen live on 2026-09-14: a space
        // landing on namekow and, in the same instant, leaveSpace -> returnToOverworldSpawn firing a travel into the
        // unhosted overworld, so the player arrived carrying both intents and the overworld one won, dumping them at
        // spawn instead of where they were going. Standing the tick down for the handoff window resolves it to the ONE
        // handoff that was dispatched first, the intended landing. Cheap (a set membership test) and a no-op when the
        // shard system is off.
        if (ShardSync.isHandingOff(player.getUUID()))
        {
            return;
        }
        // A durable guard behind the transient ShardSync one above. Once a cross-server space handoff is marked
        // (SpaceHandoff.mark, used by handOffToSpace / handOffOutOfSpace / handOffToPlanet), the intent sits on the
        // player until the far side consumes it on arrival. While it is pending the player is mid transfer, so the
        // space tick must not run: it would sink the player onto the same body again, re-dispatch a SECOND handoff,
        // re-discard the (already gone) pod and clobber the rebuild flag to false. That is the storm seen live on
        // 2026-09-14, where one landing on namekow/kaiow re-fired the same handoff every ~3 seconds and cost the pilot
        // their pod. ShardSync.isHandingOff clears when the transfer resolves, but the persisted handoff outlives a
        // stalled transfer, so this closes the window the other guard leaves open.
        if (SpaceHandoff.isPending(player))
        {
            if (!SpaceHandoff.expired(player))
            {
                return;
            }
            // the handoff never carried the player across (a stalled transfer left them on the origin shard). Drop the
            // stale intent and, if they were riding a pod that was discarded for the hop and never rebuilt, hand the
            // ship item back so a failed transfer never costs it. handBackPodItem is a no-op when the chip is still in
            // the slot, so an SU pod (which simply re-deploys from the slot) is untouched, and it never duplicates. A
            // carried DMZ ship (podShip) gets DMZ's ship item back, never a chip.
            if (SpaceHandoff.pod(player) && ridingSpacePod(player) == null)
            {
                handBackCarriedPod(player, PodDeployData.hasDeployed(player));
            }
            SpaceHandoff.clear(player);
        }

        // fill saiyan settlements on an inhabited planet (Planet Vegeta) as the player roams it. A no-op off such a
        // planet and throttled while on one; runs outside the cooldown gate below so a player who arrives inside a
        // settlement sees it populate at once rather than waiting out the landing cooldown.
        maybePopulateNeutralSettlements(player);

        Level level = player.level();
        boolean inSpace = SpaceDimension.isSpace(level);

        // AUTOPILOT (pod-only launch) is handled OUTSIDE the cooldown gate below so a fresh launch starts moving at
        // once (enterSpace stamps the cooldown, which would otherwise freeze the drive for 3s) and a relog mid-course
        // resumes immediately. It only ever teleports the POD within space; crossing dimensions (entering space,
        // landing) still goes through the cooldown-gated paths, so the 3s grace still prevents threshold ping-pong.
        if (SpaceAutopilot.isActive(player))
        {
            SpacePodEntity pod = ridingSpacePod(player);
            if (pod == null)
            {
                // dismounted or the pod was destroyed since the course was set: nothing to drag, cancel the autopilot.
                stopAutopilot(player, "no longer riding a space pod");
            }
            else if (inSpace)
            {
                driveAutopilot(player);
            }
            else if (!onCooldown(player) && isEligibleDimension(player.getServer(), level.dimension()))
            {
                // POWERED ASCENT (replaces the old altitude-free instant blink, which teleported the player straight
                // to space the moment they picked a planet). The launch button now STARTS a climb: driveAscent flies
                // the pod UP under power within this dimension and only crosses into space once it passes
                // entryAltitude, at which point it does the single unavoidable cross-dim teleport. A launch deferred
                // by the cooldown simply waits, respecting the 3s grace.
                driveAscent(player, pod);
            }
            // in a forbidden/ineligible dim we leave the course pip but never launch (refused like an on-foot entry).
        }
        else if (SpaceRouteChunks.has(player))
        {
            // no autopilot is active but this player still has a forced route window: release it. This is the safety net
            // for an autopilot that ended WITHOUT going through stopAutopilot, chiefly death (the persistent autopilot
            // tag is not copied onto the respawn clone, so the drive simply stops and no explicit cancel runs). Leaving a
            // window pinned here would be a live-server chunk leak.
            SpaceRouteChunks.release(player);
        }

        if (onCooldown(player))
        {
            return;
        }

        if (inSpace)
        {
            tickInSpace(player);
        }
        else if (SurfaceDimension.isSurface(level))
        {
            tickOnSurface(player);
        }
        else
        {
            tickEligible(player);
        }
    }

    // entry: in an eligible dimension, rising above the entry altitude sends the player to space. A pod pilot now
    // launches WITH the pod (enterSpace spawns a fresh pod in space and reseats them); a nimbus rider is held out
    // of space entirely.
    private void tickEligible(ServerPlayer player)
    {
        if (!isEligibleDimension(player.getServer(), player.level().dimension()))
        {
            return;
        }
        // a nimbus rider must NEVER be pulled into space. DMZ's FlyingNimbusEntity (and its BlackNimbusEntity
        // variant) climbs freely in any dimension, so an altitude trigger would dismount the rider on the
        // cross-dimension teleport and dump them into space on foot with the cloud left behind. This is a LIVE bug
        // today, not just a new rule: the old skip only matched the pod, so a nimbus rider crossing entryAltitude
        // was already being stranded. Stand the trigger down for the whole time they are aboard one.
        if (isRidingNimbus(player))
        {
            return;
        }
        if (player.getY() < entryAltitude)
        {
            return;
        }
        // a pod pilot above the entry altitude now ENTERS space and brings the pod (enterSpace detects the pod and
        // routes to enterSpaceAboardPod). This inverts the old skip: previously a pod was excluded so an idle
        // upward climb on Earth could not yank the pilot into space, but the user has since decided a deliberate
        // high-altitude pod climb IS a launch. The on-foot entry path is unchanged.
        enterSpace(player);
    }

    // is the x/z inside the Beerus build's horizontal footprint? Used both to detect a player standing on the Beerus
    // build within the shared surface dimension and to arm its fly-up leave (never above the open void beside the build).
    private static boolean withinBeerusFootprint(double x, double z)
    {
        return x >= BEERUS_MIN_X && x <= BEERUS_MAX_X && z >= BEERUS_MIN_Z && z <= BEERUS_MAX_Z;
    }

    // in space: landing onto a planet body is checked FIRST, before the descend-out-of-space return. That
    // ordering is deliberate: a player diving down into a body must land on it, never be bounced back to their
    // origin dimension by the return-altitude check. Only if no body is close do we run the return check.
    //
    // BOTH KINDS of body are enumerated here, and neither enumeration touches an entity or a chunk. Fixed bodies come
    // from PlanetRegistry.bodies() (the DMZ destination list) and generated bodies from GeneratedPlanets.bodyContaining
    // (a pure hash of the player's own cell plus its immediate neighbours). Both are cheap: the fixed list is a handful
    // of entries and the generated walk is 27 cells, so this runs comfortably on the per-player tick. Crucially, a
    // generated body no longer depends on its space entity existing to be landable, since bodyContaining re-derives it
    // straight from the position, so a body whose chunk is not even loaded still lands the player the instant they fly
    // into its derived cube. The earlier bug was that ONLY fixed bodies were considered on the landing path; generated
    // bodies now land through the branch just below.
    private void tickInSpace(ServerPlayer player)
    {
        // a MOON the player has flown into is checked FIRST, before its parent body: a moon orbits well outside its
        // parent (2.6x the parent radius), so a player diving toward the planet passes through the moon's cube first,
        // and brushing the visible moon must land them on the moon rather than skip to the planet behind it. The moon's
        // landing volume TRACKS its rendered orbit position (MoonBody.position reproduces the renderer's orbit exactly
        // at the current game time), so flying into the drawn moon always lands. Only fixed bodies carry moons, decided by
        // MoonBody.hasMoon against the shared SpaceLayout.fixedBodies set the renderer's drawMoon reads too.
        // WHERE THE PLAYER HAS BEEN THIS TICK, not just where they ended it. A pod crosses tens of blocks per tick
        // (the autopilot cruise alone is 12, an invested pilot hand-flies faster), and the smallest landable body is
        // 80 blocks across, so a single point sample can step straight over a planet: the player flies into the
        // thing they can see and comes out the other side having "not been let in". Every check below therefore runs
        // at intervals along the path travelled since the last tick, and the FIRST body the path meets wins.
        Vec3 to = player.position();
        Vec3 from = lastSpacePos.getOrDefault(player.getUUID(), to);
        lastSpacePos.put(player.getUUID(), to);
        for (Vec3 sample : pathSamples(from, to))
        {
            if (tryLandAt(player, sample))
            {
                return;
            }
        }
        if (player.getY() > returnAltitude)
        {
            return;
        }
        // the player has sunk below the return altitude without flying into any body. The OLD behaviour returned
        // them to their origin dimension here; the new behaviour instead PULLS THEM DOWN ONTO THE NEAREST LANDABLE
        // BODY, reusing the exact fly-into landing paths above so the arrival is identical (surface resolution,
        // chunk loading, claims, garrison, the landing message). We fall back to the origin return ONLY when there
        // is genuinely no landable body within range, which is far better than dropping the player into the void.
        if (landOnNearestBody(player))
        {
            return;
        }
        leaveSpace(player);
    }

    // Find the NEAREST landable body to the player and land them on it through the SAME path the fly-into route
    // uses, returning true if a landing happened. Returns false (caller then falls back to the origin return) only
    // when nothing landable sits within nearestPlanetRange.
    //
    // "Nearest" is measured to each body's SURFACE, i.e. the straight-line distance to its centre minus that body's
    // visual radius, NOT to its centre. Surface distance is the honest "how far must I travel to touch it", so
    // bodies of very different sizes (a 24-block generated pebble vs a 55-block fixed world vs a small moon) compete
    // fairly: a large body whose centre is a little farther but whose surface is nearer is correctly chosen as the
    // one the player would reach first, and nobody is ever sent past a body they were practically inside.
    //
    // A body is a CANDIDATE only if it is genuinely landable, and the three landable kinds are exactly the three the
    // fly-into route already handles: fixed planet bodies (PlanetRegistry.bodies already drops the nether/end/
    // otherworld and any dimension not loaded on this server), their moons (MoonBody.moonFor, only where a moon
    // truly exists), and LIVE generated planets (GeneratedPlanets.generatedNear already suppresses DESTROYED planets
    // at the source). Stars and black holes live in StarPositions / BlackHolePositions and are in NONE of those
    // sets, so this search can never drop a player into a star or a black hole; a destroyed planet's wreck is
    // likewise never a candidate. Everything selected here is therefore a real, live, landable destination.
    //
    // Per-tick cost: this method runs at most ONCE per descent event, never every tick. The instant it lands (or
    // the caller's fallback returns the player to origin) the player leaves the space dimension AND the cooldown is
    // stamped, so tickInSpace does not run again for that player until they launch afresh. The one scan it does is a
    // handful of fixed bodies plus a bounded generatedNear cell walk (about (2*ceil(range/sector)+1)^3 cells, ~729
    // at the defaults), each cell a couple of cheap hash + SavedData lookups. That is a sub-millisecond burst paid
    // once per trip, not a standing per-tick load.
    private boolean landOnNearestBody(ServerPlayer player)
    {
        MinecraftServer server = player.getServer();
        if (server == null)
        {
            return false;
        }
        Vec3 p = player.position();
        long gameTime = player.level().getGameTime();

        // best-so-far for each landable kind, tracked separately because each kind lands through its own path and
        // carries its own type. We pick the single nearest across all three at the end and dispatch to its path.
        PlanetRegistry.Planet bestFixed = null;
        double bestFixedDist = Double.MAX_VALUE;
        MoonBody.Moon bestMoon = null;
        double bestMoonDist = Double.MAX_VALUE;

        // fixed bodies (and their moons) are a tiny list, so we walk all of them and keep the nearest of each whose
        // SURFACE is within range. A fixed body's moon orbits it, so it is only ever near when its parent is near.
        for (PlanetRegistry.Planet body : PlanetRegistry.bodies(server))
        {
            double d = surfaceDistance(p, body.position(), body.radius());
            if (d <= nearestPlanetRange && d < bestFixedDist)
            {
                bestFixedDist = d;
                bestFixed = body;
            }
            MoonBody.Moon moon = MoonBody.moonFor(server, body.key, body.position(), body.radius(), gameTime);
            if (moon != null)
            {
                double md = surfaceDistance(p, moon.position, moon.radius);
                if (md <= nearestPlanetRange && md < bestMoonDist)
                {
                    bestMoonDist = md;
                    bestMoon = moon;
                }
            }
        }

        // generated bodies within range. generatedNear filters by CENTRE distance, so we widen its gather by one max
        // body radius (a body whose centre sits just past the range can still present a surface inside the range),
        // then apply the exact surface-distance rule ourselves so the range means the same thing for every kind.
        GeneratedPlanets.Generated bestGen = null;
        double bestGenDist = Double.MAX_VALUE;
        double gather = nearestPlanetRange + GeneratedPlanets.maxBodyRadius();
        for (GeneratedPlanets.Generated g : GeneratedPlanets.generatedNear(server, p, gather))
        {
            double d = surfaceDistance(p, g.position, g.radius);
            if (d <= nearestPlanetRange && d < bestGenDist)
            {
                bestGenDist = d;
                bestGen = g;
            }
        }

        // pick the single nearest across the three kinds and land through that kind's own path. Ties are resolved in
        // a fixed order (generated, then moon, then fixed) purely so the choice is deterministic; a genuine tie in
        // surface distance is vanishingly unlikely given the hashed positions.
        double best = Math.min(bestGenDist, Math.min(bestMoonDist, bestFixedDist));
        if (best == Double.MAX_VALUE)
        {
            // nothing landable within range: let the caller fall back to the origin return.
            return false;
        }

        // tell the player WHY they are being moved before the land path fires. Sent as CHAT (not the action bar) on
        // purpose: the land path below sets its own action-bar "landed on X" line this same tick, and the action bar
        // only shows the last value set, so a why-message on the action bar would be invisible. Chat gives the
        // player both, and the reason persists in the log.
        player.displayClientMessage(
                Component.translatable("message.dmz_ragnarok.core.space_sank_to_nearest"), false);

        if (best == bestGenDist)
        {
            landOnGeneratedPlanet(player, bestGen);
        }
        else if (best == bestMoonDist)
        {
            landOnMoon(player, bestMoon);
        }
        else
        {
            landOnPlanet(player, bestFixed);
        }
        return true;
    }

    // straight-line distance from a point to a body's SURFACE: distance to the body centre minus its visual radius,
    // floored at 0 so a point already inside the body reads as 0 rather than a negative value. This is the single
    // distance metric landOnNearestBody ranks every landable kind by, so "nearest" means the same thing for a fixed
    // world, a moon and a generated planet alike.
    private static double surfaceDistance(Vec3 point, Vec3 centre, double radius)
    {
        double d = point.distanceTo(centre) - radius;
        return d < 0.0 ? 0.0 : d;
    }

    // on a generated planet's surface: the radial rim boundary keeps the player on their own planet, a fall-catch
    // recovers anyone who has dropped off the rim or dug through the terrain, and flying UP past SURFACE_LEAVE_ALTITUDE
    // returns them to space beside that planet's body. Ordering matters: the leave check reads Y and runs FIRST so an
    // escaping climb is never interrupted, so the rim boundary is free to raise Y (ground-snapping a bounce out of void)
    // without ever fighting the climb. Phase 1's return-to-ORIGIN logic lives on the SPACE branch (leaveSpace), a
    // different dimension, so it cannot fire here.
    private void tickOnSurface(ServerPlayer player)
    {
        // Beerus is an authored build residing in this shared surface dimension at a FIXED footprint (surface cell 0,0),
        // not a stamped sugen planet, so it is handled by its own footprint-based branch BEFORE the generated-planet
        // logic. This must run first: the generated path derives its boundary from SurfaceTravelData.planetId / the cell
        // fold, neither of which describes Beerus, so letting it run for a player on Beerus would confine them to some
        // unrelated cell. handleBeerusSurface owns its own boundary (the footprint AABB), fall-catch and fly-up leave and
        // never touches SurfaceTravelData, which is exactly why Beerus stays unclaimable (the claim command needs a
        // recorded planet id).
        if (withinBeerusFootprint(player.getX(), player.getZ()))
        {
            handleBeerusSurface(player);
            return;
        }

        String planetId = SurfaceTravelData.planetId(player);
        if (!planetId.isEmpty() && !standsInCellOf(player, planetId))
        {
            // STALE record (bugs #1099 #1100 #985 #966 #898 #1069): the record is only cleared by a normal fly-up leave,
            // so a /home, guild home, warp or /tp out of the surface keeps it, and a later arrival on a DIFFERENT planet
            // still reads the old id. The rim clamp below would then drag the player across the grid to the old planet's
            // square corner (open void beside its disc), from where the fall-catch drops them on the old planet: the
            // wrong planet, the void, or stuck in place. A planet's whole rim sits far inside its own cell (half-extent
            // at most a few thousand blocks against a 65,536 block spacing), so a player standing in another cell cannot
            // be on the recorded planet. Drop the record and re-resolve from the position below, exactly like an
            // arrival with no record; if that cell holds no stamped surface the player is left where they are.
            SurfaceTravelData.clear(player);
            planetId = "";
        }
        if (planetId.isEmpty())
        {
            // no recorded planet. This is now the COMMON case, not an admin-only edge: only a normal landing writes
            // SurfaceTravelData, so ANY other arrival (a /tp to a player standing on a planet, chiefly, but also a login
            // or respawn onto the surface) reaches here with no record. We RESOLVE the planet from the player's position
            // and record it (adoptSurfacePlanet), after which the normal path below, and every following tick, treats
            // them exactly like a player who landed: the rim boundary confines them and a fly-up returns them to space.
            // Only if the resolve finds nothing (a genuinely empty coordinate with no stamped surface) do we keep the
            // old defensive behaviour: let them fly up to escape to a safe overworld return, and skip the boundary.
            planetId = adoptSurfacePlanet(player);
            if (planetId.isEmpty())
            {
                if (player.getY() > SURFACE_LEAVE_ALTITUDE)
                {
                    stampCooldown(player);
                    returnToOverworldSpawn(player, player.getServer());
                    SurfaceTravelData.clear(player);
                }
                return;
            }
            // resolved and recorded: fall through and run the normal surface logic with the resolved id THIS tick.
        }

        // fly up to leave. Checked before the boundary so an escaping climb is never interrupted by a horizontal
        // bounce. A player who is an ACTIVE RAIDER is a special case: for them, leaving the planet is a FORFEIT, not
        // a normal return to space. We route the fly-up into the raid manager, which resolves the raid as a loss and
        // (through the loss path) restores their items and teleports them home, so the fleeing raider is never
        // silently dropped into space with their inventory still stashed.
        if (player.getY() > SURFACE_LEAVE_ALTITUDE)
        {
            if (net.shurui.shuruisutilities.guilds.raid.GuildRaidParticipant.isRaider(player))
            {
                // the raid manager lives in the Ragnarok Key; keyless no raid is running, so this is false
                if (net.shurui.shuruisutilities.api.key.GuildRaidHooks.get().forfeitByLeaving(player.getUUID()))
                {
                    stampCooldown(player);
                    player.displayClientMessage(
                            Component.translatable("message.dmz_ragnarok.core.guild_raid_forfeit"), false);
                    return;
                }
                // no live raid backed the flag (already resolved, or an admin moved them): clear the stale flag and
                // fall through to the ordinary leave so the player is never trapped on the surface.
                net.shurui.shuruisutilities.guilds.raid.GuildRaidParticipant.clear(player);
            }
            leaveSurface(player, planetId);
            return;
        }

        // fall-catch: a player below the terrain floor has either stepped off the rim (open void beside the disc) or dug
        // straight down through the ~11-block column stack, both trivially reachable and otherwise unrecoverable (they
        // fall to the vanilla out-of-world death at Y -128 or hang under the planet). Rescue them to the centre column,
        // which is pinned solid, before the rim boundary runs: once they are back on the ground the boundary is a no-op.
        if (rescueIfFallen(player, planetId))
        {
            return;
        }

        enforceHorizontalBoundary(player, planetId);
    }

    // resolve which generated planet a player on the surface is standing on FROM THEIR POSITION, and record it on them
    // (SurfaceTravelData), so a player who reached the surface without a landing (a /tp, a login or respawn onto it) is
    // confined by the rim and returns to space just like one who landed. Returns the resolved id, or "" if the cell holds
    // no stamped surface. The id->cell fold in SurfaceDimension is one-way, so we do NOT invert the id: we invert the
    // CELL. A cell centre sits at index * CELL_SPACING + 0.5 (see SurfaceDimension.cellCentre), so the player's grid
    // index is Math.round(pos / CELL_SPACING), done in double/long, never float, because a far cell centre is near 29
    // million blocks and a 32-bit float would lose metres there. We then match that cell against every id that has a
    // STAMPED surface: a surface exists only where it was stamped, and stamping is exactly what records the flag, so any
    // planet a player can be standing on is in that set. Cheap: the set is tiny (one entry per stamped planet) and the
    // per-id cell is two hash folds; and we scan at most ONCE per missing record, because a hit writes the record (the
    // normal path takes over next tick) and a miss is memoised per cell in surfaceResolveMiss so we never rescan an
    // empty coordinate every tick.
    private String adoptSurfacePlanet(ServerPlayer player)
    {
        MinecraftServer server = player.getServer();
        if (server == null)
        {
            return "";
        }
        long cellX = Math.round(player.getX() / (double) SurfaceDimension.CELL_SPACING);
        long cellZ = Math.round(player.getZ() / (double) SurfaceDimension.CELL_SPACING);
        long packed = packCell(cellX, cellZ);

        // already scanned this exact cell for this player and found nothing: do not rescan every tick.
        Long lastMiss = surfaceResolveMiss.get(player.getUUID());
        if (lastMiss != null && lastMiss == packed)
        {
            return "";
        }

        String resolved = resolveStampedPlanetId(server, cellX, cellZ);
        if (resolved.isEmpty())
        {
            surfaceResolveMiss.put(player.getUUID(), packed);
            return "";
        }
        surfaceResolveMiss.remove(player.getUUID());

        // the SPACE body to return beside. Prefer the real body, derived by searching the space cells near a live anchor
        // (GeneratedPlanets.findGenerated), so leaving puts the player back beside the actual planet in space. If no
        // anchor is near enough to derive it (nobody is in space or standing on that planet with a recorded body), we
        // record the id with NO body: leaveSurface then drops them on the standard space arrival column, a graceful
        // fallback. We deliberately do NOT fall back to the surface cell centre the way GuildRaid.bringRaiderIn does:
        // that centre is a surface-dimension coordinate near 29 million blocks and, unlike a raider (whose fly-up is a
        // forfeit that never reaches leaveSurface), this player's fly-up DOES reach leaveSurface, which would fling them
        // to that far coordinate in space.
        GeneratedPlanets.Generated body = GeneratedPlanets.findGenerated(server, resolved);
        if (body != null)
        {
            SurfaceTravelData.setPlanet(player, resolved, body.position.x, body.position.y, body.position.z);
        }
        else
        {
            SurfaceTravelData.setPlanetWithoutBody(player, resolved);
        }
        // a player who reached the surface without a normal landing (a /tp, a login/respawn onto it) still triggers the
        // lazy garrison population, so a planet reached by any path gets its defenders exactly once. The player is
        // standing on the surface, so its chunks are loaded.
        PlanetGarrison.ensurePopulated(server, SurfaceDimension.level(server), resolved);
        return resolved;
    }

    // the stamped-surface planet id whose cell matches (cellX, cellZ), or "" if none. Scans the (tiny) stamped-surface
    // set, computing each id's cell through the same SurfaceDimension.cellX/cellZ fold the landing and boundary use, so
    // the match is exact and needs no id inversion.
    private static String resolveStampedPlanetId(MinecraftServer server, long cellX, long cellZ)
    {
        GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);
        for (String id : claims.surfaceGeneratedIds())
        {
            if (SurfaceDimension.cellX(id) == cellX && SurfaceDimension.cellZ(id) == cellZ)
            {
                return id;
            }
        }
        return "";
    }

    // true when the player's position falls in the surface cell of planetId, the cell every landing, the stamp and the
    // rim boundary all centre on. Same rounding adoptSurfacePlanet inverts a position with.
    private static boolean standsInCellOf(ServerPlayer player, String planetId)
    {
        return SurfaceDimension.cellIndexOf(player.getX()) == SurfaceDimension.cellX(planetId)
                && SurfaceDimension.cellIndexOf(player.getZ()) == SurfaceDimension.cellZ(planetId);
    }

    // pack a signed cell (cellX, cellZ) into one long key for the miss memo. Cell indices live in +/-GRID_HALF (450), so
    // each fits a signed 32-bit half with room to spare.
    private static long packCell(long cellX, long cellZ)
    {
        return (cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }

    // drop a disconnecting player's miss memo so the map does not accumulate stale entries for players who logged off
    // while sitting on an unresolvable coordinate. Pure hygiene: the memo is a per-session hint, not persisted state.
    @SubscribeEvent
    public void onPlayerLoggedOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event)
    {
        surfaceResolveMiss.remove(event.getEntity().getUUID());
        lastEdgeMessageTick.remove(event.getEntity().getUUID());
        lastSettlementCheckTick.remove(event.getEntity().getUUID());
        // drop the log-dedupe entry; the SpaceAutopilot persistent tag survives logout (an interrupted trip resumes),
        // and logAutopilotStart will re-log the "driving" line on the first drive tick after the player logs back in.
        autopilotAnnounced.remove(event.getEntity().getUUID());
        // drop the transient ascent schedule: a trip interrupted mid-ascent re-captures its start Y and its target
        // column afresh on the first drive tick after relog, so nothing needs to persist here.
        ascentTick.remove(event.getEntity().getUUID());
        ascentStartY.remove(event.getEntity().getUUID());
        ascentTarget.remove(event.getEntity().getUUID());
        autopilotHoldTicks.remove(event.getEntity().getUUID());
        lastSpacePos.remove(event.getEntity().getUUID());
        // drop the sync memo so a resumed trip re-sends the autopilot sync on its first drive tick after relog, and
        // release any route chunk window this player had forced so a logout mid-trip never leaks pinned chunks. We do
        // NOT send an inactive sync to a player who is leaving; the autopilot persistent tag survives and resumes.
        lastSync.remove(event.getEntity().getUUID());
        lastSyncDim.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer sp)
        {
            SpaceRouteChunks.release(sp);
        }
    }

    // recover a player who has fallen below the terrain floor. Returns true if a rescue happened (the caller then skips
    // the rim boundary this tick, there being no horizontal edge to enforce once they are re-seated at the centre).
    // Ground-snaps to the centre column (pinned solid, see SurfaceStamp.capHeight), zeroes their fall so the drop deals
    // no damage, kills their momentum so ki flight does not fling them straight back off, and tells them why they moved.
    // Deliberately does NOT stamp the cooldown: repeated falls must each be caught, and the cooldown would suppress the
    // second save. The 3s landing cooldown already covers the moments right after arriving at the centre.
    private boolean rescueIfFallen(ServerPlayer player, String planetId)
    {
        if (player.getY() >= surfaceFallRescueY())
        {
            return false;
        }
        ServerLevel surface = (ServerLevel) player.level();
        Vec3 centre = SurfaceDimension.cellCentre(planetId);
        Vec3 snapped = SurfaceSnap.snap(surface, centre.x, centre.z);
        player.teleportTo(surface, snapped.x, snapped.y, snapped.z, player.getYRot(), player.getXRot());
        player.setDeltaMovement(0.0, 0.0, 0.0);
        player.resetFallDistance();
        player.hurtMarked = true;
        player.displayClientMessage(
                Component.translatable("message.dmz_ragnarok.core.space_surface_fell"), true);
        return true;
    }

    // an axis-aligned SQUARE boundary at the planet's half-extent plus a fixed margin, the older, looser confinement we
    // returned to after the radial rim wall proved too tight. Pinned exactly to the wobbled rim, that wall bounced a
    // player standing on a slightly inward-dented rim column every tick and trapped them in place, unable to move. The
    // square reads the SAME half-extent the stamp sizes the disc from (GeneratedPlanets.surfaceSizeForId / 2, half the
    // side), then clamps X and Z independently into [centre - (half + margin), centre + (half + margin)]. Because the
    // margin sits OUTSIDE the wobbled rim, this is deliberately loose: near an inward dent a player CAN walk a little way
    // off the disc into open void before the square stops them. That case is NOT handled here, and is not meant to be:
    // it is caught by the fall-catch run just above this call (rescueIfFallen), which teleports a player who has dropped
    // below the terrain floor back to the pinned-solid centre column. Clamps the POSITION, not the velocity, so even a
    // tick that overshoots the wall entirely is pulled back the next tick, and the common case (inside the square on
    // both axes) returns immediately with no work. Raises Y (ground-snapping the clamp so a player pushed back out of
    // open void beside the disc lands on the terrain rather than in the air) but never lowers it: the max with the
    // current Y preserves a legitimate fly-up climb, and tickOnSurface has already run the leave check above this call,
    // so the ordering the surface comment promises is intact.
    private void enforceHorizontalBoundary(ServerPlayer player, String planetId)
    {
        // staff are exempt from the rim clamp: a creative-mode flyer (matching SpaceHazardModule's convention at line
        // 80), an opped player, or a vanished admin. They are here to build or moderate and being clamped and frozen at
        // the edge every tick is exactly the reported bug. The exemption sits at the TOP OF THE CLAMP, not at the
        // onPlayerTick gate, on purpose: the leave check and the fall-catch above this call still run for them, so a
        // creative staffer keeps the fly-up-to-leave and the fall rescue. Only the horizontal confinement is skipped.
        if (isBoundaryExempt(player))
        {
            return;
        }
        Vec3 centre = SurfaceDimension.cellCentre(planetId);
        // the STAMPED half (falls back to the derived half for a fresh planet), so the wall tracks the size the terrain
        // was actually built at rather than a re-derived one, which the persisted size keeps stable across a later
        // change to the derived formula.
        double half = GeneratedPlanetClaims.stampedSizeForId(player.getServer(), planetId) / 2.0;
        double limit = half + SURFACE_BOUNDARY_MARGIN;

        double minX = centre.x - limit;
        double maxX = centre.x + limit;
        double minZ = centre.z - limit;
        double maxZ = centre.z + limit;
        double x = player.getX();
        double z = player.getZ();
        if (x >= minX && x <= maxX && z >= minZ && z <= maxZ)
        {
            // inside the square on both axes: the common every-tick case for every player on a surface. Do no work.
            return;
        }

        // clamp each violated axis to the pushback line just inside the boundary (limit minus the pushback), so a single
        // fast tick cannot leave the player straddling the wall and re-bouncing every tick. An axis inside its range is
        // left at the player's own coordinate, so a player over the edge on one axis only slides in along that axis.
        double nx = x;
        double nz = z;
        if (x < minX)
        {
            nx = centre.x - (limit - SURFACE_BOUNDARY_PUSHBACK);
        }
        else if (x > maxX)
        {
            nx = centre.x + (limit - SURFACE_BOUNDARY_PUSHBACK);
        }
        if (z < minZ)
        {
            nz = centre.z - (limit - SURFACE_BOUNDARY_PUSHBACK);
        }
        else if (z > maxZ)
        {
            nz = centre.z + (limit - SURFACE_BOUNDARY_PUSHBACK);
        }

        // ground-snap the clamp and keep the higher of that and their current Y: never drop a climbing player, never
        // leave a bounced-from-void player hanging above (or below) the ground.
        ServerLevel surface = (ServerLevel) player.level();
        Vec3 snapped = SurfaceSnap.snap(surface, nx, nz);
        double ny = Math.max(player.getY(), snapped.y);
        player.teleportTo(surface, nx, ny, nz, player.getYRot(), player.getXRot());
        // kill any horizontal momentum so ki flight does not carry them straight back into the wall next tick, and zero
        // the fall so a bounce that raised then dropped their footing never deals fall damage.
        Vec3 v = player.getDeltaMovement();
        player.setDeltaMovement(0.0, v.y, 0.0);
        player.resetFallDistance();
        player.hurtMarked = true;
        // throttle the edge notice: the clamp can fire every tick a player holds against the wall, so send the action
        // bar at most once per EDGE_MESSAGE_INTERVAL rather than rewriting it every tick.
        long now = player.level().getGameTime();
        Long last = lastEdgeMessageTick.get(player.getUUID());
        if (last == null || now - last >= EDGE_MESSAGE_INTERVAL)
        {
            lastEdgeMessageTick.put(player.getUUID(), now);
            player.displayClientMessage(
                    Component.translatable("message.dmz_ragnarok.core.space_surface_edge"), true);
        }
    }

    // true when the player must NOT be clamped by the rim boundary: a creative-mode flyer (matching
    // SpaceHazardModule's spectator/creative exemption), an opped player, or a vanished admin. Read fresh each call
    // so toggling gamemode/op/vanish takes effect immediately with no cached state.
    private boolean isBoundaryExempt(ServerPlayer player)
    {
        if (player.isCreative() || player.hasPermissions(2))
        {
            return true;
        }
        MinecraftServer server = player.getServer();
        return server != null && VanishStorage.get(server).contains(player.getUUID());
    }

    // per-tick handling for a player standing on the Beerus build inside the shared surface dimension. Mirrors
    // tickOnSurface's ordering for a generated planet, minus everything that is sugen/claim specific: fly up past the
    // standard SURFACE_LEAVE_ALTITUDE to leave to space beside the Beerus body, otherwise a fall-catch recovers a player
    // who has dropped off the build into the surrounding void, otherwise the footprint AABB confines them (the standard
    // planet boundary, Beerus's replacement for the old glass dome). Staff (creative/op/vanish) are exempt from the
    // confinement just like on a generated surface, but keep the leave and the fall-catch.
    private void handleBeerusSurface(ServerPlayer player)
    {
        // fly up to leave, checked first so an escaping climb is never interrupted by a horizontal bounce.
        if (player.getY() > SURFACE_LEAVE_ALTITUDE)
        {
            leaveBeerus(player);
            return;
        }

        // fall-catch: a player below the build who has stepped off the rim into the void is rescued to the landing spot,
        // ground-snapped, with their fall zeroed so the drop deals no damage and their momentum killed so ki flight does
        // not fling them straight back off.
        if (player.getY() < BEERUS_FALL_RESCUE_Y)
        {
            ServerLevel surface = (ServerLevel) player.level();
            Vec3 snapped = SurfaceSnap.snap(surface, BEERUS_LANDING_X, BEERUS_LANDING_Z);
            double y = Math.max(snapped.y, BEERUS_LANDING_Y);
            player.teleportTo(surface, BEERUS_LANDING_X, y, BEERUS_LANDING_Z, player.getYRot(), player.getXRot());
            player.setDeltaMovement(0.0, 0.0, 0.0);
            player.resetFallDistance();
            player.hurtMarked = true;
            player.displayClientMessage(
                    Component.translatable("message.dmz_ragnarok.core.space_surface_fell"), true);
            return;
        }

        // horizontal confinement: the footprint AABB is the standard planet boundary for Beerus. Staff are exempt, as on
        // a generated surface. A player already inside is the common every-tick case and does no work.
        if (isBoundaryExempt(player))
        {
            return;
        }
        double x = player.getX();
        double z = player.getZ();
        double minX = BEERUS_MIN_X + SURFACE_BOUNDARY_MARGIN;
        double maxX = BEERUS_MAX_X - SURFACE_BOUNDARY_MARGIN;
        double minZ = BEERUS_MIN_Z + SURFACE_BOUNDARY_MARGIN;
        double maxZ = BEERUS_MAX_Z - SURFACE_BOUNDARY_MARGIN;
        if (x >= minX && x <= maxX && z >= minZ && z <= maxZ)
        {
            return;
        }
        double nx = x < minX ? minX + SURFACE_BOUNDARY_PUSHBACK : (x > maxX ? maxX - SURFACE_BOUNDARY_PUSHBACK : x);
        double nz = z < minZ ? minZ + SURFACE_BOUNDARY_PUSHBACK : (z > maxZ ? maxZ - SURFACE_BOUNDARY_PUSHBACK : z);
        ServerLevel surface = (ServerLevel) player.level();
        Vec3 snapped = SurfaceSnap.snap(surface, nx, nz);
        double ny = Math.max(player.getY(), snapped.y);
        player.teleportTo(surface, nx, ny, nz, player.getYRot(), player.getXRot());
        Vec3 v = player.getDeltaMovement();
        player.setDeltaMovement(0.0, v.y, 0.0);
        player.resetFallDistance();
        player.hurtMarked = true;
        long now = player.level().getGameTime();
        Long last = lastEdgeMessageTick.get(player.getUUID());
        if (last == null || now - last >= EDGE_MESSAGE_INTERVAL)
        {
            lastEdgeMessageTick.put(player.getUUID(), now);
            player.displayClientMessage(
                    Component.translatable("message.dmz_ragnarok.core.space_surface_edge"), true);
        }
    }

    // leave the Beerus build: return to space beside the Beerus space body. The body position is a pure PlanetPositions
    // hash of the fixed Beerus key (no entity, no stored state), so leaving always lands beside the drawn body exactly
    // like leaving a generated surface, without Beerus ever recording a SurfaceTravelData planet id.
    private void leaveBeerus(ServerPlayer player)
    {
        MinecraftServer server = player.getServer();
        ServerLevel space = SpaceDimension.level(server);
        if (space == null)
        {
            return;
        }
        stampCooldown(player);
        Vec3 bodyPos = PlanetPositions.position(BEERUS_KEY);
        double standoff = PlanetPositions.radius(BEERUS_KEY) + PLANET_STANDOFF;
        Vec3 target = new Vec3(bodyPos.x + standoff, bodyPos.y, bodyPos.z);
        float yaw = yawTowards(target, bodyPos);
        player.teleportTo(space, target.x, target.y, target.z, yaw, player.getXRot());
        player.displayClientMessage(
                Component.translatable("message.dmz_ragnarok.core.space_left_surface"), true);
    }

    // guard the authored Beerus build from being overwritten by a procedural stamp. Beerus resides at surface cell
    // (0,0); a generated planet, moon or super body whose id happens to fold to that same cell (a ~1-in-810,000 event
    // per id, since cellX and cellZ each fold to 1 of 900 values) would otherwise stamp terrain straight over the
    // build. Such a body that has NOT already been stamped is refused here, protecting the build at the cost of that one
    // astronomically rare body being unlandable. A body already stamped there in a pre-existing world (before Beerus
    // moved in) is left landable, since Beerus's copy-if-absent seeding would have skipped an already-occupied cell.
    // Returns true when the landing was refused (the caller must then return).
    private boolean refuseIfBeerusReservedCell(ServerPlayer player, MinecraftServer server, String id)
    {
        if (SurfaceDimension.cellX(id) != 0 || SurfaceDimension.cellZ(id) != 0)
        {
            return false;
        }
        if (server != null && GeneratedPlanetClaims.get(server).isSurfaceGenerated(id))
        {
            return false;
        }
        stampCooldown(player);
        player.displayClientMessage(
                Component.translatable("message.dmz_ragnarok.core.space_cell_reserved"), true);
        return true;
    }

    // leave a generated planet's surface: return to space beside that planet's body, exactly like arriving beside a
    // fixed body on launch. The body's space position was recorded on landing (SurfaceTravelData), because it derives
    // from the planet's SECTOR while the id embeds only the sector hash, so it cannot be recovered from the id alone
    // without a search. We place the player at a standoff from the recorded body, face them at it, clear the surface
    // record and stamp the cooldown so the arrival cannot instantly re-trigger.
    private void leaveSurface(ServerPlayer player, String planetId)
    {
        MinecraftServer server = player.getServer();
        ServerLevel space = SpaceDimension.level(server);
        if (space == null)
        {
            return;
        }
        stampCooldown(player);

        double x;
        double y;
        double z;
        float yaw = player.getYRot();
        if (SurfaceTravelData.hasBody(player))
        {
            Vec3 bodyPos = new Vec3(
                    SurfaceTravelData.bodyX(player), SurfaceTravelData.bodyY(player), SurfaceTravelData.bodyZ(player));
            // stand off along +X by the surface size (a generous, size-scaled gap) so the whole body reads in view.
            double standoff = GeneratedPlanetClaims.stampedSizeForId(server, planetId) + PLANET_STANDOFF;
            Vec3 target = new Vec3(bodyPos.x + standoff, bodyPos.y, bodyPos.z);
            x = target.x;
            y = target.y;
            z = target.z;
            yaw = yawTowards(target, bodyPos);
        }
        else
        {
            // no recorded body (should not happen: set on landing): arrive on the standard space arrival column rather
            // than strand the player. They can still fly down to return to origin from here.
            x = 0.0;
            y = SPACE_ARRIVAL_Y;
            z = 0.0;
        }
        SurfaceTravelData.clear(player);
        player.teleportTo(space, x, y, z, yaw, player.getXRot());
        player.displayClientMessage(
                Component.translatable("message.dmz_ragnarok.core.space_left_surface"), true);
    }

    // land the player on a generated planet's surface: stamp the surface once (SurfaceStamp records the generated flag
    // so it is built exactly once per planet), teleport them to the cell CENTRE ground-snapped (the locked "land at
    // centre" behaviour, distinct from the DMZ-destination spawn the fixed bodies use), record which planet they are on
    // plus the body to return beside, and stamp the cooldown.
    private void landOnGeneratedPlanet(ServerPlayer player, GeneratedPlanets.Generated planet)
    {
        MinecraftServer server = player.getServer();
        ServerLevel surface = SurfaceDimension.level(server);
        if (surface == null)
        {
            // datapack dim missing (should never happen): do nothing rather than strand the player.
            return;
        }
        if (refuseIfBeerusReservedCell(player, server, planet.id))
        {
            return;
        }
        Vec3 landing = SurfaceStamp.ensureAndLandingPos(server, surface, planet.id);
        stampCooldown(player);
        SurfaceTravelData.setPlanet(player, planet.id, planet.position.x, planet.position.y, planet.position.z);
        // the space trip is over: drop the space-return origin so a future launch records a fresh one.
        SpaceTravelData.clear(player);
        // bring a ridden pod through the teleport that follows: it is rebuilt under the player on arrival, so
        // landing never costs them the pod. No-op when on foot.
        carryPodThroughTeleport(player);
        player.teleportTo(surface, landing.x, landing.y, landing.z, player.getYRot(), player.getXRot());

        // lazily populate this planet's wild GARRISON the first time anyone arrives. Deferred until the surface is FULLY
        // stamped: the garrison scatters defenders out toward the rim, which does not exist yet while the batched stamp
        // is mid-fill, so populating early would drop defenders into unstamped void. whenStamped runs it immediately for
        // an already-stamped planet and otherwise fires it on stamp completion. Idempotent, a no-op for a claimed/
        // destroyed/already-populated planet; it never throws (see PlanetGarrison.ensurePopulated).
        SurfaceStamp.whenStamped(server, planet.id, () -> PlanetGarrison.ensurePopulated(server, surface, planet.id));

        Component name = Component.literal(GeneratedPlanets.nameFor(planet.id));
        Component owner = ownerLabel(server, planet.id);
        player.displayClientMessage(
                Component.translatable("message.dmz_ragnarok.core.space_landed_generated", name, owner), true);
    }

    // land the player on a SUPER body's surface. Reuses the generated-planet surface path wholesale: the same batched
    // stamp (SurfaceStamp keys purely on the id, so a super body gets its own themed cell like any other id, at a modest
    // 100..500 derived surface size that is far cheaper than the enormous space body), the same land-at-centre behaviour
    // and the same SurfaceTravelData record, so leaving returns beside the drawn super body. The two things unique to a
    // super body are the REVEAL (mark the body discovered and resync so every client repaints it from grey to its
    // dragon-ball colour) and the guardian: the Destroyer God is spawned once the surface is fully stamped.
    private void landOnSuperPlanet(ServerPlayer player, String superId)
    {
        MinecraftServer server = player.getServer();
        ServerLevel surface = SurfaceDimension.level(server);
        if (surface == null)
        {
            // datapack dim missing (should never happen): do nothing rather than strand the player.
            return;
        }
        Vec3 bodyPos = SuperPlanetData.get(server).position(superId);
        if (bodyPos == null)
        {
            return;
        }
        if (refuseIfBeerusReservedCell(player, server, superId))
        {
            return;
        }
        Vec3 landing = SurfaceStamp.ensureAndLandingPos(server, surface, superId);
        stampCooldown(player);
        SurfaceTravelData.setPlanet(player, superId, bodyPos.x, bodyPos.y, bodyPos.z);
        // the space trip is over: drop the space-return origin so a future launch records a fresh one.
        SpaceTravelData.clear(player);
        // bring a ridden pod through the teleport that follows: it is rebuilt under the player on arrival, so
        // landing never costs them the pod. No-op when on foot.
        carryPodThroughTeleport(player);
        player.teleportTo(surface, landing.x, landing.y, landing.z, player.getYRot(), player.getXRot());

        // spawn (or reconcile) the Destroyer God once the surface is fully stamped, so it never drops into unstamped void.
        // whenStamped runs immediately for an already-stamped body and otherwise fires on completion. ensureGuardian is a
        // no-op if the ball already dropped (body claimed) or a god is already standing, so a revisit never spawns a
        // second. There is no reveal any more: the body stays grey until its god is beaten and the ball claimed.
        SurfaceStamp.whenStamped(server, superId,
                () -> SuperPlanetGod.ensureGuardian(server, surface, superId));

        player.displayClientMessage(
                Component.translatable("message.dmz_ragnarok.core.space_landed_super"), true);
    }

    // land the player on a MOON's surface. A moon reuses the generated-planet surface path wholesale: the same stamp
    // (SurfaceStamp keys purely on the id, so a moon gets its own themed cell like any other id), the same land-at-centre
    // behaviour and the same SurfaceTravelData record, so the /spaceplanet command and the guild Planet tab work on a
    // moon with no new code. The one difference from landOnGeneratedPlanet is the body we record to RETURN beside: we
    // record the PARENT planet's position, not the moon's, so leaving the moon puts the player back beside the parent
    // world (a stable landmark) rather than beside a moon that has since orbited away.
    private void landOnMoon(ServerPlayer player, MoonBody.Moon moon)
    {
        MinecraftServer server = player.getServer();
        ServerLevel surface = SurfaceDimension.level(server);
        if (surface == null)
        {
            // datapack dim missing (should never happen): do nothing rather than strand the player.
            return;
        }
        if (refuseIfBeerusReservedCell(player, server, moon.id))
        {
            return;
        }
        // the parent body to return beside: the moon id embeds the parent key, so we re-derive the parent's space
        // position from the registry (a pure PlanetPositions hash), never from an entity.
        Vec3 parentPos = PlanetPositions.position(MoonBody.parentKeyOf(moon.id));

        Vec3 landing = SurfaceStamp.ensureAndLandingPos(server, surface, moon.id);
        stampCooldown(player);
        SurfaceTravelData.setPlanet(player, moon.id, parentPos.x, parentPos.y, parentPos.z);
        // the space trip is over: drop the space-return origin so a future launch records a fresh one.
        SpaceTravelData.clear(player);
        // bring a ridden pod through the teleport that follows: it is rebuilt under the player on arrival, so
        // landing never costs them the pod. No-op when on foot.
        carryPodThroughTeleport(player);
        player.teleportTo(surface, landing.x, landing.y, landing.z, player.getYRot(), player.getXRot());

        // a moon is a claimable body too, so populate its wild garrison exactly like a generated planet, deferred until
        // the surface is fully stamped so defenders never scatter into unstamped void (see the generated-planet note).
        SurfaceStamp.whenStamped(server, moon.id, () -> PlanetGarrison.ensurePopulated(server, surface, moon.id));

        Component name = Component.literal(MoonBody.nameFor(moon.id));
        Component owner = ownerLabel(server, moon.id);
        player.displayClientMessage(
                Component.translatable("message.dmz_ragnarok.core.space_landed_moon", name, owner), true);
    }

    // the moon whose CURRENT orbit cube (half-extent + margin on every axis) the player is inside, or null. Every fixed
    // body carries a moon (MoonBody.hasMoon against the shared fixed-body set); MoonBody.moonFor returns null only for a
    // non-fixed parent, so this lands on any fixed body's moon. The moon position is recomputed at the current game time
    // so the landing volume tracks exactly where the client draws the moon.
    /**
     * Try every landable kind at ONE point on the path, landing on the first that contains it.
     *
     * <p>ON AUTOPILOT, only the body the course points at may be landed on. A cruise crosses the whole system in a
     * straight line and will pass through anything in the way; without this a pilot who set a course for Namek gets
     * dumped onto whatever rock the line happened to clip, which reads as the pod ignoring them. The autopilot is a
     * stated destination, so everything else on the path is scenery until it is cancelled.
     *
     * @return true when a landing happened and the caller must stop
     */
    private boolean tryLandAt(ServerPlayer player, Vec3 at)
    {
        MinecraftServer server = player.getServer();
        boolean onCourse = SpaceAutopilot.isActive(player);
        String courseKey = onCourse ? SpaceAutopilot.target(player) : "";

        // a MOON is checked FIRST, before its parent body: a moon orbits well outside its parent, so a player diving
        // toward the planet passes through the moon's cube first and brushing the visible moon must land them on the
        // moon rather than skip to the planet behind it.
        if (!onCourse)
        {
            MoonBody.Moon moon = moonAt(server, player.level().getGameTime(), at);
            if (moon != null)
            {
                landOnMoon(player, moon);
                return true;
            }
        }
        PlanetRegistry.Planet body = bodyAt(server, at);
        if (body != null && (!onCourse || body.key.equals(courseKey)))
        {
            landOnPlanet(player, body);
            return true;
        }
        if (onCourse)
        {
            // Nothing else may claim a pilot who is on their way somewhere.
            return false;
        }
        // a generated planet is checked in the SAME slot as a fixed body: land on it, and only if no body of either
        // kind is on the path do we run the descend-out return.
        GeneratedPlanets.Generated generated = GeneratedPlanets.bodyContaining(server, at, LANDING_MARGIN);
        if (generated != null)
        {
            landOnGeneratedPlanet(player, generated);
            return true;
        }
        // a SUPER body lands in the same slot. Only an UNCLAIMED body is landable (landableBodyAt excludes claimed
        // ones), so once a body's ball has been taken it can never be landed on again until it relocates.
        String superId = SuperPlanetData.get(server).landableBodyAt(at, LANDING_MARGIN);
        if (!superId.isEmpty())
        {
            landOnSuperPlanet(player, superId);
            return true;
        }
        return false;
    }

    /**
     * Points along the path from {@code from} to {@code to}, spaced closely enough that no landable body can fall
     * between two of them. The step is under the smallest landing half-extent there is (a minimum-size generated
     * body is 32 + {@link #LANDING_MARGIN}), so any body the line passes through contains at least one sample.
     *
     * <p>The endpoint is always included, so a stationary player still gets exactly one check and the behaviour for
     * anyone not moving is identical to the single point test this replaced.
     */
    private static List<Vec3> pathSamples(Vec3 from, Vec3 to)
    {
        double distance = from.distanceTo(to);
        if (distance <= PATH_SAMPLE_STEP)
        {
            return List.of(to);
        }
        // Cap the count so a teleport across the system (which is not "flying into" anything) cannot turn into
        // thousands of checks in one tick.
        int steps = (int) Math.min(MAX_PATH_SAMPLES, Math.ceil(distance / PATH_SAMPLE_STEP));
        List<Vec3> out = new ArrayList<>(steps);
        for (int i = 1; i <= steps; i++)
        {
            out.add(from.lerp(to, i / (double) steps));
        }
        return out;
    }

    /** The moon whose cube contains {@code at}, or null. */
    private MoonBody.Moon moonAt(MinecraftServer server, long gameTime, Vec3 at)
    {
        if (server == null)
        {
            return null;
        }
        for (PlanetRegistry.Planet body : PlanetRegistry.bodies(server))
        {
            MoonBody.Moon moon = MoonBody.moonFor(server, body.key, body.position(), body.radius(), gameTime);
            if (moon == null)
            {
                continue;
            }
            if (insideCube(at, moon.position, moon.radius + LANDING_MARGIN))
            {
                return moon;
            }
        }
        return null;
    }

    /** The fixed planet body whose cube contains {@code at}, or null. */
    private PlanetRegistry.Planet bodyAt(MinecraftServer server, Vec3 at)
    {
        for (PlanetRegistry.Planet body : PlanetRegistry.bodies(server))
        {
            if (insideCube(at, body.position(), body.radius() + LANDING_MARGIN))
            {
                return body;
            }
        }
        return null;
    }

    private static boolean insideCube(Vec3 p, Vec3 centre, double reach)
    {
        return Math.abs(p.x - centre.x) <= reach
                && Math.abs(p.y - centre.y) <= reach
                && Math.abs(p.z - centre.z) <= reach;
    }

    // a display label for a generated planet's current owner: the owning guild's name, or an "unclaimed" label. Used
    // on landing and could be reused by the space-side lookup. Reads the claim from GeneratedPlanetClaims (the single
    // authoritative home), never from an entity.
    private static Component ownerLabel(MinecraftServer server, String planetId)
    {
        String guildId = GeneratedPlanetClaims.get(server).owner(planetId);
        if (guildId == null)
        {
            return Component.translatable("message.dmz_ragnarok.core.space_owner_unclaimed");
        }
        net.shurui.shuruisutilities.guilds.model.Guild guild =
                net.shurui.shuruisutilities.guilds.GuildManager.byId(guildId);
        return Component.literal(guild == null ? guildId : guild.name);
    }

    private void enterSpace(ServerPlayer player)
    {
        MinecraftServer server = player.getServer();
        ServerLevel space = SpaceDimension.level(server);
        if (space == null)
        {
            // datapack dim missing (should never happen): do nothing rather than crash or strand the player.
            return;
        }
        // capture the pod the pilot is riding (if any) BEFORE anything dismounts them, so we can bring it into
        // space. Null for an on-foot launch, which keeps the original teleport-only path below.
        SpacePodEntity pod = ridingSpacePod(player);
        // the SU time machine is a first-class launch vehicle too, handled exactly like the pod at these same two
        // points: it is carried into space here, and rebuilt on every landing/return (carryPodThroughTeleport).
        net.shurui.shuruisutilities.timemachine.TimeMachineEntity timeMachine =
                net.shurui.shuruisutilities.timemachine.TimeMachineDeploy.riding(player);
        SpaceTravelData.setOrigin(player);
        stampCooldown(player);
        // phase 1b: if the dimension they launched from is itself a planet BODY (e.g. the overworld = Earth),
        // arrive next to that body so flying up from Earth leaves them looking at Earth, not at a raw x/z void.
        // Otherwise fall back to phase 1a's behaviour (arrive above the take-off x/z). The origin record for the
        // return trip is untouched: only the arrival position changes.
        PlanetRegistry.Planet body = PlanetRegistry.bodyForDimension(server, player.level().dimension());
        double x;
        double y;
        double z;
        float yaw = player.getYRot();
        if (body != null)
        {
            Vec3 target = spawnBesidePlanet(body);
            x = target.x;
            y = target.y;
            z = target.z;
            // face the planet centre so the body is dead ahead on arrival.
            yaw = yawTowards(target, body.position());
        }
        else
        {
            x = player.getX();
            y = SPACE_ARRIVAL_Y;
            z = player.getZ();
        }
        // CROSS-SERVER: the space dimension is hosted on one shard (the SMP). When THIS shard does not host it,
        // teleporting into a locally-created copy is the old bug: the dimension sweep finds the player in a
        // dimension this server does not host and evicts them a second later with the "that place is on the smp
        // server, use /server" refusal. Instead, hand the player to the shard that hosts space, carrying the arrival
        // position (see SpaceHandoff) so the far side places them IN SPACE rather than at its own arrival warp.
        if (!ShardDimensions.hosts(SpaceDimension.SPACE))
        {
            handOffToSpace(player, x, y, z, yaw, pod, timeMachine);
            return;
        }
        if (pod != null)
        {
            enterSpaceAboardPod(player, space, pod, x, y, z, yaw);
        }
        else if (timeMachine != null)
        {
            net.shurui.shuruisutilities.timemachine.TimeMachineDeploy
                    .enterSpaceAboard(player, space, timeMachine, x, y, z, yaw);
        }
        else
        {
            player.teleportTo(space, x, y, z, yaw, player.getXRot());
        }
        // action-bar notice so the transition is visible; resolved in the player's own language client-side.
        player.displayClientMessage(Component.translatable("message.dmz_ragnarok.core.space_entered"), true);
    }

    // Hand a player who has crossed into space to the shard that hosts the space dimension, carrying the arrival
    // position so the far side drops them in space and not at its own arrival warp. setOrigin and stampCooldown have
    // already run in enterSpace: the origin travels in the vault for the return trip, and the cooldown suppresses a
    // second launch on the next tick before the proxy disconnects the player, so no duplicate handoff is sent.
    private void handOffToSpace(ServerPlayer player, double x, double y, double z, float yaw, SpacePodEntity pod,
            net.shurui.shuruisutilities.timemachine.TimeMachineEntity timeMachine)
    {
        String target = ShardDimensions.owner(SpaceDimension.SPACE);
        if (target == null || target.isBlank())
        {
            target = ShardConfig.get().smpServerId;
        }
        String self = ShardConfig.get().serverId;
        if (target == null || target.isBlank() || target.equalsIgnoreCase(self))
        {
            // No shard we can name hosts space, or the map points at us (a misconfiguration). Do NOT teleport into a
            // dimension we do not host, and do NOT forward blindly: leave the player put and log. The cooldown that
            // enterSpace already stamped keeps this from firing more than once every few seconds.
            LOGGER.warn("[space] {} crossed into space but this server does not host it and no owner is configured "
                    + "(dimensionOwners['{}'] and smpServerId are both unusable). Leaving them where they are.",
                    player.getGameProfile().getName(), SpaceDimension.ID);
            return;
        }
        // carryShip: a DMZ saiyan ship is rebuilt in space on the far side like an SU pod (#842 #921 #1038), instead
        // of being handed back here, which put the pilot in space on foot beside Earth, where they fell below the
        // return altitude and were pulled down onto Earth (the nearest body), i.e. back to spawn.
        // read before the discard strips the pod: a DMZ ship must be rebuilt (and, failing that, handed back) as a DMZ
        // ship on the far side, never as an SU pod whose later hand-backs would be a chip.
        boolean ship = SpacePodDeploy.dmzShip(pod, player);
        boolean hadPod = discardPodForHandoff(player, pod, true);
        boolean hadTimeMachine = net.shurui.shuruisutilities.timemachine.TimeMachineDeploy
                .discardForHandoff(player, timeMachine);
        SpaceHandoff.mark(player, SpaceDimension.SPACE, x, y, z, yaw, player.getXRot(), hadPod, hadTimeMachine);
        if (hadPod && ship)
        {
            SpaceHandoff.markPodShip(player);
        }
        // Carry the launch origin (dim + position, set by enterSpace's setOrigin just above) INSIDE the handoff, so a
        // cross-shard space entry re-establishes it on arrival even if the separate su_space_origin tag were ever lost
        // in the vault hop. Without a usable origin, leaving space on the far side falls back to the arrival warp, which
        // is correct but not where the player actually took off from; this keeps the return trip precise.
        SpaceHandoff.attachOrigin(player, SpaceTravelData.rawOrigin(player));
        SpaceRouteChunks.release(player);
        LOGGER.info("[space] Handing {} to '{}' to enter space at {} {} {}.",
                player.getGameProfile().getName(), target, (int) x, (int) y, (int) z);
        ShardTransfer.connect(player, target);
    }

    // Remove the pod a player is riding on the ORIGIN side of a cross-server hop. A DMZ space pod is an entity and
    // cannot follow a player across servers, and it is persistence-required, so leaving it behind strands an
    // immortal orphan. Strip the owner marker first so the discard is not read as a destroy (which would hand back a
    // duplicate chip), then discard it. Returns true when a pod was removed, so the arrival side rebuilds one.
    // carryShip (the space ENTRY hop only): a pod with no chip in the slot (a DMZ saiyan ship) is also carried as a
    // rebuild rather than handed back as an item, so the pilot arrives in space aboard it exactly as on a same-shard
    // entry (enterSpaceAboardPod). Its no-loss guarantee then moves to the far side: completeHandoff hands the ship item
    // back whenever the promised rebuild does not happen, and a transfer that stalls on this side is handed back by the
    // expired-handoff branch of onPlayerTick.
    private boolean discardPodForHandoff(ServerPlayer player, SpacePodEntity pod)
    {
        return discardPodForHandoff(player, pod, false);
    }

    private boolean discardPodForHandoff(ServerPlayer player, SpacePodEntity pod, boolean carryShip)
    {
        if (pod == null)
        {
            return false;
        }
        try
        {
            // decided from the pod itself before its marker is stripped: is this the player's own SU pod, and is the
            // chip still sitting in the shared slot? An SU pod always keeps its chip in the slot while deployed, so the
            // chip travels in the vault and the far side can rebuild a fresh pod under the player. A DMZ saiyan ship
            // (or an SU pod converted from one on a space entry) has NO chip in the slot, so a rebuild that a stalled
            // transfer could skip would lose the ship for good.
            boolean ours = SpacePodDeploy.ownedBy(pod, player);
            boolean chip = SpacePodDeploy.chipInSlot(player);
            boolean ship = SpacePodDeploy.dmzShip(pod, player);
            player.stopRiding();
            pod.getPersistentData().remove(SpacePodDeploy.OWNER_TAG);
            pod.discard();
            if (ship)
            {
                // DMZ's own saiyan ship. Whatever sits in the chip slot is a separate item and says nothing about this
                // ship, so it is decided on its own: carried as a DMZ ship on the space entry (the caller marks the
                // handoff podShip so the far side rebuilds and hands back a ship, never a chip), else DMZ's ship item
                // is handed straight back here. The deploy record is not about this ship, so it is left alone.
                if (carryShip)
                {
                    return true;
                }
                SpacePodDeploy.handBackShip(player);
                return false;
            }
            if (chip)
            {
                // the chip is in the slot: let the arrival side rebuild the pod under the player (hadPod = true).
                return true;
            }
            if (carryShip)
            {
                // no chip, but the arrival side rebuilds the ship (and hands its item back if it cannot). Clear the
                // stale deploy record so a stalled-transfer hand-back returns the DMZ ship item, not a chip.
                PodDeployData.clear(player);
                return true;
            }
            // no chip to carry the pod across: hand the ship item straight back here, on the shard the player is still
            // on, rather than trusting a rebuild. The entity is already gone, so this can never duplicate. Clear the
            // stale deploy record and report hadPod = false so the arrival side does NOT also rebuild one.
            SpacePodDeploy.handBackPodItem(player, ours);
            PodDeployData.clear(player);
            return false;
        }
        catch (Throwable t)
        {
            // DMZ internals shifted: arrive on foot rather than crash. The stranded pod, if any, is left for the
            // drop interceptor / respawn return to reconcile against the deploy record.
            return false;
        }
    }

    // Rebuild a fresh space pod under a player who has just arrived from another server and reseat them, mirroring
    // enterSpaceAboardPod's construction (the origin pod was discarded on the far side). Throwable-guarded: a failure
    // leaves the player on foot, never crashes the login.
    // Returns true when the player ends up seated in the rebuilt pod.
    // ship: the departure side carried DMZ's own saiyan ship (SpaceHandoff.podShip), so it is rebuilt as one: no owner
    // marker and no deploy record, exactly like the same-shard entry, so it stays a DMZ ship.
    private boolean spawnHandoffPod(ServerPlayer player, ServerLevel level, double x, double y, double z, float yaw,
            boolean ship)
    {
        SpacePodEntity fresh = null;
        try
        {
            fresh = new SpacePodEntity(MainEntities.SPACE_POD.get(), level);
            fresh.moveTo(x, y, z, yaw, 0.0F);
            if (!ship)
            {
                fresh.getPersistentData().putUUID(SpacePodDeploy.OWNER_TAG, player.getUUID());
            }
            level.addFreshEntity(fresh);
            player.startRiding(fresh, true);
            if (!ship)
            {
                PodDeployData.set(player, fresh.getUUID());
            }
            PodSpeedBoost.apply(player, fresh);
            return ridingSpacePod(player) == fresh;
        }
        catch (Throwable t)
        {
            // arrived on foot; the deploy record and drop interceptor still reconcile the chip. A half-built pod the
            // player is not seated in is removed (owner marker stripped first so it is not read as a destroy), so the
            // caller's item hand-back can never sit beside a second, riderless ship.
            if (fresh != null && ridingSpacePod(player) != fresh)
            {
                fresh.getPersistentData().remove(SpacePodDeploy.OWNER_TAG);
                fresh.discard();
            }
            return false;
        }
    }

    // Placement on the DESTINATION side of a space hop. Runs at login, AFTER ShardSync's HIGHEST-priority vault
    // apply has restored the persistent data (so the handoff record is on the player), at LOW priority. Places the
    // player at the carried destination and consumes the record. NEVER re-forwards: a record whose target dimension
    // this server does not host is dropped with a log, exactly the eviction-loop philosophy, because forwarding a
    // stale intent onward is the one thing that would turn a hop into a loop.
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onPlayerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
        {
            return;
        }
        // ARRIVAL GRACE for EVERY login that lands in a space or surface dimension, not only a space handoff. A hop to
        // the SMP (/home, a pwarp, /warp, /tpa, /server, SPAWN_ON_ARRIVAL, or a plain arrival whose carried position is
        // an unhosted dimension) restores the player at their LOCAL playerdata position, which for a player whose data
        // was frozen in dmz_ragnarok:space by an earlier bounce is space itself. Their real placement (ShardHomes,
        // ShardTp/Tpa, a warp, ShardSync's arrival placement, a DimensionHandoff) then runs within the next few ticks,
        // but the space tick would see "a player in space with no origin" and eject them via leaveSpace FIRST, on the
        // very next tick, which is Ahoka's loop on 2026-09-14: every arrival on smp bounced to OW within 40 to 70 ms,
        // before the pending placement could act. Stamping the same 3s descend-out grace an ordinary entry uses (the
        // onCooldown gate below suppresses tickInSpace / leaveSpace for that window) gives the pending placement time to
        // move them out of space; if nothing does, leaveSpace then lands them on an SMP-hosted spot rather than off the
        // server (see returnToOverworldSpawn). Only stamped when they load into a space or surface dim, so an ordinary
        // open-world login is untouched.
        Level level = player.level();
        if (SpaceDimension.isSpace(level) || SurfaceDimension.isSurface(level))
        {
            grantArrivalGrace(player);
        }
        if (SpaceHandoff.isPending(player))
        {
            completeHandoff(player);
        }
    }

    private void completeHandoff(ServerPlayer player)
    {
        MinecraftServer server = player.getServer();
        ResourceKey<Level> dim = SpaceHandoff.dimension(player);
        // read up front: the early-exit branches below clear the record before the finally runs.
        boolean podPromised = SpaceHandoff.pod(player);
        boolean podShip = SpaceHandoff.podShip(player);
        boolean podRebuilt = false;
        try
        {
            if (server == null || dim == null || SpaceHandoff.expired(player))
            {
                SpaceHandoff.clear(player);
                return;
            }
            if (!ShardDimensions.hosts(dim))
            {
                LOGGER.warn("[space] {} arrived carrying a space handoff into {}, which this server does not host; "
                        + "dropping the intent and leaving them where they loaded rather than forwarding again.",
                        player.getGameProfile().getName(), dim.location());
                SpaceHandoff.clear(player);
                return;
            }
            boolean toSpace = SpaceDimension.SPACE.equals(dim);
            // getOrCreate, not getLevel: the destination is a dimension this shard hosts, but a host that has never
            // instantiated the level yet (a dim added after its world was made) would otherwise hand back null and
            // drop the arrival, stranding the player where they loaded. Build it from the stem if needed.
            ServerLevel level = toSpace ? SpaceDimension.level(server) : DynamicLevels.getOrCreate(server, dim);
            if (level == null)
            {
                SpaceHandoff.clear(player);
                return;
            }
            // A cross-shard ENTER into space: re-establish the launch origin from the handoff if the separate origin
            // tag did not survive the hop, so a later leave returns the player to where they took off rather than
            // falling back to the arrival warp. Only fills a MISSING or space-pointing origin; a valid one is left
            // alone. Read before the finally-clear of the handoff below.
            if (toSpace && (!SpaceTravelData.hasOrigin(player) || SpaceTravelData.originIsSpace(player)))
            {
                CompoundTag carriedOrigin = SpaceHandoff.origin(player);
                if (carriedOrigin != null)
                {
                    SpaceTravelData.setRawOrigin(player, carriedOrigin);
                }
            }
            double x = SpaceHandoff.x(player);
            double y = SpaceHandoff.y(player);
            double z = SpaceHandoff.z(player);
            float yaw = SpaceHandoff.yaw(player);
            float pitch = SpaceHandoff.pitch(player);
            boolean pod = SpaceHandoff.pod(player);
            boolean timeMachine = SpaceHandoff.timeMachine(player);
            if (!toSpace)
            {
                // a RETURN out of space onto a surface: ground-snap so a world that grew since takeoff never buries
                // the player, matching leaveSpace's own return.
                Vec3 snapped = SurfaceSnap.snap(level, x, z);
                y = Math.max(snapped.y, y);
            }
            // suppress the space tick's threshold / descend-out re-trigger for the arrival grace window, so an
            // arrival beside a planet or just above the return altitude is never bounced on the very next tick.
            grantArrivalGrace(player);
            player.teleportTo(level, x, y, z, yaw, pitch);
            if (pod)
            {
                podRebuilt = spawnHandoffPod(player, level, x, y, z, yaw, podShip);
            }
            if (timeMachine)
            {
                net.shurui.shuruisutilities.timemachine.TimeMachineDeploy
                        .spawnAfterHandoff(player, level, x, y, z, yaw);
            }
            player.displayClientMessage(Component.translatable(toSpace
                    ? "message.dmz_ragnarok.core.space_entered"
                    : "message.dmz_ragnarok.core.space_left"), true);
            LOGGER.info("[space] Placed {} on arrival in {} at {} {} {}.",
                    player.getGameProfile().getName(), dim.location(), (int) x, (int) y, (int) z);
        }
        catch (Throwable t)
        {
            LOGGER.error("[space] Could not place {} on arrival: {}", player.getGameProfile().getName(), t.toString());
        }
        finally
        {
            // the departure side discarded a pod and promised a rebuild here; if none happened (an expired or unhosted
            // intent, a failed placement or rebuild), hand its item back so the ship is never lost: DMZ's ship item for a
            // carried DMZ ship, and for an SU pod a no-op while its chip is still in the slot (never a duplicate).
            if (podPromised && !podRebuilt && ridingSpacePod(player) == null)
            {
                handBackCarriedPod(player, false, podShip);
            }
            // Consumed on every path: a record left behind would re-fire on the next unrelated login and, worse, is
            // the thing the sweep exemption keys on, so it must never outlive its one placement.
            SpaceHandoff.clear(player);
        }
    }

    // bring a pod PILOT into space at the arrival point already computed by enterSpace. A cross-dimension
    // teleportTo dismounts the rider and leaves the persistence-required pod stranded in the origin dimension, so
    // instead we mirror DMZ's own TravelToPlanetC2S: dismount, teleport the player, spawn a FRESH pod in the space
    // dimension and reseat them, repoint the deploy record at the fresh pod so a later recall finds it, then
    // discard the orphaned origin pod. Throwable-guarded: a DMZ 2.1.3 shape shift must degrade to "arrived on foot"
    // (the teleport has already run, so the pilot is never stranded), not crash the player tick.
    private void enterSpaceAboardPod(ServerPlayer player, ServerLevel space, SpacePodEntity oldPod,
            double x, double y, double z, float yaw)
    {
        try
        {
            // read off the origin pod before anything moves: DMZ's own saiyan ship stays a DMZ ship in space.
            boolean ship = SpacePodDeploy.dmzShip(oldPod, player);
            player.stopRiding();
            player.teleportTo(space, x, y, z, yaw, player.getXRot());

            SpacePodEntity fresh = new SpacePodEntity(MainEntities.SPACE_POD.get(), space);
            fresh.moveTo(x, y, z, yaw, 0.0F);
            // an SU pod: mark ownership with SpacePodDeploy.OWNER_TAG (the one source of truth) so a destroy in space
            // is handled as ours by PodDropInterceptor, and record the deploy so recall finds the pod now living in
            // space. A DMZ ship gets neither (as reseatPodAfterTeleport does on the way down): marking it converted it
            // into an SU pod, so every later hand-back gave a chip, and a destroy or recall swallowed the ship.
            if (!ship)
            {
                fresh.getPersistentData().putUUID(SpacePodDeploy.OWNER_TAG, player.getUUID());
            }
            space.addFreshEntity(fresh);
            player.startRiding(fresh, true);
            if (!ship)
            {
                PodDeployData.set(player, fresh.getUUID());
            }
            // re-derive and apply the 2x-max-flight-speed boost onto the FRESH space pod (Job 1: entering space is
            // one of the re-derive points). The mount above also fires EntityMountEvent -> onPodMount, so this is
            // belt-and-braces; setBaseValue is idempotent.
            PodSpeedBoost.apply(player, fresh);

            // discard the orphaned origin-dimension pod. strip its owner marker first so the discard is not read as
            // a destroy by PodDropInterceptor, which would otherwise hand back a duplicate chip.
            oldPod.getPersistentData().remove(SpacePodDeploy.OWNER_TAG);
            oldPod.discard();
        }
        catch (Throwable t)
        {
            // DMZ internals shifted (or SPACE_POD unavailable). The teleport above already placed the pilot in
            // space, so they continue on foot; the orphaned origin pod is left for the drop interceptor / the
            // respawn-return to reconcile against the (possibly stale) deploy record.
        }
    }

    // an arrival point set back from the planet body so the player materialises IN SPACE beside it, not inside
    // it. Offset along +X by the body's radius plus a fixed standoff, at the body's altitude. Deterministic (no
    // randomness) so it is stable and the yaw-toward maths below always lines up.
    private static Vec3 spawnBesidePlanet(PlanetRegistry.Planet body)
    {
        Vec3 centre = body.position();
        double standoff = body.radius() + PLANET_STANDOFF;
        return new Vec3(centre.x + standoff, centre.y, centre.z);
    }

    // yaw (degrees) that points from `from` toward `to` on the horizontal plane, in Minecraft's convention
    // (0 = +Z/south, growing clockwise). Used so the arriving player faces the planet.
    private static float yawTowards(Vec3 from, Vec3 to)
    {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return (float) (Math.toDegrees(Math.atan2(-dx, dz)));
    }

    private void leaveSpace(ServerPlayer player)
    {
        MinecraftServer server = player.getServer();
        ServerLevel origin = SpaceTravelData.originLevel(player, server);
        // CROSS-SERVER RETURN: a player who launched from an open world is now in space, which is hosted on the SMP.
        // The recorded origin dimension (their open world) is one the SMP does not host, so returning them into the
        // SMP's local copy of it would drop them into a dimension the sweep evicts a second later. Hand them back to
        // a shard that hosts that origin instead, carrying the return position. originLevel resolves the SMP's own
        // copy of that dimension (which exists but is unhosted), so we test the dimension key against the rules.
        if (origin != null && !SpaceTravelData.originIsSpace(player) && !ShardDimensions.hosts(origin.dimension())
                && handOffOutOfSpace(player, origin.dimension()))
        {
            return;
        }
        stampCooldown(player);
        // bring a ridden pod through either return teleport: it is rebuilt under the player on arrival rather than
        // left orphaned in space. No-op when the player is descending on foot. Both branches below teleport out of
        // space, so doing it here covers the origin return and the overworld-spawn fallback.
        carryPodThroughTeleport(player);
        // action-bar notice so the transition is visible; resolved in the player's own language client-side.
        player.displayClientMessage(Component.translatable("message.dmz_ragnarok.core.space_left"), true);

        // no valid recorded origin (admin teleported in, data lost, or it somehow points at space itself):
        // fall back to overworld spawn rather than leaving them stuck in a floorless void.
        if (origin == null || SpaceTravelData.originIsSpace(player) || SpaceDimension.isSpace(origin))
        {
            returnToOverworldSpawn(player, server);
            SpaceTravelData.clear(player);
            return;
        }

        double x = SpaceTravelData.originX(player);
        double z = SpaceTravelData.originZ(player);
        // ground-snap the landing so nobody arrives inside terrain the world may have generated since takeoff.
        Vec3 snapped = SurfaceSnap.snap(origin, x, z);
        // if the snapped surface is higher than where they took off (e.g. terrain grew), trust the snap; else
        // keep the exact recorded Y so the return is precise as promised.
        double y = Math.max(snapped.y, SpaceTravelData.originY(player));
        player.teleportTo(origin, x, y, z, SpaceTravelData.originYaw(player), SpaceTravelData.originPitch(player));
        SpaceTravelData.clear(player);
    }

    // Hand a player leaving space back to a shard that hosts their origin dimension, carrying the return position so
    // the far side drops them where they took off rather than at its own arrival warp. Returns true when the handoff
    // was dispatched; false (nowhere to send them) lets leaveSpace fall back to its local return, which is safe on a
    // single server and never loops. The origin owner comes from the dimension-owner map first, then the ordinary
    // open-world balancer (the open worlds share the same terrain, so either hosts the origin coordinates), and only
    // a self / empty result declines.
    private boolean handOffOutOfSpace(ServerPlayer player, ResourceKey<Level> originDim)
    {
        String self = ShardConfig.get().serverId;
        String target = ShardDimensions.owner(originDim);
        if (target == null || target.isBlank() || target.equalsIgnoreCase(self))
        {
            target = ShardRouter.pickTarget();
        }
        if (target == null || target.isBlank() || target.equalsIgnoreCase(self))
        {
            return false;
        }
        // read the return position while the origin record is still intact, then convert it into a handoff intent.
        double x = SpaceTravelData.originX(player);
        double y = SpaceTravelData.originY(player);
        double z = SpaceTravelData.originZ(player);
        float yaw = SpaceTravelData.originYaw(player);
        float pitch = SpaceTravelData.originPitch(player);
        // the trip is over: drop any autopilot so nothing keeps pulling a course, and remove the ridden pod on this
        // side (it is rebuilt under the player on arrival from the pod flag), matching carryPodThroughTeleport.
        if (SpaceAutopilot.isActive(player))
        {
            stopAutopilot(player, "left space via a cross-server return");
        }
        boolean hadPod = discardPodForHandoff(player, ridingSpacePod(player));
        boolean hadTimeMachine = net.shurui.shuruisutilities.timemachine.TimeMachineDeploy
                .discardForHandoff(player, net.shurui.shuruisutilities.timemachine.TimeMachineDeploy.riding(player));
        stampCooldown(player);
        SpaceHandoff.mark(player, originDim, x, y, z, yaw, pitch, hadPod, hadTimeMachine);
        SpaceTravelData.clear(player);
        SpaceRouteChunks.release(player);
        LOGGER.info("[space] Handing {} to '{}' to leave space back into {} at {} {} {}.",
                player.getGameProfile().getName(), target, originDim.location(), (int) x, (int) y, (int) z);
        ShardTransfer.connect(player, target);
        return true;
    }

    // land the player on the planet body they flew into: teleport them into that body's dimension at the DMZ
    // destination's spawn (the same spawn the saiyan ship would drop them at), ground-snapped. Clears the compass
    // course only if it pointed at THIS planet, stamps the cooldown so the arrival cannot instantly re-trigger,
    // and clears the space-return origin (they are no longer "in space on a trip").
    private void landOnPlanet(ServerPlayer player, PlanetRegistry.Planet body)
    {
        MinecraftServer server = player.getServer();
        // Beerus's authored build now resides in the shared surface dimension (seeded there by PlanetRegionSeeder at the
        // same absolute coordinates), so a Beerus landing goes to planet_surface. The beerus_planet dimension is kept and
        // stays loaded on purpose: it (with its space-pod destination) is what still makes Beerus a rendered space body
        // through PlanetRegistry, and it supplies these landing coordinates; it simply no longer receives players, so its
        // region folder stays empty. The coordinates are unchanged because the build did not move, only the dimension it
        // lives in did. Every other fixed body still lands in its own dimension.
        boolean beerus = BEERUS_KEY.equals(body.key);
        // Create the target level if the world never instantiated one (a dimension added after the world was made has a
        // datapack stem but no ServerLevel until something visits it). Plain getLevel returned null forever there, so
        // the landing silently did nothing and the planet read as unreachable (bug 622). getOrCreate builds it from the
        // stem the same way the surface and space dimensions already do; on the shard network the empty per-shard copies
        // already exist, so this stays a plain lookup there and the cross-shard handoff below still takes priority.
        ServerLevel target = server == null ? null
                : (beerus ? SurfaceDimension.level(server) : DynamicLevels.getOrCreate(server, body.dimension));
        if (target == null)
        {
            // the body's dimension is genuinely not installed (no live level and no datapack stem), so do nothing
            // rather than strand the player. Leave the course and origin intact so a later attempt can still work.
            return;
        }

        SpacePodDestinationDefinition dest = destinationForDimension(server, body.key);
        boolean hasCoords = dest != null && dest.x() != null && dest.y() != null && dest.z() != null;
        // A body whose dimension lives on another shard (namekow and kaiow are hosted by OW1 only) must be handed
        // across WITH its landing spot. Teleporting here instead lands in this server's empty copy, the generic
        // dimension handoff catches that move, and it can only forward the player with no coordinates, so the far
        // side dropped them at this server's spawn column: open void on namekow (2026-09-13).
        if (!beerus && !ShardDimensions.hosts(target.dimension()) && hasCoords
                && handOffToPlanet(player, body, target.dimension(), dest))
        {
            return;
        }
        double x;
        double y;
        double z;
        if (hasCoords)
        {
            // the destination carries an explicit spawn (e.g. shuruisutilities:namekow, or the seeded planet builds
            // like Beerus at -449/8/-14): land exactly there. Honour the datapack's Y when it is ALREADY a valid
            // standing spot; only ground-snap when it is not. Snapping is kept as the fallback for the open case
            // (namekow), so a destination Y buried by grown terrain, or left hanging in air, is still corrected up to a
            // safe surface rather than dropping the player inside a block or into the void.
            x = dest.x();
            z = dest.z();
            if (SurfaceSnap.isStandable(target, x, dest.y(), z))
            {
                y = dest.y();
            }
            else
            {
                Vec3 snapped = SurfaceSnap.snap(target, x, z);
                y = Math.max(snapped.y, dest.y());
            }
        }
        else
        {
            // no coords on the destination (minecraft:overworld / dragonminez:namek / dragonminez:sacredkaiplanet
            // all ship without x/y/z): DMZ would fall back to the player's CURRENT position, which in space is a
            // raw void coordinate. Instead land at the dimension's shared spawn and ground-snap it.
            BlockPos spawn = target.getSharedSpawnPos();
            Vec3 snapped = SurfaceSnap.snap(target, spawn.getX() + 0.5, spawn.getZ() + 0.5);
            x = snapped.x;
            y = snapped.y;
            z = snapped.z;
        }

        stampCooldown(player);
        // bring a ridden pod through the cross-dimension teleport rather than leaving it orphaned in space: the
        // pilot arrives sitting in it, the way DMZ's own travel arrives. No-op when the pilot flew in on foot.
        carryPodThroughTeleport(player);
        player.teleportTo(target, x, y, z, player.getYRot(), player.getXRot());

        // only clear the course if it actually pointed at the planet we just landed on. Landing on a different
        // body by flying through it must not silently wipe a course the player set to somewhere else.
        if (body.key.equals(PlanetCourse.courseKey(player)))
        {
            PlanetCourse.clearCourse(player);
        }
        // the space trip is over: drop the return origin so a future launch records a fresh one.
        SpaceTravelData.clear(player);
        // Beerus lands INTO the shared surface dimension, where tickOnSurface runs, so clear any stale surface-planet
        // record from an earlier generated-planet visit: Beerus is handled purely by its footprint and must never be
        // treated as a claimable sugen planet through a leftover record.
        if (beerus)
        {
            SurfaceTravelData.clear(player);
        }
        // action-bar notice, resolved client-side in the player's own language.
        player.displayClientMessage(
                Component.translatable("message.dmz_ragnarok.core.space_landed"), true);
    }

    // Hand a player landing on a fixed body to the shard that hosts its dimension, carrying the space-pod destination
    // coordinates. Y is the destination's own Y, not a snap: this server only holds an empty copy of that world, so any
    // surface read here is meaningless. completeHandoff on the far side ground-snaps against the real terrain and keeps
    // the higher of the snap and this Y, the same treatment a return out of space gets. Returns false (the caller then
    // lands locally, exactly as before) when no other shard can be named.
    private boolean handOffToPlanet(ServerPlayer player, PlanetRegistry.Planet body, ResourceKey<Level> dim,
            SpacePodDestinationDefinition dest)
    {
        String self = ShardConfig.get().serverId;
        String owner = ShardDimensions.owner(dim);
        if (owner == null || owner.isBlank() || owner.equalsIgnoreCase(self))
        {
            return false;
        }
        double x = Math.floor(dest.x()) + 0.5;
        double y = dest.y();
        double z = Math.floor(dest.z()) + 0.5;
        if (SpaceAutopilot.isActive(player))
        {
            stopAutopilot(player, "landing on a planet hosted by another server");
        }
        if (body.key.equals(PlanetCourse.courseKey(player)))
        {
            PlanetCourse.clearCourse(player);
        }
        boolean hadPod = discardPodForHandoff(player, ridingSpacePod(player));
        boolean hadTimeMachine = net.shurui.shuruisutilities.timemachine.TimeMachineDeploy
                .discardForHandoff(player, net.shurui.shuruisutilities.timemachine.TimeMachineDeploy.riding(player));
        stampCooldown(player);
        SpaceHandoff.mark(player, dim, x, y, z, player.getYRot(), player.getXRot(), hadPod, hadTimeMachine);
        SpaceTravelData.clear(player);
        SpaceRouteChunks.release(player);
        LOGGER.info("[space] Handing {} to '{}' to land on {} at {} {} {}.",
                player.getGameProfile().getName(), owner, dim.location(), (int) x, (int) y, (int) z);
        ShardTransfer.connect(player, owner);
        return true;
    }

    // the DMZ space-pod destination that targets the given dimension id, preferring one that carries explicit
    // coordinates. Returns null if no destination targets that dimension. Used to read the "saiyan ship" spawn for
    // a body on landing.
    private static SpacePodDestinationDefinition destinationForDimension(MinecraftServer server, String dimId)
    {
        try
        {
            List<SpacePodDestinationDefinition> dests = SpacePodDestinationRegistry.getServerDestinations();
            if (dests == null)
            {
                return null;
            }
            SpacePodDestinationDefinition match = null;
            for (SpacePodDestinationDefinition def : dests)
            {
                if (!dimId.equals(def.dimension()))
                {
                    continue;
                }
                // prefer a destination with explicit coords; keep the first match as a fallback.
                if (def.x() != null && def.y() != null && def.z() != null)
                {
                    return def;
                }
                if (match == null)
                {
                    match = def;
                }
            }
            return match;
        }
        catch (Throwable ignored)
        {
            // DMZ API shape shift: no destination info, caller falls back to the dimension spawn.
            return null;
        }
    }

    private void returnToOverworldSpawn(ServerPlayer player, MinecraftServer server)
    {
        // A player leaving space with no usable origin must land on a dimension THIS server hosts, never be ejected off
        // the very server they just asked to reach. That was Ahoka's loop on 2026-09-14: on the SMP this method raw
        // teleported into the local, unhosted overworld, which ShardDimensions.onTravel caught and bounced to OW1's
        // spawn, so a player who kept trying to reach the SMP kept being thrown to the open world. Order:
        //   1. the arrival warp, when this server has a usable one (the SMP: the same point /warp smp uses, in an
        //      SMP-hosted dimension), so leaving space lands them ON the SMP.
        //   2. this server's own overworld world spawn, when it hosts the overworld (the open worlds), unchanged.
        //   3. only when this server hosts NEITHER (a hub with no arrival warp) route cross-server to a shard that DOES
        //      host the overworld, rather than a raw teleport into a local unhosted copy.
        WarpPoint warp = ShardDimensions.arrivalWarp();
        if (warp != null)
        {
            ServerLevel level = warp.getWorld();
            if (level != null)
            {
                player.teleportTo(level, warp.getX(), warp.getY(), warp.getZ(), warp.getYaw(), warp.getPitch());
                return;
            }
        }
        if (server != null)
        {
            ServerLevel overworld = server.overworld();
            if (overworld != null && ShardDimensions.hosts(overworld.dimension()))
            {
                var spawn = overworld.getSharedSpawnPos();
                Vec3 snapped = SurfaceSnap.snap(overworld, spawn.getX() + 0.5, spawn.getZ() + 0.5);
                player.teleportTo(overworld, snapped.x, snapped.y, snapped.z, player.getYRot(), player.getXRot());
                return;
            }
        }
        // Neither an arrival warp nor a hosted overworld here: route to a shard that hosts the overworld. Never a raw
        // teleport into the local unhosted copy, which would bounce.
        handOffToOverworldSpawn(player);
    }

    // Hand a player leaving space with NO usable origin AND no arrival warp / hosted overworld to a shard that hosts
    // them at its spawn (SPAWN_ON_ARRIVAL, the same signal /spawn uses from the SMP). Returns false when this server
    // already hosts the overworld (the caller then teleports locally, unchanged) or when no other shard can be named
    // (a single server, which never loops). This replaces the raw local overworld teleport that ShardDimensions.onTravel
    // would otherwise catch and bounce cross-shard. The overworld owner comes from the dimension-owner map first, then
    // the ordinary open-world balancer, matching handOffOutOfSpace.
    private boolean handOffToOverworldSpawn(ServerPlayer player)
    {
        if (!ShardSync.active())
        {
            return false;
        }
        MinecraftServer server = player.getServer();
        if (server == null)
        {
            return false;
        }
        ServerLevel overworld = server.overworld();
        if (overworld == null || ShardDimensions.hosts(overworld.dimension()))
        {
            return false; // we host the overworld: a local teleport is correct and never bounces
        }
        String self = ShardConfig.get().serverId;
        String target = ShardDimensions.owner(overworld.dimension());
        if (target == null || target.isBlank() || target.equalsIgnoreCase(self))
        {
            target = ShardRouter.pickTarget();
        }
        if (target == null || target.isBlank() || target.equalsIgnoreCase(self))
        {
            return false; // nowhere to route: let the caller fall back to a local teleport
        }
        // the trip is over: drop autopilot, discard a ridden pod / time machine on this side (neither can follow a
        // cross-server hop), and clear every space record so the arrival is a clean spawn placement, not a re-entry
        // that would immediately try to leave space again.
        if (SpaceAutopilot.isActive(player))
        {
            stopAutopilot(player, "left space with no usable origin");
        }
        discardPodForHandoff(player, ridingSpacePod(player));
        net.shurui.shuruisutilities.timemachine.TimeMachineDeploy
                .discardForHandoff(player, net.shurui.shuruisutilities.timemachine.TimeMachineDeploy.riding(player));
        stampCooldown(player);
        SpaceTravelData.clear(player);
        SpaceHandoff.clear(player);
        SpaceRouteChunks.release(player);
        ShardTransfer.expect(player.getUUID(), target, ShardTransfer.SPAWN_ON_ARRIVAL);
        LOGGER.info("[space] Handing {} to '{}' to leave space to spawn (no usable origin to return to).",
                player.getGameProfile().getName(), target);
        ShardTransfer.connect(player, target);
        return true;
    }

    // eligible = not one of the hard-forbidden dims AND (it is a planet body OR it is in the configured list). The
    // forbidden check runs FIRST and regardless of everything else, so an admin can never make the
    // Nether/End/otherworld eligible by editing the list, and a forbidden dim is never a body either (the registry
    // reuses the same set), so both paths agree. Deriving eligibility from the planet registry is the fix for the
    // launch bug: every dimension you can LAND on (Namek, Sacred Kai, ...) is now automatically a dimension you can
    // LAUNCH from, with no second list to keep in sync. The config list stays as an ADDITION so an admin can still
    // allow a launch point that is not a planet (a hub dimension, say) without a code change; the common case needs
    // no config at all. isBody() is cache-backed, so this stays cheap on the per-player tick.
    private boolean isEligibleDimension(MinecraftServer server, ResourceKey<Level> dim)
    {
        return canLaunchFrom(server, dim);
    }

    // Static form of the launch-eligibility test so PlanetCourse can ask "may this pod rider launch from here?" when
    // it arms the autopilot, without a module instance. Same three rules as the on-foot entry: never a forbidden dim,
    // else any planet body, else the configured extra list. eligibleDimensions is already static (config-baked).
    public static boolean canLaunchFrom(MinecraftServer server, ResourceKey<Level> dim)
    {
        String id = dim.location().toString();
        if (FORBIDDEN_DIMENSIONS.contains(id))
        {
            return false;
        }
        if (PlanetRegistry.isBody(server, dim))
        {
            return true;
        }
        return eligibleDimensions.contains(id);
    }

    // the DMZ space pod the player is riding (directly or nested), or null if they are not aboard one. Walks the
    // whole vehicle chain so a pod stacked under another rideable is still found.
    private SpacePodEntity ridingSpacePod(ServerPlayer player)
    {
        for (Entity vehicle = player.getVehicle(); vehicle != null; vehicle = vehicle.getVehicle())
        {
            if (vehicle instanceof SpacePodEntity pod)
            {
                return pod;
            }
        }
        return null;
    }

    // true while the player is riding a DMZ flying nimbus (or its black variant), directly or nested. The two are
    // separate entity classes (both extend Mob, neither extends the other), so both are checked. A nimbus rider is
    // never sent to space (see tickEligible): the cloud is not a launch vehicle and would be left behind.
    private boolean isRidingNimbus(ServerPlayer player)
    {
        for (Entity vehicle = player.getVehicle(); vehicle != null; vehicle = vehicle.getVehicle())
        {
            if (vehicle instanceof FlyingNimbusEntity || vehicle instanceof BlackNimbusEntity)
            {
                return true;
            }
        }
        return false;
    }

    // Bring the pod the player is RIDING with them through the cross-dimension teleport that is about to happen.
    //
    // A cross-dimension teleport dismounts the rider and leaves the vehicle behind, and the pod is
    // persistence-required, so a pod left standing is an immortal orphan in a dimension the pilot cannot walk back
    // to. This used to be answered by dissolving the pod into the curios chip, which had two faults. The pod
    // VANISHED on arrival, when DMZ's own TravelToPlanetC2S does the opposite (it discards the ridden pod and
    // constructs a fresh one at the destination), and the recall it called looked the pod up BY DEPLOY RECORD: a
    // pilot flying DMZ's own saiyan ship has no record, so nothing was found, the call fell through to its
    // deploy() fallback, said "No space pod equipped.", and the landing teleport stranded the ship in space for
    // good. So we now mirror DMZ: the old pod goes, an identical one is waiting under the player when they arrive.
    //
    // The rebuild is queued on the server rather than done here because the teleport has not run yet: every caller
    // invokes this immediately BEFORE its own teleportTo. server.execute lands the task at the tick's task drain,
    // by which point the player is standing at their destination and player.level() is the level to build in.
    //
    // Throwable-guarded end to end: a failure must never block the landing or return teleport that follows, and if
    // the pod cannot be rebuilt the matching ITEM is handed over instead, so a trip can never cost somebody a ship.
    private void carryPodThroughTeleport(ServerPlayer player)
    {
        // The SU time machine is carried through the SAME exit teleports as the pod. Every space-exit path calls this
        // method immediately before its teleport, so handling the time machine here covers all of them in one place.
        // No-op unless the player is riding a machine; a player never rides both, so this and the pod block below are
        // mutually exclusive.
        net.shurui.shuruisutilities.timemachine.TimeMachineDeploy.carryThroughTeleport(player);

        SpacePodEntity ridden = ridingSpacePod(player);
        if (ridden == null)
        {
            return;
        }
        // the trip is ending (a landing or a manual leave), and the pod about to be taken away is what the autopilot
        // drags: drop any autopilot here so nothing keeps pulling a course after the pod is gone. Covers all five
        // space-exit paths in one place, since every one of them calls this before its teleport. Routed through
        // stopAutopilot so a completed autopilot trip leaves its matching "ended" log line (no-op when there was no
        // autopilot, e.g. a hand-flown landing).
        stopAutopilot(player, "trip ended (landing or leave)");

        // decided from the pod itself, and read BEFORE the removal clears the marker it is read from.
        boolean ours = SpacePodDeploy.ownedBy(ridden, player);
        boolean ship = SpacePodDeploy.dmzShip(ridden, player);
        try
        {
            SpacePodDeploy.removeRidden(player, ridden);
        }
        catch (Throwable t)
        {
            // DMZ internals shifted: let the pilot land on foot rather than crash the tick. The deploy record
            // survives, so the toggle keybind or the respawn return can still recover the chip.
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null)
        {
            handBackCarriedPod(player, ours, ship);
            return;
        }
        server.execute(() -> reseatPodAfterTeleport(player, ours, ship));
    }

    // The item a carried pod came out of, for a trip that could not rebuild it: DMZ's ship item for a DMZ ship (even
    // with a pod chip in the slot, which is a different item), else the SU rule (a chip only when none is in the slot).
    private static void handBackCarriedPod(ServerPlayer player, boolean ours, boolean ship)
    {
        if (ship)
        {
            SpacePodDeploy.handBackShip(player);
        }
        else
        {
            SpacePodDeploy.handBackPodItem(player, ours);
        }
    }

    // Stalled cross-server hop: the handoff says which kind of pod was discarded for it (podShip). A record without
    // the key (written before it existed) keeps the old rule.
    private static void handBackCarriedPod(ServerPlayer player, boolean ours)
    {
        handBackCarriedPod(player, ours, SpaceHandoff.podShip(player));
    }

    // Second half of carryPodThroughTeleport, run after the caller's teleport: build a pod where the player now
    // stands and put them back in it. Ownership is carried over rather than assumed, so a DMZ ship stays a DMZ ship
    // (no owner marker, no deploy record) and only OUR pod keeps the marker that makes a later destroy return our
    // chip. The speed boost is not touched here on purpose: startRiding fires EntityMountEvent, and onPodMount
    // already applies the space boost or resets to DMZ's stock speed from the destination dimension.
    private void reseatPodAfterTeleport(ServerPlayer player, boolean ours, boolean ship)
    {
        try
        {
            if (player.isRemoved() || !(player.level() instanceof ServerLevel dest))
            {
                handBackCarriedPod(player, ours, ship);
                return;
            }

            SpacePodEntity fresh = new SpacePodEntity(MainEntities.SPACE_POD.get(), dest);
            fresh.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0.0F);
            if (ours)
            {
                fresh.getPersistentData().putUUID(SpacePodDeploy.OWNER_TAG, player.getUUID());
            }
            dest.addFreshEntity(fresh);
            player.startRiding(fresh, true);
            if (ours)
            {
                PodDeployData.set(player, fresh.getUUID());
            }
        }
        catch (Throwable t)
        {
            // DMZ internals shifted (or SPACE_POD unavailable). The old pod is already gone, so hand over the item
            // it came from: the pilot keeps their ship even though they arrive standing on the surface.
            handBackCarriedPod(player, ours, ship);
        }
    }

    // Re-derive and apply / reset the pod's 2x-max-flight-speed boost the moment a pilot BOARDS a pod (Job 1's
    // "on mount" re-derive point). Covers a pod deployed in space and boarded by right-click, and a re-board after a
    // relog. A pod boarded OUTSIDE space is reset to DMZ's stock speed. Server-authoritative for attributes, so the
    // client half of the event is ignored. Throwable-guarded via PodSpeedBoost, which never throws.
    @SubscribeEvent
    public void onPodMount(net.minecraftforge.event.entity.EntityMountEvent event)
    {
        if (!event.isMounting())
        {
            return;
        }
        if (!(event.getEntityMounting() instanceof ServerPlayer player))
        {
            return;
        }
        if (!(event.getEntityBeingMounted() instanceof SpacePodEntity pod))
        {
            return;
        }
        if (pod.level().isClientSide)
        {
            return;
        }
        if (SpaceDimension.isSpace(pod.level()))
        {
            PodSpeedBoost.apply(player, pod);
        }
        else
        {
            PodSpeedBoost.reset(pod);
        }
    }

    // Fly the pod straight UP under power toward the entry altitude this tick, the powered-ascent half of a launch
    // from a surface dimension (replaces the old instant blink to space). Once the pod passes entryAltitude we do the
    // single unavoidable cross-dimension teleport through enterSpace, which brings the pod (enterSpaceAboardPod) and
    // places arrival beside the target body; driveAutopilot then takes over in space on the following tick. The climb
    // is a CONSTANT-step rise integrated by the client's own travel off the synced target (see integratePod /
    // syncIfChanged and the space drive below), so it moves smoothly rather than snapping. Runs only while an autopilot
    // is armed, the pilot is aboard a pod, and this is an eligible non-forbidden launch dimension off cooldown (see
    // onPlayerTick).
    private void driveAscent(ServerPlayer player, SpacePodEntity pod)
    {
        logAutopilotStart(player, SpaceAutopilot.target(player));

        UUID id = player.getUUID();
        // begin the climb on the first ascent tick: capture the start Y (so the constant step spans this climb in about
        // ASCENT_TICKS ticks) and the fixed target column (launch X/Z, entryAltitude), and start the tick index at 0. A
        // resumed ascent after a relog simply re-captures from the current position.
        Integer tickBox = ascentTick.get(id);
        int t = tickBox != null ? tickBox : 0;
        if (tickBox == null)
        {
            ascentStartY.put(id, pod.getY());
            ascentTarget.put(id, new Vec3(pod.getX(), entryAltitude, pod.getZ()));
        }
        double startY = ascentStartY.getOrDefault(id, pod.getY());
        Vec3 target = ascentTarget.getOrDefault(id, new Vec3(pod.getX(), entryAltitude, pod.getZ()));

        // crossed the entry altitude, or the safety cap has run out: the single unavoidable cross-dim teleport.
        // enterSpace detects the pod and routes to enterSpaceAboardPod (spawns a fresh pod in space, reseats the pilot),
        // placing arrival beside the target body so the climb reads as continuing into space rather than stopping dead.
        // driveAutopilot resumes next tick, which sends the fresh space-cruise sync. Release the origin-dimension route
        // window and drop the sync memo BEFORE the teleport so nothing is left pinned in the origin dimension and the
        // space cruise re-syncs cleanly. Log the ACTUAL ascent length so a future timing complaint is diagnosable
        // straight from the server log.
        if (pod.getY() >= entryAltitude || t >= ASCENT_TICK_CAP)
        {
            LOGGER.info("Space autopilot: ascent for {} reached y={} in {} ticks (~{}s)",
                    player.getGameProfile().getName(), String.format("%.1f", pod.getY()), t,
                    String.format("%.1f", t / 20.0));
            ascentTick.remove(id);
            ascentStartY.remove(id);
            ascentTarget.remove(id);
            SpaceRouteChunks.release(player);
            clearSync(player);
            enterSpace(player);
            return;
        }

        ascentTick.put(id, t + 1);

        // constant per-tick step: the whole climb divided by the tick budget, so the pod rises straight up at a steady
        // rate and reaches entryAltitude in about ASCENT_TICKS ticks. Floored at the pod's own flight step so a very
        // short climb (started high) still moves at a sensible speed rather than a crawl.
        double climbStep = Math.max((entryAltitude - startY) / (double) ASCENT_TICKS, podStep(pod));

        ResourceLocation dimId = player.level().dimension().location();
        syncIfChanged(player, dimId, target, climbStep);
        integratePod(player, pod, target, climbStep);
    }

    // Drive the ridden pod toward the autopilot's target body this tick: sync the target and step to the controlling
    // client (which integrates the actual motion) and advance the server pod one matching step. Runs every tick while an
    // autopilot is active and the pilot is in space (see onPlayerTick). Moves at the SAME 2x speed as manual flight so
    // the trip feels consistent (step = FLYING_SPEED * 0.45, the exact per-tick horizontal delta SpacePodEntity.travel
    // uses). We stop advancing once the pod is within the body's landing volume and let tickInSpace's existing landing
    // detection fire landOnPlanet NATURALLY (which recalls the pod to the chip), rather than teleporting to the surface.
    private void driveAutopilot(ServerPlayer player)
    {
        SpacePodEntity pod = ridingSpacePod(player);
        if (pod == null)
        {
            // dismounted or pod destroyed mid-course: cancel so nobody is dragged by a stale course.
            stopAutopilot(player, "pod dismounted or destroyed mid-course");
            return;
        }
        MinecraftServer server = player.getServer();
        PlanetRegistry.Planet body = bodyForKey(server, SpaceAutopilot.target(player));
        if (body == null)
        {
            // the course target is no longer a body (datapack/destination change mid-trip): stop rather than fly on.
            stopAutopilot(player, "course target is no longer a planet body");
            return;
        }
        logAutopilotStart(player, SpaceAutopilot.target(player));

        Vec3 target = body.position();
        Vec3 pos = pod.position();
        Vec3 delta = target.subtract(pos);
        double dist = delta.length();
        // once inside the landing volume (a sphere well within the per-axis cube the landing check uses), hold and
        // let tickInSpace fire the landing. Guards against overshooting through the body during the cooldown window.
        double reach = body.radius() + LANDING_MARGIN;
        if (dist <= reach)
        {
            // stop coasting: zero the pod's own delta so it holds at the body until tickInSpace lands it.
            try
            {
                pod.setDeltaMovement(Vec3.ZERO);
            }
            catch (Throwable ignored)
            {
            }
            return;
        }

        double step = podStep(pod);
        if (step <= 0.0D)
        {
            return;
        }

        // HOLD WHEN CHUNKS LAG. At the cruise floor the pod crosses more than a chunk per tick and can outrun the chunk
        // stream: the controlling client SKIPS its whole tick while its local chunk has not arrived and stops sending
        // vehicle move packets, and because the client is authoritative for a ridden vehicle the server pod freezes with
        // it and the trip never completes. So before advancing we test whether the chunk the pod is ABOUT TO ENTER (its
        // position after this tick's step) is loaded full server-side. If it is not, we PAUSE the advance this tick
        // instead of driving into a chunk the client is still waiting on. We keep force-loading the forward window
        // (SpaceRouteChunks.update below) so generation/loading catches up, hold the pod's own delta at zero so it does
        // not coast, and do NOT change the synced step, so nothing accumulates: a resumed tick takes exactly one normal
        // step. See SpaceRouteChunks.chunkReady for why this is a server-side proxy for the client's state, not a
        // guarantee of it. The client stalls on the SAME missing local chunk and, being authoritative, holds with us.
        UUID id = player.getUUID();
        ResourceLocation dimId = player.level().dimension().location();
        ServerLevel level = (ServerLevel) pod.level();
        Vec3 dir = delta.scale(1.0 / dist);
        double stepThisTick = Math.min(step, dist);
        Vec3 nextPos = pos.add(dir.scale(stepThisTick));
        boolean ready = SpaceRouteChunks.chunkReady(level, nextPos.x, nextPos.z);
        int held = autopilotHoldTicks.getOrDefault(id, 0);
        if (!ready && held < MAX_HOLD_TICKS)
        {
            autopilotHoldTicks.put(id, held + 1);
            // keep pre-warming the forward line so the missing chunk loads, but do not advance the pod or touch the sync.
            SpaceRouteChunks.update(player, level, pos, dir, step);
            try
            {
                pod.setDeltaMovement(Vec3.ZERO);
            }
            catch (Throwable ignored)
            {
            }
            return;
        }
        if (!ready)
        {
            // hold timed out: the chunk never loaded within MAX_HOLD_TICKS. Advance anyway so the pilot is never trapped
            // on a stalled trip; the hold counter resets below, so if it is still missing next tick the pod creeps
            // forward one step per timeout rather than hanging forever.
            LOGGER.warn("Space autopilot: chunk ahead of {} not ready after {} ticks; advancing anyway (possible hitch)",
                    player.getGameProfile().getName(), MAX_HOLD_TICKS);
        }
        autopilotHoldTicks.remove(id);

        // sync the destination body centre and the constant step to the controlling client, then advance the SERVER pod
        // one step so other players see honest motion and arrival detection reads a real position. The client integrates
        // the same step toward the same target (see MixinDmzSpacePodTravel) and reports position through the ordinary
        // vehicle path; landing (tickInSpace) intercepts well before the pod reaches the body centre, so aiming the sync
        // at the centre is safe.
        syncIfChanged(player, dimId, target, step);
        integratePod(player, pod, target, step);
    }

    // Advance the SERVER pod one clamped step toward `target` using plain setDeltaMovement + move (NEVER teleportTo,
    // which would trigger absMoveTo on observers and re-introduce the judder). This is the server half of the
    // client-integrated drive: for the controlling pilot the client is authoritative and reports position through the
    // ordinary ServerboundMoveVehiclePacket path, but running the identical integration here keeps the server pod moving
    // honestly for OTHER players' interpolation and for arrival detection, and it sets the pod's deltaMovement to the
    // step so the vehicle "moved too quickly" tolerance (which compares against that delta) is satisfied. Also advances
    // the rolling route chunk window along the travel vector. Throwable-guarded: a DMZ/vehicle shape shift aborts the
    // autopilot cleanly rather than crashing the player tick.
    private void integratePod(ServerPlayer player, SpacePodEntity pod, Vec3 target, double step)
    {
        try
        {
            Vec3 pos = pod.position();
            Vec3 delta = target.subtract(pos);
            double dist = delta.length();
            Vec3 move = (dist <= step || dist < 1.0e-6) ? delta : delta.scale(step / dist);
            if (move.lengthSqr() > 1.0e-10)
            {
                float yaw = yawTowards(pos, target);
                pod.setYRot(yaw);
                pod.setYBodyRot(yaw);
                pod.setYHeadRot(yaw);
            }
            pod.setDeltaMovement(move);
            pod.move(MoverType.SELF, move);

            // pre-warm chunks ahead along the travel vector so the client never stalls waiting for a chunk to arrive.
            // Pass the per-tick step so the look-ahead window covers a fixed number of TICKS of travel, not a fixed
            // distance: a faster cruise (an invested flyer, or a raised cruise floor) pins proportionally more chunks
            // ahead so the lead time in ticks stays constant.
            Vec3 dir = dist < 1.0e-6 ? Vec3.ZERO : delta.scale(1.0 / dist);
            SpaceRouteChunks.update(player, (ServerLevel) pod.level(), pod.position(), dir, step);
        }
        catch (Throwable t)
        {
            stopAutopilot(player, "pod integrate failed (DMZ/vehicle shape shift)");
        }
    }

    // Send a PacketSpaceAutopilotSync to the controlling pilot only when the target, its dimension or the step has
    // actually changed since the last send, so the client learns the course on start, on a target/step change (a launch
    // crossing into space switches the target from the ascent column to the destination body) and never per tick. The
    // client integrates deterministically off this, so a per-tick stream is exactly what we are removing.
    private void syncIfChanged(ServerPlayer player, ResourceLocation dim, Vec3 target, double step)
    {
        UUID id = player.getUUID();
        double[] prev = lastSync.get(id);
        ResourceLocation prevDim = lastSyncDim.get(id);
        if (prev != null && dim.equals(prevDim)
                && Math.abs(prev[0] - target.x) < 1.0e-3
                && Math.abs(prev[1] - target.y) < 1.0e-3
                && Math.abs(prev[2] - target.z) < 1.0e-3
                && Math.abs(prev[3] - step) < 1.0e-6)
        {
            return;
        }
        lastSync.put(id, new double[] { target.x, target.y, target.z, step });
        lastSyncDim.put(id, dim);
        sendSync(player, PacketSpaceAutopilotSync.active(dim, target, step));
    }

    // Drop the per-player sync memo so the next drive tick re-sends the sync (used on the cross-into-space handoff and on
    // stop). Does not itself send anything.
    private void clearSync(ServerPlayer player)
    {
        lastSync.remove(player.getUUID());
        lastSyncDim.remove(player.getUUID());
    }

    // Send an autopilot sync to one player, guarded against a null connection (a teleport-in-progress or a player being
    // removed) so the send never throws inside the player tick.
    private void sendSync(ServerPlayer player, PacketSpaceAutopilotSync packet)
    {
        try
        {
            if (player.connection == null)
            {
                return;
            }
            NetworkUtils.sendTo(packet, player);
        }
        catch (Throwable ignored)
        {
            // a failed sync must never break the drive; the client falls back to DMZ's own travel until the next send.
        }
    }

    // the per-tick travel distance for the autopilot drive: the pod's flight speed scaled by the same factor manual
    // flight uses, so a driven trip feels exactly like a hand-flown one. Returns 0 if the attribute cannot be read
    // (callers HOLD on a 0 rather than abort, so a transient read failure never cancels a trip).
    private static double podStep(SpacePodEntity pod)
    {
        try
        {
            double attrStep = pod.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FLYING_SPEED)
                    * PodSpeedBoost.POD_HORIZONTAL_FACTOR;
            // floor the autopilot cruise so an uninvested pilot is not stranded on a many-minute crossing now that the
            // fixed planets are tens of thousands of blocks apart. A strongly invested flyer already exceeds the floor
            // and keeps their own faster speed. Only the autopilot reads this; hand-flying still uses the raw attribute.
            return Math.max(attrStep, PlanetSpawnModule.spaceAutopilotCruiseSpeed());
        }
        catch (Throwable t)
        {
            return 0.0D;
        }
    }

    // Log ONCE that the autopilot has begun driving this player toward its target. Idempotent per trip via the
    // autopilotAnnounced set, so the every-tick drive does not spam the log; the matching end line comes from
    // stopAutopilot. Runtime diagnostic on purpose: a previous drive failed SILENTLY and cost a whole test cycle to
    // find, so a live launch now leaves a visible trail.
    private void logAutopilotStart(ServerPlayer player, String target)
    {
        if (autopilotAnnounced.add(player.getUUID()))
        {
            LOGGER.info("Space autopilot: driving {} toward {}", player.getGameProfile().getName(), target);
        }
    }

    // Log (once, if it was driving) and clear an active autopilot with a stated reason. Single chokepoint so every
    // cancel/complete path leaves exactly one end line for the start line: an ABORT (dismount, target lost, nudge
    // failure) states why, and a successful trip end (a landing/leave routed through carryPodThroughTeleport) reads as the
    // trip ending. Clearing with no autopilot, or for a player who was never being driven, is a silent no-op.
    private void stopAutopilot(ServerPlayer player, String reason)
    {
        if (autopilotAnnounced.remove(player.getUUID()))
        {
            LOGGER.info("Space autopilot: ended for {} ({})", player.getGameProfile().getName(), reason);
        }
        // an aborted or completed trip must not leave a half-run ascent schedule behind for the next launch to inherit.
        ascentTick.remove(player.getUUID());
        ascentStartY.remove(player.getUUID());
        ascentTarget.remove(player.getUUID());
        // drop any chunk-lag hold state so the next trip starts un-held.
        autopilotHoldTicks.remove(player.getUUID());
        // tell the client the autopilot has stopped so its own travel integration disengages and the pod returns to
        // manual/DMZ control, drop the sync memo, and release the route chunk window. This is the single chokepoint
        // every cancel/complete path routes through, so it is where all three teardowns belong.
        clearSync(player);
        sendSync(player, PacketSpaceAutopilotSync.inactive());
        SpaceRouteChunks.release(player);
        SpaceAutopilot.clear(player);
    }

    // the planet body with the given key, or null. Mirrors the key match PlanetCourse uses; the autopilot only ever
    // targets fixed bodies (PlanetCourse.setCourseIfBody sets a course only for PlanetRegistry bodies).
    private static PlanetRegistry.Planet bodyForKey(MinecraftServer server, String key)
    {
        if (server == null || key == null || key.isEmpty())
        {
            return null;
        }
        for (PlanetRegistry.Planet p : PlanetRegistry.bodies(server))
        {
            if (p.key.equals(key))
            {
                return p;
            }
        }
        return null;
    }

    private boolean onCooldown(ServerPlayer player)
    {
        long until = player.getPersistentData().getLong(COOLDOWN_TAG);
        return until > 0 && System.currentTimeMillis() < until;
    }

    private void stampCooldown(ServerPlayer player)
    {
        player.getPersistentData().putLong(COOLDOWN_TAG, System.currentTimeMillis() + COOLDOWN_MS);
        // Every cooldown stamp marks a discontinuity (an arrival, a landing, a leave), so the previous space
        // position is no longer somewhere this player flew FROM. Dropping it stops the next tick sweeping a line
        // across the system and landing them on whatever that line happened to cross.
        lastSpacePos.remove(player.getUUID());
    }

    // Stamp the same descend-out re-trigger grace an ordinary space entry uses, for a player who was dropped INTO
    // space by an EXTERNAL teleport (chiefly a vanilla /tp, e.g. Xaero's "teleport to player", which has no SU hook
    // of its own). Without this, a player teleported next to a friend who is floating below the return altitude
    // (returnAltitude, 288 by default) would be bounced straight back to their origin dimension by the descend-out
    // check on the VERY NEXT tick, which is the "cannot teleport to people in space" half of the reported bug. The
    // grace only suppresses the automatic descend-out bounce for COOLDOWN_MS; it never blocks a deliberate fly-down
    // exit after that. Static so the vanilla-/tp mixin can call it without holding a module instance; it only
    // touches the player's persistent data, which is exactly what stampCooldown does.
    public static void grantArrivalGrace(ServerPlayer player)
    {
        player.getPersistentData().putLong(COOLDOWN_TAG, System.currentTimeMillis() + COOLDOWN_MS);
    }

    @Override
    public void load(Builder BUILDER, boolean isReload)
    {
        BUILDER.push("SpaceTravel");
        cfgEnabled = BUILDER
                .comment("Master switch for the space-travel feature. When false, flying up never sends a player to space and no altitude checks run.")
                .define("enabled", true);
        cfgEntryAltitude = BUILDER
                .comment("Y height a player must rise above (in an eligible dimension) to be moved into space. Kept above the overworld ceiling so ordinary building never triggers it.")
                .defineInRange("entryAltitude", 1000.0, 0.0, 4096.0);
        cfgReturnAltitude = BUILDER
                .comment("Y height a player in space must descend below to be pulled down onto the nearest planet (or, if no landable body is within nearestPlanetRange, returned to where they took off). Kept below the space arrival height so arriving never instantly triggers it.")
                .defineInRange("returnAltitude", 288.0, -64.0, 4096.0);
        cfgNearestPlanetRange = BUILDER
                .comment("How far, in blocks, to search for a landable body when a player sinks below returnAltitude. Measured to each body's SURFACE. If a planet or moon is within this range the player is landed on the nearest one; only if nothing landable is in range do they fall back to returning to where they took off. The default reaches a few generated sectors out so a body is almost always found, while staying a bounded once-per-trip scan.")
                .defineInRange("nearestPlanetRange", 6144.0, 0.0, 100000.0);
        cfgEligibleDimensions = BUILDER
                .comment("EXTRA dimension ids a player may launch to space from, on top of the planet bodies. Every planet body (Earth, Namek, Sacred Kai and any datapack-added destination) is already launchable automatically, so this list is only needed to allow a NON-planet launch point (e.g. a hub dimension). minecraft:the_nether, minecraft:the_end and dragonminez:otherworld are ALWAYS excluded in code and cannot be enabled here.")
                .defineList("eligibleDimensions", Arrays.asList("minecraft:overworld"), o -> o instanceof String);
        BUILDER.pop();
    }

    @Override
    public void bakeConfig(boolean reload)
    {
        enabled = cfgEnabled.get();
        entryAltitude = cfgEntryAltitude.get();
        returnAltitude = cfgReturnAltitude.get();
        nearestPlanetRange = cfgNearestPlanetRange.get();
        eligibleDimensions = new java.util.ArrayList<>(cfgEligibleDimensions.get());
    }

    @Override
    public ConfigData returnData()
    {
        return data;
    }
}
