package net.shurui.shuruisutilities.space;

import java.util.List;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.config.ConfigData;
import net.shurui.shuruisutilities.core.config.ConfigLoaderBase;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.Builder;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Stamps the space-dimension ASTEROID field into real blocks, and owns the SpacePlanets config that drives the whole
 * space layout. It NO LONGER spawns any body entities: planets, stars and black holes are not entities at all. They are
 * a pure function of the world position (see {@link GeneratedPlanets}, {@link StarPositions}, {@link BlackHolePositions}
 * and the fixed {@link PlanetRegistry}), so the CLIENT re-derives and draws them directly (SpaceBodyRenderer) and the
 * SERVER re-derives the star/black-hole hazards straight from the same hash ({@link SpaceHazardModule}). Nothing about a
 * planet, star or black hole is spawned, tracked, saved, deduped or cleaned up any more, which is what finally removes
 * the whole class of "distant scenery as tracked entities" bugs (bodies bred into unloaded chunks, ghosts that persisted,
 * bodies invisible past the tracking range, and bodies that refused to spawn because their far chunk was never loaded).
 *
 * <p>ASTEROIDS ARE THE ONE THING WITH A SERVER TICK, because they are real terrain: for each asteroid cell
 * ({@link AsteroidPositions}) near a player it STAMPS the lump into blocks once (via {@link AsteroidStamp}), recording
 * the cell in {@link AsteroidStampData} so it persists and is never re-stamped over a player's mining. A lump is only
 * stamped when its whole footprint is loaded, so no half of a structure is ever dropped into an unloaded chunk.
 *
 * <p>The config baked here also feeds the client: the layout numbers (sector sizes, densities, ring radius/jitter) are
 * synced to clients on every bake via {@link SpaceLayoutSync#syncAll()} so the client draws bodies exactly where the
 * server derives them.
 *
 * <p>Auto-registered on the Forge event bus by the SU module launcher (like {@link SpaceTravelModule}).
 */
@SUModule(name = "SpacePlanets", parentMod = ShuruisUtilities.class, version = ShuruisUtilities.CURRENT_MODULE_VERSION)
public class PlanetSpawnModule extends ConfigLoaderBase
{
    private static ForgeConfigSpec PLANETS_CONFIG;
    private static final ConfigData data = new ConfigData("SpacePlanets", PLANETS_CONFIG, new ForgeConfigSpec.Builder());

    // config-backed settings (baked in bakeConfig)
    private static boolean enabled = true;
    private static double spawnRange = 12000.0;
    private static boolean asteroidsEnabled = true;
    private static double asteroidSpawnRange = 384.0;
    private static boolean generatedEnabled = true;
    private static double generatedSpawnRange = 12000.0;

    // Hazard settings (baked in bakeConfig). The star/black-hole spawn ranges bound the per-player derivation walk in
    // SpaceHazardModule (how far out a hazard can start affecting a player); the CLIENT draw distance (a separate config,
    // synced to the client) is set far larger so a hazard is always drawn well before it can reach the player. The
    // damage/pull strengths are read by SpaceHazardModule, which owns the per-player danger tick. All are fractions of the
    // victim's MAX HEALTH per application (not flat numbers), so they stay meaningful against DMZ's inflated health pools.
    // Package-visible getters below expose the baked values to SpaceHazardModule without a second config home.
    private static boolean starsEnabled = true;
    private static double starSpawnRange = 4096.0;
    private static double starDamageOuter = 0.05;
    private static double starDamageInner = 0.40;
    private static boolean blackHolesEnabled = true;
    private static double blackHoleSpawnRange = 4096.0;
    private static double blackHolePullOuter = 0.25;
    private static double blackHolePullInner = 6.0;
    private static double blackHoleInfluenceFactor = 30.0;

    // CLIENT body draw distance (blocks). How far the client re-derives and draws planets, stars and black holes around
    // itself. This is DECOUPLED from render distance entirely: render distance governs terrain chunks and entities, and
    // these bodies are neither (the client derives them from the shared hash and draws them itself), so a low render
    // distance never hides them. Defaulted generously (a body reads as a distant landmark you fly toward) and baked into
    // SpaceLayout, which the client reads. Comfortably exceeds every hazard field (see SpaceLayout.DEFAULT_DRAW_DISTANCE)
    // so a star or black hole is always visible long before it can affect a player, whatever their settings.
    private static double bodyDrawDistance = SpaceLayout.DEFAULT_DRAW_DISTANCE;

    private static ForgeConfigSpec.BooleanValue cfgEnabled;
    private static ForgeConfigSpec.DoubleValue cfgSpawnRange;
    private static ForgeConfigSpec.BooleanValue cfgExcludeTimeChamber;
    private static ForgeConfigSpec.BooleanValue cfgRequireLoadedDimension;
    private static ForgeConfigSpec.BooleanValue cfgAsteroidsEnabled;
    private static ForgeConfigSpec.DoubleValue cfgAsteroidSpawnRange;
    private static ForgeConfigSpec.BooleanValue cfgGeneratedEnabled;
    private static ForgeConfigSpec.DoubleValue cfgGeneratedSpawnRange;
    // hazards
    private static ForgeConfigSpec.BooleanValue cfgStarsEnabled;
    private static ForgeConfigSpec.DoubleValue cfgStarSpawnRange;
    private static ForgeConfigSpec.IntValue cfgStarSectorSize;
    private static ForgeConfigSpec.DoubleValue cfgStarDensity;
    private static ForgeConfigSpec.DoubleValue cfgStarDamageOuter;
    private static ForgeConfigSpec.DoubleValue cfgStarDamageInner;
    private static ForgeConfigSpec.BooleanValue cfgBlackHolesEnabled;
    private static ForgeConfigSpec.DoubleValue cfgBlackHoleSpawnRange;
    private static ForgeConfigSpec.IntValue cfgBlackHoleSectorSize;
    private static ForgeConfigSpec.DoubleValue cfgBlackHoleDensity;
    private static ForgeConfigSpec.DoubleValue cfgBlackHolePullOuter;
    private static ForgeConfigSpec.DoubleValue cfgBlackHolePullInner;
    private static ForgeConfigSpec.DoubleValue cfgBlackHoleInfluenceFactor;
    // density tuning (all baked into the pure derivation classes, see bakeConfig)
    private static ForgeConfigSpec.IntValue cfgGeneratedSectorSize;
    private static ForgeConfigSpec.DoubleValue cfgGeneratedDensity;
    private static ForgeConfigSpec.DoubleValue cfgFixedRingRadius;
    private static ForgeConfigSpec.DoubleValue cfgFixedRingJitter;
    private static ForgeConfigSpec.DoubleValue cfgFixedPlanetMinSeparation;
    private static ForgeConfigSpec.DoubleValue cfgSpaceAutopilotCruiseSpeed;
    // client body draw distance
    private static ForgeConfigSpec.DoubleValue cfgBodyDrawDistance;
    private static ForgeConfigSpec.IntValue cfgPlanetDebrisSeconds;
    private static ForgeConfigSpec.IntValue cfgGuildRaidSpoilsSeconds;
    private static ForgeConfigSpec.IntValue cfgSurfaceStampBlocksPerTick;
    private static ForgeConfigSpec.IntValue cfgSurfaceColumnDepth;
    private static ForgeConfigSpec.BooleanValue cfgSurfaceEdgeWrap;
    private static ForgeConfigSpec.BooleanValue cfgPlanetWeather;
    private static ForgeConfigSpec.BooleanValue cfgPlanetWeatherEffects;
    private static ForgeConfigSpec.IntValue cfgMoonSurfaceSize;
    // surface water + vegetation (stage 2b)
    private static ForgeConfigSpec.BooleanValue cfgSurfaceWaterEnabled;
    private static ForgeConfigSpec.BooleanValue cfgSurfaceVegetationEnabled;
    private static ForgeConfigSpec.IntValue cfgSurfaceSeaLevelOffset;
    private static ForgeConfigSpec.DoubleValue cfgSurfaceBasinFrequency;
    private static ForgeConfigSpec.DoubleValue cfgSurfaceVegetationDensity;
    private static ForgeConfigSpec.IntValue cfgSurfaceThemeWeightStony;
    private static ForgeConfigSpec.IntValue cfgSurfaceThemeWeightOverworld;
    private static ForgeConfigSpec.IntValue cfgSurfaceThemeWeightNamek;
    private static ForgeConfigSpec.IntValue cfgSurfaceThemeWeightNether;
    private static ForgeConfigSpec.IntValue cfgSurfaceThemeWeightEnd;
    private static ForgeConfigSpec.IntValue cfgSurfaceThemeWeightKaio;
    // surface structures (stage 2c)
    private static ForgeConfigSpec.BooleanValue cfgSurfaceStructuresEnabled;
    private static ForgeConfigSpec.DoubleValue cfgSurfaceVillageFrequency;
    private static ForgeConfigSpec.DoubleValue cfgSurfaceHutFrequency;
    private static ForgeConfigSpec.DoubleValue cfgSurfaceHutSeedPlantedChance;
    private static ForgeConfigSpec.DoubleValue cfgSurfaceHutSeedHarvestChance;
    // wild defenders (garrison)
    private static ForgeConfigSpec.BooleanValue cfgWildDefendersEnabled;
    private static ForgeConfigSpec.IntValue cfgWildDefenderMinCount;
    private static ForgeConfigSpec.IntValue cfgWildDefenderMaxCount;
    private static ForgeConfigSpec.DoubleValue cfgWildDefenderBattlePowerMin;
    private static ForgeConfigSpec.DoubleValue cfgWildDefenderBattlePowerMax;
    private static ForgeConfigSpec.DoubleValue cfgWildDefenderToughnessDivisor;
    private static ForgeConfigSpec.BooleanValue cfgWildFlatToughnessWhenDisabled;
    private static ForgeConfigSpec.IntValue cfgWildDefenderWeightSaiyan;
    private static ForgeConfigSpec.IntValue cfgWildDefenderWeightOverworld;
    private static ForgeConfigSpec.IntValue cfgWildDefenderWeightNamekian;
    private static ForgeConfigSpec.IntValue cfgWildDefenderWeightSaibamen;
    private static ForgeConfigSpec.IntValue cfgWildDefenderWeightFrostDemon;
    private static ForgeConfigSpec.IntValue cfgWildDefenderWeightRobot;
    // public conquest defender BOSS (PlanetConquest)
    private static ForgeConfigSpec.BooleanValue cfgConquerEnabled;
    private static ForgeConfigSpec.DoubleValue cfgConquerStatTolerance;
    private static ForgeConfigSpec.DoubleValue cfgConquerSpawnDistance;
    private static ForgeConfigSpec.ConfigValue<String> cfgConquerBossStony;
    private static ForgeConfigSpec.ConfigValue<String> cfgConquerBossOverworld;
    private static ForgeConfigSpec.ConfigValue<String> cfgConquerBossNamek;
    private static ForgeConfigSpec.ConfigValue<String> cfgConquerBossNether;
    private static ForgeConfigSpec.ConfigValue<String> cfgConquerBossEnd;
    private static ForgeConfigSpec.ConfigValue<String> cfgConquerBossKaio;
    private static ForgeConfigSpec.ConfigValue<String> cfgConquerBossOther;
    // owner-avatar defender for a personally-claimed planet (PlanetOwnerAvatar)
    private static ForgeConfigSpec.BooleanValue cfgClaimAvatarDefender;
    private static ForgeConfigSpec.DoubleValue cfgAvatarStatMultiplier;
    private static ForgeConfigSpec.DoubleValue cfgAvatarRespawnMinutes;
    private static ForgeConfigSpec.DoubleValue cfgExplodableWindowMinutes;
    // super-body Destroyer God
    private static ForgeConfigSpec.BooleanValue cfgSuperGodEnabled;
    private static ForgeConfigSpec.DoubleValue cfgSuperGodBattlePowerMin;
    private static ForgeConfigSpec.DoubleValue cfgSuperGodBattlePowerMax;
    private static ForgeConfigSpec.DoubleValue cfgSuperRenderDistance;

    static double bodyDrawDistance()
    {
        return bodyDrawDistance;
    }

    // How long, in seconds, a destroyed planet stays rubble before its cell bumps generation and a new planet forms.
    private static int planetDebrisSeconds;

    static int planetDebrisSeconds()
    {
        return planetDebrisSeconds;
    }

    // The outer cull range (blocks) generated planets are spawned/kept within of a player. Exposed so the admin search
    // index (GeneratedPlanetIndex) enumerates exactly the region the game populates: within this range of the space
    // origin. Package-visible getter, no second config home.
    static double generatedSpawnRange()
    {
        return generatedSpawnRange;
    }

    // How many surface blocks the batched surface stamp (SurfaceStamp.StampTask) places per server tick before it
    // yields. Spreading the stamp across ticks at this budget keeps the per-tick cost flat regardless of surface size.
    // A maximum 500-wide planet with the default deep column is on the order of 26 million block sets. Since the stamp
    // now writes terrain with NO client notify (flag 0) and resends each finished chunk whole, the client is no longer the
    // limiter it used to be (the old per-block section-delta storm is gone), so the budget can be a little higher to
    // shorten the wall-clock the player is frozen watching the planet build. Raised to 49152: a maximum planet finishes in
    // roughly 26 seconds (down from ~40 at 32768) and the outward chunk reveal runs at about 29 chunks/second, which a
    // client meshes comfortably because each chunk arrives once as one full-chunk packet rather than as a stream of
    // section deltas. A single tick's 49152 flag-0 setBlocks is still well under the ~345k the stamp used to do
    // synchronously in one tick, so no tick is heavier than one the server already tolerated. Only the small centre patch
    // is placed immediately (landing is instant); the rest fills over the following ticks while the player is held, and a
    // player who is somehow not held is still caught by SpaceTravelModule's fall-catch. Read by SurfaceStamp on the server
    // thread; seeded with the default so a read before the first bake is sane.
    private static int surfaceStampBlocksPerTick = 49152;

    static int surfaceStampBlocksPerTick()
    {
        return surfaceStampBlocksPerTick;
    }

    // How many blocks of DEEP (stone-family) body a generated planet's surface stamp places beneath the thin soil band
    // in every column, i.e. how far a planet's solid ground extends below the surface. Config-driven so a planet is a
    // genuine minable column rather than the old ~11-block shell that left everything below Y86 as void. The default 128
    // takes the deepest column's floor to about Y -34 (SURFACE_Y 96 minus the 2-block soil band minus 128), comfortably
    // above the dimension floor at -64 and leaving room for a later stage's caves and basins. Read by SurfaceStamp;
    // each stamp snapshots it at begin so a mid-stamp reload cannot split one planet across two depths.
    private static int surfaceColumnDepth = 128;

    static int surfaceColumnDepth()
    {
        return surfaceColumnDepth;
    }

    // Whether walking off the edge of a TILEABLE (generator version 2) planet wraps the player to the opposite edge
    // instead of stopping them at the invisible rim wall. Legacy (version 1, disc) planets always keep the wall
    // regardless: their terrain is a wobbled disc with void corners, so there is no matching opposite edge to arrive on.
    // Read by SpaceTravelModule on the server thread; seeded true so a read before the first bake matches the default.
    private static boolean surfaceEdgeWrapEnabled = true;

    static boolean surfaceEdgeWrapEnabled()
    {
        return surfaceEdgeWrapEnabled;
    }

    // Per-planet weather (PlanetWeather): the master switch for the whole subsystem (client precipitation/sky/sound and
    // the server effects below), and a separate switch for the light gameplay effects alone (rain extinguishing fire,
    // blizzard slowness). Seeded true so a read before the first bake matches the default. Read on the server thread
    // (PlanetWeatherEffects) and mirrored to the client only implicitly: the client computes weather itself and simply
    // draws nothing extra when it stands on no synced planet, so a server that turns weather off just stops the effects
    // while a client that never learns of a planet never draws weather either.
    private static boolean planetWeatherEnabled = true;
    private static boolean planetWeatherEffectsEnabled = true;

    public static boolean planetWeatherEnabled()
    {
        return planetWeatherEnabled;
    }

    public static boolean planetWeatherEffectsEnabled()
    {
        return planetWeatherEffectsEnabled;
    }

    // All knobs for the visible defender NPCs that guard an unowned planet (see PlanetGarrison). Baked below; read by
    // PlanetGarrison (spawn/stats/toughness) and PlanetToughness (the flat-fallback flag). Package-visible getters expose
    // them without a second config home, matching the hazard getters above.
    private static boolean wildDefendersEnabled = true;
    private static int wildDefenderMinCount = 3;
    private static int wildDefenderMaxCount = 6;
    private static double wildDefenderBattlePowerMin = 5000.0;
    private static double wildDefenderBattlePowerMax = 50000.0;
    // Divides the garrison's aggregate battle power into a clash toughness, floored at the flat clashWildToughness.
    // Lowered from 100 to 10 alongside the move of clashWildToughness onto the ki-damage scale (now 5000): with the
    // 5000 floor a divisor of 100 would push every garrison's raw toughness (aggregate battle power ~15k..300k / 100 =
    // ~150..3000) below the floor, flattening it to a constant. At 10 the garrison lands ~1500..30000, so a heavily
    // defended world reads meaningfully tougher than the bare floor while a light garrison still floors at 5000.
    private static double wildDefenderToughnessDivisor = 10.0;
    private static boolean wildFlatToughnessWhenDisabled = true;
    private static int wildDefenderWeightSaiyan = 1;
    private static int wildDefenderWeightOverworld = 1;
    private static int wildDefenderWeightNamekian = 1;
    private static int wildDefenderWeightSaibamen = 1;
    private static int wildDefenderWeightFrostDemon = 1;
    private static int wildDefenderWeightRobot = 1;

    // Knobs for the single Destroyer God that guards each of the seven SUPER dragon-ball bodies (see SuperPlanetGod).
    // Read by SuperPlanetGod (spawn/stats). Package-visible getters expose them without a second config home, matching the
    // wild-defender getters above. The god's battle-power band is far higher than a wild defender's because it is a
    // god-tier boss guarding a Super Dragon Ball; its health/melee/defense derive from that band exactly as the garrison's.
    private static boolean superGodEnabled = true;
    private static double superGodBattlePowerMin = 500000.0;
    private static double superGodBattlePowerMax = 3000000.0;
    // how near (blocks) the CLIENT must be to a super body before it draws at all. Its OWN knob, independent of
    // bodyDrawDistance, and small on purpose: a super body is 20000..45000 from Earth and 15000 from its neighbours, so a
    // 1000-block cull means it is effectively invisible until you are nearly on top of it, and the radar is what leads you
    // there. Synced to clients (PacketSpaceLayoutSync) so the cull matches this value. Server-side landing is UNAFFECTED.
    private static double superRenderDistance = 1000.0;

    // The per-tick cruise speed (blocks/tick) the pod-launch AUTOPILOT flies at when the pilot's own flight speed would
    // be slower. Now that the fixed planets sit tens of thousands of blocks apart (see fixedPlanetMinSeparation), an
    // uninvested pilot's stock pod cruise (about 2.2 blocks/tick) would make a cross-system autopilot trip many minutes
    // long, so the autopilot step is FLOORED at this value: an uninvested pilot cruises here while a strongly invested
    // flyer, who already exceeds it, keeps their own faster speed. This floors ONLY the autopilot drive, not hand-flying.
    // Read by SpaceTravelModule on the server thread. Baked below; seeded with the default so a read before the first
    // bake is sane.
    private static double spaceAutopilotCruiseSpeed = 12.0;

    static double spaceAutopilotCruiseSpeed()
    {
        return spaceAutopilotCruiseSpeed;
    }

    static boolean superGodEnabled()
    {
        return superGodEnabled;
    }

    static double superRenderDistance()
    {
        return superRenderDistance;
    }

    static double superGodBattlePowerMin()
    {
        return superGodBattlePowerMin;
    }

    static double superGodBattlePowerMax()
    {
        return superGodBattlePowerMax;
    }

    static boolean wildDefendersEnabled()
    {
        return wildDefendersEnabled;
    }

    static int wildDefenderMinCount()
    {
        return wildDefenderMinCount;
    }

    static int wildDefenderMaxCount()
    {
        return wildDefenderMaxCount;
    }

    static double wildDefenderBattlePowerMin()
    {
        return wildDefenderBattlePowerMin;
    }

    static double wildDefenderBattlePowerMax()
    {
        return wildDefenderBattlePowerMax;
    }

    static double wildDefenderToughnessDivisor()
    {
        return wildDefenderToughnessDivisor;
    }

    static boolean wildFlatToughnessWhenDisabled()
    {
        return wildFlatToughnessWhenDisabled;
    }

    // family weights, indexed to match PlanetGarrisonRoster.Family.ordinal(): SAIYAN, OVERWORLD, NAMEKIAN, SAIBAMEN,
    // FROST_DEMON, ROBOT. This array MUST stay in enum-ordinal order.
    static int[] wildDefenderFamilyWeights()
    {
        return new int[] { wildDefenderWeightSaiyan, wildDefenderWeightOverworld, wildDefenderWeightNamekian,
                wildDefenderWeightSaibamen, wildDefenderWeightFrostDemon, wildDefenderWeightRobot };
    }

    // How long, in seconds of server game time, a guild's unspent right to destroy a raided planet lasts before it
    // lapses. 0 means the right never lapses. Read by the guild-raid win path (a different package) when it mints the
    // right, hence public; the actual store and grant live in GuildRaidSpoils. This config sits here, alongside the
    // debris window, so the one slow housekeeping tick below can sweep both on the same interval.
    private static int guildRaidSpoilsSeconds;

    public static int guildRaidSpoilsSeconds()
    {
        return guildRaidSpoilsSeconds;
    }

    // Only run the asteroid stamp sweep every this many server ticks. Asteroids are static terrain, so once-per-second is
    // plenty to place lumps around players without any per-tick cost. 20 ticks = ~1s.
    private static final int TICK_INTERVAL = 20;

    // Only run the debris-timer sweep this often. A destroyed planet lingers for days, so a check every 30 seconds is
    // far finer than it needs to be, and the sweep only walks the tiny "currently destroyed" set (usually empty), never
    // a scan over all cells. 600 ticks = ~30s.
    private static final int DEBRIS_SWEEP_INTERVAL = 600;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || !enabled)
        {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
        {
            return;
        }
        // poll the deferred half of the edge-wrap self-test (-Ddmzr.wrapSelfTest=true); a cheap no-op otherwise.
        SpaceTravelModule.tickWrapSelfTest(server);
        // debris timer: on its own slow interval, bump any cell whose rubble window has elapsed so a new planet forms.
        // Runs whether or not anyone is in space (the timer is wall-clock game time, not player presence) and reads only
        // the tiny destroyed set, so it is cheap even when the set is empty.
        if (server.getTickCount() % DEBRIS_SWEEP_INTERVAL == 0)
        {
            sweepDebris(server);
            // one slow housekeeping tick, not two: the guild-raid destruction rights lapse on the same game-time
            // clock as the debris window, so sweep them here rather than adding a second timer.
            sweepRaidSpoils(server);
        }
        if (server.getTickCount() % TICK_INTERVAL != 0)
        {
            return;
        }
        // owner-avatar defenders live on the SHARED planet-surface dimension, not open space, so tick them here (throttled
        // above) before the space-only asteroid work below, which returns early when no one is in open space.
        PlanetOwnerAvatar.tick(server);
        ServerLevel space = SpaceDimension.level(server);
        if (space == null)
        {
            return;
        }
        List<ServerPlayer> players = space.players();
        if (players.isEmpty())
        {
            return;
        }

        // asteroids are the ONLY body with a server tick, because they are real terrain. Derived per player from the
        // sector hash and STAMPED INTO BLOCKS once per cell (AsteroidStamp), recorded in AsteroidStampData so the lump
        // persists as real terrain (LODed by Distant Horizons) and is never re-stamped over a player's mining. Planets,
        // stars and black holes are NOT spawned here: they are drawn by the client from the same hash and their hazards
        // are re-derived by SpaceHazardModule, so nothing about them needs a server tick.
        if (asteroidsEnabled)
        {
            for (ServerPlayer player : players)
            {
                List<AsteroidPositions.Asteroid> field =
                        AsteroidPositions.asteroidsNear(server, player.position(), asteroidSpawnRange);
                for (AsteroidPositions.Asteroid asteroid : field)
                {
                    ensureAsteroid(server, space, asteroid);
                }
            }
        }
    }

    // route a wild GARRISON defender's death to its planet's record, so defeat is tracked by IDENTITY and a chunk
    // unload never miscounts a still-living defender as beaten. A non-defender death is a cheap no-op. Kept here because
    // this module already owns the generated-planet lifecycle (spawn/destroy/debris) and is on the Forge event bus.
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event)
    {
        if (event.getEntity().level().isClientSide())
        {
            return;
        }
        LivingEntity entity = event.getEntity();
        MinecraftServer server = entity.getServer();
        if (server == null)
        {
            return;
        }
        String clearedPlanet = PlanetGarrison.onDefenderDeath(server, entity);
        if (clearedPlanet != null)
        {
            // the last wild hostile of this planet just fell: begin the PUBLIC conquest boss stage. Spawn the theme boss
            // near the player who dealt the killing blow (the trigger); a non-player kill spawns none (nothing to scale
            // to) and the planet stays clear for the next player to trigger via /spaceplanet conquer.
            ServerLevel surface = SurfaceDimension.level(server);
            if (surface != null && event.getSource().getEntity() instanceof ServerPlayer killer)
            {
                PlanetConquest.onGarrisonCleared(server, surface, clearedPlanet, killer);
            }
        }
        // route a conquest DEFENDER BOSS's death to the personal-claim grant. A non-boss death is a cheap no-op.
        PlanetConquest.onLivingDeath(server, entity, event.getSource());
        // route an OWNER-AVATAR defender's death to its planet: opens the timed explodable/respawn windows. No-op otherwise.
        PlanetOwnerAvatar.onLivingDeath(server, entity);
        // route a Destroyer God's death to its super body: places that body's Super Dragon Ball, guarded against a double
        // drop. A non-god death is a cheap no-op.
        SuperPlanetGod.onGodDeath(server, entity);
    }

    // a super-ball ground item that despawned is the ONE definitive, safe "ball lost" signal (see SuperPlanetLifecycle):
    // if it was the last copy of a claimed body's ball, that body reverts to grey and relocates. A non-super despawn is a
    // cheap no-op.
    @SubscribeEvent
    public void onItemExpire(net.minecraftforge.event.entity.item.ItemExpireEvent event)
    {
        net.minecraft.world.entity.item.ItemEntity item = event.getEntity();
        if (item == null || item.level().isClientSide())
        {
            return;
        }
        SuperPlanetLifecycle.onBallDespawn(ServerLifecycleHooks.getCurrentServer(), item);
    }

    // a player MINING the placed super-ball block is the primary "collected" signal: the god is beaten, the ball was
    // dropped as a block, and a player is now taking it. Marks the body claimed (grey -> dragon ball, no longer landable).
    // Registered at LOWEST so a protection handler that cancels the break runs first: a cancelled event never reaches a
    // default (receiveCanceled=false) listener, so a refused break never claims the body. A non-super break is a cheap
    // no-op (starOfBlock returns 0).
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public void onBlockBreak(net.minecraftforge.event.level.BlockEvent.BreakEvent event)
    {
        net.minecraft.world.entity.player.Player player = event.getPlayer();
        if (player == null || player.level().isClientSide())
        {
            return;
        }
        int star = SuperPlanetLifecycle.starOfBlock(event.getState());
        if (star == 0)
        {
            return;
        }
        SuperPlanetLifecycle.onBallCollected(player.getServer(), star);
    }

    // the second "collected" signal: a player PICKING UP a loose super-ball item. This catches a ball that became an item
    // some other way than a hand-mine (an explosion or another break that turned the block into an item entity), so every
    // way a player can end up holding the ball is covered. onBallCollected only claims a body that is dropped-but-
    // uncollected, so this and onBlockBreak are idempotent when both fire for the same ball. A non-super pickup is a
    // cheap no-op.
    @SubscribeEvent
    public void onItemPickup(net.minecraftforge.event.entity.player.EntityItemPickupEvent event)
    {
        net.minecraft.world.entity.player.Player player = event.getEntity();
        if (player == null || player.level().isClientSide() || event.getItem() == null)
        {
            return;
        }
        int star = SuperPlanetLifecycle.starOfBall(event.getItem().getItem());
        if (star == 0)
        {
            return;
        }
        SuperPlanetLifecycle.onBallCollected(player.getServer(), star);
    }

    // sweep garrison defenders orphaned by a crash mid-population on server start, so an orphaned defender can never
    // accumulate on a surface. Runs alongside the clash-holder sweep the PlanetBuster module does at the same moment.
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event)
    {
        // Multiworld-backed pod destinations (the SMP survival world) are loaded ON DEMAND, so at boot they have no
        // ServerLevel and PlanetRegistry's "the dimension must be loaded" rule would drop them. That would not just
        // hide one planet: the fixed bodies are laid out on a ring sized by how many there are, so a body that comes
        // and goes as its world loads and unloads would MOVE every other planet under players who are flying to
        // them. Loading them once, here, keeps the ring stable for the whole session.
        ensureDestinationWorldsLoaded(event.getServer());
        PlanetGarrison.sweepOrphans(event.getServer());
        // drop any surface stamp task left over from a previous integrated-server session (its tick loop is gone), so it
        // cannot wedge a fresh stamp on the same planet this session.
        SurfaceStamp.resetTasks();
        // one-time migration: lock in the current derived size/theme for every planet stamped by an older build that did
        // not persist them, so a later change to the derived formula cannot retroactively resize or re-theme it. Cheap
        // (only planets missing an entry, over the tiny stamped set) and idempotent across restarts.
        GeneratedPlanetClaims.get(event.getServer()).backfillStampedGeometry();
        // build the coordinated fixed-planet layout now, BEFORE seeding the super bodies below: the super bodies are
        // anchored on Earth's position (SuperPlanetPositions.earth -> PlanetPositions.position), so the layout snapshot
        // must exist before ensureInitialized reads it, or the seed would use the per-key fallback instead of the
        // coordinated Earth. A cheap no-op once built; also emits the '[SpacePlanets] fixed layout' verification line.
        PlanetRegistry.invalidateCache();
        PlanetRegistry.bodies(event.getServer());
        // seed the seven super bodies at their initial ring positions the first time this world starts (idempotent), so
        // their positions exist for landing, rendering and the radar before any player lands. The login sync then pushes
        // them to each client.
        SuperPlanetData.get(event.getServer());
        // cheap always-on guard: every rgnpc garrison face id must resolve to a bundled RgNpcModels entry, or a defender
        // would silently render as the default 2stars (Haze Shenron) face (the old "upa" bug). Logs an ERROR per bad id.
        PlanetGarrisonRoster.validateRgNpcModelIds();
        // headless conquest self-test (-Ddmzr.conquestSelfTest=true): a no-op otherwise. Verifies the boss model table,
        // the stat-scaling roll bounds and personal-claim persistence with no client.
        PlanetConquest.runSelfTest(event.getServer());
        maybeDebugForceStamp(event.getServer());
        // headless edge-wrap self-test (-Ddmzr.wrapSelfTest=true): a no-op otherwise. Its deferred half is polled from
        // onServerTick below once the test's stamp completes.
        SpaceTravelModule.beginWrapSelfTest(event.getServer());
    }

    // release the in-flight surface stamp tasks the instant a server stops, not just when the next one starts. Each
    // StampTask holds the MinecraftServer and the planet_surface ServerLevel (and, through the level, its loaded chunks),
    // in the static StampTask.ACTIVE map. That map was cleared only on ServerStartedEvent (resetTasks), so on a
    // singleplayer client a stamp still in flight when the player left the world kept the whole previous server and its
    // level pinned on the heap until another world was loaded, a real retained-memory leak across world exits. Clearing
    // on stop drops those references at once; a partly-stamped planet re-stamps cleanly on its next visit (its generated
    // flag was never set), exactly as on a start-time reset. Idempotent and cheap.
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event)
    {
        SurfaceStamp.resetTasks();
    }

    // batch B4 measurement hook: with -Ddmzr.stampProfilePlanet=<id> (and normally -Ddmzr.stampProfile=true), begin a
    // surface stamp for that planet id at boot, on no player, so a headless server can measure the new terrain generator's
    // block count and per-tick cost. The stamp runs on the shared budgeted worker over the following ticks and logs its
    // profile on completion (SurfaceStamp, behind the same profile flag). A no-op unless the property is set, so it never
    // affects a normal run.
    private static void maybeDebugForceStamp(MinecraftServer server)
    {
        String id = System.getProperty("dmzr.stampProfilePlanet");
        if (id == null || id.isEmpty())
        {
            return;
        }
        ServerLevel surface = SurfaceDimension.level(server);
        if (surface == null)
        {
            return;
        }
        net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.info(
                "[SurfaceStamp] debug force-stamp '{}' (theme {}, size {}); watch for the profile line on completion.",
                id, SurfaceStamp.surfaceThemeFor(id), GeneratedPlanetClaims.stampedSizeForId(server, id));
        SurfaceStamp.ensureAndLandingPos(server, surface, id);
    }

    /**
     * Load every pod destination that is a MULTIWORLD world, so it has a live level for the rest of the session.
     *
     * <p>Driven off the destination list rather than a hardcoded id: a destination is exactly the statement "this
     * world is a place you can fly to", so any multiworld world named by one has to be there to fly to. A
     * destination whose dimension is not a multiworld world (Earth, Namek, our datapack dimensions) is left alone,
     * and so is one whose world cannot be loaded, which is logged by the manager itself.
     */
    private static void ensureDestinationWorldsLoaded(net.minecraft.server.MinecraftServer server)
    {
        if (server == null)
        {
            return;
        }
        try
        {
            for (com.dragonminez.common.spacepod.SpacePodDestinationDefinition def
                    : com.dragonminez.common.spacepod.SpacePodDestinationRegistry.getServerDestinations())
            {
                String dim = def == null ? null : def.dimension();
                if (dim == null || dim.isEmpty() || server.getLevel(net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.tryParse(dim))) != null)
                {
                    continue;
                }
                // core's multiworld engine: it runs keyless too, so a public pod destination in a multiworld world
                // loads with or without the Ragnarok Key (the MultiworldV2 admin module lives in the key).
                net.shurui.shuruisutilities.multiworld.v2.MultiworldEngine.manager().ensureWorldLoaded(dim);
            }
        }
        catch (Throwable t)
        {
            // Never let this stop the rest of server start: the worst case is a destination world that stays
            // unloaded until somebody enters it by other means.
            net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.warn(
                    "[SpacePlanets] Could not pre-load the multiworld pod destinations: {}", t.toString());
        }
    }

    // bump every destroyed cell whose debris window has elapsed, so its slot forms a wholly new planet. Reads only the
    // small "currently destroyed" set (a copy, so bumping while iterating is safe) and compares each entry's destruction
    // time against the configured debris seconds; a cell whose window is up is bumped (generation +1, destroyed record
    // cleared), which the shared accessor then reflects on both sides after the resync. A debrisSeconds of 0 clears a
    // destroyed cell on the very next sweep (instant respawn), which is a legitimate operator choice.
    //
    // A busted MOON rides this exact sweep with no special case: its destroyed record is keyed by the moon id as its own
    // synthetic cell, so clearWreck is a no-op for it (parseCellKey rejects a sumoon: key, so no asteroid debris was ever
    // placed) and bumpGeneration just clears the destroyed record, which flips MoonBody.hasMoon back to true and the SAME
    // moon regenerates. So the regeneration DELAY is the shared planetDebrisSeconds config (default one week), and moons
    // persist their destroyed state across restarts through the same SavedData the generated planets do.
    private static void sweepDebris(MinecraftServer server)
    {
        GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);
        java.util.Map<String, GeneratedPlanetClaims.DestroyedCell> rubble = claims.destroyedCells();
        if (rubble.isEmpty())
        {
            return;
        }
        long now = server.overworld().getGameTime();
        long windowTicks = (long) planetDebrisSeconds * 20L;
        boolean bumped = false;
        for (java.util.Map.Entry<String, GeneratedPlanetClaims.DestroyedCell> e : rubble.entrySet())
        {
            long elapsed = now - e.getValue().destroyedAtGameTime;
            // guard against a backwards clock (world time edited/rolled): a negative elapsed just waits, never bumps early.
            if (elapsed >= windowTicks)
            {
                // the wreck must vanish WITH the rubble: the derivation stopping (below, once the cell is bumped) only
                // prevents NEW wreck lumps stamping, it cannot un-place the real blocks already stamped. So actively
                // clear those blocks here, BEFORE bumping while the cell's destruction generation is still known, so the
                // old debris is gone before the new planet is derived in the slot.
                clearWreck(server, e.getKey(), e.getValue().generation);
                claims.bumpGeneration(e.getKey());
                bumped = true;
            }
        }
        // a bump changed which planets exist, and the client draws them from the synced generation/destroyed snapshot,
        // so re-push the layout to every client so the new planet appears (and the rubble clears) without a relog.
        if (bumped)
        {
            SpaceLayoutSync.syncAll();
        }
    }

    // clear a destroyed cell's wreck out of the world, called just before its generation is bumped. Re-derives the SAME
    // wreck cluster asteroidsNear stamped (pure function of the destroyed planet id at this generation), and for every
    // lump that was ACTUALLY stamped sets its blocks back to air and drops the stamped flag. Guarding on isStamped means
    // a lump no player ever flew near (so never stamped, no blocks placed) costs nothing and loads no chunks; only lumps
    // that are really out there are cleared, and setBlock loads their (usually unloaded) chunks for that one-off removal.
    // Bounded work: one tiny cluster per bumping cell, and cells bump on a days-long window, so this is a rare event.
    private static void clearWreck(MinecraftServer server, String cellKey, int generation)
    {
        ServerLevel space = SpaceDimension.level(server);
        if (space == null)
        {
            // no space dimension means no wreck blocks were ever placed, so there is nothing to clear.
            return;
        }
        AsteroidStampData data = AsteroidStampData.get(server);
        for (AsteroidPositions.Asteroid lump : AsteroidPositions.wreckFor(server, cellKey, generation))
        {
            if (!data.isStamped(lump.key))
            {
                continue;
            }
            AsteroidStamp.clearLump(space, lump);
            data.clearStamped(lump.key);
        }
    }

    // drop any guild planet-destruction rights whose window has lapsed, on the SAME slow tick as the debris sweep.
    // Housekeeping only: an expired right already fails GuildRaidSpoils.has/consume via its lazy expiry check, so this
    // just keeps the tiny map (usually empty) from accumulating dead rows. An operator-set 0-second window means a
    // right never lapses, so it is simply never dropped here.
    private static void sweepRaidSpoils(MinecraftServer server)
    {
        int dropped = net.shurui.shuruisutilities.guilds.raid.GuildRaidSpoils.get(server)
                .sweepExpired(server.overworld().getGameTime());
        if (dropped > 0)
        {
            net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.info(
                    "[GuildRaid] Swept {} expired planet-destruction right(s).", dropped);
        }
    }

    // package-visible getters so SpaceHazardModule reads the baked hazard settings without a second config home.
    static boolean starsEnabled()
    {
        return starsEnabled;
    }

    static double starDamageOuter()
    {
        return starDamageOuter;
    }

    static double starDamageInner()
    {
        return starDamageInner;
    }

    static double starSpawnRange()
    {
        return starSpawnRange;
    }

    static boolean blackHolesEnabled()
    {
        return blackHolesEnabled;
    }

    static double blackHolePullOuter()
    {
        return blackHolePullOuter;
    }

    static double blackHolePullInner()
    {
        return blackHolePullInner;
    }

    static double blackHoleSpawnRange()
    {
        return blackHoleSpawnRange;
    }

    static double blackHoleInfluenceFactor()
    {
        return blackHoleInfluenceFactor;
    }

    // stamp the asteroid for this cell into blocks once, but only when its WHOLE footprint is loaded, so no half of a
    // lump is ever placed into an unloaded chunk and left missing. AsteroidStamp records the cell in AsteroidStampData
    // the first time it stamps, so a later pass is a cheap flag check. The position/size/shape are a pure function of
    // the cell, so it does not matter which player triggered the sweep.
    private void ensureAsteroid(MinecraftServer server, ServerLevel space, AsteroidPositions.Asteroid asteroid)
    {
        if (AsteroidStampData.get(server).isStamped(asteroid.key))
        {
            return;
        }
        if (!footprintLoaded(space, asteroid))
        {
            return;
        }
        AsteroidStamp.ensureStamped(server, space, asteroid);
    }

    // true only if every chunk the lump's bounding cube can touch is loaded, so the whole structure can be placed in
    // one pass. A lump can span several chunks (radius up to a few tens of blocks), so we must check the corners of its
    // footprint, not just the centre chunk, or a big lump could be stamped with its far side dropped into an unloaded
    // chunk. Cheap: at most a handful of hasChunkAt calls per candidate.
    private static boolean footprintLoaded(ServerLevel space, AsteroidPositions.Asteroid asteroid)
    {
        Vec3 c = asteroid.position;
        int r = (int) Math.ceil(asteroid.radius);
        for (int dx = -r; dx <= r; dx += 16)
        {
            for (int dz = -r; dz <= r; dz += 16)
            {
                if (!space.hasChunkAt(BlockPos.containing(c.x + dx, c.y, c.z + dz)))
                {
                    return false;
                }
            }
        }
        // also the exact +r edges, since the 16-step loop can skip the final partial chunk.
        return space.hasChunkAt(BlockPos.containing(c.x + r, c.y, c.z + r))
                && space.hasChunkAt(BlockPos.containing(c.x - r, c.y, c.z - r));
    }

    @Override
    public void load(Builder BUILDER, boolean isReload)
    {
        BUILDER.push("SpacePlanets");
        cfgEnabled = BUILDER
                .comment("Master switch for planet bodies. When false, no planet entities are spawned in the space dimension.")
                .define("enabled", true);
        cfgSpawnRange = BUILDER
                .comment("How near (in blocks) a player must be to a planet's position for that body to be spawned/kept. Larger values keep more distant planets visible as you approach across space.")
                .defineInRange("spawnRange", 12000.0, 256.0, 64000.0);
        cfgExcludeTimeChamber = BUILDER
                .comment("Exclude dragonminez:time_chamber from being a planet body. It is a room, not a planet. Nether, End and dragonminez:otherworld are ALWAYS excluded in code and cannot be enabled here.")
                .define("excludeTimeChamber", true);
        cfgRequireLoadedDimension = BUILDER
                .comment("Only make a destination a planet body if its target dimension is actually loaded on this server. Drops addon-only dimensions (e.g. beerus, cereal) on installs that lack them.")
                .define("requireLoadedDimension", true);
        cfgAsteroidsEnabled = BUILDER
                .comment("Scatter small asteroid/debris bodies through space so it does not feel empty. They are pure scenery: no physics, no collision, no damage. When false, no asteroid entities are spawned.")
                .define("asteroidsEnabled", true);
        cfgAsteroidSpawnRange = BUILDER
                .comment("How near (in blocks) a player must be for an asteroid to be spawned/kept. Kept small so only debris right around a player is loaded, never the whole dimension. Asteroids are laid out in fixed 512-block sectors.")
                .defineInRange("asteroidSpawnRange", 384.0, 64.0, 4096.0);
        cfgGeneratedEnabled = BUILDER
                .comment("Scatter randomly generated, claimable planet bodies through space alongside the fixed ones. Each is derived from its sector hash (size, tint, position, name) and a guild can claim one to own its surface. When false, no generated planet bodies are spawned.")
                .define("generatedPlanetsEnabled", true);
        cfgGeneratedSpawnRange = BUILDER
                .comment("How near (in blocks) a player must be to a generated planet's position for that body to be spawned/kept. A body still only spawns once its own chunk is loaded, so this is an outer cull, not a load radius.")
                .defineInRange("generatedPlanetSpawnRange", 12000.0, 256.0, 64000.0);
        cfgGeneratedSectorSize = BUILDER
                .comment("Edge length (blocks) of the cubic cell a generated planet is placed in. SMALLER packs generated planets closer together so space feels populated; larger spreads them out. Two generated planets can never overlap regardless, since there is at most one per cell and a cell is far larger than any body. This is the SPACE dimension only and does NOT touch the 65,536-block surface cell spacing.")
                .defineInRange("generatedSectorSize", 2048, 256, 65536);
        cfgGeneratedDensity = BUILDER
                .comment("Fraction of cells (0..1) that hold a generated planet. HIGHER makes generated planets more common. Combined with the sector size this sets how many bodies a player sees around them.")
                .defineInRange("generatedDensity", 0.5, 0.0, 1.0);
        cfgFixedRingRadius = BUILDER
                .comment("Minimum ring radius (blocks from the space origin) for the FIXED planet bodies (Earth, Namek, Sacred Kai, ...). This is now only a FLOOR: the actual ring radius is derived from fixedPlanetMinSeparation and the number of bodies so the closest pair is at least that far apart, which is normally far larger than this floor. Raise this only to push the WHOLE system further out than the separation maths already does.")
                .defineInRange("fixedRingRadius", 2400.0, 512.0, 64000.0);
        cfgFixedRingJitter = BUILDER
                .comment("Extra hashed radius (blocks) added per fixed body so they do not all sit on one perfect circle. Only ever ADDS, and it is bounded and VERIFIED against fixedPlanetMinSeparation (the layout drops the jitter for a body-set if it would ever breach the floor), so it only adds visual variety and can never bring two planets closer than the minimum.")
                .defineInRange("fixedRingJitter", 1000.0, 0.0, 32000.0);
        cfgFixedPlanetMinSeparation = BUILDER
                .comment("The hard MINIMUM distance (blocks, centre to centre) between any two FIXED main planet bodies. The bodies are spread evenly by angle on a ring sized so the closest (adjacent) pair is at least this far apart, and the result is verified in code (see the '[SpacePlanets] fixed layout' log line, which reports the actual minimum). The default 15000 matches the Super Dragon Ball planet spacing; the allowed minimum is floored at 10000 so this can never drop below that hard rule. Larger spreads the planets further apart (and lengthens the longest autopilot trip; see spaceAutopilotCruiseSpeed).")
                .defineInRange("fixedPlanetMinSeparation", 15000.0, 10000.0, 64000.0);
        cfgSpaceAutopilotCruiseSpeed = BUILDER
                .comment("The per-tick cruise speed (blocks per tick) the pod-launch AUTOPILOT flies at when the pilot's own flight speed would be slower. With the fixed planets now tens of thousands of blocks apart, an uninvested pilot's stock pod cruise (~2.2 blocks/tick) would make a cross-system autopilot trip many minutes long, so the autopilot step is floored at this value: an uninvested pilot cruises here, a strongly invested flyer who already exceeds it keeps their faster speed. At the default 12 (240 blocks/second) the longest trip across a default 15000-separation system (~21850 blocks) takes about 91 seconds. This floors ONLY the autopilot, not hand-flying. Keep at or below about 20 so the pod stays inside the vehicle movement tolerance.")
                .defineInRange("spaceAutopilotCruiseSpeed", 12.0, 2.0, 40.0);
        cfgBodyDrawDistance = BUILDER
                .comment("How far (in blocks) the CLIENT draws planets, stars and black holes around itself. These bodies are NOT chunks or entities: the client derives them from the same hash the server does and draws them directly, so this is INDEPENDENT of render distance (a low render distance never hides them). Kept generously large so a body reads as a distant landmark you fly toward, and always far larger than a star's burn field (max ~205 blocks) or a black hole's pull field (max ~480 blocks) so a hazard is visible long before it can affect you. The minimum is floored well above the largest hazard field so that guarantee holds at every allowed value. Lower it only to ease a struggling client.")
                .defineInRange("bodyDrawDistance", SpaceLayout.DEFAULT_DRAW_DISTANCE, SpaceLayout.MIN_DRAW_DISTANCE, SpaceLayout.MAX_DRAW_DISTANCE);
        cfgPlanetDebrisSeconds = BUILDER
                .comment("How long (in seconds) a DESTROYED generated planet stays as rubble before its slot forms a wholly new planet. During this window the slot is empty (no body to draw, no landing); once it elapses the cell's generation is bumped and a genuinely different planet (new id, position, tint, size) is derived in the same slot. Default 604800 = 7 days.")
                .defineInRange("planetDebrisSeconds", 604800, 0, Integer.MAX_VALUE);
        cfgGuildRaidSpoilsSeconds = BUILDER
                .comment("How long (in seconds of server game time) a guild's unspent right to destroy a planet it won in a raid lasts before it lapses. The right is granted on a raid WIN and spent LATER by an overcharged ki blast; winning never destroys the planet automatically, it only makes destruction possible. 0 means the right NEVER lapses. Default 86400 = 24 hours of server uptime (game time, like the debris window above).")
                .defineInRange("guildRaidSpoilsSeconds", 86400, 0, Integer.MAX_VALUE);
        cfgSurfaceStampBlocksPerTick = BUILDER
                .comment("How many surface blocks a generated planet's terrain stamp places per server tick before it yields. "
                        + "Landing on a not-yet-generated planet stamps its whole surface disc; a maximum 500-wide planet with the "
                        + "default deep column is on the order of 26,000,000 block sets, so the stamp is spread across ticks at this "
                        + "budget to keep the per-tick cost flat no matter how large the surface is. Only the small centre patch is "
                        + "placed immediately so the player lands on solid ground; the player is then held in place until the disc "
                        + "has fully generated (watching it materialise outward in rings) so nobody walks or spawns onto unstamped "
                        + "void. The terrain is written without per-block client updates and each finished chunk is resent whole, so "
                        + "the client is no longer the limiter it once was; the budget mainly sets how long the player is frozen. At "
                        + "the default 49152 a maximum planet finishes in roughly 26 seconds and the outward chunk reveal runs at "
                        + "about 29 chunks/second, which a client meshes comfortably. A single tick's 49152 setBlocks stays well under "
                        + "the ~345,000 the stamp used to do synchronously in one tick, so no tick is heavier than one the server "
                        + "already tolerated. Raise it to shorten the frozen wait at a heavier tick, lower it to be gentler on the "
                        + "client (a smaller planet is proportionally faster).")
                .defineInRange("surfaceStampBlocksPerTick", 49152, 256, 1000000);
        cfgSurfaceColumnDepth = BUILDER
                .comment("How many blocks of deep stone-family ground a generated planet's surface stamp places beneath the thin "
                        + "soil band in every column, i.e. how far the solid ground extends below the surface. Larger makes a "
                        + "planet a deeper minable column; smaller returns it toward the old thin shell. The default 128 takes the "
                        + "deepest column's floor to about Y -34 (the surface sits at Y 96), which stays clear of the dimension "
                        + "floor at -64 and leaves room below for later cave and basin generation. Note that raising this raises "
                        + "the per-planet stamp cost proportionally (a 500-wide planet is ~200,000 columns), so a much larger depth "
                        + "means a longer fill at the same surfaceStampBlocksPerTick budget. The maximum is bounded so the deepest "
                        + "column can never reach the dimension floor at Y -64.")
                .defineInRange("surfaceColumnDepth", 128, 4, 150);
        cfgSurfaceEdgeWrap = BUILDER
                .comment("Wrap a player who walks off the edge of a TILEABLE planet (one whose terrain was generated by the "
                        + "version-2 generator, i.e. stamped after this feature shipped) around to the opposite edge, so the "
                        + "surface behaves like a small looping world with no invisible wall. The arrival edge carries identical "
                        + "terrain, so the wrap is seamless, and velocity, facing, fall distance and any ridden pod or mount are "
                        + "preserved. When false, tileable planets keep the same invisible wall older planets use. Older (disc) "
                        + "planets ALWAYS keep the wall regardless of this setting, because they have void corners and no matching "
                        + "opposite edge to arrive on.")
                .define("surfaceEdgeWrap", true);
        cfgPlanetWeather = BUILDER
                .comment("Per-planet weather on generated planet surfaces. All planets share one surface dimension, so "
                        + "this is our own weather system (not vanilla's per-dimension weather): each planet runs its own "
                        + "state (clear, rain, storm, snow, blizzard, dust, ash fall or a meteor shower) chosen from its "
                        + "surface theme and changing over time, computed deterministically from the corrected wall clock "
                        + "and the planet id so every shard and client agrees. It draws precipitation, darkens the sky, "
                        + "thickens fog and plays weather sound on the client. Set false to disable planet weather "
                        + "entirely (clients then draw a plain sky and the effects below never apply).")
                .define("planetWeather", true);
        cfgPlanetWeatherEffects = BUILDER
                .comment("The LIGHT gameplay effects of planet weather, applied server-side to players on a planet "
                        + "surface: rain and storms extinguish a burning player, and a blizzard imposes a mild movement "
                        + "slowness. Cheap and safe; set false to keep the weather purely cosmetic. Ignored when "
                        + "planetWeather is false.")
                .define("planetWeatherEffects", true);
        cfgMoonSurfaceSize = BUILDER
                .comment("Edge length (blocks per side) of a MOON's stamped landmass. Every fixed planet body (Earth, Namek, "
                        + "Sacred Kai, and any loaded destination) gets one orbiting moon, and this is how large the surface a "
                        + "player lands on is. The default 400 is a proper little world (about 16,000,000 block sets at the "
                        + "default column depth, filled over roughly 25 seconds at the default surfaceStampBlocksPerTick). This "
                        + "does NOT change the moon's small cube or orbit in space (those scale from the parent body's radius); "
                        + "it is only the terrain size. A moon already stamped at the OLD 20-wide size keeps 20 (its stamped "
                        + "size is persisted), so changing this only affects moons stamped after the change. Forced even.")
                .defineInRange("moonSurfaceSize", 400, 20, 500);
        cfgSurfaceWaterEnabled = BUILDER
                .comment("Master switch for surface WATER on generated planets. When true, green-themed planets get carved "
                        + "water basins (Namek its own green water, overworld and King-Kai plain water), nether planets get "
                        + "sealed lava pools and End planets get frozen (packed-ice) pools. Stony planets never get water. "
                        + "Basins are always fully enclosed by higher land and can never drain off the disc. When false, no "
                        + "basins are carved and no water is placed.")
                .define("surfaceWaterEnabled", true);
        cfgSurfaceVegetationEnabled = BUILDER
                .comment("Master switch for surface VEGETATION and rock decor on generated planets: trees, grass, flowers "
                        + "and fungus on the living themes, and spires/pillars/boulders/crystals on stony ones. When false, "
                        + "surfaces are left as bare terrain.")
                .define("surfaceVegetationEnabled", true);
        cfgSurfaceSeaLevelOffset = BUILDER
                .comment("How many blocks BELOW the base surface height (the minimum terrain cap) sea level sits. Every "
                        + "water basin fills up to this level, and all ordinary land caps at or above the base height, so "
                        + "water is always strictly below the surrounding ground. Larger sinks the water table deeper.")
                .defineInRange("surfaceSeaLevelOffset", 3, 1, 16);
        cfgSurfaceBasinFrequency = BUILDER
                .comment("Fraction (0..1) of basin candidate sites that actually become a water pool on a water-bearing "
                        + "planet. Higher makes such planets wetter (more lakes). Pools are discrete, bounded and provably "
                        + "non-overlapping, so raising this never risks a runaway flood. 0 disables basins without touching "
                        + "the rest of the surface.")
                .defineInRange("surfaceBasinFrequency", 0.30, 0.0, 1.0);
        cfgSurfaceVegetationDensity = BUILDER
                .comment("Fraction (0..1) of vegetation candidate sites that grow a feature. Higher makes surfaces lusher "
                        + "(more trees, grass, flowers and rock decor). Cost scales with this; the default adds only about "
                        + "one to two percent to a planet's stamp.")
                .defineInRange("surfaceVegetationDensity", 0.45, 0.0, 1.0);
        cfgSurfaceThemeWeightStony = BUILDER
                .comment("Relative weight of the STONY surface theme (bare rock with spires, pillars, boulders and crystal "
                        + "clusters). The theme of a planet is rolled proportionally to these six weights, so a value of 12 "
                        + "against a total of 100 makes about 12 percent of planets stony. Lowered from its old 30 so most "
                        + "planets are green. Already-stamped planets keep their stored theme and are never re-themed.")
                .defineInRange("surfaceThemeWeightStony", 12, 0, 1000);
        cfgSurfaceThemeWeightOverworld = BUILDER
                .comment("Relative weight of the OVERWORLD surface theme (grass/dirt/stone with vanilla trees and flora). "
                        + "The commonest theme by default.")
                .defineInRange("surfaceThemeWeightOverworld", 34, 0, 1000);
        cfgSurfaceThemeWeightNamek = BUILDER
                .comment("Relative weight of the NAMEK surface theme (DragonMineZ's green Namek ground, ajissa/sacred trees, "
                        + "green water).")
                .defineInRange("surfaceThemeWeightNamek", 21, 0, 1000);
        cfgSurfaceThemeWeightNether = BUILDER
                .comment("Relative weight of the NETHER surface theme (netherrack/soul-soil/basalt with nylium, fungus and "
                        + "sealed lava pools).")
                .defineInRange("surfaceThemeWeightNether", 12, 0, 1000);
        cfgSurfaceThemeWeightEnd = BUILDER
                .comment("Relative weight of the END surface theme (end stone and purpur with chorus plants and frozen "
                        + "pools).")
                .defineInRange("surfaceThemeWeightEnd", 8, 0, 1000);
        cfgSurfaceThemeWeightKaio = BUILDER
                .comment("Relative weight of the KAIO surface theme (King Kai's sacred-kai ground with its grass, flowers "
                        + "and rock clusters, and plain water). Raised from its old 5 so its green worlds actually turn up.")
                .defineInRange("surfaceThemeWeightKaio", 13, 0, 1000);
        cfgSurfaceStructuresEnabled = BUILDER
                .comment("Master switch for surface STRUCTURES on generated planets: procedural Saibamen dirt huts on "
                        + "stony and overworld planets, and small authored Namek villages (DragonMineZ's own Namek "
                        + "houses) on Namek planets. Nether, End and King-Kai planets get no structures. When false, no "
                        + "structures are placed.")
                .define("surfaceStructuresEnabled", true);
        cfgSurfaceVillageFrequency = BUILDER
                .comment("Fraction (0..1) of village candidate sites that grow a Namek village on a Namek planet. Sites "
                        + "sit on a coarse 128-block grid, so a village is a rare landmark rather than continuous. Higher "
                        + "makes Namek planets more settled. 0 disables villages without touching huts.")
                .defineInRange("surfaceVillageFrequency", 0.35, 0.0, 1.0);
        cfgSurfaceHutFrequency = BUILDER
                .comment("Fraction (0..1) of hut candidate sites that grow a Saibamen dirt hut on a stony or overworld "
                        + "planet. Sites sit on the same coarse 128-block grid, so huts read as scattered lone shelters. "
                        + "Higher makes such planets more dotted with huts. 0 disables huts without touching villages.")
                .defineInRange("surfaceHutFrequency", 0.5, 0.0, 1.0);
        cfgSurfaceHutSeedPlantedChance = BUILDER
                .comment("Chance (0..1) that a placed Saibamen dirt hut also contains ONE growing saibaman seed at its "
                        + "centre, on rocky_dirt, that you can tend (charge ki beside it) and harvest into a tamed "
                        + "saibaman. Independent of the harvestable-seed roll below.")
                .defineInRange("surfaceHutSeedPlantedChance", 0.33, 0.0, 1.0);
        cfgSurfaceHutSeedHarvestChance = BUILDER
                .comment("Chance (0..1) that a placed Saibamen dirt hut contains 1 to 3 harvestable saibaman seeds "
                        + "around its floor that you can break for the seed item. Independent of the planted-seed roll "
                        + "above, so a hut may have both, one, or neither.")
                .defineInRange("surfaceHutSeedHarvestChance", 0.33, 0.0, 1.0);
        cfgStarsEnabled = BUILDER
                .comment("Scatter large, bright, DANGEROUS stars through space. A player who flies too close is burned, harder the nearer they get, lethal at the surface. When false, no stars spawn and no burn is applied.")
                .define("starsEnabled", true);
        cfgStarSpawnRange = BUILDER
                .comment("How near (in blocks) a player must be to a star before its burn hazard is evaluated. This bounds the server-side hazard derivation only; the CLIENT draws stars from a separate, larger bodyDrawDistance, so a star is always visible long before this range. Kept at 4096 by default.")
                .defineInRange("starSpawnRange", 4096.0, 256.0, 4096.0);
        cfgStarSectorSize = BUILDER
                .comment("Edge length (blocks) of the cubic cell a star is placed in. LARGER makes stars rarer. Stars are meant to be much rarer than planets, so the default is far larger than the planet sector.")
                .defineInRange("starSectorSize", StarPositions.DEFAULT_SECTOR_SIZE, 1024, 65536);
        cfgStarDensity = BUILDER
                .comment("Fraction of star cells (0..1) that actually hold a star. LOWER makes stars rarer still. Combined with the sector size this sets how often a player passes one.")
                .defineInRange("starDensity", StarPositions.DEFAULT_DENSITY, 0.0, 1.0);
        cfgStarDamageOuter = BUILDER
                .comment("Burn dealt at the OUTER edge of a star's damage field, as a FRACTION of the victim's max health, per half-second. A fraction (not a flat number) so it stays meaningful against DMZ's inflated health. 0.05 = 5% max health per half-second at the outer edge: unpleasant, survivable if you turn away.")
                .defineInRange("starDamageOuter", 0.05, 0.0, 1.0);
        cfgStarDamageInner = BUILDER
                .comment("Burn dealt at the SURFACE of a star, as a fraction of the victim's max health, per half-second. 0.40 = 40% max health per half-second flush against the surface: lethal within a couple of seconds if you keep going.")
                .defineInRange("starDamageInner", 0.40, 0.0, 2.0);
        cfgBlackHolesEnabled = BUILDER
                .comment("Scatter DANGEROUS black holes through space. A player inside a black hole's pull field is dragged toward it, harder the closer they get, and crossing the inner event horizon KILLS them. When false, no black holes spawn and no pull is applied.")
                .define("blackHolesEnabled", true);
        cfgBlackHoleSpawnRange = BUILDER
                .comment("How near (in blocks) a player must be to a black hole before its pull/kill hazard is evaluated. This bounds the server-side hazard derivation only; the CLIENT draws black holes from a separate, larger bodyDrawDistance, so a black hole is always visible long before this range. Kept at 4096 by default.")
                .defineInRange("blackHoleSpawnRange", 4096.0, 256.0, 4096.0);
        cfgBlackHoleSectorSize = BUILDER
                .comment("Edge length (blocks) of the cubic cell a black hole is placed in. LARGER makes black holes rarer. Black holes are meant to be rarer than stars, so the default is larger than the star sector.")
                .defineInRange("blackHoleSectorSize", BlackHolePositions.DEFAULT_SECTOR_SIZE, 2048, 65536);
        cfgBlackHoleDensity = BUILDER
                .comment("Fraction of black hole cells (0..1) that actually hold a black hole. LOWER makes them rarer still.")
                .defineInRange("blackHoleDensity", BlackHolePositions.DEFAULT_DENSITY, 0.0, 1.0);
        cfgBlackHoleInfluenceFactor = BUILDER
                .comment("How far a black hole's PULL field reaches, as a MULTIPLE of the hole's visual radius. This is the outer edge where the gentle tug begins; the pull then accelerates as an inverse-square curve the closer you get. At the default 30 a typical 60-radius hole pulls from 1800 blocks out (up from the old 6x = 360), a much larger danger zone. The effective reach is still capped by blackHoleSpawnRange, so keep radius*factor below that (with the 40..80 radius range, factor 50 already reaches the 4096 search cap).")
                .defineInRange("blackHoleInfluenceFactor", 30.0, 1.0, 50.0);
        cfgBlackHolePullOuter = BUILDER
                .comment("Inward pull, in blocks per tick, at the OUTER edge of the pull field. This is a position drag, so it composes with the player's ki flight: kept small (well below a tick of flight) so a player at the edge can easily out-fly it and escape. 0.25 is a gentle but clearly noticeable tug.")
                .defineInRange("blackHolePullOuter", 0.25, 0.0, 5.0);
        cfgBlackHolePullInner = BUILDER
                .comment("MINIMUM inward pull, in blocks per tick, at the inner point of no return. This is only a FLOOR: the real inner pull is derived per-player from their own maximum flight speed (times a safety margin) so the inner zone is inescapable for everybody, including a heavily invested flyer in a boosted space pod (about 12 blocks/tick). Raise this floor if you want the inner zone hard even for players whose flight speed cannot be read. Default 6.0.")
                .defineInRange("blackHolePullInner", 6.0, 0.0, 40.0);
        cfgWildDefendersEnabled = BUILDER
                .comment("Master switch for wild planet DEFENDERS: visible NPCs that guard an UNOWNED generated planet (or moon). A guild must beat every defender before it can claim the planet, and the defenders set the planet's clash toughness and recommended battle power. When false, no defenders ever spawn and any unowned planet is claimable immediately. Default true.")
                .define("wildDefendersEnabled", true);
        cfgWildDefenderMinCount = BUILDER
                .comment("Minimum number of defenders spawned on an unowned planet the first time a player arrives on it. The actual count is a random value between this and wildDefenderMaxCount, rolled once per planet and then fixed. Default 3.")
                .defineInRange("wildDefenderMinCount", 3, 0, 64);
        cfgWildDefenderMaxCount = BUILDER
                .comment("Maximum number of defenders spawned on an unowned planet. If this is less than or equal to wildDefenderMinCount, exactly wildDefenderMinCount are spawned. Default 6.")
                .defineInRange("wildDefenderMaxCount", 6, 0, 64);
        cfgWildDefenderBattlePowerMin = BUILDER
                .comment("Lower bound of the per-defender BATTLE POWER band. Each defender rolls a battle power in [min, max]; its max health, melee and defense are derived from that battle power, and the planet's toughness and recommended battle power aggregate over the whole garrison. Default 5000.")
                .defineInRange("wildDefenderBattlePowerMin", 5000.0, 0.0, 1.0E12);
        cfgWildDefenderBattlePowerMax = BUILDER
                .comment("Upper bound of the per-defender battle power band. If this is less than or equal to the minimum, every defender uses the minimum. Default 50000.")
                .defineInRange("wildDefenderBattlePowerMax", 50000.0, 0.0, 1.0E12);
        cfgWildDefenderToughnessDivisor = BUILDER
                .comment("The planet's clash TOUGHNESS (the defending beam's ki damage when the planet is later shot with a giant ball) is the SUM of its living defenders' battle power divided by this, floored at the flat clashWildToughness (in the PlanetBuster config) so a defended planet is never EASIER to bust than an undefended one. A larger divisor makes defenders contribute less toughness. Set to 0 to use the raw aggregate battle power with no divisor. Default 10: with the default defender battle-power band this lands a garrison's toughness on the same ki-damage scale as clashWildToughness (5000), so a heavily defended world reads meaningfully tougher than an undefended one rather than being flattened to the floor.")
                .defineInRange("wildDefenderToughnessDivisor", 10.0, 0.0, 1.0E9);
        cfgWildFlatToughnessWhenDisabled = BUILDER
                .comment("Whether the flat clashWildToughness (from the PlanetBuster config) still applies to an unowned planet that has NO defenders, i.e. when wildDefendersEnabled is false or a planet has not been populated yet. When true, such a planet keeps the old flat wild toughness. When false, such a planet is as soft as possible to bust (toughness 1). A planet that DOES have defenders always uses the toughness they set, regardless of this flag. Default true.")
                .define("wildFlatToughnessWhenDisabled", true);
        cfgWildDefenderWeightSaiyan = BUILDER
                .comment("Relative weight of the SAIYAN defender family (generic saiyan grunts plus paragussoldier). A planet's whole garrison is one randomly chosen family; higher weight makes this family more common. 0 excludes it. Equal weights give an even split. Default 1.")
                .defineInRange("wildDefenderWeightSaiyan", 1, 0, 1000);
        cfgWildDefenderWeightOverworld = BUILDER
                .comment("Relative weight of the OVERWORLD defender family (Nam, Upa, King Chappa, Monaka, and other Earth fighters). 0 excludes it. Default 1.")
                .defineInRange("wildDefenderWeightOverworld", 1, 0, 1000);
        cfgWildDefenderWeightNamekian = BUILDER
                .comment("Relative weight of the NAMEKIAN defender family (the good Namekian defenders: Nail and the Piccolo line plus the neutral namek warrior). 0 excludes it. Default 1.")
                .defineInRange("wildDefenderWeightNamekian", 1, 0, 1000);
        cfgWildDefenderWeightSaibamen = BUILDER
                .comment("Relative weight of the SAIBAMEN defender family (repurposed to Earth's human Z-fighters: Krillin, Tien, Yamcha, Chiaotzu, Goten and kid Trunks; the config key keeps its historical name). 0 excludes it. Default 1.")
                .defineInRange("wildDefenderWeightSaibamen", 1, 0, 1000);
        cfgWildDefenderWeightFrostDemon = BUILDER
                .comment("Relative weight of the FROST_DEMON defender family (repurposed to a veteran hero guard: Piccolo and the strongest hybrid warriors; the config key keeps its historical name). 0 excludes it. Default 1.")
                .defineInRange("wildDefenderWeightFrostDemon", 1, 0, 1000);
        cfgWildDefenderWeightRobot = BUILDER
                .comment("Relative weight of the ROBOT defender family (the GOOD-era androids 16/17/18). 0 excludes it. Default 1.")
                .defineInRange("wildDefenderWeightRobot", 1, 0, 1000);
        cfgConquerEnabled = BUILDER
                .comment("Master switch for PUBLIC planet CONQUEST (works keyless, in singleplayer and on a keyless server, "
                        + "no guild needed). When true, clearing every wild defender of an unowned generated planet spawns "
                        + "ONE theme-fitting main-character defender boss near you; beating it claims the planet PERSONALLY "
                        + "to your own uuid. When false, no conquest boss spawns and the personal-claim path is off (the "
                        + "guild claim, on a keyed server, is unaffected). Default true.")
                .define("conquestEnabled", true);
        cfgConquerStatTolerance = BUILDER
                .comment("How far (0..1) the conquest boss's stats may differ from the triggering player's. The boss's max "
                        + "health is anchored to the player's max health and its battle power to the player's battle power, "
                        + "then each combat number is rolled independently within +/- this fraction. 0.05 = within 5%, so "
                        + "the boss is a near-even match. 0 makes it an exact mirror. Default 0.05.")
                .defineInRange("conquestStatTolerance", 0.05, 0.0, 1.0);
        cfgConquerSpawnDistance = BUILDER
                .comment("How far (in blocks) from the triggering player the conquest boss spawns, at a random bearing, "
                        + "snapped to safe ground. Default 6.")
                .defineInRange("conquestSpawnDistance", 6.0, 1.0, 64.0);
        // Conquering a planet is the villain's role, so its defender is a canonical HERO. Every default below is a
        // GOOD-aligned DragonMineZ character (a normal-sized fighter). These strings are kept byte-identical to
        // PlanetConquest.defaultTable() and to CONQUEST_BOSS_OLD_DEFAULTS (used for the one-time migration in bakeConfig).
        cfgConquerBossStony = BUILDER
                .comment("Comma-separated DragonMineZ saga entity ids the conquest boss is picked from on a STONY (barren "
                        + "rock) planet. Each must be a registered dragonminez:saga_* entity (its model ships in the "
                        + "DragonMineZ jar). A missing id is skipped. Default the reformed Prince and the young Saiyan hybrids.")
                .define("conquestBossStony", "saga_vegeta_mid_ssj,saga_ftrunks_ssj,saga_goten_ssj,saga_kid_trunks_ssj");
        cfgConquerBossOverworld = BUILDER
                .comment("Comma-separated saga ids for the conquest boss on an OVERWORLD (Earth-like) planet. Default "
                        + "Earth's champions.")
                .define("conquestBossOverworld",
                        "saga_goku_end_ssj,saga_gohan_end_ssj2,saga_piccolo,saga_krillin,saga_tien_early,saga_yamcha,saga_a17,saga_a18");
        cfgConquerBossNamek = BUILDER
                .comment("Comma-separated saga ids for the conquest boss on a NAMEK planet. Default the Namekian defenders.")
                .define("conquestBossNamek", "saga_nail,saga_piccolo,saga_piccolo_kami");
        cfgConquerBossNether = BUILDER
                .comment("Comma-separated saga ids for the conquest boss on a NETHER (demon realm) planet. Default the "
                        + "strongest heroes and fusions.")
                .define("conquestBossNether", "saga_vegetto_ssj,saga_gohan_end_ultimate,saga_goku_end_ssj3,saga_gotenks_ssj3");
        cfgConquerBossEnd = BUILDER
                .comment("Comma-separated saga ids for the conquest boss on an END (cold space) planet. Default the "
                        + "pure-Saiyan powerhouses in their highest forms.")
                .define("conquestBossEnd", "saga_goku_end_ssj3,saga_vegeta_end_ssj2,saga_ftrunks_ssg3,saga_vegetto_base");
        cfgConquerBossKaio = BUILDER
                .comment("Comma-separated saga ids for the conquest boss on a KAIO (King Kai's world) planet. Default "
                        + "otherworld heroes.")
                .define("conquestBossKaio", "saga_paikuhan,saga_shin,saga_kibito");
        cfgConquerBossOther = BUILDER
                .comment("Comma-separated saga ids used for any theme with no list of its own, and as the universal fallback "
                        + "when a theme's own list has no registered id. Default a mix of the strongest heroes.")
                .define("conquestBossOther", "saga_paikuhan,saga_goku_end_ssj2,saga_gohan_end_ultimate,saga_vegetto_ssj");
        cfgClaimAvatarDefender = BUILDER
                .comment("When true, a personally-conquered planet is guarded by an AVATAR of the owning player: a defender "
                        + "that renders as the owner (their skin plus DragonMineZ race) and fights with a snapshot of the "
                        + "owner's stats. It spawns only when a NON-owner is on the planet, and the planet CANNOT be "
                        + "destroyed while the avatar stands; a challenger who beats it opens a timed window to destroy the "
                        + "planet before the avatar returns. When false, no avatar spawns and a personally-claimed planet is "
                        + "freely destroyable (no raid requirement). This is independent of the guild raid-entitlement gate, "
                        + "which is unaffected either way. Default true.")
                .define("claimAvatarDefender", true);
        cfgAvatarStatMultiplier = BUILDER
                .comment("Multiplier applied to the owner-avatar defender's snapshotted combat stats (health, melee, "
                        + "defense, ki, battle power). 1.0 makes the avatar an even match for the owner; raise it to make a "
                        + "claimed planet harder to take. Default 1.0.")
                .defineInRange("avatarStatMultiplier", 1.0, 0.0, 1000.0);
        cfgAvatarRespawnMinutes = BUILDER
                .comment("Minutes after the owner avatar is DEFEATED before it may respawn (when a non-owner challenger is "
                        + "again on the planet). Default 10.")
                .defineInRange("avatarRespawnMinutes", 10.0, 0.0, 1440.0);
        cfgExplodableWindowMinutes = BUILDER
                .comment("Minutes after the owner avatar is defeated during which the personally-claimed planet CAN be "
                        + "destroyed. After this window closes the planet is protected again (and the avatar returns per "
                        + "avatarRespawnMinutes). Default 10.")
                .defineInRange("explodableWindowMinutes", 10.0, 0.0, 1440.0);
        cfgSuperGodEnabled = BUILDER
                .comment("Master switch for the Destroyer God that guards each of the seven SUPER dragon-ball bodies in space. When true, landing on a super body (grey until you land on it) spawns one god-tier boss you must beat to make it drop that body's Super Dragon Ball. When false, no god spawns and the ball is placed on landing instead, so the Super set is always obtainable. Default true.")
                .define("superGodEnabled", true);
        cfgSuperGodBattlePowerMin = BUILDER
                .comment("Lower bound of the Destroyer God's BATTLE POWER band. The god rolls a battle power in [min, max]; its max health, melee and defense derive from that battle power exactly as a wild defender's do (health = bp/50, melee = bp/500, defense = bp/1000), so the default band lands it tens of thousands of health. Default 500000.")
                .defineInRange("superGodBattlePowerMin", 500000.0, 0.0, 1.0E12);
        cfgSuperGodBattlePowerMax = BUILDER
                .comment("Upper bound of the Destroyer God's battle power band. If this is less than or equal to the minimum, the god always uses the minimum. Default 3000000.")
                .defineInRange("superGodBattlePowerMax", 3000000.0, 0.0, 1.0E12);
        cfgSuperRenderDistance = BUILDER
                .comment("How near (in blocks) the CLIENT must be to a SUPER dragon-ball body before it draws at all. This is INDEPENDENT of bodyDrawDistance and small on purpose: super bodies sit 20000..45000 blocks from Earth and at least 15000 apart, so a small cull keeps one from being spotted from across space; the Super radar is what guides you to it. Server-side landing is UNAFFECTED (you can fly into and land on a body you cannot yet see). Default 1000.")
                .defineInRange("superRenderDistance", 1000.0, 100.0, 45000.0);
        BUILDER.pop();
    }

    // one-time in-place migration of a conquestBoss* config value: if it still EXACTLY equals the old (villain) default,
    // rewrite it to the new (good-guy) default; otherwise leave it (an admin edited it, or it is already the new default).
    // .set() writes into the loaded spec so the new value persists to SpacePlanets.toml on the next save. Never throws.
    private static void migrateConquestDefault(ForgeConfigSpec.ConfigValue<String> value, String oldDefault,
                                               String newDefault)
    {
        if (value == null)
        {
            return;
        }
        try
        {
            String current = value.get();
            if (current != null && current.trim().equals(oldDefault))
            {
                value.set(newDefault);
            }
        }
        catch (Throwable t)
        {
            net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.warn(
                    "[PlanetConquest] Could not migrate a conquest boss list to its new default; leaving the stored "
                            + "value in place.", t);
        }
    }

    @Override
    public void bakeConfig(boolean reload)
    {
        enabled = cfgEnabled.get();
        spawnRange = cfgSpawnRange.get();
        asteroidsEnabled = cfgAsteroidsEnabled.get();
        asteroidSpawnRange = cfgAsteroidSpawnRange.get();
        generatedEnabled = cfgGeneratedEnabled.get();
        generatedSpawnRange = cfgGeneratedSpawnRange.get();
        // hazards: spawn/damage/pull settings, plus push the density tuning into the pure derivation classes (volatile
        // there, read on the server thread). Layout is a pure function of these plus the hash, so a change re-lays-out
        // the hazard field deterministically with nothing to migrate; a body that moves simply evaporates (not saved)
        // and the spawn tick re-creates it at its new derived spot, exactly like the planets.
        starsEnabled = cfgStarsEnabled.get();
        starSpawnRange = cfgStarSpawnRange.get();
        starDamageOuter = cfgStarDamageOuter.get();
        starDamageInner = cfgStarDamageInner.get();
        StarPositions.sectorSize = cfgStarSectorSize.get();
        StarPositions.density = cfgStarDensity.get();
        blackHolesEnabled = cfgBlackHolesEnabled.get();
        blackHoleSpawnRange = cfgBlackHoleSpawnRange.get();
        blackHolePullOuter = cfgBlackHolePullOuter.get();
        blackHolePullInner = cfgBlackHolePullInner.get();
        blackHoleInfluenceFactor = cfgBlackHoleInfluenceFactor.get();
        BlackHolePositions.sectorSize = cfgBlackHoleSectorSize.get();
        BlackHolePositions.density = cfgBlackHoleDensity.get();
        // density tuning: push the config values into the pure derivation classes. All are volatile there and read on
        // the server thread. The layout is a pure function of these plus the hash, so changing them re-lays-out space
        // deterministically with nothing stored to migrate; a body that moves simply evaporates (not saved) and the
        // spawn tick re-creates it at its new derived spot.
        GeneratedPlanets.sectorSize = cfgGeneratedSectorSize.get();
        GeneratedPlanets.density = cfgGeneratedDensity.get();
        PlanetPositions.ringRadius = cfgFixedRingRadius.get();
        PlanetPositions.radiusJitter = cfgFixedRingJitter.get();
        PlanetPositions.minSeparation = cfgFixedPlanetMinSeparation.get();
        spaceAutopilotCruiseSpeed = cfgSpaceAutopilotCruiseSpeed.get();
        bodyDrawDistance = cfgBodyDrawDistance.get();
        planetDebrisSeconds = cfgPlanetDebrisSeconds.get();
        guildRaidSpoilsSeconds = cfgGuildRaidSpoilsSeconds.get();
        surfaceStampBlocksPerTick = cfgSurfaceStampBlocksPerTick.get();
        surfaceColumnDepth = cfgSurfaceColumnDepth.get();
        surfaceEdgeWrapEnabled = cfgSurfaceEdgeWrap.get();
        planetWeatherEnabled = cfgPlanetWeather.get();
        planetWeatherEffectsEnabled = cfgPlanetWeatherEffects.get();
        // moon surface size: pushed into MoonBody's own volatile constant (setter forces even). Already-stamped moons read
        // their persisted size back, so this only sizes moons stamped after the bake, exactly like the other surface knobs.
        MoonBody.setSurfaceSize(cfgMoonSurfaceSize.get());
        // surface water + vegetation (stage 2b): push the baked values into SurfaceStamp's live tunables. Each in-flight
        // stamp snapshots these at construction, so a reload only affects planets stamped after it, never one mid-build.
        SurfaceStamp.setWaterEnabled(cfgSurfaceWaterEnabled.get());
        SurfaceStamp.setVegetationEnabled(cfgSurfaceVegetationEnabled.get());
        SurfaceStamp.setSeaLevelOffset(cfgSurfaceSeaLevelOffset.get());
        SurfaceStamp.setBasinFrequency(cfgSurfaceBasinFrequency.get());
        SurfaceStamp.setVegetationDensity(cfgSurfaceVegetationDensity.get());
        SurfaceStamp.setThemeWeights(cfgSurfaceThemeWeightStony.get(), cfgSurfaceThemeWeightOverworld.get(),
                cfgSurfaceThemeWeightNamek.get(), cfgSurfaceThemeWeightNether.get(), cfgSurfaceThemeWeightEnd.get(),
                cfgSurfaceThemeWeightKaio.get());
        // surface structures (stage 2c): same snapshot-at-construction discipline as the water/vegetation tunables.
        SurfaceStamp.setStructuresEnabled(cfgSurfaceStructuresEnabled.get());
        SurfaceStamp.setVillageFrequency(cfgSurfaceVillageFrequency.get());
        SurfaceStamp.setHutFrequency(cfgSurfaceHutFrequency.get());
        SurfaceStamp.setHutSeedPlantedChance(cfgSurfaceHutSeedPlantedChance.get());
        SurfaceStamp.setHutSeedHarvestChance(cfgSurfaceHutSeedHarvestChance.get());
        // wild defenders (garrison)
        wildDefendersEnabled = cfgWildDefendersEnabled.get();
        wildDefenderMinCount = cfgWildDefenderMinCount.get();
        wildDefenderMaxCount = cfgWildDefenderMaxCount.get();
        wildDefenderBattlePowerMin = cfgWildDefenderBattlePowerMin.get();
        wildDefenderBattlePowerMax = cfgWildDefenderBattlePowerMax.get();
        wildDefenderToughnessDivisor = cfgWildDefenderToughnessDivisor.get();
        wildFlatToughnessWhenDisabled = cfgWildFlatToughnessWhenDisabled.get();
        wildDefenderWeightSaiyan = cfgWildDefenderWeightSaiyan.get();
        wildDefenderWeightOverworld = cfgWildDefenderWeightOverworld.get();
        wildDefenderWeightNamekian = cfgWildDefenderWeightNamekian.get();
        wildDefenderWeightSaibamen = cfgWildDefenderWeightSaibamen.get();
        wildDefenderWeightFrostDemon = cfgWildDefenderWeightFrostDemon.get();
        wildDefenderWeightRobot = cfgWildDefenderWeightRobot.get();
        // one-time migration of the conquest boss lists from the old VILLAIN defaults to the new GOOD-guy defaults. This
        // feature is unreleased, but an operator's existing SpacePlanets.toml still holds the old villain strings; a
        // changed .define() default never touches an existing file, so we rewrite each conquestBoss* value in place, but
        // ONLY when it still EXACTLY equals the old default (an admin-edited value is never touched). Idempotent: after the
        // rewrite the value equals the new default, which differs from the old default, so it never migrates twice.
        migrateConquestDefault(cfgConquerBossStony, "saga_nappa,saga_raditz,saga_turles,saga_paragus",
                "saga_vegeta_mid_ssj,saga_ftrunks_ssj,saga_goten_ssj,saga_kid_trunks_ssj");
        migrateConquestDefault(cfgConquerBossOverworld, "saga_piccolo,saga_cell_perfect,saga_a17,saga_a18",
                "saga_goku_end_ssj,saga_gohan_end_ssj2,saga_piccolo,saga_krillin,saga_tien_early,saga_yamcha,saga_a17,saga_a18");
        migrateConquestDefault(cfgConquerBossNamek, "saga_nail,saga_piccolo,saga_slug",
                "saga_nail,saga_piccolo,saga_piccolo_kami");
        migrateConquestDefault(cfgConquerBossNether, "saga_dabura,saga_janemba_fat,saga_yakon",
                "saga_vegetto_ssj,saga_gohan_end_ultimate,saga_goku_end_ssj3,saga_gotenks_ssj3");
        migrateConquestDefault(cfgConquerBossEnd, "saga_frieza_fp,saga_cooler_5ta,saga_king_cold,saga_ginyu",
                "saga_goku_end_ssj3,saga_vegeta_end_ssj2,saga_ftrunks_ssg3,saga_vegetto_base");
        migrateConquestDefault(cfgConquerBossKaio, "saga_paikuhan,saga_kidbuu,saga_shin",
                "saga_paikuhan,saga_shin,saga_kibito");
        migrateConquestDefault(cfgConquerBossOther, "saga_paikuhan,saga_frieza_fp,saga_cell_perfect",
                "saga_paikuhan,saga_goku_end_ssj2,saga_gohan_end_ultimate,saga_vegetto_ssj");
        // public conquest defender boss: build the theme -> boss-id table from the per-theme config strings and push the
        // whole config into PlanetConquest (volatile there, read on the server thread).
        java.util.EnumMap<SurfaceStamp.Theme, java.util.List<String>> conquerTable =
                new java.util.EnumMap<>(SurfaceStamp.Theme.class);
        conquerTable.put(SurfaceStamp.Theme.STONY, PlanetConquest.parseIds(cfgConquerBossStony.get()));
        conquerTable.put(SurfaceStamp.Theme.OVERWORLD, PlanetConquest.parseIds(cfgConquerBossOverworld.get()));
        conquerTable.put(SurfaceStamp.Theme.NAMEK, PlanetConquest.parseIds(cfgConquerBossNamek.get()));
        conquerTable.put(SurfaceStamp.Theme.NETHER, PlanetConquest.parseIds(cfgConquerBossNether.get()));
        conquerTable.put(SurfaceStamp.Theme.END, PlanetConquest.parseIds(cfgConquerBossEnd.get()));
        conquerTable.put(SurfaceStamp.Theme.KAIO, PlanetConquest.parseIds(cfgConquerBossKaio.get()));
        conquerTable.put(SurfaceStamp.Theme.OTHERWORLD, PlanetConquest.parseIds(cfgConquerBossOther.get()));
        PlanetConquest.setConfig(cfgConquerEnabled.get(), cfgConquerStatTolerance.get(),
                cfgConquerSpawnDistance.get(), conquerTable);
        PlanetOwnerAvatar.setConfig(cfgClaimAvatarDefender.get(), cfgAvatarStatMultiplier.get(),
                cfgAvatarRespawnMinutes.get(), cfgExplodableWindowMinutes.get());
        superGodEnabled = cfgSuperGodEnabled.get();
        superGodBattlePowerMin = cfgSuperGodBattlePowerMin.get();
        superGodBattlePowerMax = cfgSuperGodBattlePowerMax.get();
        superRenderDistance = cfgSuperRenderDistance.get();
        PlanetRegistry.excludeTimeChamber = cfgExcludeTimeChamber.get();
        PlanetRegistry.requireLoadedDimension = cfgRequireLoadedDimension.get();
        // the exclusion toggles just changed what counts as a body, so drop the eligibility cache.
        PlanetRegistry.invalidate();
        // the layout numbers (and possibly the fixed-body set) just changed, and the client draws bodies by re-deriving
        // from those same numbers, so push the new layout to every online client. A no-op before the server exists.
        SpaceLayoutSync.syncAll();
    }

    @Override
    public ConfigData returnData()
    {
        return data;
    }
}
