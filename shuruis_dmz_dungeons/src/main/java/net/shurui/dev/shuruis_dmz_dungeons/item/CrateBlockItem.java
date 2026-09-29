package net.shurui.dev.shuruis_dmz_dungeons.item;

import java.util.function.Consumer;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import net.shurui.dev.shuruis_dmz_dungeons.block.CrateBlock;
import net.shurui.dev.shuruis_dmz_dungeons.block.CrateMetal;
import net.shurui.dev.shuruis_dmz_dungeons.block.CrateTier;

import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * The item form of a dungeon crate, drawn as the crate the stack will actually place.
 *
 * <p>Creative offers one entry per look (twelve rarity/metal pairs, seven colour crates), all the same two block items
 * separated only by their {@code CrateVariant} NBT. The LOOK comes off the stack, so the icon is the crate you place.
 * Nothing is derived from position: a stack with no variant shows the common bronze crate rather than rolling one.
 */
public class CrateBlockItem extends BlockItem implements GeoItem
{
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public CrateBlockItem(Block block, Properties properties)
    {
        super(block, properties);
    }

    /**
     * Which crate this stack wears: the whole model name (geometry, texture, animation). A skin names a whole model;
     * otherwise the rarity and metal make the name between them.
     */
    public static String modelName(ItemStack stack)
    {
        CompoundTag variant = stack == null ? null : stack.getTagElement(CrateBlock.VARIANT_TAG);
        if (variant != null)
        {
            String skin = variant.getString("Skin");
            if (!skin.isEmpty())
            {
                return "toffy_crate_" + skin;
            }
            return "crate_" + CrateTier.byOrdinal(variant.getInt("Tier")).key
                    + "_" + CrateMetal.byOrdinal(variant.getInt("Metal")).key;
        }
        return "crate_" + CrateTier.byOrdinal(0).key + "_" + CrateMetal.byOrdinal(0).key;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers)
    {
        controllers.add(new AnimationController<>(this, "crate", 0, state -> state.setAndContinue(IDLE)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache()
    {
        return cache;
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer)
    {
        consumer.accept(new IClientItemExtensions()
        {
            // Built on first use: a GeoItemRenderer reaches into Minecraft's render dispatcher, which does not exist
            // yet while items are being registered.
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer()
            {
                if (renderer == null)
                {
                    renderer = new net.shurui.dev.shuruis_dmz_dungeons.client.render.CrateItemRenderer();
                }
                return renderer;
            }
        });
    }
}
