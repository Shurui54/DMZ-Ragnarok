package net.shurui.shuruisutilities.crate.block;

import java.util.function.Consumer;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * The item form of an SU crate, drawn as the crate itself.
 *
 * <p>A crate is a GeckoLib model, and a plain block item has no way to show one: its inventory icon came from a flat
 * {@code item/generated} model pointed at the crate's texture SHEET, which is an unwrapped atlas rather than a
 * picture of a crate, so every Toffy crate read as a smear of colour and the twenty Lootcrates boxes were told apart
 * only by their names. This hands the item to a {@link net.shurui.shuruisutilities.client.crate.SuCrateItemRenderer},
 * which draws the same model the placed block draws.
 *
 * <p>The idle animation runs here too, for the same reason it runs on the block: it is what holds the effect bones
 * at zero scale. A crate drawn with no animation at all would show the markers the artwork parks beside it.
 */
public class SuCrateBlockItem extends BlockItem implements GeoItem
{
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public SuCrateBlockItem(Block block, Properties properties)
    {
        super(block, properties);
    }

    /** Geometry and animation name, taken from the block so the item and the placed crate can never disagree. */
    public String modelName()
    {
        return getBlock() instanceof SuCrateBlock crate ? crate.modelName() : "toffy_crate_raven";
    }

    /** Texture path under assets/dmz_ragnarok/textures/, without the extension. */
    public String texturePath()
    {
        return getBlock() instanceof SuCrateBlock crate ? crate.texturePath() : "block/crate/toffy_crate_raven";
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
            // Built on first use, not here: a GeoItemRenderer reaches into Minecraft's render dispatcher, which does
            // not exist yet while items are being registered.
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer()
            {
                if (renderer == null)
                {
                    renderer = new net.shurui.shuruisutilities.client.crate.SuCrateItemRenderer();
                }
                return renderer;
            }
        });
    }
}
