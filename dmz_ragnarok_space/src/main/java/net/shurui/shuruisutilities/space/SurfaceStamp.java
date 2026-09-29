package net.shurui.shuruisutilities.space;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.misc.TaskRegistry;
import net.shurui.shuruisutilities.saibaman.SaibamanCropBlock;
import net.shurui.shuruisutilities.saibaman.SaibamanCropRegistry;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.shurui.shuruisutilities.world.space.SurfaceSnap;

/**
 * On-demand terrain stamp for a generated planet's surface, in the ONE shared surface dimension. NOT a biome/worldgen
 * system: a single deterministic block stamp placed at the planet's cell centre, stamped once (the generated flag in
 * {@link GeneratedPlanetClaims} makes a later arrival skip it).
 *
 * <p>Pure function of the planet id: cell centre from {@link SurfaceDimension#cellCentre}, size from
 * {@link GeneratedPlanets#surfaceSizeForId}, and {@link Theme} and every per-column height/edge value from hashes of
 * the id and the column offset. Coordinates are double/long throughout because a far cell centre sits near +/-29
 * million blocks, where a float loses metres.
 *
 * <p>Three deterministic effects, not a flat slab:
 * <ul>
 *   <li><b>Relief.</b> Cap height from a smoothed value-noise so neighbouring columns share a slope. The centre column
 *       is pinned near the top of the relief band so the player always lands on solid high ground, never in a pit.</li>
 *   <li><b>A natural edge.</b> The disc radius is wobbled per direction, and columns near the wobbled rim are shaved
 *       down so the terrain slopes off instead of ending in a cliff.</li>
 *   <li><b>Layered materials.</b> A surface block over a subsurface band over a deep band, chosen by {@link Theme}.</li>
 * </ul>
 * The disc is inscribed in the planet's square (radius = half the side), fitting the 100x100..500x500 size range. All
 * Y stays in a band around {@link SurfaceDimension#SURFACE_Y}: the cap just above it, the deep column well below
 * (configurable, default 128 blocks) yet clear of the dimension floor.
 */
public final class SurfaceStamp
{
    private SurfaceStamp()
    {
    }

    // Block-write flag for the bulk TERRAIN stamp: skip the neighbour-shape updates (bit 16, UPDATE_KNOWN_SHAPE), the
    // neighbour block updates (bit 1 left off) AND the per-block client notify (bit 2 left off). A full stamp is ~26M
    // writes; the earlier value of 0 left bit 16 CLEAR, so Level.markAndNotifyBlock ran updateNeighbourShapes /
    // updateIndirectNeighbourShapes on every single write (verified against 1.20.1 Level.markAndNotifyBlock: the
    // `(flag & 16) == 0` guard). Each shape update calls getBlockState on all six neighbours, and a column-edge write
    // reaches into the adjacent chunk, so on a not-yet-loaded neighbour that getBlockState BLOCKING-LOADS the chunk on
    // the server thread (getChunkBlocking parkNanos). That neighbour-shape recompute plus the blocking loads were the
    // TickSampler hot stack in the 2026-09-15 smp stall. onPlace/onRemove, heightmaps and lighting all run inside
    // LevelChunk.setBlockState regardless of the flag, so the terrain is byte-identical server-side; none of the blocks
    // placed here are shape-dependent (stone/dirt/grass/deepslate/... families), so skipping the shape pass changes no
    // block. The client is brought up to date by resending each finished chunk WHOLE (StampTask.resendChunk). Water,
    // vegetation and structures keep flag 2 (a small fraction of the writes, over already-loaded chunks, streamed live).
    static final int TERRAIN_FLAG = Block.UPDATE_KNOWN_SHAPE;

    // log an unexpected stamp failure once, not on every tick of a wedged stamp.
    private static final AtomicBoolean STAMP_WARNED = new AtomicBoolean(false);

    // Subsurface (dirt-equivalent) band depth between the surface block and the deep body, like vanilla dirt under grass.
    private static final int SUBSURFACE_DEPTH = 3;

    // Depth, in blocks, of the deep body (stone-equivalent) below the subsurface band. CONFIG-DRIVEN (see
    // PlanetSpawnModule.surfaceColumnDepth) so a planet is a genuine solid column, not the old ~11-block shell. The
    // default (128) puts the deepest column's floor from SURFACE_Y (96) down to about Y -35, above the dimension floor
    // at -64. Each StampTask snapshots the value at construction so a config reload mid-stamp cannot make a resumed
    // stamp's later columns a different depth from its earlier ones; lowestTerrainY (the fall-catch floor) reads live.

    // Max relief amplitude, in blocks, above the lowest cap: cap height varies across the disc in [SURFACE_Y,
    // SURFACE_Y + MAX_RELIEF]. At 8 the hills read as believable without approaching the dimension ceiling (320).
    private static final int MAX_RELIEF = 8;

    // Grid spacing, in blocks, of the value-noise lattice the relief is sampled from: corner heights are hashed on this
    // coarse grid and bilinearly interpolated, so the surface changes over ~6 blocks (a slope) rather than per block.
    private static final int RELIEF_GRID = 6;

    // Fraction of the radius over which the rim feathers down to the base, so the disc slopes off instead of ending in a
    // vertical wall. 0.22 = the outer ~fifth of the disc ramps the cap down.
    private static final double EDGE_FEATHER = 0.22;

    // Amplitude, 0..1, of the per-direction radius wobble that breaks the perfect circle into a lumpy chunk edge. 0.10
    // keeps the rim inside the planet square (the disc radius is already half the side).
    private static final double EDGE_WOBBLE = 0.10;

    /**
     * A generated planet's surface theme: the material family the whole surface is built from. Chosen per planet from
     * its id hash ({@link #themeFor}), weighted so plain themes are common and exotic ones a find. Each theme names
     * three layers, resolved in {@link #paletteFor} (block ids on the palette constants below). NAMEK, KAIO and
     * OTHERWORLD mirror DMZ's own noise settings for those grounds (ids through {@link #dmz} with a vanilla fallback).
     * OTHERWORLD is weighted 0 so it is NEVER rolled onto a random planet: a hand-selectable theme for the public stamp
     * API (dungeons) only.
     */
    public enum Theme
    {
        // bare rock, deliberately uncommon: most planets should read green. A stony one is enriched with rock features
        // (spires, karst pillars, boulders, crystals) so it reads as an alien rockscape, not an unfinished flat.
        STONY(12),
        // grass/dirt/stone, the most common theme: a random planet is usually a plain green world.
        OVERWORLD(34),
        // DMZ Namek ground, a common green alternative to overworld.
        NAMEK(21),
        // nether rock, uncommon.
        NETHER(12),
        // end stone and purpur, rare.
        END(8),
        // King Kai's planet ground, a green oddity.
        KAIO(13),
        // DMZ's otherworld cloud plain. Weight 0: never rolled onto a planet (see themeFor / setThemeWeights, which keep
        // this index at 0), it exists only as a hand-selectable theme for the public stamp API.
        OTHERWORLD(0);

        // default relative weight, used only to seed the live config-backed weights below (themeFor rolls the live sum).
        final int weight;

        Theme(int weight)
        {
            this.weight = weight;
        }
    }

    // Live per-theme selection weights, indexed by Theme.ordinal(), seeded from the enum defaults and config-overridable
    // (PlanetSpawnModule bakes them through setThemeWeights). volatile because themeFor runs both on the server thread
    // (the stamp) and off it (the wreck's surfaceThemeFor). Nothing depends on the sum being 100: themeFor rolls over the
    // live sum, so any weights an operator sets are honoured proportionally.
    private static volatile int[] themeWeights = defaultThemeWeights();

    private static int[] defaultThemeWeights()
    {
        Theme[] values = Theme.values();
        int[] weights = new int[values.length];
        for (Theme t : values)
        {
            weights[t.ordinal()] = t.weight;
        }
        return weights;
    }

    // Push operator-configured theme weights in, in Theme.ordinal() order (STONY, OVERWORLD, NAMEK, NETHER, END, KAIO).
    // A negative value is clamped to 0. An all-zero set makes themeFor fall back to OVERWORLD, a sane green default.
    static void setThemeWeights(int stony, int overworld, int namek, int nether, int end, int kaio)
    {
        // full-length array (one slot per Theme, so weights[t.ordinal()] is always in bounds); set only the six
        // configurable planet themes, OTHERWORLD stays 0 so it is never rolled onto a random planet.
        int[] w = defaultThemeWeights();
        w[Theme.STONY.ordinal()] = Math.max(0, stony);
        w[Theme.OVERWORLD.ordinal()] = Math.max(0, overworld);
        w[Theme.NAMEK.ordinal()] = Math.max(0, namek);
        w[Theme.NETHER.ordinal()] = Math.max(0, nether);
        w[Theme.END.ordinal()] = Math.max(0, end);
        w[Theme.KAIO.ordinal()] = Math.max(0, kaio);
        w[Theme.OTHERWORLD.ordinal()] = 0;
        themeWeights = w;
    }

    // A resolved three-layer palette for a theme: surface block, subsurface (soil) band, deep body. Package-visible so
    // the NaturalSurfaceOracle can read a stamped planet's layers back through the shared expectedTerrain helper.
    record Palette(BlockState surface, BlockState subsurface, BlockState deep)
    {
    }

    // Palettes resolved once at class-load. DMZ blocks go through dmz() with a vanilla fallback so a missing id degrades
    // to ordinary rock/soil rather than a hole or a crash.
    //
    // STONY: stone surface, andesite subsurface, deepslate body. All bare rock, no soil.
    private static final Palette STONY_PALETTE = new Palette(
            Blocks.STONE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(),
            Blocks.DEEPSLATE.defaultBlockState());

    // OVERWORLD: the familiar grass / dirt / stone stack.
    private static final Palette OVERWORLD_PALETTE = new Palette(
            Blocks.GRASS_BLOCK.defaultBlockState(),
            Blocks.DIRT.defaultBlockState(),
            Blocks.STONE.defaultBlockState());

    // NAMEK: DMZ's own Namek ground, mirroring dragonminez:worldgen/noise_settings/namek.json (surface
    // namek_grass_block, under it namek_dirt, deep namek_stone). Every id resolved with a vanilla fallback.
    private static final Palette NAMEK_PALETTE = new Palette(
            dmz("namek_grass_block", Blocks.GRASS_BLOCK),
            dmz("namek_dirt", Blocks.DIRT),
            dmz("namek_stone", Blocks.STONE));

    // NETHER: netherrack over a soul-soil band over a basalt body, the hot dark stack of the nether.
    private static final Palette NETHER_PALETTE = new Palette(
            Blocks.NETHERRACK.defaultBlockState(),
            Blocks.SOUL_SOIL.defaultBlockState(),
            Blocks.BASALT.defaultBlockState());

    // END: end stone surface over a purpur body. The End has no soil, so the subsurface is end stone too.
    private static final Palette END_PALETTE = new Palette(
            Blocks.END_STONE.defaultBlockState(),
            Blocks.END_STONE.defaultBlockState(),
            Blocks.PURPUR_BLOCK.defaultBlockState());

    // KAIO: King Kai's planet, mirroring dragonminez:worldgen/noise_settings/sacredkaiplanet.json (surface
    // sacred_planet_grass_block, under it rocky_dirt, deep rocky_stone). These are the real sacred-kai-planet ground
    // blocks; every id resolved with a vanilla fallback so a missing one degrades to grass/dirt/stone.
    private static final Palette KAIO_PALETTE = new Palette(
            dmz("sacred_planet_grass_block", Blocks.GRASS_BLOCK),
            dmz("rocky_dirt", Blocks.DIRT),
            dmz("rocky_stone", Blocks.STONE));

    // OTHERWORLD: a pale cloud plain. dragonminez:worldgen/noise_settings/otherworld.json places exactly ONE ground
    // material, dragonminez:otherworld_cloud, with no soil or stone layer, so all three layers are the cloud block. A
    // missing DMZ id degrades to snow block (walkable layers) and calcite (deep body). The pastel foliage tint comes
    // from DMZ's other_world biome effects, reflected in the vegetation below.
    private static final Palette OTHERWORLD_PALETTE = new Palette(
            dmz("otherworld_cloud", Blocks.SNOW_BLOCK),
            dmz("otherworld_cloud", Blocks.SNOW_BLOCK),
            dmz("otherworld_cloud", Blocks.CALCITE));

    // Live, config-baked switches and tunables for the stage-2b surface dressing (basins/water and vegetation). volatile
    // (written on the config thread, read on the server thread by an in-flight stamp). Each StampTask SNAPSHOTS these at
    // construction (like deepDepth) so a mid-stamp config reload can never split one planet across two rule sets.
    private static volatile boolean waterEnabled = true;
    private static volatile boolean vegetationEnabled = true;
    // blocks BELOW the base cap (SURFACE_Y) that sea level sits. Every ordinary column caps at SURFACE_Y or above, so
    // water is always strictly below the surrounding land.
    private static volatile int seaLevelOffset = 3;
    // fraction 0..1 of basin SITES (one candidate per BASIN_SITE_GRID cell) that become a pool. Pools are discrete,
    // bounded and provably non-overlapping (see the site grid/jitter maths), so this never risks a runaway basin.
    private static volatile double basinFrequency = 0.30;
    // fraction 0..1 of vegetation SITES (one candidate per VEG_GRID cell) that grow a feature.
    private static volatile double vegetationDensity = 0.45;

    static void setWaterEnabled(boolean value)
    {
        waterEnabled = value;
    }

    static void setVegetationEnabled(boolean value)
    {
        vegetationEnabled = value;
    }

    static void setSeaLevelOffset(int value)
    {
        seaLevelOffset = value;
    }

    static void setBasinFrequency(double value)
    {
        basinFrequency = value;
    }

    static void setVegetationDensity(double value)
    {
        vegetationDensity = value;
    }

    // Live, config-baked switches for the stage-2c surface structures (procedural Saibamen huts and authored Namek
    // villages). volatile and snapshotted per StampTask like the stage-2b tunables, so a mid-stamp reload never splits
    // one planet across two rule sets.
    private static volatile boolean structuresEnabled = true;
    // fraction 0..1 of village SITES (one candidate per STRUCT_GRID cell on a NAMEK planet) that grow a village.
    private static volatile double villageFrequency = 0.35;
    // fraction 0..1 of hut SITES (one candidate per STRUCT_GRID cell on a STONY or OVERWORLD planet) that grow a hut.
    private static volatile double hutFrequency = 0.5;
    // independent 0..1 chances that a placed hut contains ONE growing (plantable) saibaman seed at its centre, and 1..3
    // harvestable seeds you break for the seed item. Both default 0.33. Read live in buildHut against the hut's own
    // deterministic RandomSource, so a given planet always grows identical hut contents.
    private static volatile double hutSeedPlantedChance = 0.33;
    private static volatile double hutSeedHarvestChance = 0.33;

    static void setStructuresEnabled(boolean value)
    {
        structuresEnabled = value;
    }

    static void setVillageFrequency(double value)
    {
        villageFrequency = value;
    }

    static void setHutFrequency(double value)
    {
        hutFrequency = value;
    }

    static void setHutSeedPlantedChance(double value)
    {
        hutSeedPlantedChance = value;
    }

    static void setHutSeedHarvestChance(double value)
    {
        hutSeedHarvestChance = value;
    }

    // package-visible reads of the LIVE stage-2b/2c tunables, for the fallback when a planet has no persisted stamp
    // snapshot (stamped by a build predating the snapshot store). A recompute from these can diverge from the ground if
    // config changed since; best-effort. A planet stamped by this or a later build carries its own snapshot and never
    // touches these.
    static boolean waterEnabled()
    {
        return waterEnabled;
    }

    static boolean vegetationEnabled()
    {
        return vegetationEnabled;
    }

    static int seaLevelOffset()
    {
        return seaLevelOffset;
    }

    static double basinFrequency()
    {
        return basinFrequency;
    }

    static double vegetationDensity()
    {
        return vegetationDensity;
    }

    static boolean structuresEnabled()
    {
        return structuresEnabled;
    }

    static double villageFrequency()
    {
        return villageFrequency;
    }

    static double hutFrequency()
    {
        return hutFrequency;
    }

    // Edge length, in columns, of the cell a single basin candidate is rolled in. One pool per cell at most; the
    // spacing/jitter/radius numbers below guarantee two pools in adjacent cells are always separated by a solid wall, so
    // no two pools can merge into one un-sealed basin.
    private static final int BASIN_SITE_GRID = 24;
    // half the jitter, in columns, a pool centre is nudged from its cell centre. Bounded to BASIN_SITE_GRID/2 - max
    // radius - 2 (24/2 - 6 - 2 = 4) so two adjacent pools jittered toward each other keep solid columns between their
    // rims, which is what seals each pool independently.
    private static final int BASIN_JITTER = 4;
    private static final int BASIN_MIN_RADIUS = 3;
    private static final int BASIN_MAX_RADIUS = 6;
    // how far, in blocks, a basin floor sits below sea level (the pool's water depth).
    private static final int BASIN_MIN_DEPTH = 3;
    private static final int BASIN_MAX_DEPTH = 7;
    // the inward-margin rule's extra ring, in columns, beyond the feathered edge a pool must stay inside of. The single
    // most important geometric guard here: a pool is only allowed where the WHOLE disc fits inside
    // half * (1 - EDGE_WOBBLE) * (1 - EDGE_FEATHER) - BASIN_MARGIN, strictly inside the most pinched wobbled+feathered
    // rim, so an unbroken ring of full-height (>= SURFACE_Y) land always surrounds every pool and water can NEVER reach
    // the rim and drain off the disc.
    private static final int BASIN_MARGIN = 4;
    // no pool may touch the synchronous centre landing patch, so a player touches down on dry ground and the centre-
    // clustered raid/garrison spawns never stand in water. Sized to clear the centre patch plus a max pool.
    private static final int BASIN_CENTRE_CLEAR = StampTask.SYNC_CENTRE_HALF + BASIN_MAX_RADIUS + 2;
    // salt for the basin pool roll, distinct from the relief and rim salts so pool placement does not correlate with the
    // hills or the rim wobble.
    private static final int BASIN_SALT = 0xBA5;

