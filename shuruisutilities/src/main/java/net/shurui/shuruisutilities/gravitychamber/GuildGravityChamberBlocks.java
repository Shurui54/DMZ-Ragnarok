package net.shurui.shuruisutilities.gravitychamber;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.content.ContentItems;
import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Registrations for the guild gravity chamber: the block, its BlockItem and its block entity type. All three
 * DeferredRegisters are added to the mod event bus from {@link ShuruisUtilities}. The BlockItem is slotted into the
 * shared {@link ContentItems#BLOCKS_MISC} creative-tab list as this class is first touched (its register fields are
 * pulled in the SU constructor), before the tab is built, exactly like the auction block.
 */
public final class GuildGravityChamberBlocks
{
    private GuildGravityChamberBlocks()
    {
    }

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, ShuruisUtilities.MODID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<Block> GUILD_GRAVITY_CHAMBER =
            BLOCKS.register("guild_gravity_chamber", () -> new GuildGravityChamberBlock(props()));

    public static final RegistryObject<Item> GUILD_GRAVITY_CHAMBER_ITEM =
            ITEMS.register("guild_gravity_chamber",
                    () -> new BlockItem(GUILD_GRAVITY_CHAMBER.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<GuildGravityChamberBlockEntity>> BLOCK_ENTITY =
            BLOCK_ENTITIES.register("guild_gravity_chamber",
                    () -> BlockEntityType.Builder.of(GuildGravityChamberBlockEntity::new,
                            GUILD_GRAVITY_CHAMBER.get()).build(null));

    static
    {
        ContentItems.BLOCKS_MISC.add(GUILD_GRAVITY_CHAMBER_ITEM);
    }

    // a solid, tanky machine block: metal-sounding, needs a pickaxe-grade whack, self-drops via its loot table.
    private static BlockBehaviour.Properties props()
    {
        return BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).sound(SoundType.METAL).strength(4.0F);
    }
}
