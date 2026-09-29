package net.shurui.shuruisutilities.client.planet;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Makes DragonMineZ's Namek grass block, ajissa leaves and short grass tintable by swapping their baked block models
 * for SU models whose quads carry a tint index. This is the block half of the same trick
 * {@link net.shurui.shuruisutilities.client.corrupted.ShadowRadarModelBind} uses for the radar item: register SU models
 * for baking with {@link ModelEvent.RegisterAdditional}, then at {@link ModelEvent.ModifyBakingResult} replace DMZ's
 * baked models in place.
 *
 * <p>WHY SWAP INSTEAD OF INJECT. Baked quads carry their tint index from the moment they are baked, so a tint index
 * cannot be added to DMZ's stock quads after the fact without rebuilding every quad. Instead SU ships its own models
 * ({@code shuruisutilities:block/namek_grass_block_tinted}, {@code namek_ajissa_leaves_tinted},
 * {@code namek_grass_tinted}) whose JSON authors the tint index directly, so the quads bake with the index already set,
 * exactly the way vanilla grass and leaves do. The SU models point at SU's greyscale textures so the per-biome tint from
 * {@link NamekBlockTintColors} reads cleanly rather than compounding with a baked-in colour; the grass block's dirt base
 * and bottom reuse DMZ's own coloured dirt textures untinted, and the side grass is a derived overlay that is
 * transparent below the grass line so only the grass tints.
 *
 * <p>Each target block can back many {@link BlockState}s (leaves alone vary by distance, persistence and waterlogging),
 * and every state is keyed in the baking result by its own {@link net.minecraft.client.resources.model.ModelResourceLocation}.
 * We therefore walk the block's full state list and replace each state's entry, so no variant is missed.
 *
 * <p>FAIL SOFT AND LOUD-ONCE. If DragonMineZ is absent, or an SU model failed to bake, the baking result is left
 * untouched and one line is logged; the handler never throws, because a fault here would take the client down at
 * startup.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class NamekBlockModelBind
{
    // DMZ block id -> SU replacement model. The SU models are registered for baking below and keyed in the baking
    // result under these exact plain ResourceLocations.
    private static final String[][] BINDINGS =
    {
            { "dragonminez:namek_grass_block", "dmz_ragnarok:block/namek_grass_block_tinted" },
            { "dragonminez:namek_ajissa_leaves", "dmz_ragnarok:block/namek_ajissa_leaves_tinted" },
            { "dragonminez:namek_grass", "dmz_ragnarok:block/namek_grass_tinted" },

            // wood, dirt and the rocky set: simple cube_all swaps whose SU models author a tint index on every face.
            { "dragonminez:namek_ajissa_planks", "dmz_ragnarok:block/namek_ajissa_planks_tinted" },
            { "dragonminez:namek_dirt", "dmz_ragnarok:block/namek_dirt_tinted" },
            { "dragonminez:rocky_stone", "dmz_ragnarok:block/rocky_stone_tinted" },
            { "dragonminez:rocky_cobblestone", "dmz_ragnarok:block/rocky_cobblestone_tinted" },
            { "dragonminez:rocky_dirt", "dmz_ragnarok:block/rocky_dirt_tinted" },

            // flowers: each DMZ flower block is a single blockstate that random-picks among several models. The swap
            // collapses that to one SU tinted representative model per block (see the class note), so every instance of a
            // given flower shares one shape and orientation. The SU model is DMZ's own geometry with a tint index authored
            // onto every face and its textures repointed to SU greyscale copies.
            { "dragonminez:amaryllis_flower", "dmz_ragnarok:block/amaryllis_flower_tinted" },
            { "dragonminez:catharanthus_roseus_flower", "dmz_ragnarok:block/catharanthus_roseus_flower_tinted" },
            { "dragonminez:chrysanthemum_flower", "dmz_ragnarok:block/chrysanthemum_flower_tinted" },
            { "dragonminez:marigold_flower", "dmz_ragnarok:block/marigold_flower_tinted" },
            { "dragonminez:trillium_flower", "dmz_ragnarok:block/trillium_flower_tinted" },
            { "dragonminez:lotus_flower", "dmz_ragnarok:block/lotus_flower_tinted" },
            { "dragonminez:sacred_amaryllis_flower", "dmz_ragnarok:block/sacred_amaryllis_flower_tinted" },
            { "dragonminez:sacred_catharanthus_roseus_flower", "dmz_ragnarok:block/sacred_catharanthus_roseus_flower_tinted" },
            { "dragonminez:sacred_chrysanthemum_flower", "dmz_ragnarok:block/sacred_chrysanthemum_flower_tinted" },
            { "dragonminez:sacred_marigold_flower", "dmz_ragnarok:block/sacred_marigold_flower_tinted" },
            { "dragonminez:sacred_trillium_flower", "dmz_ragnarok:block/sacred_trillium_flower_tinted" },

            // sacred plant set: sacred leaves (item 1 of this batch), sacred short grass, sacred grass block, and both
            // ferns. Leaves parent minecraft:block/leaves and the grasses/ferns parent minecraft:block/tinted_cross
            // already author a tint index; the sacred grass block is a hand-authored cube with a tint index on the top
            // and all four sides (bottom kept as DMZ's own coloured dirt). Ferns are single-blockstate random-variant
            // plants, so like the flowers the swap collapses them to one representative model.
            { "dragonminez:namek_sacred_leaves", "dmz_ragnarok:block/namek_sacred_leaves_tinted" },
            { "dragonminez:namek_sacred_grass", "dmz_ragnarok:block/namek_sacred_grass_tinted" },
            { "dragonminez:namek_sacred_grass_block", "dmz_ragnarok:block/namek_sacred_grass_block_tinted" },
            { "dragonminez:namek_fern", "dmz_ragnarok:block/namek_fern_tinted" },
            { "dragonminez:sacred_fern", "dmz_ragnarok:block/sacred_fern_tinted" },
            { "dragonminez:namek_ajissa_sapling", "dmz_ragnarok:block/namek_ajissa_sapling_tinted" },
            { "dragonminez:namek_sacred_sapling", "dmz_ragnarok:block/namek_sacred_sapling_tinted" },

            // wood set: all cube_all in DMZ (logs carry no axis property there, so a single-model swap keeps them intact),
            // repointed to SU greyscale copies with a tint index on every face via tinted_cube_all.
            { "dragonminez:namek_ajissa_log", "dmz_ragnarok:block/namek_ajissa_log_tinted" },
            { "dragonminez:namek_ajissa_wood", "dmz_ragnarok:block/namek_ajissa_wood_tinted" },
            { "dragonminez:namek_stripped_ajissa_log", "dmz_ragnarok:block/namek_stripped_ajissa_log_tinted" },
            { "dragonminez:namek_stripped_ajissa_wood", "dmz_ragnarok:block/namek_stripped_ajissa_wood_tinted" },
            { "dragonminez:namek_sacred_planks", "dmz_ragnarok:block/namek_sacred_planks_tinted" },
            { "dragonminez:namek_sacred_log", "dmz_ragnarok:block/namek_sacred_log_tinted" },
            { "dragonminez:namek_sacred_wood", "dmz_ragnarok:block/namek_sacred_wood_tinted" },
            { "dragonminez:namek_stripped_sacred_log", "dmz_ragnarok:block/namek_stripped_sacred_log_tinted" },
            { "dragonminez:namek_stripped_sacred_wood", "dmz_ragnarok:block/namek_stripped_sacred_wood_tinted" },

            // stone, ores and mineral blocks: all cube_all single-variant, tinted with the shared rock tone per planet.
            { "dragonminez:namek_stone", "dmz_ragnarok:block/namek_stone_tinted" },
            { "dragonminez:namek_cobblestone", "dmz_ragnarok:block/namek_cobblestone_tinted" },
            { "dragonminez:namek_block", "dmz_ragnarok:block/namek_block_tinted" },
            { "dragonminez:namek_coal_ore", "dmz_ragnarok:block/namek_coal_ore_tinted" },
            { "dragonminez:namek_copper_ore", "dmz_ragnarok:block/namek_copper_ore_tinted" },
            { "dragonminez:namek_diamond_ore", "dmz_ragnarok:block/namek_diamond_ore_tinted" },
            { "dragonminez:namek_emerald_ore", "dmz_ragnarok:block/namek_emerald_ore_tinted" },
            { "dragonminez:namek_gold_ore", "dmz_ragnarok:block/namek_gold_ore_tinted" },
            { "dragonminez:namek_iron_ore", "dmz_ragnarok:block/namek_iron_ore_tinted" },
            { "dragonminez:namek_lapis_ore", "dmz_ragnarok:block/namek_lapis_ore_tinted" },
            { "dragonminez:namek_redstone_ore", "dmz_ragnarok:block/namek_redstone_ore_tinted" },
            { "dragonminez:namek_kikono_ore", "dmz_ragnarok:block/namek_kikono_ore_tinted" },
            { "dragonminez:gete_block", "dmz_ragnarok:block/gete_block_tinted" },
            { "dragonminez:gete_debris_ore", "dmz_ragnarok:block/gete_debris_ore_tinted" },

            // deepslate set (DEEPSLATE tint, a darker take on the rock tone): the base deepslate is DMZ's cube_column with
            // the same texture on end and side, so a single tinted cube_all swap covers all three axis variants without
            // any visible loss of rotation, and it also stitches the greyscale namek_deepslate sprite the stairs/slab/wall
            // wrappers below reuse. The eight deepslate ores are plain cube_all like the ordinary ores, tinted to match.
            { "dragonminez:namek_deepslate", "dmz_ragnarok:block/namek_deepslate_tinted" },
            { "dragonminez:namek_deepslate_coal_ore", "dmz_ragnarok:block/namek_deepslate_coal_ore_tinted" },
            { "dragonminez:namek_deepslate_copper_ore", "dmz_ragnarok:block/namek_deepslate_copper_ore_tinted" },
            { "dragonminez:namek_deepslate_diamond_ore", "dmz_ragnarok:block/namek_deepslate_diamond_ore_tinted" },
            { "dragonminez:namek_deepslate_emerald_ore", "dmz_ragnarok:block/namek_deepslate_emerald_ore_tinted" },
            { "dragonminez:namek_deepslate_gold_ore", "dmz_ragnarok:block/namek_deepslate_gold_ore_tinted" },
            { "dragonminez:namek_deepslate_iron_ore", "dmz_ragnarok:block/namek_deepslate_iron_ore_tinted" },
            { "dragonminez:namek_deepslate_lapis_ore", "dmz_ragnarok:block/namek_deepslate_lapis_ore_tinted" },
            { "dragonminez:namek_deepslate_redstone_ore", "dmz_ragnarok:block/namek_deepslate_redstone_ore_tinted" },
    };

    // Geometry-bearing blocks (stairs, slabs, walls, fences, gates, buttons, pressure plates) that share a tinted base
    // type. These CANNOT use the single-model swap above: each carries states whose rotation and shape are baked per
    // variant during blockstate resolution, so one model would flatten every orientation. Instead each state's own DMZ
    // baked model is wrapped by TintedRetexturedModel, which keeps DMZ's geometry and rotation verbatim and only repoints
    // the sprite to SU's greyscale copy (same texture path, dmz_ragnarok namespace) and forces a tint index. The greyscale
    // copy must already be stitched on the block atlas, which it is because the matching base cube (planks, stone,
    // cobblestone) is in BINDINGS above and references it. A per-biome BlockColor for each block below is registered in
    // NamekBlockTintColors so the forced tint index resolves to the wood or rock tint, matching the base block exactly.
    private static final String[] GEOMETRY_BLOCKS =
    {
            // ajissa wood set (WOOD tint): planks-textured stairs, slab (incl. the double-slab planks model), fence,
            // fence gate, button, pressure plate.
            "dragonminez:namek_ajissa_stairs",
            "dragonminez:namek_ajissa_slab",
            "dragonminez:namek_ajissa_fence",
            "dragonminez:namek_ajissa_fence_gate",
            "dragonminez:namek_ajissa_button",
            "dragonminez:namek_ajissa_pressure_plate",

            // sacred wood set (WOOD tint).
            "dragonminez:namek_sacred_stairs",
            "dragonminez:namek_sacred_slab",
            "dragonminez:namek_sacred_fence",
            "dragonminez:namek_sacred_fence_gate",
            "dragonminez:namek_sacred_button",
            "dragonminez:namek_sacred_pressure_plate",

            // namek stone / cobblestone set (ROCK tint): stairs, slab, wall.
            "dragonminez:namek_stone_stairs",
            "dragonminez:namek_stone_slab",
            "dragonminez:namek_stone_wall",
            "dragonminez:namek_cobblestone_stairs",
            "dragonminez:namek_cobblestone_slab",
            "dragonminez:namek_cobblestone_wall",

            // rocky set (ROCK tint): stairs, slab, wall for both rocky stone and rocky cobblestone.
            "dragonminez:rocky_stone_stairs",
            "dragonminez:rocky_stone_slab",
            "dragonminez:rocky_stone_wall",
            "dragonminez:rocky_cobblestone_stairs",
            "dragonminez:rocky_cobblestone_slab",
            "dragonminez:rocky_cobblestone_wall",

            // deepslate set (DEEPSLATE tint): stairs, slab, wall. All three reuse the base namek_deepslate texture, which
            // the base cube swap above already stitches onto the block atlas, so no stitch model is needed here.
            "dragonminez:namek_deepslate_stairs",
            "dragonminez:namek_deepslate_slab",
            "dragonminez:namek_deepslate_wall",

            // doors and trapdoors (WOOD tint). Unlike the rest of the wood set these carry their own door/trapdoor
            // textures (not the planks texture), so SU ships greyscale copies of those six textures and the stitch models
            // below force them onto the block atlas so the wrapper can repoint to them.
            "dragonminez:namek_ajissa_door",
            "dragonminez:namek_ajissa_trapdoor",
            "dragonminez:namek_sacred_door",
            "dragonminez:namek_sacred_trapdoor",
    };

    // SU greyscale textures that no tinted MODEL references directly (the door and trapdoor art). A texture only lands on
    // the block atlas when a baked model references it, so these tiny cube_all stitch models exist solely to stitch those
    // six greyscale copies; they are never assigned to a block or item, only registered for baking here so the wrapper's
    // sprite lookup for doors and trapdoors resolves.
    private static final String[] STITCH_MODELS =
    {
            "dmz_ragnarok:block/namek_ajissa_door_bottom_stitch",
            "dmz_ragnarok:block/namek_ajissa_door_top_stitch",
            "dmz_ragnarok:block/namek_ajissa_trapdoor_stitch",
            "dmz_ragnarok:block/namek_sacred_door_bottom_stitch",
            "dmz_ragnarok:block/namek_sacred_door_top_stitch",
            "dmz_ragnarok:block/namek_sacred_trapdoor_stitch",
    };

    private static final AtomicBoolean BOUND_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean DMZ_MISSING_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean SU_MISSING_LOGGED = new AtomicBoolean(false);

    private NamekBlockModelBind()
    {
    }

    // Register SU's tinted models for baking. Nothing else references them, so without this they would not be baked and
    // would be missing from the baking result, leaving the swap with nothing to install.
    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event)
    {
        for (String[] binding : BINDINGS)
        {
            event.register(new ResourceLocation(binding[1]));
        }
        for (String stitchModel : STITCH_MODELS)
        {
            event.register(new ResourceLocation(stitchModel));
        }
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event)
    {
        try
        {
            Map<ResourceLocation, BakedModel> models = event.getModels();
            for (String[] binding : BINDINGS)
            {
                bindOne(models, binding[0], binding[1]);
            }
            for (String geometryBlockId : GEOMETRY_BLOCKS)
            {
                wrapGeometry(models, geometryBlockId);
            }
        }
        catch (Throwable t)
        {
            // never crash the client during model baking; on any fault the blocks keep DMZ's stock models.
        }
    }

    private static void bindOne(Map<ResourceLocation, BakedModel> models, String dmzBlockId, String suModelId)
    {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(dmzBlockId));
        if (block == null)
        {
            if (DMZ_MISSING_LOGGED.compareAndSet(false, true))
            {
                LoggingHandler.sulog.info(
                        "[PlanetTint] " + dmzBlockId + " absent; tinted model swap skipped (DragonMineZ missing or the "
                                + "block renamed)");
            }
            return;
        }

        BakedModel su = models.get(new ResourceLocation(suModelId));
        if (su == null)
        {
            if (SU_MISSING_LOGGED.compareAndSet(false, true))
            {
                LoggingHandler.sulog.info(
                        "[PlanetTint] " + suModelId + " failed to bake; tint swap for " + dmzBlockId
                                + " skipped, DMZ model left untouched");
            }
            return;
        }

        // Replace every blockstate variant's baked model. DMZ's targets are simple variant models, and computing the
        // per-state key is the robust way to cover all variants (leaves alone have many).
        for (BlockState state : block.getStateDefinition().getPossibleStates())
        {
            models.put(BlockModelShaper.stateToModelLocation(state), su);
        }

        if (BOUND_LOGGED.compareAndSet(false, true))
        {
            LoggingHandler.sulog.info("[PlanetTint] tinted planet block models bound (grass, leaves, wood, dirt, rock, flowers)");
        }
    }

    // Wrap every blockstate variant of a geometry-bearing block with TintedRetexturedModel, keeping DMZ's shape and
    // rotation and only repointing the sprite to SU greyscale plus forcing a tint index. The #inventory item model uses a
    // different key and is left alone, so the block in hand keeps DMZ's own coloured look.
    private static void wrapGeometry(Map<ResourceLocation, BakedModel> models, String dmzBlockId)
    {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(dmzBlockId));
        if (block == null)
        {
            if (DMZ_MISSING_LOGGED.compareAndSet(false, true))
            {
                LoggingHandler.sulog.info(
                        "[PlanetTint] " + dmzBlockId + " absent; geometry tint wrap skipped (DragonMineZ missing or the "
                                + "block renamed)");
            }
            return;
        }

        for (BlockState state : block.getStateDefinition().getPossibleStates())
        {
            ResourceLocation key = BlockModelShaper.stateToModelLocation(state);
            BakedModel existing = models.get(key);
            if (existing == null || existing instanceof TintedRetexturedModel)
            {
                continue;
            }
            models.put(key, new TintedRetexturedModel(existing, 0));
        }
    }
}