    // Edge length, in columns, of the cell a single vegetation candidate is rolled in. Denser than the basin grid so
    // ground cover reads as continuous, not scattered dots.
    private static final int VEG_GRID = 5;
    private static final int VEG_JITTER = 2;
    private static final int VEG_SALT = 0x7E9;
    // salt for the per-site feature-choice and placement RandomSource, distinct from the occupancy salt so which feature
    // grows does not correlate with whether one grows.
    private static final int VEG_PICK_SALT = 0x13D;
    // max horizontal reach, in columns, of a placed feature from its site column (a big tree's canopy). The whole
    // footprint must sit on terrain the sweep has finished, so the interleaved veg wave is held this far inside the
    // terrain edge before a feature at a given ring may be planted.
    private static final int VEG_FEATURE_REACH = 8;
    // extra columns kept between the trailing vegetation wave and the advancing terrain edge, on top of the feature
    // reach, so trees appear a few rings BEHIND the growing terrain rather than riding its lip. A pacing number only.
    private static final int VEG_TRAIL_LAG = 10;

    // Edge length, in columns, of the cell a single structure candidate is rolled in. Far coarser than the veg grid: one
    // structure per 128-column cell keeps huts scattered and villages well apart. The pitch/jitter and footprint numbers
    // below guarantee two structures in adjacent cells never overlap.
    private static final int STRUCT_GRID = 128;
    private static final int STRUCT_JITTER = 6;
    private static final int STRUCT_SALT = 0x57C;
    // salt for the per-site kind/rotation/placement RandomSource, distinct from the occupancy salt.
    private static final int STRUCT_PICK_SALT = 0x2A9;
    // extra ring, in columns, beyond the feathered edge a structure's whole footprint must stay inside of, the same
    // inward-margin discipline the basins use so nothing overhangs the void rim.
    private static final int STRUCT_MARGIN = 6;
    // no structure may touch the synchronous centre landing patch, so a player never spawns inside a building. Sized to
    // clear the centre patch plus a small gap; the footprint is subtracted from the site distance at the gate.
    private static final int STRUCT_CENTRE_CLEAR = StampTask.SYNC_CENTRE_HALF + 8;
    // house-slot pitch, in blocks, on the fixed cluster lattice. Wide enough that the widest Namek house (~30 blocks)
    // never reaches its neighbour slot's house.
    private static final int VILLAGE_SLOT_PITCH = 36;
    // conservative half-extent, in columns, of the whole village cluster, used only by the placement gates. 18 (max slot
    // offset) + 16 (half the widest house, with slack) = 34.
    private static final int VILLAGE_FOOTPRINT = 34;
    // half-extent, in columns, of a hut's flattened pad, used by the gates. The hut itself is a 3-radius ring.
    private static final int HUT_FOOTPRINT = 5;
    // how far, in blocks, a structure pad is cleared above the floor line (so no hill/canopy overhangs the building) and
    // a dip below the floor is bridged (so it never floats). Clear height covers max relief (8) plus the tallest tree;
    // fill depth covers max relief plus slack.
    private static final int PAD_CLEAR_HEIGHT = 24;
    private static final int PAD_FILL_DEPTH = 12;
    // flat block cost charged for one placed house TEMPLATE body, on top of the house's real pad cost (flattenPad returns
    // its exact block count). Stands in for the template's placeInWorld blocks, which are not counted individually. 6000
    // covers the bigger Namek residences, so the per-tick budget re-check after EACH indivisible house (a village yields
    // between houses, never placing a whole cluster in one iteration) accounts for the template honestly.
    private static final int HOUSE_COST_ESTIMATE = 6000;
    // house-slot offsets from the village centre, in blocks: a compact three-house cluster, each pair at least
    // VILLAGE_SLOT_PITCH apart so the widest houses leave a solid gap.
    private static final int[][] VILLAGE_SLOTS = {
            { -VILLAGE_SLOT_PITCH / 2, -VILLAGE_SLOT_PITCH / 2 },
            {  VILLAGE_SLOT_PITCH / 2, -VILLAGE_SLOT_PITCH / 2 },
            {  0,                       VILLAGE_SLOT_PITCH / 2 }
    };
    // DragonMineZ's own Namek houses, reused for the village. Plain StructureTemplate .nbt files
    // (data/dragonminez/structures/village_ajissa/houses/...) whose only jigsaw content is interior connectors with
    // final_state=minecraft:air, so JigsawReplacementProcessor strips the connectors and leaves just the building.
    // cc_namekian_house (the small 11x11 one) is listed twice so the compact house is the common pick. dragonminez is a
    // mandatory dependency; a renamed/removed asset is handled gracefully (logged once, that slot skipped).
    private static final ResourceLocation[] NAMEK_HOUSES = {
            new ResourceLocation("dragonminez", "village_ajissa/houses/cc_namekian_house"),
            new ResourceLocation("dragonminez", "village_ajissa/houses/cc_namekian_house"),
            new ResourceLocation("dragonminez", "village_ajissa/houses/residence/ajissa_house_small"),
            new ResourceLocation("dragonminez", "village_ajissa/houses/residence/ajissa_house_big")
    };
    // ids we have already warned about, so a missing/renamed template logs once, not once per site.
    private static final java.util.Set<String> LOGGED_MISSING_TEMPLATES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // the theme's basin fill block, or null for a theme that gets no water (STONY). The green themes get real water
    // (Namek its own GREEN water), nether gets sealed LAVA, and END gets PACKED ICE, a SOLID block, so END "pools"
    // schedule zero fluid ticks at all.
    static BlockState liquidFor(Theme theme)
    {
        switch (theme)
        {
            case OVERWORLD:
            case KAIO:
                return Blocks.WATER.defaultBlockState();
            case OTHERWORLD:
                // DMZ's otherworld has no natural fluid, but its other_world biome tints water pale blue, so basins read
                // as still pale pools. Plain vanilla water, which that biome tints.
                return Blocks.WATER.defaultBlockState();
            case NAMEK:
                return dmz("namek_water_liquid_block", Blocks.WATER);
            case NETHER:
                return Blocks.LAVA.defaultBlockState();
            case END:
                return Blocks.PACKED_ICE.defaultBlockState();
            case STONY:
            default:
                return null;
        }
    }

    // sentinel returned by basinFloorAt for a column in no pool. Integer.MIN_VALUE can never be a real basin floor Y
    // (basins sit a few blocks below sea level), so it is an unambiguous "no basin" marker.
    static final int NO_BASIN = Integer.MIN_VALUE;

    // approximate block budget charged for one placed configured feature. The real count varies; a feature is placed as
    // one indivisible unit regardless (never split across ticks).
    private static final int VEG_FEATURE_COST = 200;

    // one vegetation option: EITHER a configured-feature id placed via holder.place, OR a single ground-cover block, with
    // a relative weight. Exactly one of featureId/cover is non-null.
    private record VegEntry(String featureId, BlockState cover, int weight)
    {
        static VegEntry feature(String id, int weight)
        {
            return new VegEntry(id, null, weight);
        }

        static VegEntry cover(Block block, int weight)
        {
            return new VegEntry(null, block.defaultBlockState(), weight);
        }
    }

    // OVERWORLD: vanilla trees over a lush floor of grass, fern and scattered flowers. Trees are rare relative to the
    // ground cover so a planet reads as meadow with stands of wood rather than solid forest.
    private static final List<VegEntry> OVERWORLD_VEG = List.of(
            VegEntry.feature("minecraft:oak", 4),
            VegEntry.feature("minecraft:birch", 2),
            VegEntry.feature("minecraft:spruce", 2),
            VegEntry.cover(Blocks.GRASS, 30),
            VegEntry.cover(Blocks.FERN, 10),
            VegEntry.cover(Blocks.POPPY, 4),
            VegEntry.cover(Blocks.DANDELION, 4));

    // NAMEK: DMZ's own ajissa and sacred trees over its configured grass and flower patches, all of which place onto the
    // namek grounds (namek_grass_block is in the minecraft:dirt tag, so the trees accept it).
    private static final List<VegEntry> NAMEK_VEG = List.of(
            VegEntry.feature("dragonminez:namek_ajissa_tree", 4),
            VegEntry.feature("dragonminez:namek_sacred_tree", 1),
            VegEntry.feature("dragonminez:namek_patch_grass_configured", 22),
            VegEntry.feature("dragonminez:namek_flowers_configured", 8));

    // KAIO: King Kai's ground has no tree feature of its own, so it borrows the namek sacred tree (very rare) over DMZ's
    // sacred-kai grass patch, flowers and rock clusters.
    private static final List<VegEntry> KAIO_VEG = List.of(
            VegEntry.feature("dragonminez:namek_sacred_tree", 1),
            VegEntry.feature("dragonminez:sacredkai_grass_patch", 22),
            VegEntry.feature("dragonminez:sacredkai_flowers", 8),
            VegEntry.feature("dragonminez:sacredkai_rock_cluster", 4));

    // END: only the chorus plant, sparse and pale, over the end-stone.
    private static final List<VegEntry> END_VEG = List.of(
            VegEntry.feature("minecraft:chorus_plant", 1));

    // OTHERWORLD: DMZ grows nothing on the cloud plain, so there is no feature to borrow. All direct ground-cover blocks
    // (no tree/feature that would demand soil the cloud is not), matching the biome's pastel palette.
    private static final List<VegEntry> OTHERWORLD_VEG = List.of(
            VegEntry.cover(Blocks.WHITE_TULIP, 6),
            VegEntry.cover(Blocks.PINK_TULIP, 6),
            VegEntry.cover(Blocks.CORNFLOWER, 4),
            VegEntry.cover(Blocks.LILY_OF_THE_VALLEY, 3));

    // the veg option list for a theme. STONY and NETHER are handled by their own hand-built routines and never reach here.
    private static List<VegEntry> vegEntriesFor(Theme theme)
    {
        switch (theme)
        {
            case NAMEK:
                return NAMEK_VEG;
            case KAIO:
                return KAIO_VEG;
            case END:
                return END_VEG;
            case OTHERWORLD:
                return OTHERWORLD_VEG;
            case OVERWORLD:
            default:
                return OVERWORLD_VEG;
        }
    }

    // total blocks a single stamped column places: surface block + (SUBSURFACE_DEPTH - 1) subsurface + deepDepth deep.
    // Derived from the same constants placeColumn uses so it can never drift; used to charge each column against the
    // per-tick budget. Takes the deep depth as a parameter now that it is config-driven and snapshotted per StampTask.
    private static int columnBlocks(int deepDepth)
    {
        return 1 + (SUBSURFACE_DEPTH - 1) + deepDepth;
    }

    // default per-tick block budget for a caller-driven stamp that does not set its own, matching the planet default
    // (PlanetSpawnModule surfaceStampBlocksPerTick). A plain constant so the general path never depends on planet config:
    // a 500-wide max planet finishes in ~26s at this budget.
    public static final int DEFAULT_BLOCKS_PER_TICK = 49152;

    /**
     * A caller-owned record of whether one stamped area already exists, so the caller (not SU's planet SavedData) owns
     * persistence. Queried before {@link #beginStamp} and marked when the stamp COMPLETES, the same generated-flag
     * contract the planet path uses through {@link GeneratedPlanetClaims}: a stamp that never completes (server stop,
     * crash) never marks generated, so a half-built area re-stamps cleanly rather than being skipped as done. Once
     * generated is recorded the area is NEVER re-stamped over, which is what makes a hand-added build inside it persist.
     */
    public interface GenerationStore
    {
        boolean isGenerated();

        void markGenerated();
    }

    // whether a player should be held frozen by the shared FreezeTask while an area stamps. One per stamp, so the planet
    // path can hold on cell geometry and the general path on a radius around the centre, off the SAME shared worker.
    @FunctionalInterface
    interface FreezeArea
    {
        boolean holds(ServerPlayer player);
    }

    // optional hook fired once when a stamp begins, carrying the geometry snapshot it is built at. The planet path
    // persists it into GeneratedPlanetClaims for the salvage oracle; the general path leaves it null (a caller owns its
    // own persistence through the store).
    @FunctionalInterface
    interface GeometryRecorder
    {
        void record(int size, String themeName, int deepDepth, int seaLevelOffset, double basinFrequency,
                    double vegetationDensity, double villageFrequency, double hutFrequency, boolean waterEnabled,
                    boolean vegetationEnabled, boolean structuresEnabled);
    }

    /**
     * A request to stamp themed terrain into ANY level at ANY centre, reusing the batched, budgeted, player-freezing and
     * whole-chunk-resend machinery the generated planets use. Build one through {@link #builder} and pass it to
     * {@link #beginStamp}. Required args (level, centre, size, depth, theme, seed, key, store) are on the builder factory;
     * everything else defaults to the planet tuning.
     */
    public static final class StampRequest
    {
        final ServerLevel level;
        final int cx;
        final int cy;
        final int cz;
        final int size;
        final int depth;
        final Theme theme;
        final long seed;
        final String key;
        final GenerationStore store;

        final int blocksPerTick;
        final boolean freezePlayers;
        final double freezeRadius;
        final boolean waterEnabled;
        final boolean vegetationEnabled;
        final boolean structuresEnabled;
        final int seaLevelOffset;
        final double basinFrequency;
        final double vegetationDensity;
        final double villageFrequency;
        final double hutFrequency;
        final Runnable onComplete;

        private StampRequest(Builder b)
        {
            this.level = b.level;
            this.cx = b.cx;
            this.cy = b.cy;
            this.cz = b.cz;
            this.size = b.size;
            this.depth = b.depth;
            this.theme = b.theme;
            this.seed = b.seed;
            this.key = b.key;
            this.store = b.store;
            this.blocksPerTick = b.blocksPerTick;
            this.freezePlayers = b.freezePlayers;
            this.freezeRadius = b.freezeRadius;
            this.waterEnabled = b.waterEnabled;
            this.vegetationEnabled = b.vegetationEnabled;
            this.structuresEnabled = b.structuresEnabled;
            this.seaLevelOffset = b.seaLevelOffset;
            this.basinFrequency = b.basinFrequency;
            this.vegetationDensity = b.vegetationDensity;
            this.villageFrequency = b.villageFrequency;
            this.hutFrequency = b.hutFrequency;
            this.onComplete = b.onComplete;
        }

        /**
         * Start a request. {@code level}: target dimension (NOT assumed to be the planet surface). {@code centre}: the
         * terrain's centre column, whose Y is the surface height the relief caps around. {@code size}: side of the square
         * the disc is inscribed in. {@code depth}: how far the deep body extends below the surface. {@code seed}: a stable
         * per-area hash making the terrain deterministic. {@code key}: uniquely identifies this stamp so concurrent stamps
         * do not collide.
         */
        public static Builder builder(ServerLevel level, BlockPos centre, int size, int depth, Theme theme, long seed,
                                      String key, GenerationStore store)
        {
            return new Builder(level, centre, size, depth, theme, seed, key, store);
        }

        public static final class Builder
        {
            private final ServerLevel level;
            private final int cx;
            private final int cy;
            private final int cz;
            private final int size;
            private final int depth;
            private final Theme theme;
            private final long seed;
            private final String key;
            private final GenerationStore store;

            // defaults mirror the planet tuning so an unconfigured dungeon stamp reads like a planet surface.
            private int blocksPerTick = DEFAULT_BLOCKS_PER_TICK;
            private boolean freezePlayers = true;
            private double freezeRadius;   // 0 = derive half + 16 at build time
            private boolean waterEnabled = true;
            private boolean vegetationEnabled = true;
            private boolean structuresEnabled = true;
            private int seaLevelOffset = 3;
            private double basinFrequency = 0.30;
            private double vegetationDensity = 0.45;
            private double villageFrequency = 0.35;
            private double hutFrequency = 0.5;
            private Runnable onComplete;

            private Builder(ServerLevel level, BlockPos centre, int size, int depth, Theme theme, long seed, String key,
                            GenerationStore store)
            {
                this.level = level;
                this.cx = centre.getX();
                this.cy = centre.getY();
                this.cz = centre.getZ();
                this.size = size;
                this.depth = depth;
                this.theme = theme;
                this.seed = seed;
                this.key = key;
                this.store = store;
            }

            public Builder blocksPerTick(int value)
            {
                this.blocksPerTick = value;
                return this;
            }

            public Builder freezePlayers(boolean value)
            {
                this.freezePlayers = value;
                return this;
            }

            public Builder freezeRadius(double value)
            {
                this.freezeRadius = value;
                return this;
            }

            public Builder water(boolean enabled, int seaLevelOffset, double basinFrequency)
            {
                this.waterEnabled = enabled;
                this.seaLevelOffset = seaLevelOffset;
                this.basinFrequency = basinFrequency;
                return this;
            }

            public Builder vegetation(boolean enabled, double density)
            {
                this.vegetationEnabled = enabled;
                this.vegetationDensity = density;
                return this;
            }

