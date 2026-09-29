package net.shurui.shuruisutilities.block;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Block registry for Shurui's Utilities. Currently the two colored portal fill blocks (a nether-lit and an
 * end-lit variant), placed by the portal system when a portal is given a fill color. Registered to the mod
 * event bus in {@link ShuruisUtilities}.
 */
public final class SUBlocks
{
    private SUBlocks() {}

    public static final DeferredRegister<Block> REGISTER =
            DeferredRegister.create(ForgeRegistries.BLOCKS, ShuruisUtilities.MODID);

    private static BlockBehaviour.Properties portal(int light)
    {
        // Indestructible (like vanilla portals), no collision, translucent (noOcclusion), emits light.
        return BlockBehaviour.Properties.of().noCollission().noOcclusion().lightLevel(s -> light)
                .strength(-1.0F, 3600000.0F).noLootTable();
    }

    public static final RegistryObject<Block> COLORED_NETHER_PORTAL =
            REGISTER.register("colored_nether_portal", () -> new ColoredPortalBlock(portal(11)));

    public static final RegistryObject<Block> COLORED_END_PORTAL =
            REGISTER.register("colored_end_portal", () -> new EndPortalPlaneBlock(portal(15)));
}
