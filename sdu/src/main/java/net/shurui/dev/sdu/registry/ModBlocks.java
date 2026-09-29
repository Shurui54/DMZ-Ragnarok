package net.shurui.dev.sdu.registry;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.block.BarrierBlock;
import net.shurui.dev.sdu.block.GravityChamberBlock;
import net.shurui.dev.sdu.shenron.ShenronShrineBlock;
import net.shurui.dev.sdu.shenron.ShrineColor;

import java.util.EnumMap;
import java.util.Map;

/**
 * Block registry: the four Shenron-shrine colour variants plus the two data-carrying blocks
 * ({@code gravity_chamber}, {@code level_barrier}) that share {@code ModBlockEntities}.
 */
public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, DmzNpc.MODID);

    /** The four shrine blocks keyed by colour. Registry names are {@code shenron_shrine_<color>}. */
    public static final Map<ShrineColor, RegistryObject<Block>> SHRINES = new EnumMap<>(ShrineColor.class);

    /** Gravity Chamber (Feature 5): area TP multiplier + shared pool. Stateless full cube. */
    public static final RegistryObject<Block> GRAVITY_CHAMBER = BLOCKS.register("gravity_chamber",
            () -> new GravityChamberBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops()));

    /** Level Barrier (Feature 6): per-block DMZ-level break gate. Stateless full cube. */
    public static final RegistryObject<Block> LEVEL_BARRIER = BLOCKS.register("level_barrier",
            () -> new BarrierBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    // Invisible (INVISIBLE render shape) must not occlude neighbours or cause lighting
                    // artifacts; mirror the shrines / vanilla Barrier.
                    .noOcclusion()
                    // isSuffocating / isViewBlocking both default to "is the collision shape a full block",
                    // true here, so a head inside took vanilla suffocation (inWall) damage, and because that
                    // static check ignores per-entity empty collision, even a player phasing the open gate
                    // suffocated. Pin both false (like glass / vanilla Barrier): block movement only, never
                    // damage. Collision itself is untouched.
                    .isSuffocating((state, level, pos) -> false)
                    .isViewBlocking((state, level, pos) -> false)));

    static {
        // Block items for the two data-carrying blocks (registered on ModItems.ITEMS like the shrines).
        ModItems.ITEMS.register("gravity_chamber",
                () -> new BlockItem(GRAVITY_CHAMBER.get(), new Item.Properties()));
        ModItems.ITEMS.register("level_barrier",
                () -> new BlockItem(LEVEL_BARRIER.get(), new Item.Properties()));

        for (ShrineColor color : ShrineColor.values()) {
            RegistryObject<Block> block = BLOCKS.register(color.blockName(), () -> new ShenronShrineBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_GREEN)
                            .strength(3.0F, 6.0F)
                            .requiresCorrectToolForDrops()
                            .noOcclusion(),
                    color));
            SHRINES.put(color, block);
            ModItems.ITEMS.register(color.blockName(),
                    () -> new BlockItem(block.get(), new Item.Properties()));
        }
    }

    private ModBlocks() {
    }
}
