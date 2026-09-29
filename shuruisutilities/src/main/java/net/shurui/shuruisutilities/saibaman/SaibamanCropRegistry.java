package net.shurui.shuruisutilities.saibaman;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.content.ContentItems;
import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Registrations for the saibaman seed crop: the {@link SaibamanSeedItem}, the {@link SaibamanCropBlock} ground crop
 * it plants, and the crop's tick-counter block-entity type. The three DeferredRegisters are attached to the mod event
 * bus from {@link net.shurui.shuruisutilities.core.ShuruisUtilities}, alongside SU's other registers.
 *
 * <p>Kept separate from {@link SaibamanPetEntities} (which owns the entity type + attributes) so the pet and its
 * plant stay in their own registries, mirroring how the senzu farm keeps items/blocks/block-entities together in
 * {@link net.shurui.shuruisutilities.senzu.SenzuRegistry}. The crop block itself has NO BlockItem: it is placed by
 * the seed, so only the seed goes into a creative tab.
 */
public final class SaibamanCropRegistry
{
    private SaibamanCropRegistry() {}

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, ShuruisUtilities.MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ShuruisUtilities.MODID);

    // the ground crop. A cross-shaped, walk-through plant with four growth stages, placeable only on
    // dragonminez:rocky_dirt (enforced by the block's canSurvive).
    public static final RegistryObject<Block> SAIBAMAN_CROP =
            BLOCKS.register("saibaman_crop", () -> new SaibamanCropBlock(cropProps()));

    // the seed item. An ItemNameBlockItem so its translation key is the item's own registry id
    // (item.shuruisutilities.saibaman_seed) rather than the crop block's, exactly the vanilla wheat-seeds pattern.
    public static final RegistryObject<Item> SAIBAMAN_SEED =
            ITEMS.register("saibaman_seed",
                    () -> new SaibamanSeedItem(SAIBAMAN_CROP.get(), new Item.Properties()));

    // the crop's tick-counter block-entity type. One type, bound to the single crop block.
    public static final RegistryObject<BlockEntityType<SaibamanCropBlockEntity>> SAIBAMAN_CROP_BE =
            BLOCK_ENTITIES.register("saibaman_crop",
                    () -> BlockEntityType.Builder.of(SaibamanCropBlockEntity::new, SAIBAMAN_CROP.get()).build(null));

    /**
     * The buried seed found in the dirt of a Saibaman hut on Vegeta.
     *
     * <p>Inert: it grows nothing and does nothing where it lies. Breaking one is a gamble, and its loot table is
     * where the odds live. It exists because the hut seeds SU already places only happen on procedurally generated
     * planets, through {@code SurfaceStamp}; the Vegeta huts are an authored jigsaw structure that code never
     * touches, which is why none were ever found there. A worldgen processor swaps a small share of each hut's
     * rocky dirt for this, so the huts carry seeds without their templates being rebuilt.
     *
     * <p>Deliberately dirt-strong rather than plant-weak: it is part of a floor, and a block that shattered on a
     * brushed elbow would be looted by accident rather than on purpose.
     */
    public static final RegistryObject<Block> SAIBAMAN_SEED_INERT =
            BLOCKS.register("saibaman_seed_inert", () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.DIRT)
                    .strength(0.6F)
                    .sound(SoundType.GRAVEL)));

    public static final RegistryObject<Item> SAIBAMAN_SEED_INERT_ITEM =
            ITEMS.register("saibaman_seed_inert",
                    () -> new net.minecraft.world.item.BlockItem(SAIBAMAN_SEED_INERT.get(), new Item.Properties()));

    // admin tool that instantly completes a growing saibaman crop (right-click the crop). Staff-gated in its own use()
    // (creative or op level 2 only), so it is inert in survival hands.
    public static final RegistryObject<Item> SAIBAMAN_GROW_TOOL =
            ITEMS.register("saibaman_grow_tool", () -> new SaibamanGrowItem(new Item.Properties()));

    // slot the seed into the CONSUMABLES creative tab (where the senzu seeds also live), and the admin grow tool into
    // the BLOCKS_MISC tab. Called from the SU constructor before the registers attach, so both land in the tab list
    // after the ContentItems placeholders.
    public static void slotIntoTabs()
    {
        ContentItems.CONSUMABLES.add(SAIBAMAN_SEED);
        ContentItems.BLOCKS_MISC.add(SAIBAMAN_GROW_TOOL);
    }

    // crop block properties: a walk-through, instantly-breakable plant on the crop sound. noOcclusion because the
    // cross model never fills the cube, and pushReaction DESTROY so a piston pops it as an item like any plant.
    private static BlockBehaviour.Properties cropProps()
    {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.PLANT)
                .noCollission()
                .noOcclusion()
                .instabreak()
                .sound(SoundType.CROP)
                .pushReaction(PushReaction.DESTROY);
    }
}
