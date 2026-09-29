package net.shurui.shuruisutilities.auction;

import net.shurui.shuruisutilities.content.ContentItems;
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
 * Registrations for the auction-house block and its BlockItem. The two DeferredRegisters are added to the mod event
 * bus from {@link ShuruisUtilities}, alongside SU's other registers. The BlockItem is slotted into the shared
 * {@link ContentItems#BLOCKS_MISC} creative-tab list as it registers, exactly like the senzu pots, so it shows up in
 * the creative tabs; that runs when this class is first touched (its register fields are pulled in the SU
 * constructor), before the tab is built.
 */
public final class AuctionRegistry
{
    private AuctionRegistry() {}

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, ShuruisUtilities.MODID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    public static final RegistryObject<Block> AUCTION_BLOCK =
            BLOCKS.register("auction_block", () -> new AuctionBlock(props()));

    public static final RegistryObject<Item> AUCTION_BLOCK_ITEM =
            ITEMS.register("auction_block", () -> new BlockItem(AUCTION_BLOCK.get(), new Item.Properties()));

    static
    {
        ContentItems.BLOCKS_MISC.add(AUCTION_BLOCK_ITEM);
    }

    // a solid decorative block: full cube, needs a pickaxe-grade whack, self-drops via its loot table.
    private static BlockBehaviour.Properties props()
    {
        return BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).sound(SoundType.METAL).strength(3.0F);
    }
}
