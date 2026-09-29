package net.shurui.shuruisutilities.client.planet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Registers per-biome tint handlers for DragonMineZ's Namek grass block, ajissa leaves and short grass, so their
 * greyscale-textured, tint-indexed models (see {@link NamekBlockModelBind}) take their colour from
 * {@link NamekBlockTints}. The grass block top face and side grass overlay and the short grass plant all use the GRASS
 * tint; the leaves use the FOLIAGE tint.
 *
 * <p>DMZ already tints only its SACRED_PLANET_GRASS_BLOCK by biome and never touches these three blocks, so there is no
 * competing handler to clobber. FAIL SOFT: if DragonMineZ is absent the blocks resolve to null and nothing registers.
 *
 * <p>The biome id is read from {@code Minecraft.getInstance().level.getBiome(pos)} rather than the render-thread
 * {@code BlockAndTintGetter} (which cannot resolve a biome id), which is why the handler keys off the client level. No
 * blending across neighbours, so biome edges are hard, which is fine for planet regions.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class NamekBlockTintColors
{
    private NamekBlockTintColors()
    {
    }

    @SubscribeEvent
    public static void onRegisterBlockColors(RegisterColorHandlersEvent.Block event)
    {
        BlockColor grassTint = (state, level, pos, tintIndex) -> NamekBlockTints.grass(biomeAt(pos));
        BlockColor foliageTint = (state, level, pos, tintIndex) -> NamekBlockTints.foliage(biomeAt(pos));
        BlockColor woodTint = (state, level, pos, tintIndex) -> NamekBlockTints.wood(biomeAt(pos));
        BlockColor dirtTint = (state, level, pos, tintIndex) -> NamekBlockTints.dirt(biomeAt(pos));
        BlockColor rockTint = (state, level, pos, tintIndex) -> NamekBlockTints.rock(biomeAt(pos));
        BlockColor deepslateTint = (state, level, pos, tintIndex) -> NamekBlockTints.deepslate(biomeAt(pos));
        BlockColor flowerTeal = (state, level, pos, tintIndex) -> NamekBlockTints.flowerTeal(biomeAt(pos));
        BlockColor flowerAmaryllis = (state, level, pos, tintIndex) -> NamekBlockTints.flowerAmaryllis(biomeAt(pos));
        BlockColor flowerLotus = (state, level, pos, tintIndex) -> NamekBlockTints.flowerLotus(biomeAt(pos));

        // Leaves carry TWO tint indices now: 0 is the leaf mass (FOLIAGE), 1 is the blossom overlay split out of the
        // texture so the blooms colour independently of the leaves. Ajissa and sacred each get their own blossom colour.
        BlockColor ajissaLeaves = (state, level, pos, tintIndex) -> tintIndex == 1
                ? NamekBlockTints.ajissaLeafFlower(biomeAt(pos))
                : NamekBlockTints.foliage(biomeAt(pos));
        BlockColor sacredLeaves = (state, level, pos, tintIndex) -> tintIndex == 1
                ? NamekBlockTints.sacredLeafFlower(biomeAt(pos))
                : NamekBlockTints.foliage(biomeAt(pos));

        registerFor(event, grassTint, "namek_grass_block");
        registerFor(event, grassTint, "namek_grass");
        registerFor(event, grassTint, "namek_sacred_grass_block");
        registerFor(event, grassTint, "namek_sacred_grass");
        registerFor(event, ajissaLeaves, "namek_ajissa_leaves");
        registerFor(event, sacredLeaves, "namek_sacred_leaves");
        registerFor(event, foliageTint, "namek_fern");
        registerFor(event, foliageTint, "sacred_fern");
        registerFor(event, foliageTint, "namek_ajissa_sapling");
        registerFor(event, foliageTint, "namek_sacred_sapling");

        registerFor(event, woodTint, "namek_ajissa_planks");
        registerFor(event, woodTint, "namek_ajissa_log");
        registerFor(event, woodTint, "namek_ajissa_wood");
        registerFor(event, woodTint, "namek_stripped_ajissa_log");
        registerFor(event, woodTint, "namek_stripped_ajissa_wood");
        registerFor(event, woodTint, "namek_sacred_planks");
        registerFor(event, woodTint, "namek_sacred_log");
        registerFor(event, woodTint, "namek_sacred_wood");
        registerFor(event, woodTint, "namek_stripped_sacred_log");
        registerFor(event, woodTint, "namek_stripped_sacred_wood");
        registerFor(event, dirtTint, "namek_dirt");
        registerFor(event, rockTint, "rocky_stone");
        registerFor(event, rockTint, "rocky_cobblestone");
        registerFor(event, rockTint, "rocky_dirt");
        registerFor(event, rockTint, "namek_stone");
        registerFor(event, rockTint, "namek_cobblestone");
        registerFor(event, rockTint, "namek_block");
        registerFor(event, rockTint, "namek_coal_ore");
        registerFor(event, rockTint, "namek_copper_ore");
        registerFor(event, rockTint, "namek_diamond_ore");
        registerFor(event, rockTint, "namek_emerald_ore");
        registerFor(event, rockTint, "namek_gold_ore");
        registerFor(event, rockTint, "namek_iron_ore");
        registerFor(event, rockTint, "namek_lapis_ore");
        registerFor(event, rockTint, "namek_redstone_ore");
        registerFor(event, rockTint, "namek_kikono_ore");
        registerFor(event, rockTint, "gete_block");
        registerFor(event, rockTint, "gete_debris_ore");

        // deepslate set (DEEPSLATE tint, a darker sibling of the rock tone): base block, its eight ores, and the
        // stairs/slab/wall geometry variants further below.
        registerFor(event, deepslateTint, "namek_deepslate");
        registerFor(event, deepslateTint, "namek_deepslate_coal_ore");
        registerFor(event, deepslateTint, "namek_deepslate_copper_ore");
        registerFor(event, deepslateTint, "namek_deepslate_diamond_ore");
        registerFor(event, deepslateTint, "namek_deepslate_emerald_ore");
        registerFor(event, deepslateTint, "namek_deepslate_gold_ore");
        registerFor(event, deepslateTint, "namek_deepslate_iron_ore");
        registerFor(event, deepslateTint, "namek_deepslate_lapis_ore");
        registerFor(event, deepslateTint, "namek_deepslate_redstone_ore");

        // Geometry-bearing variants (stairs, slabs, walls, fences, gates, buttons, pressure plates). Their baked models
        // are wrapped by TintedRetexturedModel (see NamekBlockModelBind) which forces tint index 0 onto every face, so a
        // single-colour handler per block colours them exactly like the base block: WOOD for the ajissa and sacred sets,
        // ROCK for the stone, cobblestone and rocky sets.
        registerFor(event, woodTint, "namek_ajissa_stairs");
        registerFor(event, woodTint, "namek_ajissa_slab");
        registerFor(event, woodTint, "namek_ajissa_fence");
        registerFor(event, woodTint, "namek_ajissa_fence_gate");
        registerFor(event, woodTint, "namek_ajissa_button");
        registerFor(event, woodTint, "namek_ajissa_pressure_plate");
        registerFor(event, woodTint, "namek_sacred_stairs");
        registerFor(event, woodTint, "namek_sacred_slab");
        registerFor(event, woodTint, "namek_sacred_fence");
        registerFor(event, woodTint, "namek_sacred_fence_gate");
        registerFor(event, woodTint, "namek_sacred_button");
        registerFor(event, woodTint, "namek_sacred_pressure_plate");
        registerFor(event, rockTint, "namek_stone_stairs");
        registerFor(event, rockTint, "namek_stone_slab");
        registerFor(event, rockTint, "namek_stone_wall");
        registerFor(event, rockTint, "namek_cobblestone_stairs");
        registerFor(event, rockTint, "namek_cobblestone_slab");
        registerFor(event, rockTint, "namek_cobblestone_wall");
        registerFor(event, rockTint, "rocky_stone_stairs");
        registerFor(event, rockTint, "rocky_stone_slab");
        registerFor(event, rockTint, "rocky_stone_wall");
        registerFor(event, rockTint, "rocky_cobblestone_stairs");
        registerFor(event, rockTint, "rocky_cobblestone_slab");
        registerFor(event, rockTint, "rocky_cobblestone_wall");
        registerFor(event, deepslateTint, "namek_deepslate_stairs");
        registerFor(event, deepslateTint, "namek_deepslate_slab");
        registerFor(event, deepslateTint, "namek_deepslate_wall");
        registerFor(event, woodTint, "namek_ajissa_door");
        registerFor(event, woodTint, "namek_ajissa_trapdoor");
        registerFor(event, woodTint, "namek_sacred_door");
        registerFor(event, woodTint, "namek_sacred_trapdoor");

        // flowers: amaryllis is lilac, the lotus is a teal-green pad, the rest share a Namek teal. All swing to the
        // planet colour on Vegeta and Beerus inside NamekBlockTints.
        registerFor(event, flowerAmaryllis, "amaryllis_flower");
        registerFor(event, flowerAmaryllis, "sacred_amaryllis_flower");
        registerFor(event, flowerLotus, "lotus_flower");
        registerFor(event, flowerTeal, "catharanthus_roseus_flower");
        registerFor(event, flowerTeal, "chrysanthemum_flower");
        registerFor(event, flowerTeal, "marigold_flower");
        registerFor(event, flowerTeal, "trillium_flower");
        registerFor(event, flowerTeal, "sacred_catharanthus_roseus_flower");
        registerFor(event, flowerTeal, "sacred_chrysanthemum_flower");
        registerFor(event, flowerTeal, "sacred_marigold_flower");
        registerFor(event, flowerTeal, "sacred_trillium_flower");
    }

    // Resolve the biome id at a world position from the client level. Returns null when there is no level or position
    // yet, or the biome has no registry key, in which case NamekBlockTints falls back to its Namek default. Block colour
    // handlers run on chunk-meshing worker threads, so the whole lookup is guarded: any fault reading the live level
    // off-thread falls back to the default tint rather than disturbing meshing.
    private static ResourceLocation biomeAt(BlockPos pos)
    {
        try
        {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || pos == null)
            {
                return null;
            }
            return mc.level.getBiome(pos).unwrapKey().map(k -> k.location()).orElse(null);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private static void registerFor(RegisterColorHandlersEvent.Block event, BlockColor handler, String dmzPath)
    {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("dragonminez", dmzPath));
        if (block == null)
        {
            LoggingHandler.sulog.info(
                    "[PlanetTint] dragonminez:" + dmzPath + " absent; per-biome tint for it skipped (DragonMineZ "
                            + "missing or the block renamed)");
            return;
        }
        event.register(handler, block);
    }
}
