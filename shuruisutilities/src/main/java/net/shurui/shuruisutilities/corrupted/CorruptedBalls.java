package net.shurui.shuruisutilities.corrupted;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registry for the seven swap blocks (corrupted_dball1..corrupted_dball7) and their BlockItems. These are SU's
 * own blocks, not a DMZ ball set. Both registers are added to the mod event bus from {@link ShuruisUtilities}.
 */
public final class CorruptedBalls
{
    private CorruptedBalls() {}

    public static final int COUNT = 7;

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, ShuruisUtilities.MODID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    // index 0 unused; 1..7 so BALLS[star] reads naturally
    @SuppressWarnings("unchecked")
    public static final RegistryObject<Block>[] BALLS = new RegistryObject[COUNT + 1];

    // Decorative "corrupted shrine": weathered stone shrine with a black-star ball on top. Purely set dressing
    // for the shadow dragon event; plain vanilla-model Block, no block entity / renderer / GUI / use() behaviour.
    // Intentionally NOT added to any creative tab: this is a deliberate secret with no in-game hints. Admins
    // obtain it with /give. Do not "fix" the missing tab. Note the sibling corrupted ball blocks below ARE now
    // shown, on purpose: they are the real "cracked" dragon balls and by explicit request they populate the
    // shuruisutilities dragon_balls creative tab (see ContentTabs). Only this shrine stays hidden.
    public static final RegistryObject<Block> CORRUPTED_SHRINE =
            BLOCKS.register("corrupted_shrine", () -> new Block(shrineProps()));

    static
    {
        ITEMS.register("corrupted_shrine", () -> new BlockItem(CORRUPTED_SHRINE.get(), new Item.Properties()));
    }

    static
    {
        for (int s = 1; s <= COUNT; s++)
        {
            final int star = s;
            final String name = "corrupted_dball" + star;
            RegistryObject<Block> block = BLOCKS.register(name, () -> new CorruptedBallBlock(star, props()));
            BALLS[star] = block;
            ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
        }
    }

    private static BlockBehaviour.Properties props()
    {
        // noOcclusion() is required: the GeckoLib-rendered ball is not a full opaque cube, so without this the
        // engine culls the neighbour faces behind and below it and you see through the world into the caves.
        // this mirrors DMZ's own DragonBallBlock properties.
        return BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).sound(SoundType.AMETHYST)
                .strength(1.5F, 6.0F).lightLevel(s -> 7).noOcclusion();
    }

    private static BlockBehaviour.Properties shrineProps()
    {
        // noOcclusion() is REQUIRED: the shrine model is taller than one block and non-cubic, so without this the
        // engine culls the neighbour faces behind/below it and you see through the world. This exact bug already
        // bit the corrupted ball block above; mirrors the sdu:shenron_shrine reference properties.
        // requiresCorrectToolForDrops() only yields the loot table when mined with the correct tool, which in
        // 1.20.1 is decided by the minecraft:mineable/* block tags. This block is put into minecraft:mineable/pickaxe
        // (data/minecraft/tags/blocks/mineable/pickaxe.json, replace:false) and in NO needs_*_tool tag, so any
        // pickaxe tier counts as correct and it drops itself. Without that tag it would silently drop nothing.
        return BlockBehaviour.Properties.of().mapColor(MapColor.STONE).sound(SoundType.STONE)
                .strength(3.0F, 6.0F).requiresCorrectToolForDrops().noOcclusion();
    }
}
