package net.shurui.dev.shuruis_dmz_dungeons.client.render;

import net.minecraft.resources.ResourceLocation;

import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.dev.shuruis_dmz_dungeons.block.CrateBlockEntity;
import net.shurui.dev.shuruis_dmz_dungeons.block.CrateMetal;
import net.shurui.dev.shuruis_dmz_dungeons.block.CrateTier;
import net.shurui.dev.shuruis_dmz_dungeons.client.ClientCrateTiers;

import software.bernie.geckolib.model.GeoModel;

/**
 * Picks which of the twelve crate models a viewer sees.
 *
 * <p>A crate does not know its own rarity or metal. Both are worked out here from the crate's position, the
 * floor's refresh window and the viewer's id, the same function the server runs when it hands out the reward.
 * That lets two players stand at one crate, see different crates, and each get the loot their crate was showing,
 * with nothing stored on the block and nothing synced per crate.
 *
 * <p>Geometry, texture and animations share the name (crate_&lt;rarity&gt;_&lt;metal&gt;), so the three lookups
 * are the same string in three folders.
 */
public final class CrateGeoModel extends GeoModel<CrateBlockEntity> {

    private static String name(CrateBlockEntity crate) {
        // a hand-placed colour crate names its whole model, so nothing is derived for it at all.
        String skin = crate.variantSkin();
        if (skin != null && !skin.isEmpty()) {
            return "toffy_crate_" + skin;
        }
        // a crate placed from a specific creative variant keeps the look its placer chose; everything else is
        // derived from where it stands, per viewer, as it always was.
        CrateTier tier = crate.variantTier() >= 0
                ? CrateTier.byOrdinal(crate.variantTier())
                : ClientCrateTiers.tierAt(crate.getBlockPos());
        CrateMetal metal = crate.variantMetal() >= 0
                ? CrateMetal.byOrdinal(crate.variantMetal())
                : ClientCrateTiers.metalAt(crate.getBlockPos());
        return "crate_" + tier.key + "_" + metal.key;
    }

    @Override
    public ResourceLocation getModelResource(CrateBlockEntity crate) {
        return new ResourceLocation(Shuruis_dmz_dungeons.MODID, "geo/block/crate/" + name(crate) + ".geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(CrateBlockEntity crate) {
        return new ResourceLocation(Shuruis_dmz_dungeons.MODID, "textures/block/crate/" + name(crate) + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(CrateBlockEntity crate) {
        return new ResourceLocation(Shuruis_dmz_dungeons.MODID,
                "animations/block/crate/" + name(crate) + ".animation.json");
    }
}
