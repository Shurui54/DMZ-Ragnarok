package net.shurui.dev.sdu.registry;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.block.BarrierBlockEntity;
import net.shurui.dev.sdu.block.GravityChamberBlockEntity;

/**
 * The two data-carrying blocks from the shrine system: {@code gravity_chamber} (area TP multiplier + shared
 * pool) and {@code level_barrier} (per-block DMZ-level gate). Registered on the mod bus AFTER {@link ModBlocks}
 * because the block-entity types reference the block instances.
 */
public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, DmzNpc.MODID);

    public static final RegistryObject<BlockEntityType<GravityChamberBlockEntity>> GRAVITY_CHAMBER =
            BLOCK_ENTITIES.register("gravity_chamber", () -> BlockEntityType.Builder
                    .of(GravityChamberBlockEntity::new, ModBlocks.GRAVITY_CHAMBER.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<BarrierBlockEntity>> LEVEL_BARRIER =
            BLOCK_ENTITIES.register("level_barrier", () -> BlockEntityType.Builder
                    .of(BarrierBlockEntity::new, ModBlocks.LEVEL_BARRIER.get())
                    .build(null));

    private ModBlockEntities() {
    }
}
