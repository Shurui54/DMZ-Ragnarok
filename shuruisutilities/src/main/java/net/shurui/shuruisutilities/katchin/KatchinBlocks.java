package net.shurui.shuruisutilities.katchin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.world.item.BlockItem;
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
 * The twelve katchin blocks and their BlockItems.
 *
 * <p>Katchin (the dark, single-colour family): {@code katchin_ore}, {@code deepslate_katchin_ore},
 * {@code katchin_block}. Katchi katchin (the light family, three interchangeable colours blue_grey/orange/cream):
 * {@code katchi_katchin_ore_<colour>}, {@code deepslate_katchi_katchin_ore_<colour>},
 * {@code katchi_katchin_block_<colour>}.
 *
 * <p>Every ore drops ITSELF, ancient-debris style: there is no scrap and no ingot, the dropped ore item is the
 * crafting material. That drop is produced by a "drop self" loot table (asset side), which only fires because these
 * blocks call {@link BlockBehaviour.Properties#requiresCorrectToolForDrops()} and are put in the right harvest tags
 * ({@code minecraft:mineable/pickaxe} plus a needs-tool tag). Without those tags the block would silently drop
 * nothing; this exact trap is documented in {@code corrupted/CorruptedBalls.java}.
 *
 * <p>Both registers attach to the mod event bus from {@link ShuruisUtilities}'s constructor. {@link #BLOCK_ITEMS}
 * keeps registration order so the creative tab can pour them in as one contiguous block.
 */
public final class KatchinBlocks
{
    private KatchinBlocks() {}

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, ShuruisUtilities.MODID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    // BlockItems in registration order (for the creative tab), and a name -> item lookup (for repair ingredients).
    public static final List<RegistryObject<Item>> BLOCK_ITEMS = new ArrayList<>();
    private static final Map<String, RegistryObject<Item>> ITEM_BY_NAME = new LinkedHashMap<>();

    public static final RegistryObject<Block> KATCHIN_ORE =
            block("katchin_ore", () -> new Block(katchinOre(false)));
    public static final RegistryObject<Block> DEEPSLATE_KATCHIN_ORE =
            block("deepslate_katchin_ore", () -> new Block(katchinOre(true)));
    public static final RegistryObject<Block> KATCHIN_BLOCK =
            block("katchin_block", () -> new Block(katchinStorage()));

    // The ore item that doubles as the katchin crafting/repair material.
    public static final RegistryObject<Item> KATCHIN_ORE_ITEM = ITEM_BY_NAME.get("katchin_ore");

    // Indexed by colour (0 blue_grey, 1 orange, 2 cream) so the asteroid stamp can reference the ore blocks directly.
    @SuppressWarnings("unchecked")
    public static final RegistryObject<Block>[] KATCHI_KATCHIN_ORE = new RegistryObject[KatchiKatchinColour.COUNT];
    @SuppressWarnings("unchecked")
    public static final RegistryObject<Block>[] DEEPSLATE_KATCHI_KATCHIN_ORE = new RegistryObject[KatchiKatchinColour.COUNT];

    static
    {
        for (int c = 0; c < KatchiKatchinColour.COUNT; c++)
        {
            String colour = KatchiKatchinColour.NAMES[c];
            MapColor map = colourMap(c);
            KATCHI_KATCHIN_ORE[c] = block("katchi_katchin_ore_" + colour, () -> new Block(katchiKatchinOre(false, map)));
            DEEPSLATE_KATCHI_KATCHIN_ORE[c] =
                    block("deepslate_katchi_katchin_ore_" + colour, () -> new Block(katchiKatchinOre(true, map)));
            block("katchi_katchin_block_" + colour, () -> new Block(katchiKatchinStorage(map)));
        }
    }

    private static RegistryObject<Block> block(String name, Supplier<Block> sup)
    {
        RegistryObject<Block> b = BLOCKS.register(name, sup);
        RegistryObject<Item> i = ITEMS.register(name, () -> new BlockItem(b.get(), new Item.Properties()));
        BLOCK_ITEMS.add(i);
        ITEM_BY_NAME.put(name, i);
        return b;
    }

    private static MapColor colourMap(int colour)
    {
        switch (colour)
        {
            case 1:  return MapColor.COLOR_ORANGE;      // orange
            case 2:  return MapColor.TERRACOTTA_WHITE;  // cream
            default: return MapColor.COLOR_BLUE;        // blue_grey
        }
    }

    // katchin ore: stone/deepslate hosted, tough, needs the correct tool. Dark map colour.
    private static BlockBehaviour.Properties katchinOre(boolean deepslate)
    {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .sound(deepslate ? SoundType.DEEPSLATE : SoundType.STONE)
                .strength(20.0F, 900.0F)
                .requiresCorrectToolForDrops();
    }

    private static BlockBehaviour.Properties katchinStorage()
    {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .sound(SoundType.METAL)
                .strength(24.0F, 1000.0F)
                .requiresCorrectToolForDrops();
    }

    private static BlockBehaviour.Properties katchiKatchinOre(boolean deepslate, MapColor map)
    {
        return BlockBehaviour.Properties.of()
                .mapColor(map)
                .sound(deepslate ? SoundType.DEEPSLATE : SoundType.STONE)
                .strength(30.0F, 1100.0F)
                .requiresCorrectToolForDrops();
    }

    private static BlockBehaviour.Properties katchiKatchinStorage(MapColor map)
    {
        return BlockBehaviour.Properties.of()
                .mapColor(map)
                .sound(SoundType.METAL)
                .strength(34.0F, 1200.0F)
                .requiresCorrectToolForDrops();
    }
}
