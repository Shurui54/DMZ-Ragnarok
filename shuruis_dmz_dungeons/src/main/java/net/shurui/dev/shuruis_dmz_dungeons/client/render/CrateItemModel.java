package net.shurui.dev.shuruis_dmz_dungeons.client.render;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.dev.shuruis_dmz_dungeons.item.CrateBlockItem;

import software.bernie.geckolib.model.GeoModel;

/**
 * Points a dungeon crate ITEM at its art.
 *
 * <p>The look lives on the STACK, not the item, so the name has to come from somewhere GeckoLib's model hooks
 * cannot see: they are handed the animatable, and both crate blocks share one item across every variant. The
 * renderer sets the stack on this model at the top of each render, the one place the stack is known, and all
 * three lookups then agree on one name.
 *
 * <p>Geometry, texture and animation MUST come from the same name. GeckoLib crashes rather than skipping when an
 * animation moves a bone the model lacks, and a colour crate's rig has nothing in common with the dungeon set's,
 * so resolving the animation from anything but the current look would take the client down.
 */
public final class CrateItemModel extends GeoModel<CrateBlockItem>
{
    private String name = "crate_common_bronze";

    /** Called by the renderer before anything is drawn, while the stack is still in hand. */
    void setStack(ItemStack stack)
    {
        this.name = CrateBlockItem.modelName(stack);
    }

    /** The look currently being drawn, for the renderer's animation instance id. */
    String currentName()
    {
        return name;
    }

    @Override
    public ResourceLocation getModelResource(CrateBlockItem crate)
    {
        return new ResourceLocation(Shuruis_dmz_dungeons.MODID, "geo/block/crate/" + name + ".geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(CrateBlockItem crate)
    {
        return new ResourceLocation(Shuruis_dmz_dungeons.MODID, "textures/block/crate/" + name + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(CrateBlockItem crate)
    {
        return new ResourceLocation(Shuruis_dmz_dungeons.MODID,
                "animations/block/crate/" + name + ".animation.json");
    }
}
