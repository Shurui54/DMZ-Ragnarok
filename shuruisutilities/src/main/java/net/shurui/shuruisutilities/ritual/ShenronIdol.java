package net.shurui.shuruisutilities.ritual;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * The Shenron Idol: the physical trace the eternal dragon leaves behind when a Super Saiyan 5 is made.
 *
 * <p>Placed in the world by the Ragnarok Key's {@code ShenronIdolSpawner} the moment the fusion spends the Earth set,
 * and registered on the dragon radar so it can actually be hunted rather than stumbled upon. Broken by hand it drops as
 * an item, and that item is what a namekian right clicks to restore the set.
 *
 * <p>A block entity rather than a plain block because it draws with DragonMineZ's own Shenron model, scaled down. The
 * block registers are added to the mod event bus from {@link ShuruisUtilities} alongside the corrupted ball ones.
 */
public final class ShenronIdol
{
    private ShenronIdol() {}

    public static final String NAME = "shenron_idol";

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, ShuruisUtilities.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<Block> BLOCK = BLOCKS.register(NAME, () -> new ShenronIdolBlock(props()));

    /**
     * The item form. NOT a plain {@link BlockItem}: right clicking it is the whole ritual, so it needs its own use
     * behaviour. It can still be placed, which is what lets a player put it down somewhere safe while they go and earn
     * the level they need.
     */
    public static final RegistryObject<Item> ITEM =
            ITEMS.register(NAME, () -> new ShenronIdolItem(BLOCK.get(),
                    new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant()));

    /**
     * The Namek counterpart. A separate ITEM, but the same block: it places the {@code dragon=porunga} state, so the
     * ritual, the radar and the recreation prompt all keep dealing with one block.
     */
    public static final String PORUNGA_NAME = "porunga_idol";

    public static final RegistryObject<Item> PORUNGA_ITEM =
            ITEMS.register(PORUNGA_NAME, () -> new ShenronIdolItem(BLOCK.get(),
                    new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant(),
                    ShenronIdolBlock.IdolDragon.PORUNGA));

    public static final RegistryObject<BlockEntityType<ShenronIdolBlockEntity>> BLOCK_ENTITY =
            BLOCK_ENTITIES.register(NAME,
                    () -> BlockEntityType.Builder.of(ShenronIdolBlockEntity::new, BLOCK.get()).build(null));

    // Stone-ish and slow to break, so it reads as something carved rather than dropped, but no tool requirement: a
    // player who has found it should never be turned away for want of the right pickaxe.
    private static BlockBehaviour.Properties props()
    {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GREEN)
                .strength(2.5F, 1200.0F)
                .sound(SoundType.STONE)
                .lightLevel(state -> 7)
                .noOcclusion();
    }
}
