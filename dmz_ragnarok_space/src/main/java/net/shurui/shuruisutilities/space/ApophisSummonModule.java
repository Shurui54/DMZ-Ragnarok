package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonBallSetDefinition;
import com.dragonminez.common.events.DMZEvent;
import com.dragonminez.server.events.DragonBallsHandler;
import com.dragonminez.server.world.data.DragonBallSavedData;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.shurui.shuruisutilities.world.space.SurfaceTravelData;

/**
 * The Black Star ("apophis") dragon-ball endgame: seven balls seeded across seven random generated planets within 45,000
 * blocks of Earth, a red dragon (apophis) summonable ONLY on a generated planet, a one-real-week timer armed by that
 * summon, and a planet-aware radar so a player can actually find the balls across planets.
 *
 * <h3>Placement (DMZ's native pending model)</h3>
 * The Black Star SET carries copies 0 (see {@code SuDragonBallDefinitions}), so DMZ never auto-scatters it. Instead this
 * module seeds seven PENDING entries into {@link DragonBallSavedData} on the shared {@code planet_surface} dimension, one
 * per chosen planet at that planet's surface cell-centre XZ. DMZ's own {@code DragonBallsHandler} materialises a pending
 * ball from the heightmap the first time its chunk loads (which happens when a player lands on the planet, since the
 * landing stamps the surface BEFORE teleporting the player in). Which star sits on which planet is persisted in
 * {@link ApophisPlanetData} so it never moves within a cycle and survives restarts.
 *
 * <h3>Summon gate</h3>
 * The dimension gate itself is enforced by {@code MixinDmzDragonBallBlock} (a HEAD inject on {@code use} that refuses a
 * completed Black Star set outside {@code planet_surface} with a message). This module owns everything that follows a
 * SUCCESSFUL summon, hooked off DMZ's own {@code DragonSummonedEvent}: arm the timer and re-scatter fresh balls.
 *
 * <h3>Timer</h3>
 * Arming records the summon planet, its SPACE cell key (so the planet can be re-derived a week later with nobody nearby,
 * via {@link GeneratedPlanets#generatedFor}, not the anchor-based {@code findGenerated}), and an absolute wall-clock
 * deadline (mirroring {@code GuildRaidLockouts}, so it advances while the server is offline: the user asked for ONE REAL
 * WEEK, not game time). "Re-found" is defined as every one of the seven Black Star stars having been COLLECTED (its ball
 * block mined by a player, firing {@link BlockEvent.BreakEvent}) at least once since the summon. When all seven are
 * collected the timer disarms and the planet is spared; if the deadline passes first the planet is destroyed via
 * {@link PlanetDestruction}. A slow server tick drives expiry and the approaching-deadline warnings regardless of who is
 * online.
 */
@SUModule(name = "ApophisSummon", parentMod = ShuruisUtilities.class, version = ShuruisUtilities.CURRENT_MODULE_VERSION)
public class ApophisSummonModule
{
    private static final String SET_ID = "blackstar";

    // The scatter field: seven balls on generated planets within this many blocks of Earth's space body.
    private static final double SCATTER_RADIUS = 45000.0;

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60L * MINUTE;
    private static final long DAY = 24L * HOUR;
    private static final long WEEK = 7L * DAY;

    // Approaching-deadline broadcast thresholds, DESCENDING. warnStage in ApophisPlanetData indexes this array so each
    // fires exactly once as the remaining time crosses it.
    private static final long[] WARN_THRESHOLDS = { 3L * DAY, DAY, 6L * HOUR, HOUR, 10L * MINUTE };