            public Builder structures(boolean enabled, double villageFrequency, double hutFrequency)
            {
                this.structuresEnabled = enabled;
                this.villageFrequency = villageFrequency;
                this.hutFrequency = hutFrequency;
                return this;
            }

            public Builder onComplete(Runnable value)
            {
                this.onComplete = value;
                return this;
            }

            public StampRequest build()
            {
                return new StampRequest(this);
            }
        }
    }

    /**
     * Begin a caller-driven terrain stamp, or attach to one already running for the same key, and return the ground-
     * snapped centre landing position. The centre patch is stamped synchronously before this returns, so the snap finds
     * solid ground this tick; the rest of the disc fills over following ticks on the shared batched worker at the
     * request's per-tick budget, players inside the freeze radius are held with a progress action bar, and each finished
     * terrain chunk is resent whole. On completion the store is marked generated and {@code onComplete} runs (immediately
     * if the area is already generated). Runs on the server thread.
     */
    public static Vec3 beginStamp(StampRequest request)
    {
        MinecraftServer server = request.level.getServer();
        if (request.store.isGenerated())
        {
            // already built: run the completion hook straight away so a caller waiting to place portals is not left
            // hanging, and snap onto the existing terrain.
            if (request.onComplete != null)
            {
                request.onComplete.run();
            }
            return SurfaceSnap.snap(request.level, request.cx + 0.5, request.cz + 0.5);
        }
        StampConfig cfg = StampConfig.forRequest(server, request);
        if (!StampTask.begin(cfg) && request.onComplete != null)
        {
            // a stamp for this key was already in flight (a concurrent request): attach this completion hook to it rather
            // than dropping it. The store/geometry are already owned by the in-flight task.
            if (!StampTask.attach(request.key, request.onComplete))
            {
                request.onComplete.run();
            }
        }
        return SurfaceSnap.snap(request.level, request.cx + 0.5, request.cz + 0.5);
    }

    /**
     * Ensure the surface for this planet id exists and return the cell-centre landing position (top of the terrain at
     * the centre column), ground-snapped so the player never spawns buried. Runs on the server thread.
     *
     * <p>The stamp is BATCHED, not synchronous: building the whole disc in one tick would stall the server (and trip the
     * watchdog at a large surface size), so only the small centre patch is stamped synchronously here, enough for the
     * player and the centre-clustered raid/garrison spawns to stand. The rest fills over following ticks on
     * {@link StampTask}, throttled to a per-tick block budget. The surface-generated flag is set only when that task
     * COMPLETES, so an interrupted stamp (server stop, crash) re-stamps from scratch on the next visit rather than being
     * marked done but half-built.
     *
     * <p>A thin PLANET-flavoured caller of the shared {@link StampTask}: it builds a {@link StampConfig} from the
     * {@link SurfaceDimension} geometry, the {@link GeneratedPlanetClaims} generated flag and the live planet config, so
     * the planet path and the general path share one implementation and cannot drift.
     */
    public static Vec3 ensureAndLandingPos(MinecraftServer server, ServerLevel surface, String planetId)
    {
        GeneratedPlanetClaims data = GeneratedPlanetClaims.get(server);
        Vec3 centre = SurfaceDimension.cellCentre(planetId);

        if (!data.isSurfaceGenerated(planetId))
        {
            // begin (or attach to) the batched stamp. This synchronously stamps the centre patch before returning, so
            // the ground-snap below finds solid ground this tick.
            StampTask.begin(StampConfig.forPlanet(server, surface, planetId));
        }

        // land on top of the centre column, ground-snapped. The centre column is pinned solid (see capHeight) and is in
        // the synchronous centre patch, so the snap always finds ground even while the rest of the disc is filling in.
        return SurfaceSnap.snap(surface, centre.x, centre.z);
    }

    /**
     * The fully-resolved inputs one {@link StampTask} is built from, so the planet path and the general path build the
     * SAME worker from the SAME fields and there is exactly one stamping implementation. {@link #forPlanet} resolves them
     * from the {@link SurfaceDimension} geometry, {@link GeneratedPlanetClaims} and the live planet config;
     * {@link #forRequest} from a caller's {@link StampRequest}.
     */
    static final class StampConfig
    {
        MinecraftServer server;
        ServerLevel surface;
        String key;
        long seed;
        Theme theme;
        int size;
        int cx;
        int cz;
        int baseY;
        int deepDepth;
        boolean waterOn;
        boolean vegOn;
        int seaLevelOffset;
        double basinFreq;
        double vegDensity;
        boolean structuresOn;
        double villageFreq;
        double hutFreq;
        IntSupplier budget;
        boolean freezeEnabled;
        FreezeArea freezeArea;
        GenerationStore store;
        GeometryRecorder geometryRecorder;
        Runnable onComplete;

        // the PLANET path: resolve every input from SurfaceDimension geometry, GeneratedPlanetClaims and the live planet
        // config (theme, size, depth, the stage-2b/2c snapshots, the live per-tick budget, the cell-based freeze and the
        // salvage geometry snapshot).
        static StampConfig forPlanet(MinecraftServer server, ServerLevel surface, String planetId)
        {
            Vec3 centre = SurfaceDimension.cellCentre(planetId);
            StampConfig cfg = new StampConfig();
            cfg.server = server;
            cfg.surface = surface;
            cfg.key = planetId;
            cfg.seed = GeneratedPlanets.hashOf(planetId + "#terrain");
            cfg.theme = GeneratedPlanetClaims.stampedThemeForId(server, planetId);
            cfg.size = GeneratedPlanetClaims.stampedSizeForId(server, planetId);
            cfg.cx = (int) Math.floor(centre.x);
            cfg.cz = (int) Math.floor(centre.z);
            cfg.baseY = (int) Math.floor(SurfaceDimension.SURFACE_Y);
            cfg.deepDepth = PlanetSpawnModule.surfaceColumnDepth();
            cfg.waterOn = waterEnabled;
            cfg.vegOn = vegetationEnabled;
            // qualify: StampConfig has an instance field of the same name, so an unqualified read here would bind to that
            // (a compile error) instead of the outer static config volatile.
            cfg.seaLevelOffset = SurfaceStamp.seaLevelOffset;
            cfg.basinFreq = basinFrequency;
            cfg.vegDensity = vegetationDensity;
            cfg.structuresOn = structuresEnabled;
            cfg.villageFreq = villageFrequency;
            cfg.hutFreq = hutFrequency;
            // read the budget LIVE every tick (a config reload mid-stamp shifts the frozen wait, exactly as before).
            cfg.budget = PlanetSpawnModule::surfaceStampBlocksPerTick;
            cfg.freezeEnabled = true;
            cfg.freezeArea = planetFreezeArea(planetId);
            cfg.store = new GenerationStore()
            {
                @Override
                public boolean isGenerated()
                {
                    return GeneratedPlanetClaims.get(server).isSurfaceGenerated(planetId);
                }

                @Override
                public void markGenerated()
                {
                    GeneratedPlanetClaims.get(server).markSurfaceGenerated(planetId);
                }
            };
            cfg.geometryRecorder = (sz, name, dd, slo, bf, vd, vf, hf, w, v, s) ->
                    GeneratedPlanetClaims.get(server).recordStampedGeometry(planetId, sz, name,
                            new GeneratedPlanetClaims.StampParams(dd, slo, bf, vd, vf, hf, w, v, s));
            cfg.onComplete = null;
            return cfg;
        }

        // the GENERAL path: resolve every input from the caller's request, with the freeze bound to a radius around the
        // centre rather than a planet cell.
        static StampConfig forRequest(MinecraftServer server, StampRequest req)
        {
            StampConfig cfg = new StampConfig();
            cfg.server = server;
            cfg.surface = req.level;
            cfg.key = req.key;
            cfg.seed = req.seed;
            cfg.theme = req.theme;
            cfg.size = req.size;
            cfg.cx = req.cx;
            cfg.cz = req.cz;
            cfg.baseY = req.cy;
            cfg.deepDepth = req.depth;
            cfg.waterOn = req.waterEnabled;
            cfg.vegOn = req.vegetationEnabled;
            cfg.seaLevelOffset = req.seaLevelOffset;
            cfg.basinFreq = req.basinFrequency;
            cfg.vegDensity = req.vegetationDensity;
            cfg.structuresOn = req.structuresEnabled;
            cfg.villageFreq = req.villageFrequency;
            cfg.hutFreq = req.hutFrequency;
            int bpt = req.blocksPerTick > 0 ? req.blocksPerTick : DEFAULT_BLOCKS_PER_TICK;
            cfg.budget = () -> bpt;
            cfg.freezeEnabled = req.freezePlayers;
            double radius = req.freezeRadius > 0 ? req.freezeRadius : (req.size / 2.0 + 16);
            double centreX = req.cx + 0.5;
            double centreZ = req.cz + 0.5;
            cfg.freezeArea = player ->
            {
                if (player.isSpectator())
                {
                    return false;
                }
                double ddx = player.getX() - centreX;
                double ddz = player.getZ() - centreZ;
                return ddx * ddx + ddz * ddz <= radius * radius;
            };
            cfg.store = req.store;
            cfg.geometryRecorder = null;
            cfg.onComplete = req.onComplete;
            return cfg;
        }
    }

    // the planet freeze predicate: a non-spectator standing on this planet's cell (the exact cell test the old FreezeTask
    // enrol loop used inline), so the planet freeze is byte-identical to before.
    private static FreezeArea planetFreezeArea(String planetId)
    {
        long cellX = SurfaceDimension.cellX(planetId);
        long cellZ = SurfaceDimension.cellZ(planetId);
        return player ->
        {
            if (player.isSpectator())
            {
                return false;
            }
            return Math.round(player.getX() / (double) SurfaceDimension.CELL_SPACING) == cellX
                    && Math.round(player.getZ() / (double) SurfaceDimension.CELL_SPACING) == cellZ;
        };
    }

    /**
     * Run {@code onComplete} once this planet's surface is FULLY stamped: immediately if already complete, queued if a
     * batched stamp is in progress, else immediately rather than being dropped. Used to defer work that needs the WHOLE
     * disc present, chiefly the garrison population that scatters defenders to the rim (they must not spawn into unstamped
     * void). Runs on the server thread.
     */
    public static void whenStamped(MinecraftServer server, String planetId, Runnable onComplete)
    {
        if (onComplete == null)
        {
            return;
        }
        if (GeneratedPlanetClaims.get(server).isSurfaceGenerated(planetId))
        {
            onComplete.run();
            return;
        }
        if (!StampTask.attach(planetId, onComplete))
        {
            onComplete.run();
        }
    }

    /**
     * Drop every in-flight stamp task. Called on server start so a task left over from a previous integrated-server
     * session (whose tick loop is gone) cannot wedge a fresh one. Any partly-stamped planet simply re-stamps on its next
     * visit, since its generated flag was never set.
     */
    public static void resetTasks()
    {
        StampTask.clearAll();
    }

    /**
     * The resumable worker that stamps a planet's surface disc across ticks. Mirrors {@link
     * net.shurui.shuruisutilities.commands.world.TickTaskBlockUpdater}: a fixed per-tick block budget ({@link
     * PlanetSpawnModule#surfaceStampBlocksPerTick}) is processed and the task yields, so the per-tick cost is flat
     * whatever the surface size. One task per planet id, tracked in {@link #ACTIVE}; a concurrent landing on the same
     * unstamped planet attaches rather than starting a second. Being a block task it counts against the registry's {@code
     * MAX_BLOCK_TASKS} throttle.
     *
     * <p>Completion sets the surface-generated flag and fires the deferred callbacks. Abort (server stop clears the
     * registry's task list; a fresh session clears {@link #ACTIVE} via {@link #resetTasks}) never sets the flag, so a
     * half-stamped planet re-stamps cleanly. Driven by the server tick, not any player, so a logout or wander mid-stamp
     * neither wedges nor leaks it; a player who outruns the fill is caught by the fall-catch in SpaceTravelModule.
     */
    static final class StampTask implements TaskRegistry.TickTask
    {
        private static final ConcurrentHashMap<String, StampTask> ACTIVE = new ConcurrentHashMap<>();

        // half-extent, in columns, of the centre patch stamped synchronously on begin so the player and the centre-
        // clustered raid/garrison spawns land on solid ground the same tick. A 17x17 patch, negligible next to the disc.
        private static final int SYNC_CENTRE_HALF = 8;

        // ONE shared per-server-tick allowance across every concurrent stamp. Before this each StampTask ran a full
        // budget every tick, so N planets stamping at once (12 space arrivals and 6 planet_surface arrivals in the
        // 2026-09-15 window) multiplied the per-tick cost by N. Now the first stamp to tick in a given server tick arms
        // the allowance and the wall-clock deadline, keyed on the tick count so no separate scheduler hook is needed, and
        // every stamp that runs this tick draws the SAME counter down. Touched only on the server thread.
        private static long budgetTickStamp = Long.MIN_VALUE;
        private static int sharedBlocksLeft;
        private static long tickDeadlineNanos;
        // hard wall-clock ceiling, in nanoseconds, on all surface stamping in one server tick. The block budget is the
        // primary throttle; this caps the tail when a burst of edge columns forces neighbour-chunk loads or a feature
        // placement runs long, so a tick can never spend more than this in stamping whatever the block budget works out
        // to. 5 ms of a 50 ms tick.
        private static final long STAMP_TICK_NANOS = 5_000_000L;
        // how many columns between wall-clock checks, so System.nanoTime is not read on every single block.
        private static final int CLOCK_CHECK_STRIDE = 512;

        // the phase ordering the water fill and structure flatten depend on, structural not incidental. TERRAIN stamps
        // ALL solid terrain (every basin's carved floor/walls and the underground) AND, interleaved on the SAME budget,
        // dresses vegetation on a wave trailing a few rings behind the terrain edge (never on an unfinished column, never
        // on a basin column, so a tree can never land in a pool). WATER then fills every basin (carved as air pockets
        // during TERRAIN). VEGETATION drains only the trailing tail the interleave had not reached. STRUCTURES runs LAST,
        // after terrain is complete, so its heavy footprint flatten never races the sweep and clears any tree under a
        // building.
        private enum Phase
        {
            TERRAIN, WATER, VEGETATION, STRUCTURES
        }

        private final MinecraftServer server;
        private final ServerLevel surface;
        // unique id for this stamp (planet id, or a caller key); the ACTIVE map and freeze HELD anchors are keyed on it.
        private final String key;
        // caller-owned persistence: queried before begin, marked on completion.
        private final GenerationStore store;
        // per-tick block budget, read live each tick (planet path returns the config value, general path a fixed value).
        private final IntSupplier budgetSupplier;
        // whether players in this stamp's area are frozen while it builds, and the predicate that decides who is in it.
        private final boolean freezeEnabled;
        private final FreezeArea freezeArea;
        private final long seed;
        private final Theme theme;
        private final Palette palette;
        private final int cx;
        private final int cz;
        private final int baseY;
        private final int half;
        private final double radiusSq;
        // deep-stone band depth, snapshotted from config at begin so every column of this one stamp (centre patch and the
        // batched remainder, across any resume) is built to the identical depth even if the config reloads mid-stamp.
        private final int deepDepth;
        // absolute Y of the lowest solid block every column bottoms out at, so a basin (whose top is lower than an
        // ordinary column) still fills solid down to the same floor and never leaves a void pocket under it.
        private final int bottomY;
        // stage-2b snapshots, frozen at construction like deepDepth so a config reload mid-stamp cannot split one planet
        // across two rule sets. seaLevel is the Y every basin fills up to; liquid is the theme's fill block (null = this
        // theme gets no water, so no basins are carved at all).
        private final boolean waterOn;
        private final boolean vegOn;
        private final int seaLevel;
        private final double basinFreq;
        private final double vegDensity;
        private final BlockState liquid;
        // stage-2c snapshots, frozen at construction. The two inward-margin inner radii the basins and structures gate
        // against are held on the shared SurfaceParams below.
        private final boolean structuresOn;
        private final double villageFreq;
        private final double hutFreq;
        // the shared geometry snapshot the site-resolution helpers run off, built once from the same fields so the stamp
        // and the salvage oracle run the identical basin/veg/structure maths.
        private final SurfaceParams params;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private final List<Runnable> onComplete = new CopyOnWriteArrayList<>();
        // configured-feature cache, so a feature id is looked up once per stamp not per placement. A null value marks an
        // id that resolved to nothing, so a missing id is not looked up repeatedly.
        private final java.util.Map<String, ConfiguredFeature<?, ?>> featureCache = new java.util.HashMap<>();

