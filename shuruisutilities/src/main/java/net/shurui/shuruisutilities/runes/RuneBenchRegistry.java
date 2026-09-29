package net.shurui.shuruisutilities.runes;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Block, block item and menu type for the rune bench. Registers attach to the mod bus in ShuruisUtilities. */
public final class RuneBenchRegistry
{
    private RuneBenchRegistry() {}

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, ShuruisUtilities.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<Block> BENCH = BLOCKS.register("rune_bench",
            () -> new RuneBenchBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(3.5F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.STONE)
                    // The model is a table with open sides and a top at y12, so it must not be treated as a solid
                    // cube: without this it culls the faces of whatever is beside and beneath it and you see
                    // through the world around the bench.
                    .noOcclusion()));

    public static final RegistryObject<Item> BENCH_ITEM = ITEMS.register("rune_bench",
            () -> new BlockItem(BENCH.get(), new Item.Properties()));

    public static final RegistryObject<MenuType<RuneBenchMenu>> MENU = MENUS.register("rune_bench",
            () -> IForgeMenuType.create((id, inv, buf) -> new RuneBenchMenu(id, inv)));
}
