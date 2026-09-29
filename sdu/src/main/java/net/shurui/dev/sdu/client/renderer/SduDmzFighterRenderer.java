package net.shurui.dev.sdu.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.shurui.dev.sdu.entity.SduDmzFighter;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Renderer for {@link SduDmzFighter} - a standard GeckoLib entity renderer over {@link SduDmzFighterModel}, so
 * the true-saga-AI fighter draws with our custom model + texture and DMZ's saga animations.
 */
public class SduDmzFighterRenderer extends GeoEntityRenderer<SduDmzFighter> {

    /**
     * Bones on the CNPC-Gecko-Addon default geo ({@code cnpcgeckoaddon:geo/geo_npc.geo.json}) that hold
     * placeholder held-item cubes. They are UV-mapped onto head/body pixels, so if left visible they render as
     * corrupt quads sticking out of the hands. The Gecko addon's own renderer hides them every frame; we mirror
     * that here. Guarded by {@code ifPresent}, so this is a no-op for DMZ-native geo models that lack these bones.
     */
    private static final String[] HIDDEN_BONES = {
            "held_item", "left_held_item", "held_item_locator", "left_held_item_locator"
    };

    public SduDmzFighterRenderer(EntityRendererProvider.Context context) {
        super(context, new SduDmzFighterModel());
        this.shadowRadius = 0.5f;
        this.addRenderLayer(new SduDmzFighterHairLayer(this));
    }

    @Override
    public void preRender(PoseStack poseStack, SduDmzFighter animatable, BakedGeoModel bakedModel,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, float red, float green,
                          float blue, float alpha) {
        super.preRender(poseStack, animatable, bakedModel, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, red, green, blue, alpha);
        // Hide the CNPC default-geo held-item placeholder bones before recursive bone rendering. No-op when the
        // model does not have them (e.g. DMZ-native saga geo).
        for (String bone : HIDDEN_BONES) {
            bakedModel.getBone(bone).ifPresent(b -> b.setHidden(true));
        }
    }
}