        private Phase phase = Phase.TERRAIN;
        // shared centre-outward Chebyshev ring cursor, reused by EVERY phase (reset at each beginX). Mirrors the ring walk
        // in PlanetSalvage.SalvageTask so the disc materialises outward from the centre, not in strips: the chunk locality
        // means a tick's writes land in a handful of chunks (each resent whole, once, instead of smearing across many).
        // State is (rRing, rEdge, rIdx) plus its outer bound rMax, so the sweep is fully resumable across ticks. cell[] is
        // the current (a, b) offset: column offsets in TERRAIN, site-cell offsets after.
        private int rRing;
        private int rEdge;
        private int rIdx;
        private int rMax;
        private final int[] cell = new int[2];
        // a SECOND, independent centre-outward ring cursor for vegetation, which is interleaved with TERRAIN and so runs
        // concurrently with the shared cursor above. It walks veg-site cells, trailing a few rings behind the terrain edge
        // during TERRAIN then draining its tail in VEGETATION, so its state carries straight across the phase boundary
        // rather than reset. vegCell[] is the current site-cell offset.
        private int vRing;
        private int vEdge;
        private int vIdx;
        private int vMax;
        private final int[] vegCell = new int[2];
        // the veg ring the interleave had reached when the VEGETATION tail phase began, so that phase's progress band
        // measures only the tail it drains (vTailStart..vMax) rather than the whole 0..vMax sweep.
        private int vTailStart;
        // in-flight village state: a village is placed one house per budget iteration (never a whole cluster at once), so
        // the seeded RandomSource is carried across those iterations and consumed in the identical order the old single-
        // call village used, keeping placement byte-identical. Only one village is mid-build at a time (the ring cursor
        // holds on its cell until the last house). villageRandom == null means no village is in progress. structCellDone
        // reports whether the current structure cell is fully placed this call.
        private RandomSource villageRandom;
        private int villageSlot;
        private boolean structCellDone = true;
        // chunks written by TERRAIN (TERRAIN_FLAG, no client notify) whose client copy is stale, keyed on ChunkPos.asLong()
        // -> the chunk's geometric max Chebyshev distance from the centre. Each is resent WHOLE (one full-chunk packet) the
        // instant the ring sweep has fully passed it (flushTerrainRing), turning a per-tick section-delta storm into a few
        // hundred full-chunk packets. Later phases keep flag 2, so their sparse dressing streams in live over it.
        private final Map<Long, Integer> pendingChunks = new HashMap<>();

        private StampTask(StampConfig cfg)
        {
            this.server = cfg.server;
            this.surface = cfg.surface;
            this.key = cfg.key;
            this.store = cfg.store;
            this.budgetSupplier = cfg.budget;
            this.freezeEnabled = cfg.freezeEnabled;
            this.freezeArea = cfg.freezeArea;
            this.seed = cfg.seed;
            this.theme = cfg.theme;
            this.palette = paletteFor(theme);
            this.half = cfg.size / 2;
            this.cx = cfg.cx;
            this.cz = cfg.cz;
            this.baseY = cfg.baseY;
            this.radiusSq = (double) half * half;
            this.deepDepth = cfg.deepDepth;
            this.bottomY = baseY - (SUBSURFACE_DEPTH - 1) - deepDepth;
            // stage-2b snapshots. liquid is null for STONY (no water, so basinFloorAt short-circuits and no basins carve).
            this.waterOn = cfg.waterOn;
            this.vegOn = cfg.vegOn;
            this.seaLevel = baseY - cfg.seaLevelOffset;
            this.basinFreq = cfg.basinFreq;
            this.vegDensity = cfg.vegDensity;
            this.liquid = liquidFor(theme);
            // stage-2c snapshots.
            this.structuresOn = cfg.structuresOn;
            this.villageFreq = cfg.villageFreq;
            this.hutFreq = cfg.hutFreq;
            // start the TERRAIN sweep at the centre ring; the outer ring is the disc half, so ring 0..half covers exactly
            // the same [-half, half] bounding square the old dx/dz strip walk did (an ORDER change only, terrain identical).
            ringReset(half);
            // arm the separate vegetation cursor over the same veg-site cell range the old VEGETATION phase swept. It is
            // advanced during TERRAIN (trailing the terrain edge) and finished in the VEGETATION phase, so it is set up
            // once here and never reset.
            vegRingReset(ringMaxFor(vegCellMin(), vegCellMax()));
            // the shared geometry snapshot, built from the SAME per-task fields, so the stamp and the salvage oracle drive
            // the identical basin/veg/structure site maths. seaLevelOffset is recovered from the snapshotted seaLevel.
            this.params = new SurfaceParams(seed, half, baseY, theme, deepDepth, waterOn, cfg.seaLevelOffset,
                    basinFreq, vegOn, vegDensity, structuresOn, villageFreq, hutFreq);
            // persist the geometry snapshot the moment the stamp begins (planet path only; the general path leaves the
            // recorder null and owns its own persistence). A mid-stamp read and a resume after an interrupted stamp both
            // then see the exact size/theme this terrain is being built at, and a later planet-destroy salvage recompute
            // reads back the exact parameters even if config was reloaded in between.
            if (cfg.geometryRecorder != null)
            {
                cfg.geometryRecorder.record(cfg.size, theme.name(), deepDepth, cfg.seaLevelOffset, basinFreq, vegDensity,
                        villageFreq, hutFreq, waterOn, vegOn, structuresOn);
            }
            if (cfg.onComplete != null)
            {
                this.onComplete.add(cfg.onComplete);
            }
        }

        // begin a stamp for this config, or do nothing if one is already running for its key. Returns whether a NEW task
        // was started (false = one was already in flight, so a caller can attach its completion hook instead). Stamps the
        // centre patch synchronously so a landing this tick stands on solid ground.
        static boolean begin(StampConfig cfg)
        {
            if (ACTIVE.containsKey(cfg.key))
            {
                return false;
            }
            StampTask task = new StampTask(cfg);
            task.stampCentre();
            ACTIVE.put(cfg.key, task);
            TaskRegistry.schedule(task);
            // hold every player in this stamp's area while it builds. The freeze task is a single shared server-tick
            // worker that self-schedules on the first stamp and self-removes once nothing is stamping and nobody is held.
            if (task.freezeEnabled)
            {
                ensureFreezeTask();
            }
            return true;
        }

        static boolean attach(String key, Runnable onComplete)
        {
            StampTask task = ACTIVE.get(key);
            if (task == null)
            {
                return false;
            }
            task.onComplete.add(onComplete);
            return true;
        }

        static void clearAll()
        {
            ACTIVE.clear();
            // release every held player and let a fresh session re-schedule the freeze worker. Called on server start
            // (after the tick registry has already dropped last session's tasks), so nothing survives a restart.
            HELD.clear();
            FREEZE_SCHEDULED.set(false);
        }

        // stamp the centre patch synchronously so a landing this tick stands on solid ground. Bounded by the disc half,
        // so a tiny planet never over-stamps beyond its own rim. The centre patch is cleared of basins by construction
        // (BASIN_CENTRE_CLEAR), so it is always dry solid ground. These columns are re-visited by the sweep below and
        // re-placed identically (same flag, same blocks), which is a cheap, harmless redundancy. The centre is sent to the
        // client by the normal chunk tracking that fires when the player is teleported in, so it shows even though these
        // writes carry no client notify.
        private void stampCentre()
        {
            int centreHalf = Math.min(SYNC_CENTRE_HALF, half);
            for (int ddx = -centreHalf; ddx <= centreHalf; ++ddx)
            {
                for (int ddz = -centreHalf; ddz <= centreHalf; ++ddz)
                {
                    stampColumn(ddx, ddz);
                }
            }
        }

        @Override
        public boolean tick()
        {
            try
            {
                return tickBody();
            }
            catch (Throwable t)
            {
                // never wedge the server tick loop, and never strand a frozen player: log once, abort this stamp (its
                // generated flag was never set, so it re-stamps cleanly on the next visit) and drop it from ACTIVE, which
                // is exactly what releases anyone the freeze task is holding on this planet.
                if (STAMP_WARNED.compareAndSet(false, true))
                {
                    LoggingHandler.sulog.warn("[SurfaceStamp] Surface stamp for {} failed; it will re-stamp on the "
                            + "next visit.", key, t);
                }
                ACTIVE.remove(key, this);
                return true;
            }
        }

        private boolean tickBody()
        {
            // arm the shared per-tick allowance once per server tick (the first stamp to run this tick sets it), then draw
            // it down here; a second concurrent stamp that ticks later this same tick sees what is left rather than a fresh
            // budget, so several stamps together cost one budget, not one each. The wall-clock deadline is the hard ceiling.
            long tickId = server.getTickCount();
            if (tickId != budgetTickStamp)
            {
                budgetTickStamp = tickId;
                sharedBlocksLeft = Math.max(1, budgetSupplier.getAsInt());
                tickDeadlineNanos = System.nanoTime() + STAMP_TICK_NANOS;
            }
            int sinceClockCheck = 0;
            while (sharedBlocksLeft > 0)
            {
                // time is the hard cap: even a cheap block count can run long if a burst of edge columns forces neighbour
                // chunk loads, so bail the moment this tick's stamp deadline passes. Checked every CLOCK_CHECK_STRIDE
                // columns so nanoTime is not read per block.
                if (++sinceClockCheck >= CLOCK_CHECK_STRIDE)
                {
                    sinceClockCheck = 0;
                    if (System.nanoTime() >= tickDeadlineNanos)
                    {
                        return false;
                    }
                }
                int worked;
                switch (phase)
                {
                    case TERRAIN:
                        if (rRing > rMax)
                        {
                            // all solid terrain (including every basin's carved floor/walls and the underground) is now
                            // placed, so resend any rim chunk still pending and start filling water. The interleaved
                            // vegetation wave has trailed a few rings behind the edge, so a small outer tail of veg cells
                            // is still unplaced; the VEGETATION phase drains it after the water fill.
                            flushAllPendingChunks();
                            beginWater();
                            continue;
                        }
                        // interleave: while the trailing vegetation wave is still inside the terrain the sweep has already
                        // finished (site + feature reach + lag all within the last fully completed ring), plant ONE
                        // feature there this iteration instead of a terrain column, so trees appear a few rings behind the
                        // advancing terrain edge, "the same way the terrain does", rather than in one wave at the end. The
                        // veg site gate excludes basin columns geometrically (a pure position test), so a tree can never
                        // land in a basin even though water has not been filled yet.
                        if (vegOn && vRing <= vMax && vRing <= safeVegRing(rRing - 1))
                        {
                            vegRingCurrent();
                            worked = Math.max(1, placeVegAtSite(vegCell[0], vegCell[1]));
                            vegRingAdvance();
                            break;
                        }
                        // charge a stamped column its block count and a skipped column a single unit, so a tick's work is
                        // bounded even across the empty corners of the bounding square.
                        ringCurrent();
                        worked = Math.max(1, stampColumn(cell[0], cell[1]));
                        // advancing past the last cell of a ring means that whole ring is done, so every chunk the sweep
                        // has now fully passed can be resent as one full-chunk packet: the outward reveal, cheaply.
                        int doneTerrain = ringAdvance();
                        if (doneTerrain >= 0)
                        {
                            flushTerrainRing(doneTerrain);
                        }
                        break;
                    case WATER:
                        if (!waterOn || liquid == null || rRing > rMax)
                        {
                            beginVegetation();
                            continue;
                        }
                        // a whole pool is filled in a single fillPoolAtSite call (never split across ticks), so a pool is
                        // an indivisible work unit and its water is always sealed before any fluid tick can run.
                        ringCurrent();
                        worked = Math.max(1, fillPoolAtSite(cell[0], cell[1]));
                        ringAdvance();
                        break;
                    case VEGETATION:
                        if (!vegOn || vRing > vMax)
                        {
                            // the whole vegetation sweep (the bulk interleaved during TERRAIN plus this trailing tail) is
                            // placed, so it is safe to start dropping structures onto the flat interior (they flatten their
                            // own footprint, clearing any tree that grew there).
                            beginStructures();
                            continue;
                        }
                        // drain the outer tail the interleave had not reached when terrain finished, on the SAME veg cursor
                        // it left off on, so no cell is placed twice and the centre-outward order is unbroken.
                        vegRingCurrent();
                        worked = Math.max(1, placeVegAtSite(vegCell[0], vegCell[1]));
                        vegRingAdvance();
                        break;
                    case STRUCTURES:
                    default:
                        if (!structuresOn || rRing > rMax)
                        {
                            complete();
                            return true;
                        }
                        // a structure is an indivisible work unit, but a VILLAGE is a cluster of several houses and each
                        // house (its pad flatten plus its template placement) is the real unit: placeStructAtSite places
                        // exactly ONE house per call and leaves structCellDone false until the last one, so the cursor
                        // holds on the cell across ticks and a whole village never lands in a single iteration. A hut is a
                        // single unit and completes its cell in one call.
                        ringCurrent();
                        worked = Math.max(1, placeStructAtSite(cell[0], cell[1]));
                        if (structCellDone)
                        {
                            ringAdvance();
                        }
                        break;
                }
                sharedBlocksLeft -= worked;
            }
            return false;
        }

        // reset the shared cursor to the centre of a square of Chebyshev radius max, so the next sweep runs ring 0..max.
        private void ringReset(int max)
        {
            rRing = 0;
            rEdge = 0;
            rIdx = 0;
            rMax = Math.max(0, max);
        }

        // fill cell[] with the current (a, b) offset the cursor is on. Ring 0 is the single centre cell; each higher ring
        // walks its perimeter as four edges (top, bottom, left, right), the corners covered once by the top/bottom edges.
        // Identical geometry to PlanetSalvage.SalvageTask.currentCell.
        private void ringCurrent()
        {
            if (rRing == 0)
            {
                cell[0] = 0;
                cell[1] = 0;
                return;
            }
            switch (rEdge)
            {
                case 0:            // top edge: b = -ring, a = -ring .. ring
                    cell[0] = -rRing + rIdx;
                    cell[1] = -rRing;
                    break;
                case 1:            // bottom edge: b = ring, a = -ring .. ring
                    cell[0] = -rRing + rIdx;
                    cell[1] = rRing;
                    break;
                case 2:            // left edge: a = -ring, b = -ring+1 .. ring-1
                    cell[0] = -rRing;
                    cell[1] = -rRing + 1 + rIdx;
                    break;
                default:           // right edge: a = ring, b = -ring+1 .. ring-1
                    cell[0] = rRing;
                    cell[1] = -rRing + 1 + rIdx;
                    break;
            }
        }

        // step the cursor to the next perimeter cell, out to the next ring when this one is exhausted. Returns the ring
        // number JUST COMPLETED (>= 0) when the step rolls onto a new ring, else -1, so the terrain phase knows exactly
        // when a ring is fully done. Mirrors PlanetSalvage.SalvageTask.advance.
        private int ringAdvance()
        {
            if (rRing == 0)
            {
                rRing = 1;
                rEdge = 0;
                rIdx = 0;
                return 0;
            }
            int lim = (rEdge == 0 || rEdge == 1) ? 2 * rRing : 2 * rRing - 2;
            if (rIdx < lim)
            {
                rIdx++;
                return -1;
            }
            rIdx = 0;
            if (rEdge < 3)
            {
                rEdge++;
                return -1;
            }
            int completed = rRing;
            rRing++;
            rEdge = 0;
            rIdx = 0;
            return completed;
        }

        // the outer ring a site-cell phase must sweep to cover its whole cell range. A ring 0..max square is a superset of
        // the phase's [min, max] cell range; any extra cell it visits is outside the disc/inner-radius and resolves to a
        // no-op in the site helpers, so the placed output is identical to the old [min, max] strip walk (verified: every
        // site helper gates on the disc/inner-radius geometry first).
        private static int ringMaxFor(int cellMin, int cellMax)
        {
            return Math.max(Math.abs(cellMin), Math.abs(cellMax));
        }

        // the second, independent centre-outward cursor, dedicated to vegetation so it can run concurrently with the
        // shared terrain cursor during the TERRAIN phase. Same geometry as ringReset/ringCurrent/ringAdvance above, on the
        // vRing/vEdge/vIdx/vMax state and writing vegCell[], just without the per-ring "completed ring" bookkeeping the
        // terrain chunk resends need (vegetation writes with a notifying flag and streams live, so it needs no resend).
        private void vegRingReset(int max)
        {
            vRing = 0;
            vEdge = 0;
            vIdx = 0;
            vMax = Math.max(0, max);
        }

        private void vegRingCurrent()
        {
            if (vRing == 0)
            {
                vegCell[0] = 0;
                vegCell[1] = 0;
                return;
            }
            switch (vEdge)
            {
                case 0:            // top edge: b = -ring, a = -ring .. ring
                    vegCell[0] = -vRing + vIdx;
                    vegCell[1] = -vRing;
                    break;
                case 1:            // bottom edge: b = ring, a = -ring .. ring
                    vegCell[0] = -vRing + vIdx;
                    vegCell[1] = vRing;
                    break;
                case 2:            // left edge: a = -ring, b = -ring+1 .. ring-1
                    vegCell[0] = -vRing;
                    vegCell[1] = -vRing + 1 + vIdx;
                    break;
                default:           // right edge: a = ring, b = -ring+1 .. ring-1
                    vegCell[0] = vRing;
                    vegCell[1] = -vRing + 1 + vIdx;
                    break;
            }
        }

        private void vegRingAdvance()
        {
            if (vRing == 0)
            {
                vRing = 1;
                vEdge = 0;
                vIdx = 0;
                return;
            }
            int lim = (vEdge == 0 || vEdge == 1) ? 2 * vRing : 2 * vRing - 2;
            if (vIdx < lim)
            {
                vIdx++;
                return;
            }
            vIdx = 0;
            if (vEdge < 3)
            {
                vEdge++;
                return;
            }
            vRing++;
            vEdge = 0;
            vIdx = 0;
        }

        // the outermost vegetation ring whose whole feature footprint is guaranteed to sit on terrain the sweep has
        // ALREADY finished, given the terrain ring most recently completed. A veg site in cell-ring v reaches out to
        // v*VEG_GRID + VEG_GRID/2 + VEG_JITTER columns, a feature adds VEG_FEATURE_REACH of canopy on top, and
        // VEG_TRAIL_LAG holds the wave a few more columns inside the edge so trees visibly trail the terrain rather than
        // ride its lip. Everything inside the returned ring is on completed terrain, so no terrain write can ever land on
        // a placed feature (the terrain sweep only ever writes columns farther out than the ring it has completed).
        private int safeVegRing(int completedTerrainRing)
        {
            int reach = VEG_GRID / 2 + VEG_JITTER + VEG_FEATURE_REACH + VEG_TRAIL_LAG;
            return Math.floorDiv(completedTerrainRing - reach, VEG_GRID);
        }

