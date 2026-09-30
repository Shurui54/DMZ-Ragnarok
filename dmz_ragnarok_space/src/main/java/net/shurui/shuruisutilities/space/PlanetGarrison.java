package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dragonminez.common.init.EntityAttributes;
import com.dragonminez.common.init.entities.IBattlePower;
import com.dragonminez.common.init.entities.sagas.DBSagasEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.core.misc.SafeSpotResolver;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.shurui.shuruisutilities.world.space.InhabitedPlanets;
import net.shurui.shuruisutilities.saiyan.SaiyanAppearance;
import net.shurui.shuruisutilities.saiyan.SaiyanArmorSets;

/**
 * Server-side controller for a wild planet's VISIBLE GARRISON: the killable NPC defenders a guild must beat before it
 * can claim an unowned planet. Lazily populates a planet the first time a player arrives, stamps each defender with
 * randomised stats, tracks defeat by IDENTITY (not "is anything loaded"), sets the planet's clash toughness and
 * recommended battle power, gates the claim, and cleans up on claim or destruction.
 *
 * <h2>Not the clash holder (read this)</h2>
 * A GARRISON defender is a VISIBLE, killable surface NPC, a completely different thing from {@link PlanetDefenderEntity}
 * / {@link PlanetDefenderEntities}, the INVISIBLE, invulnerable beam-clash HOLDER the planet-buster spawns to let
 * DragonMineZ pair two beams. The two never share an entity or type; only the word "defender". The species/art table is
 * {@link PlanetGarrisonRoster}; the persisted per-planet state {@link PlanetGarrisonData}.
 *
 * <h2>The "never permanently unclaimable" guarantee</h2>
 * A broken defender system must never leave a planet a guild can never claim. Two mechanisms:
 * <ul>
 *   <li>Population is ATOMIC and failure-open: {@link #ensurePopulated} always finishes by recording a garrison, even if
 *       every spawn threw or the feature is disabled. An EMPTY roster reads as "0 remaining", immediately claimable, so
 *       a crash during population yields no phantom that blocks forever.</li>
 *   <li>The claim gate RECONCILES: if the record shows defenders remaining but a live scan finds none alive, the phantom
 *       is cleared and the claim proceeds. A claimer stands on the planet, so its surface disc is loaded and the scan is
 *       authoritative.</li>
 * </ul>
 * Every DragonMineZ touchpoint is wrapped so any Throwable is caught and logged ONCE via a latched boolean, then
 * degrades to fewer/plainer defenders, never a crash or a log flood.
 */
public final class PlanetGarrison
{
    private PlanetGarrison()
    {
    }

    // ForgeData marker written on every garrison defender so a death, cleanup sweep or claim scan can recognise it and
    // route to the right planet. Survives a chunk reload (ForgeData is saved with the entity).
    private static final String DEFENDER_FLAG = "su_planet_defender";
    private static final String DEFENDER_PLANET = "su_planet_defender_planet";

    // the opt-out flag: tell DragonMineZ these stats are hand-managed so its entity-join stat init does not overwrite
    // what we set. Written exactly as CloneStats / the raid bosses write it.
    private static final String DMZ_STATS_CONFIGURED = "dmz_stats_configured";

    // raw NPC-defense key read by SU's own mitigation handler (the CloneStats key). Never also set ARMOR, or damage
    // would be mitigated twice.
    private static final String NPC_DEFENSE_KEY = "dmz_npc_defense";

    // A defender's max health, melee and defense derive from its rolled BATTLE POWER through these divisors, so one
    // configurable battle-power band (PlanetSpawnModule) drives all its combat numbers, like CloneStats.fallback. Each
    // is floored so even a low-BP defender is a real, hittable body rather than a one-shot.
    private static final double HEALTH_BP_DIVISOR = 50.0;   // bp 50,000 -> 1,000 max health
    private static final double MELEE_BP_DIVISOR = 500.0;   // bp 50,000 -> 100 melee
    private static final double DEFENSE_BP_DIVISOR = 1000.0; // bp 50,000 -> 50 raw NPC defense
    private static final double MIN_HEALTH = 20.0;
    private static final double MIN_MELEE = 2.0;

    // the datapack structure whose footprint hosts the neutral saiyan crowd on an inhabited planet (Planet Vegeta).
    // Saibaman huts that also generate there are NOT populated by this neutral path (a saibaman is hostile), left for a
    // future hostile populator. A ResourceLocation resolved from the live registry, never compiled against, since a
    // datapack could omit it.
    private static final ResourceLocation SAIYAN_SETTLEMENT_ID =
            new ResourceLocation("shuruisutilities", "saiyan_settlement");

    // How many of each kind fill ONE settlement: a small group per settlement, not one big crowd, so the planet reads as
    // populated across its surface. Every settlement also gets exactly one trader.
    private static final int NEUTRAL_MIN_PER_SETTLEMENT = 4;
    private static final int NEUTRAL_MAX_PER_SETTLEMENT = 8;
    private static final int NEUTRAL_TRADERS_PER_SETTLEMENT = 1;

    // How many HOSTILE garrison saiyans defend ONE saiyan_settlement structure: Vegeta's wild warrior outposts, so every
    // one is armed as well as inhabited. Placed alongside the citizens in the same footprint and populateSettlement pass,
    // tracked by the same per-settlement key so a killed soldier stays dead. They use the PlanetSaiyanGarrisonEntity saga
    // chassis, are statted to the Vegeta saiyan tier by applyVegetaSaiyanStats (which lights up the saga AI tier and
    // ranged ki-skill pool so they FIGHT), roll the citizens' appearance and armor, and are confined to Vegeta by their
    // home planet. NOT garrison DEFENDERS: Vegeta is inhabited and never claimable, so they carry no defender flag and
    // never enter the claim roster. A squad of 3 to 6 suits a defended outpost. Tunable; confirm with a launch test.
    private static final int GARRISON_MIN_PER_SETTLEMENT = 3;
    private static final int GARRISON_MAX_PER_SETTLEMENT = 6;

    // How far from the player we sample for a settlement footprint, so a settlement lights up as the player approaches
    // rather than only dead-centre. Each sample is a cheap getStructureAt on already-loaded chunk data; a point in an
    // unloaded chunk misses (no generation is forced).
    private static final int SETTLEMENT_SAMPLE_REACH = 48;

    // Planet Vegeta's city ships as PRE-GENERATED region data seeded from the jar (PlanetRegionSeeder), NOT a jigsaw
    // structure. Those authored chunks carry NO structure references, so the settlement path above (getStructureAt) can
    // never find anything inside the city. The city gets its own population path keyed off POSITION: a fixed box over
    // the build, tiled into cells, each filled once as a player roams in.
    private static final String PLANET_VEGETA_ID = "dmz_ragnarok:planet_vegeta";

    // The authored build's horizontal extent in blocks (inclusive), pinned by the region build. Landing coordinate
    // (155, 65, -75) sits on open ground in the middle. A hardcoded box, not the shuruisutilities:vegeta_city biome
    // test, on purpose: deterministic and independent of the in-progress biome stamping, so saiyans appear even if the
    // biome is absent, and every candidate is still ground-validated below.
    private static final int CITY_MIN_X = -318;
    private static final int CITY_MAX_X = 670;
    private static final int CITY_MIN_Z = -322;
    private static final int CITY_MAX_Z = 351;

