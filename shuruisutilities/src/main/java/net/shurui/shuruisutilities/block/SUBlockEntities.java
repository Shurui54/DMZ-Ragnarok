package net.shurui.shuruisutilities.block;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.corrupted.CorruptedBallBlockEntity;
import net.shurui.shuruisutilities.corrupted.CorruptedBalls;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Block entity types for Shurui's Utilities (currently just the vertical end-portal renderer marker). */
public final class SUBlockEntities
{
    private SUBlockEntities() {}

    public static final DeferredRegister<BlockEntityType<?>> REGISTER =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<BlockEntityType<EndPortalBlockEntity>> END_PORTAL = REGISTER.register(
            "colored_end_portal",
            () -> BlockEntityType.Builder.of(EndPortalBlockEntity::new, SUBlocks.COLORED_END_PORTAL.get()).build(null));

    // one block entity type shared by all seven swap balls (corrupted_dball1..7). the supplier collects the
    // registered block instances lazily, so it resolves after CorruptedBalls has registered them.
    public static final RegistryObject<BlockEntityType<CorruptedBallBlockEntity>> CORRUPTED_BALL = REGISTER.register(
            "corrupted_dball",
            () -> BlockEntityType.Builder.of(CorruptedBallBlockEntity::new, corruptedBallBlocks()).build(null));

    // the decorative time machine block's GeckoLib renderer marker (one block, one type).
    public static final RegistryObject<BlockEntityType<net.shurui.shuruisutilities.timemachine.TimeMachineBlockEntity>>
            TIME_MACHINE = REGISTER.register("time_machine_block",
                    () -> BlockEntityType.Builder.of(
                            net.shurui.shuruisutilities.timemachine.TimeMachineBlockEntity::new,
                            net.shurui.shuruisutilities.timemachine.TimeMachineBlocks.TIME_MACHINE_BLOCK.get())
                            .build(null));

    // one type for every SU crate block: which crate it is comes from the block, so they share an implementation.
    public static final RegistryObject<BlockEntityType<net.shurui.shuruisutilities.crate.block.SuCrateBlockEntity>>
            SU_CRATE = REGISTER.register("su_crate",
                    () -> BlockEntityType.Builder.of(
                            net.shurui.shuruisutilities.crate.block.SuCrateBlockEntity::new,
                            net.shurui.shuruisutilities.crate.block.SuCrateBlocks.blocks()).build(null));

    private static Block[] corruptedBallBlocks()
    {
        List<Block> blocks = new ArrayList<>();
        for (int star = 1; star <= CorruptedBalls.COUNT; star++)
            blocks.add(CorruptedBalls.BALLS[star].get());
        return blocks.toArray(new Block[0]);
    }
}