        // record that the TERRAIN phase touched the chunk containing world column (wx, wz), so it can be resent whole once
        // the ring sweep passes it. Stores the chunk's GEOMETRIC max Chebyshev distance from the centre (over its whole
        // 16x16 footprint, disc or not), so a chunk is flushed only once the ring has definitely passed every column it
        // could hold, never while a farther column of the same chunk is still to come.
        private void markTerrainChunk(int wx, int wz)
        {
            long key = ChunkPos.asLong(wx >> 4, wz >> 4);
            if (pendingChunks.containsKey(key))
            {
                return;
            }
            int ckx = wx >> 4;
            int ckz = wz >> 4;
            int x0 = (ckx << 4) - cx;
            int x1 = x0 + 15;
            int z0 = (ckz << 4) - cz;
            int z1 = z0 + 15;
            int cheb = Math.max(Math.max(Math.abs(x0), Math.abs(x1)), Math.max(Math.abs(z0), Math.abs(z1)));
            pendingChunks.put(key, cheb);
        }

        // resend every pending chunk the ring sweep has now fully passed (its geometric reach is within the just-completed
        // ring). One full-chunk packet each, then dropped from the pending set.
        private void flushTerrainRing(int completedRing)
        {
            if (pendingChunks.isEmpty())
            {
                return;
            }
            Iterator<Map.Entry<Long, Integer>> it = pendingChunks.entrySet().iterator();
            while (it.hasNext())
            {
                Map.Entry<Long, Integer> e = it.next();
                if (e.getValue() <= completedRing)
                {
                    resendChunk(ChunkPos.getX(e.getKey()), ChunkPos.getZ(e.getKey()));
                    it.remove();
                }
            }
        }

        // resend any chunk still pending (the outermost rim chunks whose geometric reach exceeds the disc half, so the
        // ring sweep stopped before covering them). Called when terrain finishes and again at completion, belt and braces.
        private void flushAllPendingChunks()
        {
            if (pendingChunks.isEmpty())
            {
                return;
            }
            for (Long key : pendingChunks.keySet())
            {
                resendChunk(ChunkPos.getX(key), ChunkPos.getZ(key));
            }
            pendingChunks.clear();
        }

        // send the whole chunk (blocks + light) to every player tracking it, exactly the packet the client gets when a
        // chunk first loads, so the flag-0 terrain writes it never saw are applied in one shot. A resend is a visual
        // nicety, so any failure is swallowed rather than allowed to touch the stamp.
        private void resendChunk(int ckx, int ckz)
        {
            try
            {
                net.minecraft.server.level.ServerChunkCache source = surface.getChunkSource();
                List<ServerPlayer> tracking = source.chunkMap.getPlayers(new ChunkPos(ckx, ckz), false);
                if (tracking == null || tracking.isEmpty())
                {
                    return;
                }
                LevelChunk chunk = surface.getChunk(ckx, ckz);
                ClientboundLevelChunkWithLightPacket packet =
                        new ClientboundLevelChunkWithLightPacket(chunk, source.getLightEngine(), null, null);
                for (ServerPlayer p : tracking)
                {
                    p.connection.send(packet);
                }
            }
            catch (Throwable ignored)
            {
                // the chunk still holds the correct blocks server-side; the client corrects on its next natural resend.
            }
        }

        // a 0..1 estimate of how far this stamp has progressed, for the frozen player's action bar. Each phase owns a
        // band and moves through it by its OWN ring cursor, so the bar climbs continuously instead of jumping between
        // fixed constants and then sitting frozen through a phase. The bands are weighted by real per-tick cost: terrain
        // (measured ~520 ticks, and it now also carries the bulk of the interleaved vegetation) genuinely dominates, so
        // it owns 0..0.88; water is ~1 tick (a flash) at 0.88..0.91; the vegetation TAIL the interleave did not reach is
        // a handful of ticks at 0.91..0.97; structures (~1 tick plus the now-yielded village houses) finish 0.97..1.00.
        // Deliberately NOT proportional-to-ticks (that would put terrain at ~0.98 and hide every finishing step): the tail
        // bands are widened just enough that each closing step shows visible motion, while terrain still owns the great
        // majority so the bar does not crawl through terrain and then sprint. Every band starts at or above the previous
        // band's ceiling, so the bar is monotonic and never steps backward at a phase transition.
        float progress()
        {
            switch (phase)
            {
                case TERRAIN:
                    return rMax <= 0 ? 0f : Math.min(0.88f, 0.88f * rRing / (rMax + 1f));
                case WATER:
                    return 0.88f + 0.03f * ringFrac(rRing, rMax);
                case VEGETATION:
                {
                    int denom = vMax - vTailStart + 1;
                    float frac = denom <= 0 ? 1f : Math.min(1f, Math.max(0f, (vRing - vTailStart) / (float) denom));
                    return 0.91f + 0.06f * frac;
                }
                case STRUCTURES:
                default:
                    return 0.97f + 0.03f * ringFrac(rRing, rMax);
            }
        }

        // a 0..1 fraction of a shared-cursor sweep, used by the water and structure progress bands.
        private static float ringFrac(int ring, int max)
        {
            return max <= 0 ? 1f : Math.min(1f, ring / (max + 1f));
        }

        // move to the WATER phase, resetting the shared ring cursor to cover the basin-site cell range from the centre out.
        private void beginWater()
        {
            phase = Phase.WATER;
            ringReset(ringMaxFor(basinCellMin(), basinCellMax()));
        }

        // move to the VEGETATION tail phase: the interleave (during TERRAIN) already placed every veg cell inside the
        // trailing frontier, so this phase simply continues the SAME veg cursor from where it left off out to vMax. The
        // cursor is NOT reset (that would re-place the interior). vTailStart records where it stands now, so the progress
        // band measures only the tail this phase actually drains.
        private void beginVegetation()
        {
            phase = Phase.VEGETATION;
            vTailStart = vRing;
        }

        private int basinCellMin()
        {
            return Math.floorDiv(-half, BASIN_SITE_GRID) - 1;
        }

        private int basinCellMax()
        {
            return Math.floorDiv(half, BASIN_SITE_GRID) + 1;
        }

        private int vegCellMin()
        {
            return Math.floorDiv(-half, VEG_GRID) - 1;
        }

        private int vegCellMax()
        {
            return Math.floorDiv(half, VEG_GRID) + 1;
        }

        // move to the STRUCTURES phase, resetting the ring cursor to cover the structure-site cell range from the centre out.
        private void beginStructures()
        {
            phase = Phase.STRUCTURES;
            ringReset(ringMaxFor(structCellMin(), structCellMax()));
        }

        private int structCellMin()
        {
            return Math.floorDiv(-half, STRUCT_GRID) - 1;
        }

        private int structCellMax()
        {
            return Math.floorDiv(half, STRUCT_GRID) + 1;
        }

        // stamp one column at offset (dx, dz) from the cell centre, if it falls inside the disc's inscribed circle AND
        // inside that column's per-direction wobbled rim. Returns the number of blocks placed (0 if the column is outside
        // the disc). The SINGLE per-column routine both the synchronous centre patch and the batched sweep call, so both
        // place byte-identical terrain and neither can drift from the other. A column that basinFloorAt marks as a basin
        // is carved down to its floor (top solid at the floor, air above for the water phase to fill); every other column
        // is the ordinary surface/subsurface/deep stack.
        private int stampColumn(int dx, int dz)
        {
            double distSq = (double) dx * dx + (double) dz * dz;
            if (distSq > radiusSq)
            {
                // outside the inscribed circle: nothing, so the surface is never a hard square.
                return 0;
            }
            double dist = Math.sqrt(distSq);
            double wobbledRadius = rimRadius(seed, half, dx, dz);
            if (dist > wobbledRadius)
            {
                return 0;
            }
            // this column is solid terrain: its chunk's client copy will be stale (the writes below use TERRAIN_FLAG, no
            // client notify), so mark the chunk for a whole-chunk resend once the ring sweep passes it.
            markTerrainChunk(cx + dx, cz + dz);
            int basinFloor = basinFloorAt(dx, dz);
            if (basinFloor != NO_BASIN)
            {
                placeBasinColumn(cx + dx, cz + dz, basinFloor);
                return columnBlocks(deepDepth);
            }
            int capY = capHeight(seed, dx, dz, dist, wobbledRadius, baseY);
            placeColumn(surface, pos, cx + dx, cz + dz, capY, palette, deepDepth);
            return columnBlocks(deepDepth);
        }

        // carve a basin column: a lakebed of subsurface (soil) at the floor and the band below it, then the deep body all
        // the way down to the shared bottom Y so the pool sits in solid ground with no void beneath. Everything ABOVE the
        // floor is left as air for the water phase to fill from floor+1 up to sea level; above sea level stays open air.
        private void placeBasinColumn(int wx, int wz, int basinFloor)
        {
            pos.set(wx, basinFloor, wz);
            surface.setBlock(pos, palette.subsurface(), TERRAIN_FLAG);
            int y = basinFloor - 1;
            int subFloor = basinFloor - SUBSURFACE_DEPTH;
            for (; y > subFloor; --y)
            {
                pos.set(wx, y, wz);
                surface.setBlock(pos, palette.subsurface(), TERRAIN_FLAG);
            }
            for (; y >= bottomY; --y)
            {
                pos.set(wx, y, wz);
                surface.setBlock(pos, palette.deep(), TERRAIN_FLAG);
            }
        }

        // the basin floor Y for column (dx, dz), or NO_BASIN if the column is not inside any pool. A theme with no water
        // (STONY, where liquid is null) has no basins at all. A pool disc can spill out of the cell its site sits in (up
        // to a radius plus jitter), so the 3x3 neighbourhood of site cells is checked; the site spacing guarantees at
        // most one pool can actually contain the column.
        private int basinFloorAt(int dx, int dz)
        {
            return SurfaceStamp.basinFloorAt(params, dx, dz, poolScratch);
        }

        // resolve the pool (if any) for one basin-site cell into out[] = {centreDx, centreDz, radius, floorY}, returning
        // whether a pool exists there. A pool exists only where the occupancy roll passes basinFreq AND the WHOLE disc
        // clears the centre landing patch AND fits inside basinInnerRadius (the inward-margin rule), so every pool is
        // wrapped in an unbroken ring of full-height land and can never drain off the disc. Pure function of the seed and
        // the cell, so the carve (terrain phase) and the fill (water phase) resolve the identical pool.
        private boolean poolInCell(int cellX, int cellZ, int[] out)
        {
            return SurfaceStamp.poolInCell(params, cellX, cellZ, out);
        }

        // fill one basin-site cell's pool with the theme's liquid, in a single call (never split across ticks). The fill
        // is BOTTOM-UP (a source is only ever placed once the block below it, a solid floor or a lower source, exists) and
        // the whole pool completes within this one server tick, so by the time the fluid ticks that onPlace schedules come
        // due (>= 5 ticks later for water, more for lava) every source is already sealed on all sides by the surrounding
        // full-height land and the solid floor, and each of those ticks is a no-op that spreads nothing and reschedules
        // nothing. Returns the number of blocks placed. For the END theme the "liquid" is packed ice, a SOLID block, so
        // its pools schedule no fluid ticks at all.
        private int fillPoolAtSite(int cellX, int cellZ)
        {
            if (!waterOn || liquid == null || !poolInCell(cellX, cellZ, poolScratch))
            {
                return 0;
            }
            int pcx = poolScratch[0];
            int pcz = poolScratch[1];
            int r = poolScratch[2];
            int floorY = poolScratch[3];
            int placed = 0;
            for (int y = floorY + 1; y <= seaLevel; ++y)
            {
                for (int ddx = -r; ddx <= r; ++ddx)
                {
                    for (int ddz = -r; ddz <= r; ++ddz)
                    {
                        if ((long) ddx * ddx + (long) ddz * ddz > (long) r * r)
                        {
                            continue;
                        }
                        int gx = pcx + ddx;
                        int gz = pcz + ddz;
                        // fill only the columns the carve actually carved: the exact same disc/rim gate stampColumn used,
                        // so a column left solid is never topped with floating water. The inward-margin rule keeps the
                        // whole disc well inside the rim, so this gate effectively never trips, but it keeps carve and
                        // fill provably in lockstep.
                        double gdSq = (double) gx * gx + (double) gz * gz;
                        if (gdSq > radiusSq || Math.sqrt(gdSq) > rimRadius(seed, half, gx, gz))
                        {
                            continue;
                        }
                        pos.set(cx + gx, y, cz + gz);
                        surface.setBlock(pos, liquid, 2);
                        placed++;
                    }
                }
            }
            return placed;
        }

        // place at most one vegetation feature for one veg-site cell, returning an approximate block cost for the budget.
        // The site is gated to the flat interior (inside the feathered rim so a canopy never overhangs the disc edge) and
        // off any basin (no plants standing in water). Placement is deterministic: a RandomSource seeded from the site
        // hash drives both the feature choice and the feature's own internal variation, so the same planet always grows
        // the identical forest.
        private int placeVegAtSite(int cellX, int cellZ)
        {
            if (!vegOn)
            {
                return 0;
            }
            if (!SurfaceStamp.vegColumnInCell(params, cellX, cellZ, vegScratch, poolScratch))
            {
                return 0;
            }
            int gx = vegScratch[0];
            int gz = vegScratch[1];
            int capY = vegScratch[2];
            RandomSource random = RandomSource.create(hash(seed, gx, gz, VEG_PICK_SALT));
            return placeVegetation(cx + gx, capY, cz + gz, random);
        }

        // dispatch vegetation by theme: hand-built rock decor for STONY, a nylium speckle with growth for NETHER, and a
        // weighted configured-feature / ground-cover pick for the green and End themes.
        private int placeVegetation(int wx, int groundY, int wz, RandomSource random)
        {
            switch (theme)
            {
                case STONY:
                    return placeRockDecor(wx, groundY, wz, random);
                case NETHER:
                    return placeNetherVeg(wx, groundY, wz, random);
                default:
                    return placeFeatureVeg(wx, groundY, wz, random);
            }
        }

        // pick a weighted entry from the theme's veg list and place it: a configured feature (trees, grass/flower patches,
        // chorus) via holder.place, or a single ground-cover block via setBlock. A feature that fails to resolve or fails
        // its own placement checks simply leaves the ground bare, never crashes.
        private int placeFeatureVeg(int wx, int groundY, int wz, RandomSource random)
        {
            List<VegEntry> entries = vegEntriesFor(theme);
            int total = 0;
            for (VegEntry e : entries)
            {
                total += e.weight();
            }
            if (total <= 0)
            {
                return 0;
            }
            int roll = random.nextInt(total);
            VegEntry chosen = entries.get(entries.size() - 1);
            int acc = 0;
            for (VegEntry e : entries)
            {
                acc += e.weight();
                if (roll < acc)
                {
                    chosen = e;
                    break;
                }
            }
            if (chosen.featureId() != null)
            {
                ConfiguredFeature<?, ?> cf = configuredFeature(chosen.featureId());
                if (cf != null)
                {
                    cf.place((WorldGenLevel) surface, surface.getChunkSource().getGenerator(), random,
                            new BlockPos(wx, groundY + 1, wz));
                }
                return VEG_FEATURE_COST;
            }
            pos.set(wx, groundY + 1, wz);
            surface.setBlock(pos, chosen.cover(), 2);
            return 1;
        }

        // NETHER growth: convert this column's surface block to crimson or warped nylium (a solid speckle on the
        // netherrack), then either grow the matching huge fungus on it (the vanilla feature finds the nylium below) or
        // set a roots / sprouts cover on top. All solid or plant blocks, so no fluid ticks.
        private int placeNetherVeg(int wx, int groundY, int wz, RandomSource random)
        {
            boolean crimson = random.nextBoolean();
            pos.set(wx, groundY, wz);
            surface.setBlock(pos, (crimson ? Blocks.CRIMSON_NYLIUM : Blocks.WARPED_NYLIUM).defaultBlockState(), 2);
            int roll = random.nextInt(10);
            if (roll < 2)
            {
                ConfiguredFeature<?, ?> cf =
                        configuredFeature(crimson ? "minecraft:crimson_fungus" : "minecraft:warped_fungus");
                if (cf != null)
                {
                    cf.place((WorldGenLevel) surface, surface.getChunkSource().getGenerator(), random,
                            new BlockPos(wx, groundY + 1, wz));
                }
                return VEG_FEATURE_COST;
            }
            BlockState cover = roll < 6
                    ? (crimson ? Blocks.CRIMSON_ROOTS : Blocks.WARPED_ROOTS).defaultBlockState()
                    : Blocks.NETHER_SPROUTS.defaultBlockState();
            pos.set(wx, groundY + 1, wz);
            surface.setBlock(pos, cover, 2);
            return 2;
        }