    // The city's ground level (the pinned landing Y) and the band around it a spawn must land within. A resolved spot
    // higher than CITY_GROUND_Y + CITY_GROUND_ABOVE is a rooftop and is rejected; lower than CITY_GROUND_Y -
    // CITY_GROUND_BELOW is a pit/basement and is rejected. This is what keeps dense authored architecture from putting a
    // saiyan on a roof, since SafeSpotResolver on its own would happily stand one on any solid block including a roof.
    private static final int CITY_GROUND_Y = 65;
    private static final int CITY_GROUND_BELOW = 8;
    private static final int CITY_GROUND_ABOVE = 6;

    // The city is tiled into CITY_CELL-square cells (48 blocks = 3x3 chunks), each populated exactly once and tracked by
    // its anchor chunk key in the SAME neutralSettlements store the wilderness path uses. A cell holds a small group so
    // the crowd is spread across the whole city rather than heaped in one square; the count that matters for lag is how
    // many are LOADED near the player, and at this density a render-distance disc of filled cells is a busy-but-safe crowd.
    private static final int CITY_CELL = 48;
    private static final int CITY_CHUNKS_PER_CELL = CITY_CELL / 16;
    // Lowered from 3..6 after live play reported the capital was too crowded: a 3x3 disc of cells around the player is
    // filled, so at 1..3 per cell the loaded crowd is roughly a third of before while the city still reads as populated.
    // Wilderness settlement counts (NEUTRAL_MIN/MAX_PER_SETTLEMENT) are deliberately left untouched.
    private static final int CITY_CITIZENS_MIN = 1;
    private static final int CITY_CITIZENS_MAX = 3;
    // Not every cell gets a trader (a shop every 48 blocks would be absurd); lowered to roughly one cell in five so a
    // shopper is still never far from a stall without the city being dotted with traders.
    private static final float CITY_TRADER_CHANCE = 0.2F;

    // How many random columns we try to place ONE townsperson before giving up on it. A dense city means many candidates
    // land in a wall or on a roof and are rejected, so we need several tries; if all fail the cell simply gets fewer
    // people rather than a body forced into a bad spot.
    private static final int CITY_PLACE_ATTEMPTS = 12;

    // one-shot latch so a neutral-population failure is logged ONCE, never on every tick a player roams an inhabited
    // planet, and a second latch for a genuinely missing settlement structure (a datapack that omits it).
    private static final AtomicBoolean NEUTRAL_WARNED = new AtomicBoolean(false);
    private static final AtomicBoolean SETTLEMENT_MISSING_WARNED = new AtomicBoolean(false);

    // one-shot latch so a DragonMineZ stat/skill API drift is logged ONCE across the whole run, never per spawn.
    private static final AtomicBoolean DMZ_STAT_WARNED = new AtomicBoolean(false);
    // one-shot latch for a population failure, so a persistent problem does not spam the log on every landing.
    private static final AtomicBoolean POPULATE_WARNED = new AtomicBoolean(false);

    /**
     * Ensure this planet's garrison exists, spawning it the first time a player arrives and never again. Safe to call on
     * every landing/arrival: it returns immediately if the feature is disabled, the planet is claimed or destroyed, or
     * it is already populated. Runs on the server thread. NEVER throws: the whole body is wrapped so any failure records
     * an EMPTY garrison (a claimable planet) rather than leaving the planet un-populated to be retried forever or, worse,
     * permanently unclaimable.
     */
    public static void ensurePopulated(MinecraftServer server, ServerLevel surface, String planetId)
    {
        if (server == null || surface == null || planetId == null || planetId.isEmpty())
        {
            return;
        }
        PlanetGarrisonData data = PlanetGarrisonData.get(server);
        if (data.isPopulated(planetId))
        {
            return; // rolled once already; a revisit must not re-roll.
        }

        // feature off: still MARK the planet populated with no defenders so it is claimable and never re-checked. The
        // flat wild toughness (governed by its own config flag) is applied by PlanetToughness, not stored here.
        if (!PlanetSpawnModule.wildDefendersEnabled())
        {
            data.markPopulated(planetId, "", new HashSet<>(), flatToughness(), 0.0);
            return;
        }

        GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);
        // isOwned, not isClaimed: a planet held by a PERSONAL conquest claim is just as owned as a guild-claimed one and
        // must grow no wild garrison either.
        if (claims.isOwned(planetId) || claims.isDestroyed(planetId))
        {
            // an owned or destroyed world gets no wild garrison; mark it populated-empty so we do not keep re-checking.
            data.markPopulated(planetId, "", new HashSet<>(), flatToughness(), 0.0);
            return;
        }