    // Run the timer sweep on this slow interval (wall-clock driven, so a coarse cadence is plenty). 200 ticks = ~10s.
    private static final int SWEEP_INTERVAL = 200;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event)
    {
        MinecraftServer server = event.getServer();
        ApophisPlanetData data = ApophisPlanetData.get(server);
        if (data.isScatterInitialized())
        {
            return;
        }
        // first-ever setup: seed the seven balls. If the field somehow cannot yield seven planets (it comfortably does
        // at default config, ~520 within range), leave it uninitialised and retry on the next start rather than crash.
        if (scatter(server))
        {
            data.setScatterInitialized(true);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
        {
            return;
        }
        if (server.getTickCount() % SWEEP_INTERVAL == 0)
        {
            try
            {
                sweep(server);
            }
            catch (Throwable t)
            {
                // never let the timer sweep take down the server tick; a DMZ/space API drift degrades to "no sweep".
                LoggingHandler.sulog.warn("[Apophis] Timer sweep failed; skipping this pass.", t);
            }
        }
    }

    @SubscribeEvent
    public void onDragonSummoned(DMZEvent.DragonSummonedEvent event)
    {
        try
        {
            if (!SET_ID.equals(event.getBallSetId()))
            {
                return;
            }
            ServerLevel level = event.getLevel();
            MinecraftServer server = level.getServer();
            if (server == null)
            {
                return;
            }

            // the planet the wish fired on: the summoner is standing on it, so their recorded surface planet is the
            // authoritative answer; fall back to resolving from the ball block position for an odd arrival.
            String planetId = "";
            if (event.getPlayer() instanceof ServerPlayer summoner)
            {
                planetId = SurfaceTravelData.planetId(summoner);
            }
            if (planetId.isEmpty())
            {
                planetId = resolvePlanetFromPos(server, event.getPosition());
            }

            ApophisPlanetData data = ApophisPlanetData.get(server);
            if (!planetId.isEmpty())
            {
                // resolve the SPACE cell key while an anchor is still near (the summoner is on the surface with a
                // recorded body position, so findGenerated resolves it). Stored so a week later we re-derive the planet
                // purely, with nobody around.
                GeneratedPlanets.Generated planet = GeneratedPlanets.findGenerated(server, planetId);
                if (planet != null)
                {
                    data.arm(planetId, planet.cellKey, System.currentTimeMillis() + WEEK);
                    broadcast(server, "apophis_planet_armed", Component.literal(GeneratedPlanets.nameFor(planetId)));
                }
                else
                {
                    LoggingHandler.sulog.warn("[Apophis] Summoned on {} but could not resolve its space cell; no "
                            + "destruction timer armed.", planetId);
                }
            }
            else
            {
                LoggingHandler.sulog.warn("[Apophis] Summon fired but the planet could not be resolved; no timer armed.");
            }

            // re-scatter seven fresh balls so they can be re-found within the week. This IS the cycle the timer measures.
            scatter(server);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[Apophis] Failed to handle the dragon summon.", t);
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event)
    {
        if (event.getLevel().isClientSide())
        {
            return;
        }
        Block block = event.getState().getBlock();
        DragonBallSetDefinition def = DragonBallDefinitions.getBallSetForBlock(block);
        if (def == null || !SET_ID.equals(def.getId()))
        {
            return;
        }
        Integer star = def.getStarForBlock(block);
        if (star == null)
        {
            return;
        }
        ServerPlayer breaker = event.getPlayer() instanceof ServerPlayer sp ? sp : null;
        MinecraftServer server = breaker != null ? breaker.getServer() : ServerLifecycleHooks.getCurrentServer();
        if (server == null)
        {
            return;
        }
        ApophisPlanetData data = ApophisPlanetData.get(server);
        if (!data.isArmed())
        {
            return;
        }
        data.markFound(star);
        if (data.allFound())
        {
            broadcast(server, "apophis_planet_spared",
                    Component.literal(GeneratedPlanets.nameFor(data.summonPlanetId())));
            data.disarm();
        }
    }

    private void sweep(MinecraftServer server)
    {
        ApophisPlanetData data = ApophisPlanetData.get(server);
        if (!data.isArmed())
        {
            return;
        }
        if (data.allFound())
        {
            broadcast(server, "apophis_planet_spared",
                    Component.literal(GeneratedPlanets.nameFor(data.summonPlanetId())));
            data.disarm();
            return;
        }
        long remaining = data.deadlineMillis() - System.currentTimeMillis();
        if (remaining <= 0L)
        {
            destroyPlanet(server, data);
            return;
        }
        // approaching-deadline warnings: advance the stage cursor over every threshold now crossed, each broadcast once.
        int stage = data.warnStage();
        Component name = Component.literal(GeneratedPlanets.nameFor(data.summonPlanetId()));
        while (stage < WARN_THRESHOLDS.length && remaining <= WARN_THRESHOLDS[stage])
        {
            broadcast(server, "apophis_planet_warning", name, Component.literal(humanDuration(WARN_THRESHOLDS[stage])));
            stage++;
        }
        data.setWarnStage(stage);
    }

    private void destroyPlanet(MinecraftServer server, ApophisPlanetData data)
    {
        String planetId = data.summonPlanetId();
        Component name = Component.literal(GeneratedPlanets.nameFor(planetId));
        int[] cell = GeneratedPlanets.parseCellKey(data.summonCellKey());
        if (cell != null)
        {
            // re-derive the planet purely from its stored space cell (no anchor needed). A null result means the cell is
            // already destroyed or has regenerated into a different planet; an id mismatch means it regenerated. In
            // either case the planet they summoned on is already gone, so we simply disarm and announce.
            GeneratedPlanets.Generated planet = GeneratedPlanets.generatedFor(server, cell[0], cell[1], cell[2]);
            if (planet != null && planet.id.equals(planetId))
            {
                PlanetDestruction.destroy(server.overworld(), planet, null, false);
            }
        }
        broadcast(server, "apophis_planet_destroyed", name);
        data.disarm();
    }

    // pick seven live generated planets within SCATTER_RADIUS of Earth's body and seed one Black Star ball on each,
    // persisting the star -> planet assignment. Returns false (and changes nothing) if the field cannot yield seven.
    private boolean scatter(MinecraftServer server)
    {
        ServerLevel surface = SurfaceDimension.level(server);
        if (surface == null)
        {
            return false;
        }
        DragonBallSetDefinition def = DragonBallDefinitions.getBallSet(SET_ID);
        if (def == null)
        {
            LoggingHandler.sulog.warn("[Apophis] Black Star set is not registered; cannot scatter.");
            return false;
        }
        Vec3 earth = PlanetPositions.position("minecraft:overworld");
        List<GeneratedPlanets.Generated> candidates = GeneratedPlanets.generatedNear(server, earth, SCATTER_RADIUS);
        if (candidates.size() < 7)
        {
            LoggingHandler.sulog.warn("[Apophis] Only {} generated planet(s) within {} of Earth; need 7 to scatter.",
                    candidates.size(), (long) SCATTER_RADIUS);
            return false;
        }
        Collections.shuffle(candidates, new Random());

        List<Integer> stars = new ArrayList<>(def.getStars());
        Collections.sort(stars);
        DragonBallSavedData db = DragonBallSavedData.get(surface);
        Map<Integer, ApophisPlanetData.Assignment> assignments = new HashMap<>();
        for (int i = 0; i < 7 && i < stars.size(); i++)
        {
            int star = stars.get(i);
            GeneratedPlanets.Generated planet = candidates.get(i);
            Vec3 centre = SurfaceDimension.cellCentre(planet.id);
            // reset any prior pending/active for this star, then register the fresh pending target at the cell centre.
            List<BlockPos> pending = db.getPendingBalls(SET_ID).get(star);
            List<BlockPos> active = db.getActiveBalls(SET_ID).get(star);
            if (pending != null)
            {
                pending.clear();
                pending.add(new BlockPos((int) Math.floor(centre.x), 0, (int) Math.floor(centre.z)));
            }
            if (active != null)
            {
                active.clear();
            }
            assignments.put(star, new ApophisPlanetData.Assignment(planet.id, planet.cellKey));
        }
        db.setDirty();
        DragonBallsHandler.syncRadar(surface);
        ApophisPlanetData.get(server).setAssignments(assignments);
        // push the fresh star -> planet layout so the client radar HUD can map each Black Star ball's surface cell to its
        // planet's SPACE body (PacketSpaceLayoutSync carries the surface-cell -> body anchors built from these
        // assignments). Without this a re-scatter's new planets would not be mappable client-side until the next relog.
        SpaceLayoutSync.syncAll();
        LoggingHandler.sulog.info("[Apophis] Scattered seven Black Star balls across seven generated planets.");
        return true;
    }

    // the stamped generated planet whose surface cell matches this planet_surface position, or "" if none. Mirrors
    // SpaceTravelModule.resolveStampedPlanetId: cell centres sit at index * CELL_SPACING, so the grid index is the
    // rounded quotient, matched against the tiny set of ids with a stamped surface.
    private static String resolvePlanetFromPos(MinecraftServer server, BlockPos pos)
    {
        long cellX = Math.round(pos.getX() / (double) SurfaceDimension.CELL_SPACING);
        long cellZ = Math.round(pos.getZ() / (double) SurfaceDimension.CELL_SPACING);
        for (String id : GeneratedPlanetClaims.get(server).surfaceGeneratedIds())
        {
            if (SurfaceDimension.cellX(id) == cellX && SurfaceDimension.cellZ(id) == cellZ)
            {
                return id;
            }
        }
        return "";
    }

    private static void broadcast(MinecraftServer server, String key, Component... args)
    {
        Component msg = Component.translatable("message.dmz_ragnarok.core." + key, (Object[]) args);
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            player.sendSystemMessage(msg);
        }
    }

    // an English duration label for a warning threshold ("3 days", "1 hour", "10 minutes"). Kept short and coarse: the
    // thresholds are always whole days/hours/minutes, so this never needs a compound form.
    private static String humanDuration(long millis)
    {
        if (millis >= DAY)
        {
            long d = millis / DAY;
            return d + (d == 1 ? " day" : " days");
        }
        if (millis >= HOUR)
        {
            long h = millis / HOUR;
            return h + (h == 1 ? " hour" : " hours");
        }
        long m = millis / MINUTE;
        return m + (m == 1 ? " minute" : " minutes");
    }
}