        // STONY rock decor, hand-built so it needs no worldgen context and places only solid blocks (zero ticks). A
        // weighted pick of spires, karst pillars, boulder fields and crystal clusters, so a stony world reads as a
        // deliberately alien rockscape rather than an empty grey flat.
        private int placeRockDecor(int wx, int groundY, int wz, RandomSource random)
        {
            int roll = random.nextInt(15);
            if (roll < 4)
            {
                return buildSpire(wx, groundY, wz, random);
            }
            if (roll < 7)
            {
                return buildKarstPillar(wx, groundY, wz, random);
            }
            if (roll < 13)
            {
                return buildBoulder(wx, groundY, wz, random);
            }
            return buildCrystal(wx, groundY, wz, random);
        }

        // a tapering rock cone: a wide deep-stone base narrowing to a subsurface tip.
        private int buildSpire(int wx, int groundY, int wz, RandomSource random)
        {
            int height = 4 + random.nextInt(6);
            int placed = 0;
            for (int i = 0; i < height; ++i)
            {
                int y = groundY + 1 + i;
                int rad = (int) Math.round((height - i) / (double) height * 2.0);
                BlockState block = i < height * 0.6 ? palette.deep() : palette.subsurface();
                for (int ddx = -rad; ddx <= rad; ++ddx)
                {
                    for (int ddz = -rad; ddz <= rad; ++ddz)
                    {
                        if (ddx * ddx + ddz * ddz > rad * rad)
                        {
                            continue;
                        }
                        pos.set(wx + ddx, y, wz + ddz);
                        surface.setBlock(pos, block, 2);
                        placed++;
                    }
                }
            }
            return placed;
        }

        // a thin tall rock finger, 1x1 or 2x2.
        private int buildKarstPillar(int wx, int groundY, int wz, RandomSource random)
        {
            int height = 6 + random.nextInt(9);
            boolean thick = random.nextBoolean();
            int placed = 0;
            for (int i = 0; i < height; ++i)
            {
                int y = groundY + 1 + i;
                if (thick)
                {
                    for (int a = 0; a < 2; ++a)
                    {
                        for (int b = 0; b < 2; ++b)
                        {
                            pos.set(wx + a, y, wz + b);
                            surface.setBlock(pos, palette.deep(), 2);
                            placed++;
                        }
                    }
                }
                else
                {
                    pos.set(wx, y, wz);
                    surface.setBlock(pos, palette.surface(), 2);
                    placed++;
                }
            }
            return placed;
        }

        // a rounded boulder half-sitting on the ground, a rock-mix blob.
        private int buildBoulder(int wx, int groundY, int wz, RandomSource random)
        {
            int r = 2 + random.nextInt(2);
            int cy = groundY + r;
            int placed = 0;
            for (int ddx = -r; ddx <= r; ++ddx)
            {
                for (int ddy = -r; ddy <= r; ++ddy)
                {
                    for (int ddz = -r; ddz <= r; ++ddz)
                    {
                        if (ddx * ddx + ddy * ddy + ddz * ddz > r * r)
                        {
                            continue;
                        }
                        int y = cy + ddy;
                        if (y <= groundY)
                        {
                            continue;
                        }
                        pos.set(wx + ddx, y, wz + ddz);
                        surface.setBlock(pos, random.nextInt(3) == 0 ? palette.subsurface() : palette.deep(), 2);
                        placed++;
                    }
                }
            }
            return placed;
        }

        // a cluster of amethyst spikes on a calcite base, the "crystal formation" of a stony alien world.
        private int buildCrystal(int wx, int groundY, int wz, RandomSource random)
        {
            int placed = 0;
            pos.set(wx, groundY, wz);
            surface.setBlock(pos, Blocks.CALCITE.defaultBlockState(), 2);
            placed++;
            int spikes = 2 + random.nextInt(3);
            for (int s = 0; s < spikes; ++s)
            {
                int ox = random.nextInt(3) - 1;
                int oz = random.nextInt(3) - 1;
                int spikeHeight = 2 + random.nextInt(3);
                for (int i = 0; i < spikeHeight; ++i)
                {
                    pos.set(wx + ox, groundY + 1 + i, wz + oz);
                    surface.setBlock(pos, Blocks.AMETHYST_BLOCK.defaultBlockState(), 2);
                    placed++;
                }
            }
            return placed;
        }

        // place ONE work-unit of the structure at one structure-site cell, returning an approximate block cost for the
        // budget, and set structCellDone to whether the cell is now fully placed (the caller advances the ring cursor only
        // then). The kind is a pure function of the theme: NAMEK grows an authored DMZ Namek village, STONY and OVERWORLD
        // grow a procedural Saibamen dirt hut, and every other theme gets nothing (NETHER/END are left hostile and empty,
        // and NAMEK is reserved for the villages). A hut is one unit and finishes its cell in one call; a village yields
        // between houses (see below). The occupancy roll, the jittered site and the pick RandomSource are all hashed off
        // the seed and the cell, so the same planet always grows the identical structures.
        private int placeStructAtSite(int cellX, int cellZ)
        {
            structCellDone = true;
            if (!structuresOn)
            {
                return 0;
            }
            if (!SurfaceStamp.structColumnInCell(params, cellX, cellZ, structScratch, poolScratch))
            {
                return 0;
            }
            int gx = structScratch[0];
            int gz = structScratch[1];
            int capY = structScratch[2];
            if (theme == Theme.NAMEK)
            {
                return buildVillageHouse(cx + gx, capY, cz + gz, gx, gz);
            }
            RandomSource random = RandomSource.create(hash(seed, gx, gz, STRUCT_PICK_SALT));
            return buildHut(cx + gx, capY, cz + gz, random);
        }

        // place the NEXT house of a Namek village and yield, so a compact cluster is built one house per budget iteration
        // rather than a whole village (its several pad flattens plus template placements) in a single loop iteration,
        // which was the spike that stalled a tick. A house is an indivisible unit (never split), so yielding between
        // houses is safe. Determinism is preserved: the RandomSource is seeded from the cell's jittered site position (not
        // from loop timing), created lazily on the first house and carried in villageRandom across the houses, so it is
        // consumed in the identical order the old single-call village used and picks the identical houses/rotations. Sets
        // structCellDone false until the last slot so the ring cursor holds on this cell across ticks. Each house is placed
        // as one atomic template; a house whose template fails to resolve simply leaves its slot empty (no orphan pad).
        private int buildVillageHouse(int wx, int groundY, int wz, int gx, int gz)
        {
            if (villageRandom == null)
            {
                villageRandom = RandomSource.create(hash(seed, gx, gz, STRUCT_PICK_SALT));
                villageSlot = 0;
            }
            int[] slot = VILLAGE_SLOTS[villageSlot];
            ResourceLocation house = NAMEK_HOUSES[villageRandom.nextInt(NAMEK_HOUSES.length)];
            int placed = placeHouse(house, wx + slot[0], groundY, wz + slot[1],
                    Rotation.getRandom(villageRandom), villageRandom);
            villageSlot++;
            if (villageSlot >= VILLAGE_SLOTS.length)
            {
                // village complete: clear the in-flight state and let the cursor move to the next cell.
                villageRandom = null;
                villageSlot = 0;
                structCellDone = true;
            }
            else
            {
                structCellDone = false;
            }
            return placed;
        }

        // place one authored house template, centred on its slot with its floor at groundY, its footprint flattened
        // first. Returns the pad cost plus a flat per-house estimate, or 0 if the template does not resolve (nothing is
        // flattened in that case, so no orphan pad). The rotation is applied through the place settings and the footprint
        // is read back from the transformed bounding box, so the pad always matches the rotated building.
        private int placeHouse(ResourceLocation id, int slotX, int groundY, int slotZ, Rotation rot, RandomSource random)
        {
            StructureTemplate template = loadTemplate(id);
            if (template == null)
            {
                return 0;
            }
            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setRotation(rot)
                    .setMirror(Mirror.NONE)
                    .setIgnoreEntities(true)
                    .addProcessor(JigsawReplacementProcessor.INSTANCE);
            // centre the transformed template on the slot with its bottom layer at groundY. The local box (placed at the
            // origin) gives the transformed extents; shifting by its centre puts the building's middle on the slot.
            BoundingBox local = template.getBoundingBox(settings, BlockPos.ZERO);
            int bcx = (local.minX() + local.maxX()) / 2;
            int bcz = (local.minZ() + local.maxZ()) / 2;
            BlockPos origin = new BlockPos(slotX - bcx, groundY - local.minY(), slotZ - bcz);
            BoundingBox box = template.getBoundingBox(settings, origin);
            int pad = flattenPad(box.minX(), box.minZ(), box.maxX(), box.maxZ(), groundY);
            boolean ok;
            try
            {
                ok = template.placeInWorld(surface, origin, origin, settings, random, 2);
            }
            catch (Throwable t)
            {
                logMissingOnce(id.toString(), t);
                return pad;
            }
            return ok ? pad + HOUSE_COST_ESTIMATE : pad;
        }

        // load a StructureTemplate by resource location through the vanilla structure manager. A missing template (DMZ
        // asset renamed/removed) or any lookup failure returns null, logged once, so the caller skips it and never
        // crashes or half-places.
        private StructureTemplate loadTemplate(ResourceLocation id)
        {
            try
            {
                StructureTemplateManager manager = server.getStructureManager();
                Optional<StructureTemplate> opt = manager.get(id);
                if (opt.isPresent())
                {
                    return opt.get();
                }
            }
            catch (Throwable t)
            {
                logMissingOnce(id.toString(), t);
                return null;
            }
            logMissingOnce(id.toString(), null);
            return null;
        }

        // flatten a rectangular pad for a structure: clear everything above the floor line (removing any terrain hill or
        // tree canopy that would overhang the building) and bridge any dip below the floor with subsurface (so the
        // building never floats over lower ground). Returns the number of blocks changed. The pad is exactly the
        // structure's footprint; village houses never share columns, so flattening one pad never touches another.
        private int flattenPad(int minX, int minZ, int maxX, int maxZ, int groundY)
        {
            int placed = 0;
            for (int x = minX; x <= maxX; ++x)
            {
                for (int z = minZ; z <= maxZ; ++z)
                {
                    for (int y = groundY + 1; y <= groundY + PAD_CLEAR_HEIGHT; ++y)
                    {
                        pos.set(x, y, z);
                        if (!surface.getBlockState(pos).isAir())
                        {
                            surface.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                            placed++;
                        }
                    }
                    for (int y = groundY; y >= groundY - PAD_FILL_DEPTH; --y)
                    {
                        pos.set(x, y, z);
                        BlockState here = surface.getBlockState(pos);
                        if (here.isAir() || !here.getFluidState().isEmpty())
                        {
                            surface.setBlock(pos, palette.subsurface(), 2);
                            placed++;
                        }
                    }
                }
            }
            return placed;
        }

        // build a small procedural Saibamen dirt hut in the same hand-built idiom as the stony rock decor: a flattened
        // pad, a rough coarse-dirt wall ring with a one-wide doorway, and a low dirt roof. All solid blocks, so no fluid
        // ticks, and the pad flatten clears any tree the vegetation phase grew on the spot.
        private int buildHut(int wx, int groundY, int wz, RandomSource random)
        {
            int r = 3;
            int placed = flattenPad(wx - r - 1, wz - r - 1, wx + r + 1, wz + r + 1, groundY);
            BlockState wall = Blocks.COARSE_DIRT.defaultBlockState();
            BlockState roof = Blocks.DIRT.defaultBlockState();
            int wallHeight = 3;
            int doorDir = random.nextInt(4);
            for (int i = 0; i < wallHeight; ++i)
            {
                int y = groundY + 1 + i;
                for (int ddx = -r; ddx <= r; ++ddx)
                {
                    for (int ddz = -r; ddz <= r; ++ddz)
                    {
                        int distSq = ddx * ddx + ddz * ddz;
                        // a one-block-thick ring: inside the inner circle is open air, outside the outer circle is nothing.
                        if (distSq < (r - 1) * (r - 1) || distSq > r * r)
                        {
                            continue;
                        }
                        // leave a two-high doorway open on one side.
                        if (i < 2 && isDoorway(ddx, ddz, r, doorDir))
                        {
                            continue;
                        }
                        pos.set(wx + ddx, y, wz + ddz);
                        surface.setBlock(pos, wall, 2);
                        placed++;
                    }
                }
            }
            int roofY = groundY + 1 + wallHeight;
            for (int ddx = -r; ddx <= r; ++ddx)
            {
                for (int ddz = -r; ddz <= r; ++ddz)
                {
                    if (ddx * ddx + ddz * ddz > r * r)
                    {
                        continue;
                    }
                    pos.set(wx + ddx, roofY, wz + ddz);
                    surface.setBlock(pos, roof, 2);
                    placed++;
                }
            }
            pos.set(wx, roofY + 1, wz);
            surface.setBlock(pos, roof, 2);
            placed++;
            placed += plantHutSeeds(wx, groundY, wz, random);
            return placed;
        }