        try
        {
            populate(server, surface, planetId, data);
        }
        catch (Throwable t)
        {
            // population failed hard (a DMZ drift, a spawn error): record an EMPTY garrison so the planet is CLAIMABLE,
            // never stuck. This is the core "a broken defender system never makes a planet permanently unclaimable" net.
            data.markPopulated(planetId, "", new HashSet<>(), flatToughness(), 0.0);
            if (POPULATE_WARNED.compareAndSet(false, true))
            {
                LoggingHandler.sulog.warn("[PlanetGarrison] Failed to populate defenders for planet {}; leaving it "
                        + "undefended (claimable) rather than blocking it.", planetId, t);
            }
        }
    }

    // roll and spawn the garrison, then record it. Extracted so ensurePopulated's try/catch guards the whole thing.
    private static void populate(MinecraftServer server, ServerLevel surface, String planetId, PlanetGarrisonData data)
    {
        RandomSource random = surface.getRandom();
        PlanetGarrisonRoster.Family family =
                PlanetGarrisonRoster.pickFamily(random, PlanetSpawnModule.wildDefenderFamilyWeights());

        int min = PlanetSpawnModule.wildDefenderMinCount();
        int max = PlanetSpawnModule.wildDefenderMaxCount();
        int count = min >= max ? min : min + random.nextInt(max - min + 1);

        Vec3 centre = SurfaceDimension.cellCentre(planetId);
        int half = Math.max(1, GeneratedPlanetClaims.stampedSizeForId(server, planetId) / 2 - 2);

        Set<UUID> roster = new HashSet<>();
        double aggregateBp = 0.0;
        double strongestBp = 0.0;
        for (int k = 0; k < count; k++)
        {
            double bp = rollBattlePower(random);
            UUID id = spawnOne(surface, family, planetId, centre, half, bp, random);
            if (id != null)
            {
                roster.add(id);
                aggregateBp += bp;
                strongestBp = Math.max(strongestBp, bp);
            }
        }

        // toughness: the aggregate defender battle power scaled by the config divisor, floored at the flat wild toughness
        // so a defended planet is never EASIER to bust than an undefended one. An empty roster keeps the flat value.
        double toughness;
        if (roster.isEmpty())
        {
            toughness = flatToughness();
        }
        else
        {
            double divisor = PlanetSpawnModule.wildDefenderToughnessDivisor();
            double fromDefenders = divisor > 0.0 ? aggregateBp / divisor : aggregateBp;
            toughness = Math.max(flatToughness(), fromDefenders);
        }

        data.markPopulated(planetId, roster.isEmpty() ? "" : family.name(), roster, toughness, strongestBp);
    }

    /**
     * Populate the NEUTRAL saiyan crowd on an inhabited planet (Planet Vegeta) AT ITS SETTLEMENTS, lazily and once per
     * settlement. Called on a THROTTLED player tick while a player roams the planet (see {@code SpaceTravelModule}).
     *
     * <p>There are TWO population paths and both run every pass:
     * <ul>
     *   <li><b>City (position-based):</b> Planet Vegeta's authored city ships as pre-generated region data, so its
     *       chunks carry NO structure references and the structure path below can never see it. It is instead filled by
     *       {@link #ensureCityCells} off a fixed bounding box over the build, tiled into cells populated once each as the
     *       player nears them. A no-op on any other planet.</li>
     *   <li><b>Wilderness (structure-reference based):</b> it samples the {@code saiyan_settlement} structure footprints
     *       at and around the player, and the first time a player comes near one it fills that settlement (Vegeta's wild
     *       warrior GARRISON) with a small group of PASSIVE saiyans plus a trader AND an armed HOSTILE garrison squad,
     *       all inside the one structure footprint.</li>
     * </ul>
     * Both record what they filled (per cell / per settlement) so nothing ever re-populates and killed inhabitants never
     * respawn.
     *
     * <h2>Why per-settlement and how it is bounded</h2>
     * Populating every settlement on a whole planet at once could be thousands of entities and would wreck the server.
     * Instead each settlement is populated LAZILY the first time a player is near it, tracked per settlement position in
     * {@link PlanetGarrisonData#markSettlementPopulated}, so the cost is spread out and only settlements a player actually
     * visits ever spawn anything. Discovery is cheap: {@code StructureManager.getStructureAt} reads only the structure
     * references already stored in LOADED chunks around the player (no {@code findNearestMapStructure}, no forced chunk
     * generation, no wide outward search), so it is safe to run on a throttled tick and never hitches the server.
     *
     * <h2>CRITICAL separation from the claim system (unchanged from the crowd version)</h2>
     * A neutral saiyan is NEVER a garrison defender: it carries only its own {@code su_planet_citizen} marker
     * (self-stamped in its constructor), never {@code su_planet_defender}, and it is NEVER added to the {@link
     * PlanetGarrisonData} roster (the settlement store is a wholly separate map that the roster/defeat/claim code never
     * reads). So {@link #claimBlocked}/{@code countLiveDefenders} and {@link #sweepOrphans}, which only ever touch
     * DEFENDER-flagged entities, are blind to a citizen. A real inhabited dimension is not a claimable generated planet
     * anyway. No hostile combat stat block is applied: a citizen is a passive {@link
     * net.minecraft.world.entity.PathfinderMob} with no target goals, so it simply keeps its entity-type defaults.
     *
     * <p>Never throws.
     */
    public static void ensureNeutralSettlements(MinecraftServer server, ServerLevel level, String planetId,
                                                BlockPos around)
    {
        if (server == null || level == null || planetId == null || planetId.isEmpty() || around == null)
        {
            return;
        }
        // the garrison only spawns a peaceful crowd on a NEUTRAL planet; anything else is a caller error and refused, so
        // the hostile-vs-neutral split lives in one place (InhabitedPlanets), never duplicated here.
        if (!InhabitedPlanets.isNeutral(planetId))
        {
            return;
        }
        try
        {
            PlanetGarrisonData data = PlanetGarrisonData.get(server);
            RandomSource random = level.getRandom();

            // City path (position-based): fill Planet Vegeta's authored, pre-generated city, which carries no structure
            // references at all and so is invisible to the structure-reference sampling below. A no-op on any other
            // neutral planet (its box gate does not match). This is ADDITIONAL to, not a replacement for, the wilderness
            // path: both run every pass and key into the same neutralSettlements store with disjoint keys.
            ensureCityCells(level, planetId, around, data, random);

            // Wilderness path (structure-reference based): the procedurally generated saiyan_settlement footprints out in
            // the terrain, unchanged. Skipped only if the datapack omits the structure entirely (logged once).
            Structure settlement = resolveSettlementStructure(level);
            if (settlement == null)
            {
                return; // datapack omits the settlement structure (logged once); the city path above already ran.
            }
            for (BlockPos sample : sampleAround(around))
            {
                // only probe chunks that are ALREADY loaded: getStructureAt would otherwise drive a chunk to its
                // structure-reference worldgen status, which is exactly the forced generation we promised to avoid. A
                // sample in an unloaded chunk is simply skipped and picked up on a later pass once the player nears it.
                if (!level.hasChunk(sample.getX() >> 4, sample.getZ() >> 4))
                {
                    continue;
                }
                StructureStart start = level.structureManager().getStructureAt(sample, settlement);
                if (!start.isValid())
                {
                    continue;
                }
                long key = start.getChunkPos().toLong();
                if (data.isSettlementPopulated(planetId, key))
                {
                    continue; // this settlement already has its crowd; never re-populate, so killed saiyans stay dead.
                }
                int spawned = populateSettlement(level, planetId, start, random);
                if (spawned > 0)
                {
                    // mark ONLY after something actually joined the world, so a settlement whose chunks were mid-load
                    // (spawned 0) is retried on a later pass rather than marked done but empty.
                    data.markSettlementPopulated(planetId, key);
                }
            }
        }
        catch (Throwable t)
        {
            if (NEUTRAL_WARNED.compareAndSet(false, true))
            {
                LoggingHandler.sulog.warn("[PlanetGarrison] Failed to populate neutral settlements for planet {}; will "
                        + "retry as players roam it.", planetId, t);
            }
        }
    }

    // the sample points used to find settlement footprints near a player: the player's own block plus four points a
    // fixed reach out on each horizontal axis, so a settlement populates as the player nears it, not only dead-centre.
    // Y is irrelevant to getStructureAt's horizontal bounding-box test, so we keep the player's Y on every sample.
    private static List<BlockPos> sampleAround(BlockPos around)
    {
        List<BlockPos> points = new ArrayList<>(5);
        points.add(around);
        points.add(around.offset(SETTLEMENT_SAMPLE_REACH, 0, 0));
        points.add(around.offset(-SETTLEMENT_SAMPLE_REACH, 0, 0));
        points.add(around.offset(0, 0, SETTLEMENT_SAMPLE_REACH));
        points.add(around.offset(0, 0, -SETTLEMENT_SAMPLE_REACH));
        return points;
    }

    // Populate Planet Vegeta's authored city by POSITION. The city is tiled into fixed cells; the cell the player stands
    // in and its eight neighbours are each filled once with a small saiyan crowd the first time a player comes near them,
    // so the crowd lights up ahead of and around the player as they walk the city, and is tracked per cell so it never
    // re-populates and killed inhabitants stay dead. A no-op on any planet other than Vegeta (no other planet ships an
    // authored city), so a future inhabited planet without a city is unaffected. Returns how many joined the world.
    private static int ensureCityCells(ServerLevel level, String planetId, BlockPos around, PlanetGarrisonData data,
                                       RandomSource random)
    {
        if (!PLANET_VEGETA_ID.equals(planetId))
        {
            return 0;
        }
        int centreCellX = Math.floorDiv(around.getX(), CITY_CELL);
        int centreCellZ = Math.floorDiv(around.getZ(), CITY_CELL);
        int total = 0;
        for (int dcx = -1; dcx <= 1; dcx++)
        {
            for (int dcz = -1; dcz <= 1; dcz++)
            {
                int cellX = centreCellX + dcx;
                int cellZ = centreCellZ + dcz;
                // clip the cell to the authored city extent, so a cell straddling the edge only ever spawns inside the
                // build and a cell wholly outside it (the box's ragged fringe) is skipped entirely.
                int minX = Math.max(cellX * CITY_CELL, CITY_MIN_X);
                int minZ = Math.max(cellZ * CITY_CELL, CITY_MIN_Z);
                int maxX = Math.min(cellX * CITY_CELL + CITY_CELL - 1, CITY_MAX_X);
                int maxZ = Math.min(cellZ * CITY_CELL + CITY_CELL - 1, CITY_MAX_Z);
                if (minX > maxX || minZ > maxZ)
                {
                    continue; // this cell lies outside the city; nothing to fill.
                }
                // key by the cell's anchor CHUNK, so it lives in the same neutralSettlements store as wilderness
                // settlement keys with no collision: the city box and the wilderness never share a chunk (the box is all
                // seeded, on-disk chunks, so no wilderness structure can generate inside it).
                long key = ChunkPos.asLong(cellX * CITY_CHUNKS_PER_CELL, cellZ * CITY_CHUNKS_PER_CELL);
                if (data.isSettlementPopulated(planetId, key))
                {
                    continue; // this cell already has its crowd; never re-populate.
                }
                int spawned = populateCityCell(level, planetId, minX, maxX, minZ, maxZ, random);
                if (spawned > 0)
                {
                    // mark ONLY after something actually joined, matching the wilderness path: a cell whose chunks were
                    // mid-load (spawned 0) is retried on a later pass rather than marked done but empty.
                    data.markSettlementPopulated(planetId, key);
                    total += spawned;
                }
            }
        }
        return total;
    }

    // fill one city cell with a small group of neutral saiyans plus an occasional trader, each safely ground-placed
    // inside the cell's clipped bounds. Returns how many joined the world alive.
    private static int populateCityCell(ServerLevel level, String planetId, int minX, int maxX, int minZ, int maxZ,
                                        RandomSource random)
    {
        int min = CITY_CITIZENS_MIN;
        int max = CITY_CITIZENS_MAX;
        int count = min >= max ? min : min + random.nextInt(max - min + 1);
        int spawned = 0;
        for (int k = 0; k < count; k++)
        {
            if (spawnCityCitizen(level, planetId, minX, maxX, minZ, maxZ, random))
            {
                spawned++;
            }
        }
        if (random.nextFloat() < CITY_TRADER_CHANCE && spawnCityTrader(level, planetId, minX, maxX, minZ, maxZ, random))
        {
            spawned++;
        }
        return spawned;
    }

    // spawn one PASSIVE saiyan somewhere safe inside a city cell. Same neutral contract as the settlement citizen: no
    // defender flag, no roster, no combat stat block. Returns true only if a safe ground spot was found AND it joined
    // the world alive; a cell with no safe spot for this body simply gets one fewer person.
    private static boolean spawnCityCitizen(ServerLevel level, String planetId, int minX, int maxX, int minZ, int maxZ,
                                            RandomSource random)
    {
        SafeSpotResolver.Result spot = resolveCitySpot(level, minX, maxX, minZ, maxZ, random);
        if (spot == null)
        {
            return false;
        }
        PlanetSaiyanCitizenEntity citizen = PlanetSaiyanTownEntities.CITIZEN.get().create(level);
        if (citizen == null)
        {
            return false;
        }
        citizen.moveTo(spot.x, spot.y, spot.z, random.nextFloat() * 360.0F, 0.0F);
        citizen.randomize(random);
        citizen.setHomePlanet(planetId);
        citizen.equipSaiyanArmor();
        // stat this inhabitant to its DMZ saga tier (a Frieza soldier, or the Ginyu Force block for a named donor NPC).
        // COMBAT NUMBERS ONLY: the citizen's passive goals and its setTarget refusal are untouched, so it stays peaceful,
        // just tanky. This runs only from the neutral-settlement path, which is gated to inhabited planets, and Vegeta is
        // the only inhabited planet today, so the saiyan tier lands on Vegeta and never on a generated planet's garrison.
        applyVegetaSaiyanStats(citizen, citizen);
        citizen.setPersistenceRequired();
        return addNeutralAlive(level, citizen, planetId);
    }

    // spawn one saiyan trader somewhere safe inside a city cell. The trader wears a full saiyan set too (the town's
    // saiyans are all armored), rolled from the shared pool like everyone else.
    private static boolean spawnCityTrader(ServerLevel level, String planetId, int minX, int maxX, int minZ, int maxZ,
                                           RandomSource random)
    {
        SafeSpotResolver.Result spot = resolveCitySpot(level, minX, maxX, minZ, maxZ, random);
        if (spot == null)
        {
            return false;
        }
        SaiyanTraderEntity trader = PlanetSaiyanTownEntities.TRADER.get().create(level);
        if (trader == null)
        {
            return false;
        }
        trader.moveTo(spot.x, spot.y, spot.z, random.nextFloat() * 360.0F, 0.0F);
        trader.randomize(random);
        trader.setHomePlanet(planetId);
        SaiyanArmorSets.equip(trader, random);
        trader.setPersistenceRequired();
        return addNeutralAlive(level, trader, planetId);
    }

    // find a safe ground stand inside the given city bounds, trying up to CITY_PLACE_ATTEMPTS random columns. A candidate
    // is accepted only if its chunk is already loaded (so we never force-generate the city's edge on a throttled tick),
    // SafeSpotResolver did not have to fall back to world spawn, and the resolved Y sits in the ground band (rejecting a
    // rooftop above or a basement/pit below, the two ways dense architecture defeats a naive ground snap). Returns null
    // if every attempt failed, and the caller then spawns one fewer townsperson rather than forcing a bad placement.
    private static SafeSpotResolver.Result resolveCitySpot(ServerLevel level, int minX, int maxX, int minZ, int maxZ,
                                                           RandomSource random)
    {
        for (int attempt = 0; attempt < CITY_PLACE_ATTEMPTS; attempt++)
        {
            int x = minX + random.nextInt(maxX - minX + 1);
            int z = minZ + random.nextInt(maxZ - minZ + 1);
            if (!level.hasChunk(x >> 4, z >> 4))
            {
                continue; // unloaded column: skip so we never drive worldgen from here; a later pass retries it.
            }
            SafeSpotResolver.Result spot = SafeSpotResolver.resolve(level, x + 0.5, CITY_GROUND_Y, z + 0.5);
            if (spot.fellBackToSpawn)
            {
                continue; // nothing safe anywhere near this column.
            }
            if (spot.y < CITY_GROUND_Y - CITY_GROUND_BELOW || spot.y > CITY_GROUND_Y + CITY_GROUND_ABOVE)
            {
                continue; // resolved onto a roof or down a shaft, not street/ground level: reject and try elsewhere.
            }
            return spot;
        }
        return null;
    }

    // resolve the saiyan-settlement Structure from the live registry, or null (logged once) if the datapack omits it.
    private static Structure resolveSettlementStructure(ServerLevel level)
    {
        try
        {
            Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            Structure structure = registry.get(SAIYAN_SETTLEMENT_ID);
            if (structure == null && SETTLEMENT_MISSING_WARNED.compareAndSet(false, true))
            {
                LoggingHandler.sulog.warn("[PlanetGarrison] Structure '{}' is not registered; no neutral saiyans will "
                        + "spawn at settlements.", SAIYAN_SETTLEMENT_ID);
            }
            return structure;
        }
        catch (Throwable t)
        {
            if (SETTLEMENT_MISSING_WARNED.compareAndSet(false, true))
            {
                LoggingHandler.sulog.warn("[PlanetGarrison] Failed resolving structure '{}'; no neutral saiyans will "
                        + "spawn at settlements.", SAIYAN_SETTLEMENT_ID, t);
            }
            return null;
        }
    }

    // fill one settlement's footprint with a small group of neutral saiyans plus a trader, scattering each within the
    // structure's bounding box on safe ground. Returns how many joined the world alive; the caller marks the settlement
    // populated only when this is positive.
    private static int populateSettlement(ServerLevel level, String planetId, StructureStart start, RandomSource random)
    {
        BoundingBox box = start.getBoundingBox();
        int min = NEUTRAL_MIN_PER_SETTLEMENT;
        int max = NEUTRAL_MAX_PER_SETTLEMENT;
        int count = min >= max ? min : min + random.nextInt(max - min + 1);

        int spawned = 0;
        for (int k = 0; k < count; k++)
        {
            if (spawnCitizenInBox(level, planetId, box, random))
            {
                spawned++;
            }
        }
        for (int k = 0; k < NEUTRAL_TRADERS_PER_SETTLEMENT; k++)
        {
            if (spawnTraderInBox(level, planetId, box, random))
            {
                spawned++;
            }
        }
        // the garrison: an armed saiyan squad defending this outpost, placed inside the same footprint as the citizens.
        int gmin = GARRISON_MIN_PER_SETTLEMENT;
        int gmax = GARRISON_MAX_PER_SETTLEMENT;
        int gcount = gmin >= gmax ? gmin : gmin + random.nextInt(gmax - gmin + 1);
        for (int k = 0; k < gcount; k++)
        {
            if (spawnGarrisonInBox(level, planetId, box, random))
            {
                spawned++;
            }
        }
        return spawned;
    }

    // spawn one HOSTILE garrison saiyan inside a settlement (garrison) footprint. Uses the PlanetSaiyanGarrisonEntity
    // saga chassis (the same one the generated-planet garrison uses), scatter-and-safe-spots it within the structure box
    // exactly like the citizen path, rolls its appearance and saiyan armor, sets its home planet so its confinement goal
    // keeps it on Vegeta, and stats it to the Vegeta saiyan tier: because the chassis is a DBSagasEntity,
    // applyVegetaSaiyanStats also installs the saga AI tier and the ranged ki-skill pool, so it actually FIGHTS rather
    // than standing inert. It is NOT a garrison defender (no defender flag, never added to the claim roster): Vegeta is
    // an inhabited, unclaimable planet, so the claim/defeat system never touches it. Returns true only if it joined the
    // world alive; a footprint with no safe spot simply gets one fewer soldier.
    private static boolean spawnGarrisonInBox(ServerLevel level, String planetId, BoundingBox box, RandomSource random)
    {
        PlanetSaiyanGarrisonEntity saiyan = PlanetSaiyanGarrisonEntities.GARRISON_SAIYAN.get().create(level);
        if (saiyan == null)
        {
            return false;
        }
        placeInBox(level, saiyan, box, random);
        saiyan.randomize(random);
        saiyan.setHomePlanet(planetId);
        SaiyanArmorSets.equip(saiyan, random);
        applyVegetaSaiyanStats(saiyan, saiyan);
        saiyan.setPersistenceRequired();
        return addNeutralAlive(level, saiyan, planetId);
    }

    // spawn one PASSIVE saiyan inside a settlement box: mint the citizen, scatter-and-safe-spot it within the footprint,
    // roll its saiyan appearance and equip its saiyan armor (the shared recipe), assign its home planet, pin it
    // persistent, and add it. NEVER stamps a defender flag, NEVER touches the roster, NEVER applies the combat stat
    // block. Returns true only if it joined the world alive.
    private static boolean spawnCitizenInBox(ServerLevel level, String planetId, BoundingBox box, RandomSource random)
    {
        PlanetSaiyanCitizenEntity citizen = PlanetSaiyanTownEntities.CITIZEN.get().create(level);
        if (citizen == null)
        {
            return false;
        }
        placeInBox(level, citizen, box, random);
        citizen.randomize(random);
        citizen.setHomePlanet(planetId);
        citizen.equipSaiyanArmor();
        // stat this inhabitant to its DMZ saga tier (a Frieza soldier, or the Ginyu Force block for a named donor NPC).
        // COMBAT NUMBERS ONLY: the citizen's passive goals and its setTarget refusal are untouched, so it stays peaceful,
        // just tanky. This runs only from the neutral-settlement path, which is gated to inhabited planets, and Vegeta is
        // the only inhabited planet today, so the saiyan tier lands on Vegeta and never on a generated planet's garrison.
        applyVegetaSaiyanStats(citizen, citizen);
        citizen.setPersistenceRequired();
        return addNeutralAlive(level, citizen, planetId);
    }

    // spawn one saiyan trader inside a settlement box: roll its look, gear it in a full saiyan set (the town's saiyans
    // are all armored) and assign the town's planet. Returns true only if it joined the world alive.
    private static boolean spawnTraderInBox(ServerLevel level, String planetId, BoundingBox box, RandomSource random)
    {
        SaiyanTraderEntity trader = PlanetSaiyanTownEntities.TRADER.get().create(level);
        if (trader == null)
        {
            return false;
        }
        placeInBox(level, trader, box, random);
        trader.randomize(random);
        trader.setHomePlanet(planetId);
        SaiyanArmorSets.equip(trader, random);
        trader.setPersistenceRequired();
        return addNeutralAlive(level, trader, planetId);
    }

    // scatter a townsperson at a random column within the settlement's bounding box, then snap it to a safe stand so
    // nobody lands inside a wall or hanging in the air. The box centre Y is a hint; SafeSpotResolver scans to the ground.
    private static void placeInBox(ServerLevel level, Mob mob, BoundingBox box, RandomSource random)
    {
        int x = box.minX() + random.nextInt(Math.max(1, box.getXSpan()));
        int z = box.minZ() + random.nextInt(Math.max(1, box.getZSpan()));
        double yHint = (box.minY() + box.maxY()) / 2.0;
        SafeSpotResolver.Result spot = SafeSpotResolver.resolve(level, x + 0.5, yHint, z + 0.5);
        mob.moveTo(spot.x, spot.y, spot.z, random.nextFloat() * 360.0F, 0.0F);
    }

    // add a neutral townsperson to the world and report whether it joined alive. A failed add is logged once and skipped.
    private static boolean addNeutralAlive(ServerLevel level, Mob mob, String planetId)
    {
        if (!level.addFreshEntity(mob) || mob.isRemoved() || !mob.isAlive())
        {
            if (NEUTRAL_WARNED.compareAndSet(false, true))
            {
                LoggingHandler.sulog.warn("[PlanetGarrison] A neutral townsperson for planet {} failed to spawn; "
                        + "skipping it.", planetId);
            }
            return false;
        }
        return true;
    }

    // spawn one defender of the given family near the planet centre, stat-stamp it, mark it, and add it to the world.
    // Returns the entity's UUID on success, or null if the roster row was skipped (a missing DMZ id) or the add failed.
    private static UUID spawnOne(ServerLevel surface, PlanetGarrisonRoster.Family family, String planetId, Vec3 centre,
                                 int half, double battlePower, RandomSource random)
    {
        PlanetGarrisonRoster.Member member = family.pickMember(random);
        if (member == null)
        {
            return null;
        }
        LivingEntity defender = member.create(surface);
        if (defender == null)
        {
            return null; // a missing DMZ face (logged once in the roster); skip this row.
        }

        // scatter around the centre so defenders do not stack, then snap to a safe stand on the pinned-solid surface.
        double jitterX = (random.nextDouble() - 0.5) * 2.0 * half;
        double jitterZ = (random.nextDouble() - 0.5) * 2.0 * half;
        SafeSpotResolver.Result spot =
                SafeSpotResolver.resolve(surface, centre.x + jitterX, centre.y, centre.z + jitterZ);
        defender.moveTo(spot.x, spot.y, spot.z, random.nextFloat() * 360.0F, 0.0F);

        // a DMZ custom-character saiyan rolls its whole appearance here (server-authoritative, synced + persisted),
        // equips its saiyan armor, and (for one of the three named NPCs) multiplies its battle power to its tier BEFORE
        // the shared stat derivation, so the higher battle power flows into health/melee/defense through the same
        // divisors as every other defender.
        double effectiveBattlePower = battlePower;
        if (defender instanceof PlanetSaiyanGarrisonEntity saiyan)
        {
            saiyan.randomize(random);
            saiyan.setHomePlanet(planetId);
            SaiyanArmorSets.equip(saiyan, random);
            effectiveBattlePower = battlePower * saiyan.getStrengthMultiplier();
        }

        applyDefenderStats(defender, effectiveBattlePower);

        // the garrison marker: a boolean flag plus the planet id it defends. Read by the death hook, the cleanup sweep
        // and the claim scan. Set BEFORE addFreshEntity so it is present the instant the entity joins the world.
        defender.getPersistentData().putBoolean(DEFENDER_FLAG, true);
        defender.getPersistentData().putString(DEFENDER_PLANET, planetId);

        // tell an rgnpc-faced garrison fighter which planet it guards, so its own confinement goal keeps it on that
        // planet's surface disc (it now has real saga AI that can chase and teleport toward a target). The saiyan
        // custom-character already had its home set above; DMZ-native faces (saibaman, enemy namekian) have no such goal
        // and are held only by the surface geometry and the claim scan.
        if (defender instanceof PlanetGarrisonDefenderEntity garrison)
        {
            garrison.setHomePlanet(planetId);
        }

        // keep the defender from despawning while nobody is looking, so a claim can never be beaten by a wander-off. DMZ
        // saga mobs and rgnpc defenders are all Mobs; a (defensive) non-Mob living entity simply skips this.
        if (defender instanceof Mob mob)
        {
            mob.setPersistenceRequired();
        }

        if (!surface.addFreshEntity(defender) || defender.isRemoved() || !defender.isAlive())
        {
            LoggingHandler.sulog.warn("[PlanetGarrison] A defender for planet {} failed to spawn; skipping it.", planetId);
            return null;
        }
        return defender.getUUID();
    }

    // roll a battle power inside the configured band. A degenerate band (min >= max) returns min.
    private static double rollBattlePower(RandomSource random)
    {
        double min = PlanetSpawnModule.wildDefenderBattlePowerMin();
        double max = PlanetSpawnModule.wildDefenderBattlePowerMax();
        if (min >= max)
        {
            return min;
        }
        return min + random.nextDouble() * (max - min);
    }

    // push a defender's combat numbers onto it, following CloneStats' approach adapted to a plain LivingEntity: health,
    // melee and raw NPC defense derived from battle power, plus (for a DMZ saga mob) its battle power, AI tier and a
    // small ki-skill pool. The dmz_stats_configured opt-out flag is stamped last so DragonMineZ does not overwrite any
    // of this when the entity joins. Every DMZ-specific call is wrapped so an API drift degrades to a plain melee mob.
    private static void applyDefenderStats(LivingEntity defender, double battlePower)
    {
        double health = Math.max(MIN_HEALTH, battlePower / HEALTH_BP_DIVISOR);
        double melee = Math.max(MIN_MELEE, battlePower / MELEE_BP_DIVISOR);
        double defense = battlePower / DEFENSE_BP_DIVISOR;

        setAttribute(defender, Attributes.MAX_HEALTH, health);
        defender.setHealth((float) health);
        setAttribute(defender, Attributes.ATTACK_DAMAGE, melee);

        if (defense > 0.0)
        {
            defender.getPersistentData().putDouble(NPC_DEFENSE_KEY, defense);
        }

        // DMZ saga combat chassis: battle power (so a scouter reads the right band), the LOWEST AI tier, and a small
        // ranged pool so it is not a pure melee dummy. Wrapped: any drift degrades to "no DMZ combat extras", never a
        // crash. AiTier.SIMPLE is DragonMineZ's weakest tier (the one its own saibaman fights at), which is exactly the
        // "same AI as saga fighters at the lowest level" the user asked for; setAiTierById is 1-based (id = ordinal+1).
        if (defender instanceof DBSagasEntity saga)
        {
            try
            {
                saga.setBattlePower((int) Math.round(Math.min(battlePower, Integer.MAX_VALUE)));
                saga.setAiTierById(DBSagasEntity.AiTier.SIMPLE.ordinal() + 1);
                saga.getSkillPool().clear();
                saga.addKiSkill(DBSagasEntity.KiSkillType.GENERIC_KI_WAVE, 60, 1.0F);
                saga.addKiSkill(DBSagasEntity.KiSkillType.KI_LASER, 45, 1.0F);
            }
            catch (Throwable t)
            {
                if (DMZ_STAT_WARNED.compareAndSet(false, true))
                {
                    LoggingHandler.sulog.warn("[PlanetGarrison] DragonMineZ saga stat/skill API drift; garrison "
                            + "defenders fight with basic stats only.", t);
                }
            }
        }

        // stamp the opt-out flag LAST so DragonMineZ's join handler leaves every number above untouched.
        defender.getPersistentData().putBoolean(DMZ_STATS_CONFIGURED, true);
    }

    // Planet Vegeta's inhabitants are statted to a fixed DragonMineZ saga TIER, so the saiyan homeworld's residents read
    // at real saga strength while a generated planet's garrison keeps its rolled battle-power band (applyDefenderStats
    // above). These are the exact numbers DMZ ships in its frieza-saga KILL objectives (data/dragonminez/previousQuests/
    // quests/saga_frieza in the DMZ jar): an ORDINARY inhabitant is a Frieza soldier (01_secure_namek_landing: health
    // 1547, melee 100, ki 107, AITier 1); a NAMED donor NPC is a rank-and-file Ginyu Force member, the shared block of
    // Recoome, Burter and Jeice (08/09_defeat_*: health 4862, melee 265, ki 238, AITier 2). Guldo sits below that trio
    // (1989/122/119) and Captain Ginyu above it (7072/376/333), so the trio value is the honest "Ginyu Force" tier.
    private static final double VEGETA_SOLDIER_HEALTH = 1547.0;
    private static final double VEGETA_SOLDIER_MELEE = 100.0;
    private static final float VEGETA_SOLDIER_KI = 107.0F;
    private static final int VEGETA_SOLDIER_AI_TIER = 1;
    private static final double VEGETA_GINYU_HEALTH = 4862.0;
    private static final double VEGETA_GINYU_MELEE = 265.0;
    private static final float VEGETA_GINYU_KI = 238.0F;
    private static final int VEGETA_GINYU_AI_TIER = 2;

    // Stat a Planet Vegeta saiyan to its DMZ saga tier: the Ginyu Force block for a named donor NPC, the Frieza soldier
    // block for a generic inhabitant. This changes only COMBAT NUMBERS (max health, melee, ki damage and the battle power a
    // scouter reads), never AI GOALS, so a passive citizen stays passive: its own registerGoals installs no target goal and
    // its setTarget refuses one, and nothing here touches either. Every number is carried on the citizen now: its attribute
    // supplier includes ATTACK_DAMAGE and DMZ's KI_BLAST_DAMAGE, and it implements IBattlePower with a SYNCED battle power,
    // so the scouter reads the tier band off the client entity. Mitigation (dmz_npc_defense) and the shown battle power are
    // derived from the tier HEALTH through the SAME divisors the garrison uses, so tankiness, shown battle power and damage
    // reduction stay in step. The only saga-exclusive extras (a DMZ AI tier and a ranged ki-skill pool) belong to the
    // hostile combat brain the citizen was deliberately forked away from, so they apply ONLY to a DBSagasEntity and light up
    // automatically if a Vegeta saiyan is ever moved onto that chassis; a passive citizen has no such AI by construction.
    // The dmz_stats_configured opt-out is stamped LAST so DragonMineZ never overwrites any of it. Never throws (every DMZ
    // call is wrapped).
    private static void applyVegetaSaiyanStats(LivingEntity saiyan, SaiyanAppearance appearance)
    {
        boolean named = appearance != null && appearance.isNamed();
        double health = named ? VEGETA_GINYU_HEALTH : VEGETA_SOLDIER_HEALTH;
        double melee = named ? VEGETA_GINYU_MELEE : VEGETA_SOLDIER_MELEE;
        float ki = named ? VEGETA_GINYU_KI : VEGETA_SOLDIER_KI;
        int aiTierId = named ? VEGETA_GINYU_AI_TIER : VEGETA_SOLDIER_AI_TIER;
        double battlePower = health * HEALTH_BP_DIVISOR; // scouter reads the tier health through the shared divisor
        double defense = battlePower / DEFENSE_BP_DIVISOR;

        setAttribute(saiyan, Attributes.MAX_HEALTH, health);
        saiyan.setHealth((float) health);
        setAttribute(saiyan, Attributes.ATTACK_DAMAGE, melee);
        // ki damage as a carried attribute. Lands on the citizen (its supplier now includes KI_BLAST_DAMAGE) and on any
        // saga chassis that carries it; a no-op where the attribute is absent, so it never throws.
        setKiBlastDamageAttribute(saiyan, ki);

        if (defense > 0.0)
        {
            saiyan.getPersistentData().putDouble(NPC_DEFENSE_KEY, defense);
        }

        // Battle power so a scouter reads the right tier band. Set on ANY IBattlePower chassis: the citizen overrides it
        // with a SYNCED field (which is what reaches the client scouter), and a DBSagasEntity likewise syncs its own. This
        // is the ONE battle-power write for every chassis, so there is no half-applied path.
        if (saiyan instanceof IBattlePower ibp)
        {
            try
            {
                ibp.setBattlePower((int) Math.round(Math.min(battlePower, Integer.MAX_VALUE)));
            }
            catch (Throwable t)
            {
                warnVegetaDmzDrift(t);
            }
        }

        // Saga-only combat brain: a DMZ AI tier and the ranged ki-skill pool. Skipped for the passive PathfinderMob
        // citizen (it has no DMZ combat AI and must stay peaceful); lights up only if a Vegeta saiyan is ever moved onto a
        // saga chassis.
        if (saiyan instanceof DBSagasEntity saga)
        {
            try
            {
                saga.setAiTierById(aiTierId);
                saga.getSkillPool().clear();
                saga.addKiSkill(DBSagasEntity.KiSkillType.GENERIC_KI_WAVE, 60, 1.0F);
                saga.addKiSkill(DBSagasEntity.KiSkillType.KI_LASER, 45, 1.0F);
            }
            catch (Throwable t)
            {
                warnVegetaDmzDrift(t);
            }
        }

        // stamp the opt-out flag LAST so DragonMineZ's join handler leaves every number above untouched.
        saiyan.getPersistentData().putBoolean(DMZ_STATS_CONFIGURED, true);
    }

    // set the DragonMineZ KI_BLAST_DAMAGE attribute if the entity carries it (the Vegeta citizen now does; a saga chassis
    // does too). Wrapped and RegistryObject-resolved separately from setAttribute (which handles vanilla attributes only),
    // so a missing/renamed DMZ attribute degrades to no ki damage rather than a crash.
    private static void setKiBlastDamageAttribute(LivingEntity entity, float value)
    {
        try
        {
            net.minecraft.world.entity.ai.attributes.Attribute attribute = EntityAttributes.KI_BLAST_DAMAGE.get();
            AttributeInstance instance = entity.getAttribute(attribute);
            if (instance != null && value > 0.0F)
            {
                instance.setBaseValue(value);
            }
        }
        catch (Throwable t)
        {
            warnVegetaDmzDrift(t);
        }
    }

    // one latched warning for any DragonMineZ stat/skill API drift on the Vegeta path, shared with the generated-planet
    // path's latch so a drift logs exactly once per run rather than flooding.
    private static void warnVegetaDmzDrift(Throwable t)
    {
        if (DMZ_STAT_WARNED.compareAndSet(false, true))
        {
            LoggingHandler.sulog.warn("[PlanetGarrison] DragonMineZ saga stat/skill API drift; Vegeta saiyans keep basic "
                    + "stats only.", t);
        }
    }

    // set a living entity's attribute base value if the attribute exists on it. Every current defender chassis (the SU
    // saga fighter and the DMZ saibaman / enemy namekian) carries ATTACK_DAMAGE; the null guard is purely defensive.
    private static void setAttribute(LivingEntity entity, net.minecraft.world.entity.ai.attributes.Attribute attribute,
                                     double value)
    {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null && value > 0.0)
        {
            instance.setBaseValue(value);
        }
    }

    // the flat wild toughness fallback (the existing PlanetBuster config value), used when a planet has no defenders.
    private static double flatToughness()
    {
        return Math.max(1.0, PlanetBusterModule.clashWildToughness());
    }

    /**
     * Route a defender's death to its planet's record. Called from the LivingDeathEvent handler. Reads the garrison
     * marker off the dying entity, so it is unambiguous which planet the defender belonged to even after chunk reloads.
     * A non-defender death is a cheap no-op. Never throws.
     *
     * @return the planet id if THIS death cleared the last living garrison defender of that planet (so the caller can
     *         start the public conquest boss stage), or null otherwise. A death that leaves defenders standing, or a
     *         non-defender death, returns null.
     */
    public static String onDefenderDeath(MinecraftServer server, LivingEntity entity)
    {
        if (server == null || entity == null)
        {
            return null;
        }
        if (!entity.getPersistentData().getBoolean(DEFENDER_FLAG))
        {
            return null;
        }
        String planetId = entity.getPersistentData().getString(DEFENDER_PLANET);
        if (planetId.isEmpty())
        {
            return null;
        }
        PlanetGarrisonData data = PlanetGarrisonData.get(server);
        data.markDefeated(planetId, entity.getUUID());
        // the transition to zero is the "all hostiles cleared" signal the conquest boss stage waits for. remaining is
        // computed over the persisted roster minus the defeated set, so this is authoritative even across a chunk reload.
        return data.remaining(planetId) <= 0 ? planetId : null;
    }

    /**
     * Whether living defenders still block a claim on this planet. Returns true only if the record shows defenders
     * remaining AND a live scan of the loaded surface confirms at least one is actually alive. If the record shows
     * remaining but the scan finds none (a defender vanished without a death event), the phantom is reconciled away and
     * this returns false, so the planet can never be permanently unclaimable. Runs on the server thread; the caller is a
     * player standing on the planet, so its surface disc is loaded and the scan is authoritative.
     */
    public static boolean claimBlocked(MinecraftServer server, String planetId)
    {
        if (server == null || planetId == null || planetId.isEmpty())
        {
            return false;
        }
        PlanetGarrisonData data = PlanetGarrisonData.get(server);
        if (data.remaining(planetId) <= 0)
        {
            return false;
        }
        if (countLiveDefenders(server, planetId) > 0)
        {
            return true;
        }
        // record says defended, world says empty: the garrison is genuinely gone. Reconcile so the claim proceeds.
        data.markAllDefeated(planetId);
        return false;
    }

    // count the living garrison defenders for a planet in the surface dimension right now. Matches on the persisted
    // marker + planet id, so identity is exact and a defender for a different planet is never counted.
    private static int countLiveDefenders(MinecraftServer server, String planetId)
    {
        ServerLevel surface = SurfaceDimension.level(server);
        if (surface == null)
        {
            return 0;
        }
        int count = 0;
        for (Entity entity : surface.getAllEntities())
        {
            if (isDefenderFor(entity, planetId) && entity.isAlive() && !entity.isRemoved())
            {
                count++;
            }
        }
        return count;
    }

    /**
     * Remove a planet's garrison on DESTRUCTION: discard every live defender entity for it in the surface dimension and
     * drop its record, so no orphaned defender is left standing in the void of a dead world and no phantom state lingers.
     * Called from the single {@link PlanetDestruction} entry point. Never throws.
     */
    public static void onPlanetDestroyed(MinecraftServer server, String planetId)
    {
        if (server == null || planetId == null || planetId.isEmpty())
        {
            return;
        }
        try
        {
            discardDefenders(server, planetId);
            PlanetGarrisonData.get(server).clear(planetId);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[PlanetGarrison] Failed to clear defenders for destroyed planet {}; continuing.",
                    planetId, t);
        }
    }

    /**
     * Drop a planet's garrison RECORD on CLAIM. The defenders are already dead by the time a claim succeeds (the gate
     * enforces it), so this only clears the bookkeeping. Discards any straggler entity too, defensively. Never throws.
     */
    public static void onPlanetClaimed(MinecraftServer server, String planetId)
    {
        if (server == null || planetId == null || planetId.isEmpty())
        {
            return;
        }
        try
        {
            discardDefenders(server, planetId);
            PlanetGarrisonData.get(server).clear(planetId);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[PlanetGarrison] Failed to clear defender state for claimed planet {}; "
                    + "continuing.", planetId, t);
        }
    }

    // discard every live garrison defender for a planet in the surface dimension.
    private static void discardDefenders(MinecraftServer server, String planetId)
    {
        ServerLevel surface = SurfaceDimension.level(server);
        if (surface == null)
        {
            return;
        }
        // snapshot first: getAllEntities is a live view, and discarding while iterating it would throw.
        List<Entity> snapshot = new ArrayList<>();
        for (Entity entity : surface.getAllEntities())
        {
            snapshot.add(entity);
        }
        for (Entity entity : snapshot)
        {
            if (isDefenderFor(entity, planetId))
            {
                entity.discard();
            }
        }
    }

    /**
     * Sweep orphaned garrison defenders on server start: any defender entity whose planet has no garrison record, or
     * whose UUID is not part of that record's roster, was left by a crash mid-population and is discarded. Defenders that
     * are legitimately part of a live record are left alone. Mirrors {@code GuildRaidClones.sweepOrphans} in spirit.
     * Never throws.
     */
    public static void sweepOrphans(MinecraftServer server)
    {
        if (server == null)
        {
            return;
        }
        try
        {
            PlanetGarrisonData data = PlanetGarrisonData.get(server);
            int removed = 0;
            for (ServerLevel level : server.getAllLevels())
            {
                // snapshot first: getAllEntities is a live view, and discarding while iterating it would throw.
                List<Entity> snapshot = new ArrayList<>();
                for (Entity entity : level.getAllEntities())
                {
                    snapshot.add(entity);
                }
                for (Entity entity : snapshot)
                {
                    if (!entity.getPersistentData().getBoolean(DEFENDER_FLAG))
                    {
                        continue;
                    }
                    // Legacy safety net: an RgNpcEntity carrying the defender marker is a garrison defender from the
                    // earlier rejected display-mode build. Now that RgNpcEntity is a pure display piece again (immune to
                    // gameplay damage), it can no longer be beaten and would leave its planet unclaimable. Discard it
                    // unconditionally; the claim gate's live scan then reconciles the record to claimable (see
                    // claimBlocked), and a later visit repopulates the planet with the new fighting chassis. Roster
                    // membership is irrelevant here because rgnpc is no longer a valid defender chassis at all.
                    if (entity instanceof net.shurui.shuruisutilities.ragnarok.RgNpcEntity)
                    {
                        entity.discard();
                        removed++;
                        continue;
                    }
                    String planetId = entity.getPersistentData().getString(DEFENDER_PLANET);
                    Set<UUID> roster = planetId.isEmpty() ? Set.of() : data.roster(planetId);
                    if (!roster.contains(entity.getUUID()))
                    {
                        entity.discard();
                        removed++;
                    }
                }
            }
            if (removed > 0)
            {
                LoggingHandler.sulog.info(
                        "[PlanetGarrison] Swept {} orphaned planet-defender(s) left by a previous session.", removed);
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[PlanetGarrison] Orphan defender sweep failed; continuing.", t);
        }
    }

    // true if the entity is a garrison defender marked for the given planet id.
    private static boolean isDefenderFor(Entity entity, String planetId)
    {
        return entity.getPersistentData().getBoolean(DEFENDER_FLAG)
                && planetId.equals(entity.getPersistentData().getString(DEFENDER_PLANET));
    }
}
