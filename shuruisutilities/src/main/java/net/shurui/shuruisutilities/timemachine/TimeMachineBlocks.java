package net.shurui.shuruisutilities.timemachine;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Registry for the life-size decorative time machine multiblock: the anchor {@link TimeMachineBlock}, the invisible
 * solid {@link TimeMachinePartBlock} that fills the rest of the footprint, and the placement {@link TimeMachineItem}.
 * All three DeferredRegisters attach to the mod event bus from {@link ShuruisUtilities}'s constructor; the anchor's
 * block-entity type is declared in {@link net.shurui.shuruisutilities.block.SUBlockEntities}.
 */
public final class TimeMachineBlocks
{
    private TimeMachineBlocks() {}

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, ShuruisUtilities.MODID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    public static final RegistryObject<Block> TIME_MACHINE_BLOCK =
            BLOCKS.register("time_machine_block", () -> new TimeMachineBlock(anchorProps()));

    // one shared invisible solid filler for every non-anchor cell of the footprint.
    public static final RegistryObject<Block> TIME_MACHINE_PART =
            BLOCKS.register("time_machine_part", () -> new TimeMachinePartBlock(partProps()));

    // the placement item (a custom Item, NOT a BlockItem: it sets the whole multiblock at once).
    public static final RegistryObject<Item> TIME_MACHINE_ITEM =
            ITEMS.register("time_machine_block", () -> new TimeMachineItem(new Item.Properties()));

    // anchor: solid metal, noOcclusion (animated block), noLootTable (the structure teardown drops the one item).
    private static BlockBehaviour.Properties anchorProps()
    {
        return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).sound(SoundType.METAL)
                .strength(2.0F, 6.0F).noOcclusion().noLootTable();
    }

    // part: solid but invisible, noOcclusion, noLootTable. Same hardness so any cell breaks the same way.
    private static BlockBehaviour.Properties partProps()
    {
        return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).sound(SoundType.METAL)
                .strength(2.0F, 6.0F).noOcclusion().noLootTable();
    }
}