        // interior floor offsets inside a hut (all columns with dx^2 + dz^2 < (r-1)^2 for r = 3, i.e. the open circle
        // the wall ring encloses), ordered so index 0 is the centre. Used to place saibaman seeds on the hut floor.
        private static final int[][] HUT_INTERIOR_OFFSETS = {
                {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };

        // the DragonMineZ rocky_dirt a saibaman crop must sit on (its canSurvive gate). Placed as the floor block under
        // any seed so the crop survives on a hut floor that would otherwise be pad subsurface.
        private static final ResourceLocation ROCKY_DIRT_ID = new ResourceLocation("dragonminez", "rocky_dirt");

        // maybe seed a hut with saibaman crops: an independent 33% chance of ONE growing seed at the centre (you tend it
        // into a pet), and an independent 33% chance of 1..3 harvestable seeds around the floor (you break them for the
        // seed item). Both rolls use the hut's own deterministic RandomSource (consumed AFTER the doorway roll, so hut
        // shells are unchanged), so a given planet always grows the identical hut contents. Each crop is placed on a
        // rocky_dirt floor block with flag 2 (no neighbour update, so the crop is not broken as it is written), age 0.
        private int plantHutSeeds(int wx, int groundY, int wz, RandomSource random)
        {
            Block rockyDirt = ForgeRegistries.BLOCKS.getValue(ROCKY_DIRT_ID);
            Block cropBlock = SaibamanCropRegistry.SAIBAMAN_CROP.get();
            if (rockyDirt == null || cropBlock == null)
            {
                return 0;
            }
            BlockState rockyState = rockyDirt.defaultBlockState();
            BlockState cropState = cropBlock.defaultBlockState().setValue(SaibamanCropBlock.AGE, 0);

            boolean planted = random.nextDouble() < hutSeedPlantedChance;
            boolean harvest = random.nextDouble() < hutSeedHarvestChance;
            int placed = 0;
            // index 0 is the centre: reserved for the planted seed when that roll hits.
            if (planted)
            {
                placed += placeSeed(wx, groundY, wz, rockyState, cropState);
            }
            if (harvest)
            {
                int count = 1 + random.nextInt(3);
                int start = planted ? 1 : 0;
                for (int i = start; i < HUT_INTERIOR_OFFSETS.length && count > 0; ++i)
                {
                    int[] off = HUT_INTERIOR_OFFSETS[i];
                    placed += placeSeed(wx + off[0], groundY, wz + off[1], rockyState, cropState);
                    count--;
                }
            }
            return placed;
        }

        // place one saibaman crop: rocky_dirt at the floor line and the crop one above it. Flag 2 (no neighbour update)
        // so the crop's support check is not fired while it is being written.
        private int placeSeed(int wx, int groundY, int wz, BlockState rockyState, BlockState cropState)
        {
            pos.set(wx, groundY, wz);
            surface.setBlock(pos, rockyState, 2);
            pos.set(wx, groundY + 1, wz);
            surface.setBlock(pos, cropState, 2);
            return 2;
        }

        // whether a ring column faces the hut's doorway side, so the wall there is left open for a two-high entrance.
        private static boolean isDoorway(int ddx, int ddz, int r, int doorDir)
        {
            switch (doorDir)
            {
                case 0:
                    return ddz == 0 && ddx >= r - 1;
                case 1:
                    return ddz == 0 && ddx <= -(r - 1);
                case 2:
                    return ddx == 0 && ddz >= r - 1;
                default:
                    return ddx == 0 && ddz <= -(r - 1);
            }
        }

        // log a missing/failed structure template once. A null throwable is a plain "not found"; a non-null one is a
        // placement failure with the cause attached.
        private static void logMissingOnce(String id, Throwable t)
        {
            if (LOGGED_MISSING_TEMPLATES.add(id))
            {
                if (t != null)
                {
                    LoggingHandler.sulog.warn(
                            "[SurfaceStamp] Structure template {} failed to place; skipping it on generated planets.",
                            id, t);
                }
                else
                {
                    LoggingHandler.sulog.warn(
                            "[SurfaceStamp] Structure template {} not found; skipping it on generated planets.", id);
                }
            }
        }

        // resolve a configured feature by id, cached per stamp. A null cache entry marks an id that resolved to nothing,
        // so a missing id is only looked up once. Any lookup failure degrades to null (the caller then places nothing).
        private ConfiguredFeature<?, ?> configuredFeature(String id)
        {
            if (featureCache.containsKey(id))
            {
                return featureCache.get(id);
            }
            ConfiguredFeature<?, ?> cf = null;
            try
            {
                Registry<ConfiguredFeature<?, ?>> reg =
                        server.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
                cf = reg.get(new ResourceLocation(id));
            }
            catch (Throwable t)
            {
                cf = null;
            }
            featureCache.put(id, cf);
            return cf;
        }

        // reusable scratch for poolInCell so basinFloorAt (called per column, nine times) allocates nothing. Safe as a
        // single-threaded task field: the stamp runs only on the server thread.
        private final int[] poolScratch = new int[4];
        // reusable out arrays for the shared veg/structure site resolvers, distinct from poolScratch (which they use
        // internally for the basin gate) so an out value is never clobbered by the basin lookup inside the same call.
        private final int[] vegScratch = new int[3];
        private final int[] structScratch = new int[4];

        // the stamp finished: record the generated flag (ONLY now, on genuine completion) and fire the deferred
        // callbacks. Each callback is guarded so one throwing does not strand the others or leave the task un-removed.
        private void complete()
        {
            // belt and braces: resend any chunk still pending (a rim chunk the ring sweep never fully covered, plus any
            // chunk a later phase re-touched with flag 0), so the client's copy is complete before we mark it generated.
            flushAllPendingChunks();
            ACTIVE.remove(key, this);
            store.markGenerated();
            for (Runnable r : onComplete)
            {
                try
                {
                    r.run();
                }
                catch (Throwable t)
                {
                    LoggingHandler.sulog.warn("[SurfaceStamp] Deferred post-stamp task failed for {}.",
                            key, t);
                }
            }
            onComplete.clear();
        }

        @Override
        public boolean editsBlocks()
        {
            return true;
        }

        // players currently held on a planet whose surface is still stamping, UUID -> hold state. Populated by the shared
        // FreezeTask (any non-spectator standing on an ACTIVE planet's cell) and each held player is released the instant
        // their planet leaves ACTIVE (stamp done, aborted, or cleared on restart), on a safety timeout, or when they
        // disconnect / leave the surface. In-memory only, cleared on server start (clearAll), so nothing survives a restart.
        private static final ConcurrentHashMap<UUID, Hold> HELD = new ConcurrentHashMap<>();
        // whether the single shared FreezeTask is currently scheduled on the tick registry. Reset by the task itself when
        // it self-removes (no planet stamping, nobody held) and by clearAll on server start.
        private static final AtomicBoolean FREEZE_SCHEDULED = new AtomicBoolean(false);
        // hard cap on how long a player may be held, so a wedged stamp can NEVER soft-lock anyone: they are released after
        // this even if generation has not finished. Well beyond the wall-clock of the largest planet at the default budget.
        private static final int MAX_FREEZE_TICKS = 3600;   // 3 minutes
        // how far, in blocks squared, a held player may drift before being pulled back to their anchor. Small enough to
        // read as frozen, but a perfectly still player triggers no correction packet at all.
        private static final double FREEZE_LEASH_SQ = 0.35 * 0.35;

        // one held player's anchor (their landing spot), the stamp key they are held for, and the tick they were first
        // held, for the timeout backstop.
        private record Hold(String key, double x, double y, double z, long startTick)
        {
        }

        static int stampPercent(String key)
        {
            StampTask task = ACTIVE.get(key);
            return task == null ? 100 : Math.round(task.progress() * 100f);
        }

        // ensure the single shared freeze worker is running. Called from begin, so it starts on the first stamp that
        // freezes players and self-removes once there is nothing left to hold.
        private static void ensureFreezeTask()
        {
            if (FREEZE_SCHEDULED.compareAndSet(false, true))
            {
                TaskRegistry.schedule(new FreezeTask());
            }
        }

        // the shared server-tick worker that holds players on stamping planets in place and releases them the moment their
        // planet's stamp is no longer active. Read-only over blocks, so it self-limits by holding only the few players on
        // stamping planets and does not ride the block-task throttle. Fully defensive: any failure is swallowed so it can
        // never wedge the tick loop, and it self-removes when idle.
        static final class FreezeTask implements TaskRegistry.TickTask
        {
            @Override
            public boolean tick()
            {
                try
                {
                    return run();
                }
                catch (Throwable t)
                {
                    if (STAMP_WARNED.compareAndSet(false, true))
                    {
                        LoggingHandler.sulog.warn("[SurfaceStamp] Surface-gen freeze tick failed; players will still be "
                                + "released by the stamp finishing or the safety timeout.", t);
                    }
                    return false;
                }
            }

            private boolean run()
            {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server == null || (ACTIVE.isEmpty() && HELD.isEmpty()))
                {
                    HELD.clear();
                    FREEZE_SCHEDULED.set(false);
                    return true;   // nothing to do this session yet, or all done: self-remove until the next landing.
                }
                long now = server.getTickCount();

                // enrol: freeze any player the stamp's FreezeArea holds that is not already held, in each active stamp's
                // OWN level (the planet path scans the surface dimension on a cell test, byte-identical to before; a
                // general stamp scans its own dimension on a radius test). This also catches a SECOND player who lands on
                // the same stamp mid-build: they are held and released with the rest.
                for (StampTask task : ACTIVE.values())
                {
                    if (!task.freezeEnabled || task.surface == null)
                    {
                        continue;
                    }
                    for (ServerPlayer player : task.surface.players())
                    {
                        if (HELD.containsKey(player.getUUID()))
                        {
                            continue;
                        }
                        if (!task.freezeArea.holds(player))
                        {
                            continue;
                        }
                        HELD.put(player.getUUID(),
                                new Hold(task.key, player.getX(), player.getY(), player.getZ(), now));
                    }
                }

                // process every held player: hold them at their anchor, or release them.
                Iterator<Map.Entry<UUID, Hold>> it = HELD.entrySet().iterator();
                while (it.hasNext())
                {
                    Map.Entry<UUID, Hold> entry = it.next();
                    Hold hold = entry.getValue();
                    ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                    if (player == null)
                    {
                        it.remove();   // disconnected: nothing survives a logout.
                        continue;
                    }
                    StampTask task = ACTIVE.get(hold.key());
                    if (task == null)
                    {
                        release(player);   // stamp done, aborted, or cleared on restart: let them move.
                        it.remove();
                        continue;
                    }
                    if (now - hold.startTick() > MAX_FREEZE_TICKS)
                    {
                        release(player);   // safety timeout: never soft-lock, even if the stamp somehow wedged.
                        it.remove();
                        continue;
                    }
                    if (player.level() != task.surface)
                    {
                        it.remove();   // no longer in the stamp's level (an admin moved them, say): stop holding.
                        continue;
                    }
                    holdInPlace(player, hold);
                }

                if (ACTIVE.isEmpty() && HELD.isEmpty())
                {
                    FREEZE_SCHEDULED.set(false);
                    return true;
                }
                return false;
            }

            // pin the player at their anchor and show the action-bar progress line. Zeroing momentum each tick keeps ki
            // flight from building up, and a pull-back only when they drift past the leash avoids spamming teleports while
            // they stand still. Skills are untouched (movement-only), matching the raid-boss transform rooting.
            private void holdInPlace(ServerPlayer player, Hold hold)
            {
                player.setDeltaMovement(0.0, 0.0, 0.0);
                player.hasImpulse = true;
                player.resetFallDistance();
                double ddx = player.getX() - hold.x();
                double ddy = player.getY() - hold.y();
                double ddz = player.getZ() - hold.z();
                if (ddx * ddx + ddy * ddy + ddz * ddz > FREEZE_LEASH_SQ)
                {
                    // connection.teleport sends a position correction the client honours, so a walk or a ki-flight nudge is
                    // pulled straight back; a still player never trips this, so it costs nothing while they wait.
                    player.connection.teleport(hold.x(), hold.y(), hold.z(), player.getYRot(), player.getXRot());
                }
                int percent = stampPercent(hold.key());
                player.displayClientMessage(Component.translatable(
                        "message.dmz_ragnarok.core.space_generating", percent), true);
            }

            // clear the progress action bar with a short confirmation when a held player is released.
            private void release(ServerPlayer player)
            {
                player.setDeltaMovement(0.0, 0.0, 0.0);
                player.resetFallDistance();
                player.displayClientMessage(Component.translatable(
                        "message.dmz_ragnarok.core.space_generated_ready"), true);
            }

            @Override
            public boolean editsBlocks()
            {
                return false;
            }
        }
    }

    // the wobbled rim radius for one column of a planet, the SINGLE source of truth for where the solid terrain ends.
    // The salvage scan reads this so it never reads columns beyond the terrain edge, and it follows the exact irregular
    // outline the stamp built. dx/dz are column offsets from the cell centre, matching the stamp loop. The radius is the
    // STAMPED half (falling back to the derived half for a fresh planet), so the rim tracks the size the terrain was
    // actually built at rather than a re-derived one, keeping it byte-identical to what was stamped.
    public static double rimRadius(MinecraftServer server, String planetId, int dx, int dz)
    {
        long seed = GeneratedPlanets.hashOf(planetId + "#terrain");
        int radius = GeneratedPlanetClaims.stampedSizeForId(server, planetId) / 2;
        return rimRadius(seed, radius, dx, dz);
    }

    // the shared rim formula, taking the already-derived terrain seed and integer half-radius. Both the stamp (per
    // column, inside stampSurface) and the public rimRadius above call THIS, so there is one implementation of the rim,
    // not two copies that merely agree today. Do not inline it back into either caller.
    static double rimRadius(long seed, int radius, int dx, int dz)
    {
        return radius * (1.0 - EDGE_WOBBLE * edgeWobble(seed, dx, dz));
    }

    // the conservative inner radius a basin disc must fit entirely inside of (the inward-margin rule), and the matching
    // inner bound a structure footprint must stay inside of. Both are pure functions of the stamped half, exposed so the
    // oracle reconstructs them from the persisted size instead of re-deriving the constants. The stamp holds the same two
    // values as snapshot fields (basinInnerRadius / structInnerBound), computed from the identical expression.
    static double basinInnerRadius(int half)
    {
        return half * (1.0 - EDGE_WOBBLE) * (1.0 - EDGE_FEATHER) - BASIN_MARGIN;
    }

    static double structInnerBound(int half)
    {
        return half * (1.0 - EDGE_WOBBLE) * (1.0 - EDGE_FEATHER) - STRUCT_MARGIN;
    }

    // horizontal half-extent and vertical reach, from a vegetation site's ground block up, of the box the salvage oracle
    // treats as "originally generated" so a natural plant or rock-decor blob is never counted as a player build. Covers
    // the widest rock-decor blob (a boulder of radius 3) and the tallest (a karst pillar ~15 tall); a stray tall DMZ tree
    // block above this is at worst counted as a little free salvage, never a deletion. Starts at the ground block itself
    // because NETHER nylium and STONY calcite replace the surface block in place.
    private static final int VEG_FOOTPRINT_RADIUS = 3;
    private static final int VEG_FOOTPRINT_HEIGHT = 16;

    /**
     * An immutable snapshot of the geometry a planet's surface was stamped at: everything the deterministic terrain,
     * basin, vegetation and structure placement is a pure function of. The stamp builds one from its per-task snapshot and
     * the {@link NaturalSurfaceOracle} builds one from the persisted snapshot, so BOTH drive the shared static helpers
     * below off the identical numbers. Derived values (palette, liquid, bottomY, the two inner radii, hasLiquid, seaLevel)
     * are computed here exactly as the stamp computes them, so a consumer never re-derives the constants.
     */
    static final class SurfaceParams
    {
        final long seed;
        final int half;
        final int baseY;
        final Theme theme;
        final Palette palette;
        final BlockState liquid;
        final int deepDepth;
        final int bottomY;
        final boolean hasLiquid;
        final int seaLevel;
        final double basinFreq;
        final double basinInnerRadius;
        final boolean vegOn;
        final double vegDensity;
        final boolean structOn;
        final double villageFreq;
        final double hutFreq;
        final double structInnerBound;

        SurfaceParams(long seed, int half, int baseY, Theme theme, int deepDepth, boolean waterOn, int seaLevelOffset,
                double basinFreq, boolean vegOn, double vegDensity, boolean structOn, double villageFreq, double hutFreq)
        {
            this.seed = seed;
            this.half = half;
            this.baseY = baseY;
            this.theme = theme;
            this.palette = paletteFor(theme);
            this.liquid = liquidFor(theme);
            this.deepDepth = deepDepth;
            this.bottomY = baseY - (SUBSURFACE_DEPTH - 1) - deepDepth;
            this.hasLiquid = waterOn && this.liquid != null;
            this.seaLevel = baseY - seaLevelOffset;
            this.basinFreq = basinFreq;
            this.basinInnerRadius = basinInnerRadius(half);
            this.vegOn = vegOn;
            this.vegDensity = vegDensity;
            this.structOn = structOn;
            this.villageFreq = villageFreq;
            this.hutFreq = hutFreq;
            this.structInnerBound = structInnerBound(half);
        }
    }

    // whether a column offset (dx, dz) falls inside the disc AND inside that column's wobbled rim, the exact gate the
    // stamp uses. The salvage scan calls this to skip a void column without reading its whole vertical band.
    static boolean columnInside(SurfaceParams p, int dx, int dz)
    {
        double distSq = (double) dx * dx + (double) dz * dz;
        if (distSq > (double) p.half * p.half)
        {
            return false;
        }
        return Math.sqrt(distSq) <= rimRadius(p.seed, p.half, dx, dz);
    }

    // the block the stamp WOULD have placed at column offset (dx, dz), height y: the deterministic natural terrain, or
    // null where the natural surface is air/void. This mirrors placeColumn / placeBasinColumn (the solid stack) plus
    // fillPoolAtSite (a basin's water floor+1..seaLevel); it does NOT model vegetation or structures, which sit above the
    // cap and are excluded by the footprint tests instead. Keep this in lockstep with those three placement routines.
    static BlockState expectedTerrain(SurfaceParams p, int dx, int dz, int y, int[] scratch)
    {
        double distSq = (double) dx * dx + (double) dz * dz;
        if (distSq > (double) p.half * p.half)
        {
            return null;
        }
        double dist = Math.sqrt(distSq);
        double wobbled = rimRadius(p.seed, p.half, dx, dz);
        if (dist > wobbled)
        {
            return null;
        }
        int basinFloor = basinFloorAt(p, dx, dz, scratch);
        if (basinFloor != NO_BASIN)
        {
            if (y > basinFloor)
            {
                // above the lakebed: water up to sea level (a solid packed-ice "pool" on END), open air above that.
                if (p.liquid != null && y <= p.seaLevel)
                {
                    return p.liquid;
                }
                return null;
            }
            if (y > basinFloor - SUBSURFACE_DEPTH)
            {
                return p.palette.subsurface();
            }
            if (y >= p.bottomY)
            {
                return p.palette.deep();
            }
            return null;
        }
        int capY = capHeight(p.seed, dx, dz, dist, wobbled, p.baseY);
        if (y > capY)
        {
            return null;
        }
        if (y == capY)
        {
            return p.palette.surface();
        }
        int subFloor = capY - SUBSURFACE_DEPTH;
        if (y > subFloor)
        {
            return p.palette.subsurface();
        }
        if (y > subFloor - p.deepDepth)
        {
            return p.palette.deep();
        }
        return null;
    }

    // whether (dx, dz, y) lies inside the footprint box of a vegetation/rock-decor site the stamp actually placed. The
    // 3x3 cell neighbourhood covers every site whose jittered position plus footprint can reach the query column.
    static boolean inVegetationFootprint(SurfaceParams p, int dx, int dz, int y, int[] out, int[] scratch)
    {
        if (!p.vegOn)
        {
            return false;
        }
        int cellX = Math.floorDiv(dx, VEG_GRID);
        int cellZ = Math.floorDiv(dz, VEG_GRID);
        for (int ox = -1; ox <= 1; ++ox)
        {
            for (int oz = -1; oz <= 1; ++oz)
            {
                if (!vegColumnInCell(p, cellX + ox, cellZ + oz, out, scratch))
                {
                    continue;
                }
                int gx = out[0];
                int gz = out[1];
                int capY = out[2];
                if (Math.abs(dx - gx) <= VEG_FOOTPRINT_RADIUS && Math.abs(dz - gz) <= VEG_FOOTPRINT_RADIUS
                        && y >= capY && y <= capY + VEG_FOOTPRINT_HEIGHT)
                {
                    return true;
                }
            }
        }
        return false;
    }

    // whether (dx, dz, y) lies inside the footprint of a hut or village the stamp actually placed, including the pad band
    // it flattens/fills (groundY - PAD_FILL_DEPTH up to groundY + PAD_CLEAR_HEIGHT). The 3x3 cell neighbourhood covers
    // every site whose jittered position plus footprint can reach the query column.
    static boolean inStructureFootprint(SurfaceParams p, int dx, int dz, int y, int[] out, int[] scratch)
    {
        if (!p.structOn)
        {
            return false;
        }
        int cellX = Math.floorDiv(dx, STRUCT_GRID);
        int cellZ = Math.floorDiv(dz, STRUCT_GRID);
        for (int ox = -1; ox <= 1; ++ox)
        {
            for (int oz = -1; oz <= 1; ++oz)
            {
                if (!structColumnInCell(p, cellX + ox, cellZ + oz, out, scratch))
                {
                    continue;
                }
                int gx = out[0];
                int gz = out[1];
                int capY = out[2];
                int footprint = out[3];
                if (Math.abs(dx - gx) <= footprint && Math.abs(dz - gz) <= footprint
                        && y >= capY - PAD_FILL_DEPTH && y <= capY + PAD_CLEAR_HEIGHT)
                {
                    return true;
                }
            }
        }
        return false;
    }

