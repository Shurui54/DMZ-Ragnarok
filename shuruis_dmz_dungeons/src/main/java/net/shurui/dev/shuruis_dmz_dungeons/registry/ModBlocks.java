package net.shurui.dev.shuruis_dmz_dungeons.registry;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.dev.shuruis_dmz_dungeons.block.AdvancedSpawnerBlock;
import net.shurui.dev.shuruis_dmz_dungeons.block.CrateBarrelBlock;
import net.shurui.dev.shuruis_dmz_dungeons.block.CrateChestBlock;

public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Shuruis_dmz_dungeons.MODID);

    // props roughly match minecraft:spawner (needs a pickaxe), no XP drop, invisible render
    public static final RegistryObject<AdvancedSpawnerBlock> ADVANCED_SPAWNER = BLOCKS.register("advanced_spawner",
            () -> new AdvancedSpawnerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(5.0f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    // the two dungeon crate blocks placed at generation (see CrateConversionTask). Wooden, chest-strength,
    // noOcclusion so the slightly-proud tinted latch/band overlay is never neighbour-culled. The four rarity
    // colours are a client-side tint on the TIER blockstate property, not separate blocks.
    public static final RegistryObject<CrateChestBlock> CRATE_CHEST = BLOCKS.register("crate_chest",
            () -> new CrateChestBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.5f)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));

    public static final RegistryObject<CrateBarrelBlock> CRATE_BARREL = BLOCKS.register("crate_barrel",
            () -> new CrateBarrelBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.5f)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));

    private ModBlocks() {
    }
}
