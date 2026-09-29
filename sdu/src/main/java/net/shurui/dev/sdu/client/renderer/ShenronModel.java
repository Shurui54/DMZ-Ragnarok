package net.shurui.dev.sdu.client.renderer;

import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.entity.ShenronDisplayEntity;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeoModel for {@link ShenronDisplayEntity}. Resolves the geo + texture from the entity's synced config values
 * (per shrine colour), falling back to the shipped SDU shenron assets, and picks the animation library that
 * matches the chosen geo (loaded by resource-location string, never classloading DMZ code).
 */
public class ShenronModel extends GeoModel<ShenronDisplayEntity> {

    private static final ResourceLocation FALLBACK_ANIM = ResourceLocation.parse(ShenronDisplayEntity.ANIM);

    @Override
    public ResourceLocation getModelResource(ShenronDisplayEntity animatable) {
        try {
            // Geo path may come from a shrine config saved before the merge (sdu:geo/...); normalize to dmz_ragnarok
            // so it resolves to the moved asset. Non-our paths (dragonminez dragon packs) pass through.
            return ResourceLocation.parse(
                    net.shurui.shuruisutilities.ragnarok.LegacyIds.normalize(animatable.getGeo()));
        } catch (Exception e) {
            return ResourceLocation.parse(ShenronDisplayEntity.DEFAULT_GEO);
        }
    }

    @Override
    public ResourceLocation getTextureResource(ShenronDisplayEntity animatable) {
        try {
            // Texture path may come from a pre-merge shrine config (sdu:textures/...); normalize to dmz_ragnarok.
            return ResourceLocation.parse(
                    net.shurui.shuruisutilities.ragnarok.LegacyIds.normalize(animatable.getTexture()));
        } catch (Exception e) {
            return ResourceLocation.parse(ShenronDisplayEntity.DEFAULT_TEXTURE);
        }
    }

    /**
     * The animation is chosen from the GEO, not fixed: the picker now offers four rigs with disjoint bone names
     * (DMZ shenron, DMZ porunga, our shadow shenron, our HD serpent), and a clip authored against one of them
     * drives nothing on the others, leaving the dragon frozen in its bind pose rather than erroring. See
     * {@link net.shurui.dev.sdu.shenron.ShrineModels}.
     */
    @Override
    public ResourceLocation getAnimationResource(ShenronDisplayEntity animatable) {
        try {
            return ResourceLocation.parse(net.shurui.dev.sdu.shenron.ShrineModels.animationFor(
                    net.shurui.shuruisutilities.ragnarok.LegacyIds.normalize(animatable.getGeo())));
        } catch (Exception e) {
            return FALLBACK_ANIM;
        }
    }
}