    // resolve the pool (if any) for one basin-site cell into out[] = {centreDx, centreDz, radius, floorY}. The ONE
    // implementation of the pool maths, called by both the stamp (carve + fill) and the oracle. See the instance
    // StampTask.poolInCell doc for the geometry guarantees.
    static boolean poolInCell(SurfaceParams p, int cellX, int cellZ, int[] out)
    {
        if (!p.hasLiquid)
        {
            return false;
        }
        long h = hash(p.seed, cellX, cellZ, BASIN_SALT);
        if (unitAt(h, 24) >= p.basinFreq)
        {
            return false;
        }
        int r = BASIN_MIN_RADIUS + (int) (unitAt(h, 0) * (BASIN_MAX_RADIUS - BASIN_MIN_RADIUS + 1));
        int depth = BASIN_MIN_DEPTH + (int) (unitAt(h, 40) * (BASIN_MAX_DEPTH - BASIN_MIN_DEPTH + 1));
        int jx = (int) (unitAt(h, 12) * (2 * BASIN_JITTER + 1)) - BASIN_JITTER;
        int jz = (int) (unitAt(h, 36) * (2 * BASIN_JITTER + 1)) - BASIN_JITTER;
        int pcx = cellX * BASIN_SITE_GRID + BASIN_SITE_GRID / 2 + jx;
        int pcz = cellZ * BASIN_SITE_GRID + BASIN_SITE_GRID / 2 + jz;
        double dc = Math.sqrt((double) pcx * pcx + (double) pcz * pcz);
        if (dc < BASIN_CENTRE_CLEAR + r)
        {
            return false;
        }
        if (dc + r > p.basinInnerRadius)
        {
            return false;
        }
        out[0] = pcx;
        out[1] = pcz;
        out[2] = r;
        out[3] = p.seaLevel - depth;
        return true;
    }

    // the basin floor Y for column (dx, dz), or NO_BASIN. The ONE implementation, shared by the stamp and the oracle.
    static int basinFloorAt(SurfaceParams p, int dx, int dz, int[] scratch)
    {
        if (!p.hasLiquid)
        {
            return NO_BASIN;
        }
        int cellX = Math.floorDiv(dx, BASIN_SITE_GRID);
        int cellZ = Math.floorDiv(dz, BASIN_SITE_GRID);
        for (int ox = -1; ox <= 1; ++ox)
        {
            for (int oz = -1; oz <= 1; ++oz)
            {
                if (!poolInCell(p, cellX + ox, cellZ + oz, scratch))
                {
                    continue;
                }
                long ddx = dx - scratch[0];
                long ddz = dz - scratch[1];
                long r = scratch[2];
                if (ddx * ddx + ddz * ddz <= r * r)
                {
                    return scratch[3];
                }
            }
        }
        return NO_BASIN;
    }

    // whether a structure of the given footprint half-extent may sit at column offset (gx, gz). The ONE implementation of
    // the placement gate, shared by the stamp and the oracle. See StampTask.siteClear for the rules it enforces.
    static boolean siteClear(SurfaceParams p, int gx, int gz, int footprint, int[] scratch)
    {
        double dc = Math.sqrt((double) gx * gx + (double) gz * gz);
        if (dc + footprint > p.structInnerBound)
        {
            return false;
        }
        if (dc - footprint < STRUCT_CENTRE_CLEAR)
        {
            return false;
        }
        if (basinFloorAt(p, gx, gz, scratch) != NO_BASIN)
        {
            return false;
        }
        for (int i = 0; i < 8; ++i)
        {
            double a = i * (Math.PI / 4.0);
            int sx = gx + (int) Math.round(Math.cos(a) * footprint);
            int sz = gz + (int) Math.round(Math.sin(a) * footprint);
            if (basinFloorAt(p, sx, sz, scratch) != NO_BASIN)
            {
                return false;
            }
        }
        return true;
    }

    // resolve the vegetation site (if any) for one veg-site cell into out[] = {gx, gz, capY}, returning whether a feature
    // is placed there. The ONE implementation of the veg site gating, shared by the stamp and the oracle; the caller adds
    // its own vegOn guard (the stamp) or reads p.vegOn (the footprint test) before this.
    static boolean vegColumnInCell(SurfaceParams p, int cellX, int cellZ, int[] out, int[] scratch)
    {
        long h = hash(p.seed, cellX, cellZ, VEG_SALT);
        if (unitAt(h, 24) >= p.vegDensity)
        {
            return false;
        }
        int jx = (int) (unitAt(h, 0) * (2 * VEG_JITTER + 1)) - VEG_JITTER;
        int jz = (int) (unitAt(h, 40) * (2 * VEG_JITTER + 1)) - VEG_JITTER;
        int gx = cellX * VEG_GRID + VEG_GRID / 2 + jx;
        int gz = cellZ * VEG_GRID + VEG_GRID / 2 + jz;
        double distSq = (double) gx * gx + (double) gz * gz;
        if (distSq > (double) p.half * p.half)
        {
            return false;
        }
        double dist = Math.sqrt(distSq);
        double wobbled = rimRadius(p.seed, p.half, gx, gz);
        if (dist > wobbled * (1.0 - EDGE_FEATHER))
        {
            return false;
        }
        if (basinFloorAt(p, gx, gz, scratch) != NO_BASIN)
        {
            return false;
        }
        out[0] = gx;
        out[1] = gz;
        out[2] = capHeight(p.seed, gx, gz, dist, wobbled, p.baseY);
        return true;
    }

    // resolve the structure site (if any) for one structure-site cell into out[] = {gx, gz, capY, footprint}, returning
    // whether a hut/village is placed there. The ONE implementation of the structure site gating, shared by the stamp and
    // the oracle; the caller adds its own structuresOn guard (the stamp) or reads p.structOn (the footprint test) first.
    static boolean structColumnInCell(SurfaceParams p, int cellX, int cellZ, int[] out, int[] scratch)
    {
        boolean village = p.theme == Theme.NAMEK;
        boolean hut = p.theme == Theme.STONY || p.theme == Theme.OVERWORLD;
        if (!village && !hut)
        {
            return false;
        }
        double freq = village ? p.villageFreq : p.hutFreq;
        long h = hash(p.seed, cellX, cellZ, STRUCT_SALT);
        if (unitAt(h, 24) >= freq)
        {
            return false;
        }
        int jx = (int) (unitAt(h, 0) * (2 * STRUCT_JITTER + 1)) - STRUCT_JITTER;
        int jz = (int) (unitAt(h, 40) * (2 * STRUCT_JITTER + 1)) - STRUCT_JITTER;
        int gx = cellX * STRUCT_GRID + STRUCT_GRID / 2 + jx;
        int gz = cellZ * STRUCT_GRID + STRUCT_GRID / 2 + jz;
        int footprint = village ? VILLAGE_FOOTPRINT : HUT_FOOTPRINT;
        if (!siteClear(p, gx, gz, footprint, scratch))
        {
            return false;
        }
        double dist = Math.sqrt((double) gx * gx + (double) gz * gz);
        out[0] = gx;
        out[1] = gz;
        out[2] = capHeight(p.seed, gx, gz, dist, rimRadius(p.seed, p.half, gx, gz), p.baseY);
        out[3] = footprint;
        return true;
    }

    // the Y of the lowest solid block any column can place, computed from the same constants placeColumn uses so it can
    // never drift from the real terrain floor. A column with the minimum cap (baseY, when relief rounds to 0) places its
    // surface block at baseY, two subsurface blocks below it, then surfaceColumnDepth deep blocks, so the deepest block
    // sits at baseY - (SUBSURFACE_DEPTH - 1) - surfaceColumnDepth. The fall-catch in SpaceTravelModule is written against
    // this number rather than a magic constant. Reads the live config depth: the value only shifts on a config reload,
    // and the fall-catch is a coarse safety net (it teleports a player who is well below the terrain back to the centre),
    // so it does not need the per-stamp snapshot the terrain build uses. With SURFACE_Y = 96 and the default depth (128)
    // this is Y -34, well above the dimension floor at -64.
    public static int lowestTerrainY()
    {
        int baseY = (int) Math.floor(SurfaceDimension.SURFACE_Y);
        return baseY - (SUBSURFACE_DEPTH - 1) - PlanetSpawnModule.surfaceColumnDepth();
    }

    // place one column: the surface block at the cap, a subsurface band below it, then the deep body below that. Written
    // with TERRAIN_FLAG (bit 16 set), which skips the neighbour-shape pass and the client notify. A full stamp is ~26M
    // columns, so both the shape recompute (and the neighbour-chunk blocking loads it forces at column edges) and a
    // per-block client notify would stall the tick. onPlace/onRemove, heightmaps and lighting all run inside
    // LevelChunk.setBlockState regardless of this flag, so the terrain is byte-identical server-side; the finished chunk
    // is resent whole (StampTask.resendChunk) so the client still sees it.
    private static void placeColumn(ServerLevel surface, BlockPos.MutableBlockPos pos, int wx, int wz, int capY,
                                    Palette palette, int deepDepth)
    {
        // surface block at the cap.
        pos.set(wx, capY, wz);
        surface.setBlock(pos, palette.surface(), TERRAIN_FLAG);

        // subsurface (soil) band.
        int y = capY - 1;
        int subFloor = capY - SUBSURFACE_DEPTH;
        for (; y > subFloor; --y)
        {
            pos.set(wx, y, wz);
            surface.setBlock(pos, palette.subsurface(), TERRAIN_FLAG);
        }

        // deep body. This is the deepest and largest band, and it is exactly where stage 2b's basin carving and cave
        // generation should hook in: a basin carves down from sea level into this solid deep column and must leave at
        // least a block or two of deep body beneath it as its floor, and cave carving must skip any column directly under
        // a basin, so the deep band is intentionally left as one uniform solid fill here rather than pre-carved.
        int deepFloor = subFloor - deepDepth;
        for (; y > deepFloor; --y)
        {
            pos.set(wx, y, wz);
            surface.setBlock(pos, palette.deep(), TERRAIN_FLAG);
        }
    }

    // the cap Y for a column: a smoothed value-noise height in [baseY, baseY + MAX_RELIEF], feathered down toward baseY
    // as the column approaches the wobbled rim so the disc slopes off instead of ending in a cliff. Pure integer/double
    // arithmetic off the seed and the column offset, so it never drifts.
    static int capHeight(long seed, int dx, int dz, double dist, double wobbledRadius, int baseY)
    {
        double relief = reliefNoise(seed, dx, dz) * MAX_RELIEF;

        // feather the outer EDGE_FEATHER fraction of the disc down to the base, so the rim ramps off rather than
        // dropping as a wall. Inside the feather band relief is untouched; from there out it scales linearly to 0.
        double featherStart = wobbledRadius * (1.0 - EDGE_FEATHER);
        if (dist > featherStart && wobbledRadius > featherStart)
        {
            double t = (dist - featherStart) / (wobbledRadius - featherStart);
            relief *= Math.max(0.0, 1.0 - t);
        }
        return baseY + (int) Math.round(relief);
    }

    // smoothed 0..1 value-noise for a column: hash the four corners of the RELIEF_GRID lattice cell the column sits in
    // and bilinearly interpolate between them, so neighbouring columns share a slope (a rolling surface) rather than
    // each getting independent noise (which would just be static). Cheap: four hashes and a lerp per column.
    private static double reliefNoise(long seed, int dx, int dz)
    {
        int gx = Math.floorDiv(dx, RELIEF_GRID);
        int gz = Math.floorDiv(dz, RELIEF_GRID);
        double fx = (dx - gx * RELIEF_GRID) / (double) RELIEF_GRID;
        double fz = (dz - gz * RELIEF_GRID) / (double) RELIEF_GRID;

        double c00 = latticeHeight(seed, gx, gz);
        double c10 = latticeHeight(seed, gx + 1, gz);
        double c01 = latticeHeight(seed, gx, gz + 1);
        double c11 = latticeHeight(seed, gx + 1, gz + 1);

        // smoothstep the interpolation weights so the slope eases in/out at the lattice lines rather than kinking.
        double sx = smoothstep(fx);
        double sz = smoothstep(fz);
        double top = c00 + (c10 - c00) * sx;
        double bottom = c01 + (c11 - c01) * sx;
        return top + (bottom - top) * sz;
    }

    // 0..1 hashed height at a lattice corner, a pure function of the seed and the corner coordinates.
    private static double latticeHeight(long seed, int gx, int gz)
    {
        long h = hash(seed, gx, gz, 0x51D);
        return unit(h);
    }

    // classic smoothstep 3t^2 - 2t^3 on 0..1, so the interpolated relief eases at the lattice boundaries.
    private static double smoothstep(double t)
    {
        return t * t * (3.0 - 2.0 * t);
    }

    // 0..1 per-direction rim wobble for a column, quantised to a coarse direction grid so the wobble varies over
    // several blocks (a smooth bulge/dent), not per block. Mirrors AsteroidStamp.directionWobble in spirit.
    private static double edgeWobble(long seed, int dx, int dz)
    {
        int qx = Math.floorDiv(dx, 4);
        int qz = Math.floorDiv(dz, 4);
        return unit(hash(seed, qx, qz, 0xE3E));
    }

    // the surface theme for a planet id, from an independent window of its terrain hash, weighted by Theme.weight so the
    // plain themes stay common and the exotic ones are a find. A pure function of the id, so the theme is stable across
    // re-derivation and nothing about it is stored.
    // the surface theme for a planet id, exposed for the WRECK derivation so a destroyed world's debris can inherit its
    // ground's material family (a destroyed Namek leaves namek-ish rock, not generic grey). Pure (it only hashes the
    // id), so it is safe to call off the server thread, and it routes through the SAME themeFor the surface stamp uses,
    // so the wreck's theme can never drift from the ground the planet actually had.
    public static Theme surfaceThemeFor(String planetId)
    {
        return themeFor(planetId);
    }

    private static Theme themeFor(String planetId)
    {
        // a MOON is deliberately barren: force the STONY theme so a moon reads as grey rock (spires, boulders, crystals as
        // its only decor) and, crucially, gets NO water (STONY's liquid is null, so no basins are carved at all). A moon
        // should not have oceans. An already-stamped moon keeps its persisted theme through stampedThemeForId, so this
        // only steers a FRESHLY stamped moon; the existing Earth moon is untouched.
        if (MoonBody.isMoon(planetId))
        {
            return Theme.STONY;
        }
        // a SUPER dragon-ball body's surface is forced STONY too: the user wants a bare stone world under the god, not the
        // hash-derived (possibly Namek) theme a plain id would roll. Same short-circuit shape as the moon rule above; an
        // already-stamped super surface keeps its persisted theme through stampedThemeForId, so this only steers a fresh one.
        if (SuperPlanetPositions.isSuper(planetId))
        {
            return Theme.STONY;
        }
        int[] weights = themeWeights;
        int total = 0;
        for (int w : weights)
        {
            total += w;
        }
        if (total <= 0)
        {
            // every weight zeroed out: default to the plain green world rather than picking nothing.
            return Theme.OVERWORLD;
        }
        long h = GeneratedPlanets.hashOf(planetId + "#surfaceTheme");
        int roll = (int) (unit(h) * total);
        int acc = 0;
        for (Theme t : Theme.values())
        {
            acc += weights[t.ordinal()];
            if (roll < acc)
            {
                return t;
            }
        }
        // unreachable (roll < total), but fall back to the plain green world defensively.
        return Theme.OVERWORLD;
    }

    // the resolved three-layer palette for a theme.
    static Palette paletteFor(Theme theme)
    {
        switch (theme)
        {
            case OVERWORLD:
                return OVERWORLD_PALETTE;
            case NAMEK:
                return NAMEK_PALETTE;
            case NETHER:
                return NETHER_PALETTE;
            case END:
                return END_PALETTE;
            case KAIO:
                return KAIO_PALETTE;
            case OTHERWORLD:
                return OTHERWORLD_PALETTE;
            case STONY:
            default:
                return STONY_PALETTE;
        }
    }

    // resolve a DragonMineZ block by its registry path, returning its default state, or the given vanilla fallback's
    // default state if the id is not registered. dragonminez is a mandatory dependency so the ids normally exist, but a
    // lookup-with-fallback means a wrong or removed id degrades to sensible rock/soil rather than a crash or a silent
    // air block (which would be a hole in the world). Every id passed here was verified present in the 2.1.3 jar (both
    // as a blockstate asset AND registered in com.dragonminez.common.init.MainBlocks). Mirrors AsteroidStamp.dmz.
    private static BlockState dmz(String path, Block fallback)
    {
        Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("dragonminez", path));
        if (b == null || b == Blocks.AIR)
        {
            return fallback.defaultBlockState();
        }
        return b.defaultBlockState();
    }

    // splitmix64-style hash of the terrain seed and two lattice/direction coordinates plus a salt, so different uses
    // (relief lattice vs rim wobble) never correlate. Deterministic and JVM-stable (integer arithmetic only).
    private static long hash(long seed, int a, int b, int salt)
    {
        long z = seed
                ^ (a * 0x9E3779B97F4A7C15L)
                ^ (b * 0xC2B2AE3D27D4EB4FL)
                ^ (salt * 0x165667B19E3779F9L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // 0..1 double from a 64-bit hash's top 24-bit window.
    private static double unit(long h)
    {
        return ((h >>> 24) & 0xFFFFFFL) / 16777216.0;
    }

    // 0..1 double from an arbitrary 24-bit window of a hash, so one hash can feed several independent rolls (pool
    // occupancy, radius, depth, jitter) from different byte windows. The windows may overlap slightly, which only weakly
    // correlates those rolls; that is cosmetically irrelevant for pool shape, and determinism is unaffected.
    private static double unitAt(long h, int shift)
    {
        return ((h >>> shift) & 0xFFFFFFL) / 16777216.0;
    }
}
