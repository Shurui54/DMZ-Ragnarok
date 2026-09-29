package net.shurui.shuruisutilities.spaceconsole;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * The four decorative space consoles: a full console, a compact console, a hologram projector and a module column.
 *
 * <p>Each is a {@link SpaceConsoleBlock} (horizontal facing, metal, non full, drops itself). Their collision and
 * selection shapes are authored in the NORTH facing to match the visible model bounds and rotated per facing inside
 * the block. Both registers attach to the mod event bus from {@link ShuruisUtilities}'s constructor;
 * {@link #BLOCK_ITEMS} keeps registration order so the creative tab pours them in as one contiguous group.
 */
public final class SpaceConsoleBlocks
{
    private SpaceConsoleBlocks() {}

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, ShuruisUtilities.MODID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    public static final List<RegistryObject<Item>> BLOCK_ITEMS = new ArrayList<>();

    private static BlockBehaviour.Properties props()
    {
        // Metal furniture: firm but breakable by hand-ish, sensible for admin decor. Non full, non occluding.
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .sound(SoundType.METAL)
                .strength(2.0F, 6.0F)
                .noOcclusion();
    }

    // Shapes authored in the NORTH facing, in model units (0..16 maps to one block; values may run past the cell
    // where the model overhangs). box16 divides by 16 for us.

    // CONSOLE_01: full desk (x -13..29, z -1..17) with a thin monitor rising to y 32.
    private static final VoxelShape CONSOLE_A = Shapes.or(
            SpaceConsoleBlock.box16(-13, 0, -1, 29, 16, 17),
            SpaceConsoleBlock.box16(-10, 16, 11, 26, 32, 16));

    // CONSOLE_02: compact slanted console.
    private static final VoxelShape CONSOLE_B =
            SpaceConsoleBlock.box16(-5, 0, -1, 21, 17.3D, 16);

    // HOLOGRAM_PROJECTOR: broad base plate with arms, plus a central emitter column and the floating hologram to y 32.
    private static final VoxelShape HOLOGRAM = Shapes.or(
            SpaceConsoleBlock.box16(-11, 0, -8, 27, 14, 24),
            SpaceConsoleBlock.box16(3, 14, 3, 13, 32, 13));

    // MODULE: a tall two-block column with wider top and bottom slabs.
    private static final VoxelShape MODULE =
            SpaceConsoleBlock.box16(-2, 0, -2, 18, 31.9D, 18);

    public static final RegistryObject<Block> SPACE_CONSOLE_A =
            block("space_console_a", CONSOLE_A);
    public static final RegistryObject<Block> SPACE_CONSOLE_B =
            block("space_console_b", CONSOLE_B);
    public static final RegistryObject<Block> HOLOGRAM_PROJECTOR =
            block("hologram_projector", HOLOGRAM);
    public static final RegistryObject<Block> SPACE_MODULE =
            block("space_module", MODULE);

    private static RegistryObject<Block> block(String name, VoxelShape northShape)
    {
        RegistryObject<Block> b = BLOCKS.register(name, () -> new SpaceConsoleBlock(props(), northShape));
        RegistryObject<Item> i = ITEMS.register(name, () -> new BlockItem(b.get(), new Item.Properties()));
        BLOCK_ITEMS.add(i);
        return b;
    }
}
