package net.shurui.dev.shuruis_dmz_dungeons.registry;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.dev.shuruis_dmz_dungeons.block.AdvancedSpawnerBlockEntity;
import net.shurui.dev.shuruis_dmz_dungeons.block.CrateBarrelBlockEntity;
import net.shurui.dev.shuruis_dmz_dungeons.block.CrateChestBlockEntity;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Shuruis_dmz_dungeons.MODID);

    public static final RegistryObject<BlockEntityType<AdvancedSpawnerBlockEntity>> ADVANCED_SPAWNER =
            BLOCK_ENTITIES.register("advanced_spawner", () ->
                    BlockEntityType.Builder.of(AdvancedSpawnerBlockEntity::new, ModBlocks.ADVANCED_SPAWNER.get())
                            .build(null));

    // the crate BEs carry no data of their own (rarity and metal are derived from position). They exist so the
    // crate can be ANIMATED by GeckoLib, which needs a block entity for its animation controller. Two types
    // because a BE type is bound to its block; both are the same implementation (CrateBlockEntity).
    public static final RegistryObject<BlockEntityType<CrateChestBlockEntity>> CRATE_CHEST =
            BLOCK_ENTITIES.register("crate_chest", () ->
                    BlockEntityType.Builder.of(CrateChestBlockEntity::new, ModBlocks.CRATE_CHEST.get())
                            .build(null));

    public static final RegistryObject<BlockEntityType<CrateBarrelBlockEntity>> CRATE_BARREL =
            BLOCK_ENTITIES.register("crate_barrel", () ->
                    BlockEntityType.Builder.of(CrateBarrelBlockEntity::new, ModBlocks.CRATE_BARREL.get())
                            .build(null));

    private ModBlockEntities() {
    }
}
