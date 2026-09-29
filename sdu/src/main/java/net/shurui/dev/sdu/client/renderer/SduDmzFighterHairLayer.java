package net.shurui.dev.sdu.client.renderer;

import com.dragonminez.client.render.hair.HairRenderer;
import com.dragonminez.common.hair.CustomHair;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzHair;
import net.shurui.dev.sdu.entity.SduDmzFighter;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.util.RenderUtils;

/**
 * Draws the {@link SduDmzFighter}'s DMZ hairstyle at its {@code head} bone - the same technique as the Custom
 * NPC hair layer (translate to the head pivot, {@code HairRenderer.render} with null character/stats/player,
 * then re-select the body render type so the body texture isn't corrupted).
 */
public class SduDmzFighterHairLayer extends GeoRenderLayer<SduDmzFighter> {

    public SduDmzFighterHairLayer(GeoRenderer<SduDmzFighter> renderer) {
        super(renderer);
    }

    @Override
    public void renderForBone(PoseStack poseStack, SduDmzFighter animatable, GeoBone bone, RenderType renderType,
                              MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
                              int packedLight, int packedOverlay) {
        if (bone == null || !"head".equals(bone.getName()) || animatable == null) {
            return;
        }
        String code = animatable.getHairCode();
        if (code == null || code.isEmpty()) {
            return;
        }
        CustomHair hair = DmzHair.decode(code);
        if (hair == null || hair.isEmpty()) {
            return;
        }
        float[] override = DmzHair.parseHex(animatable.getHairColor());
        boolean force = override != null;
        float[] rgb = force ? override : DmzHair.globalRgb(hair);

        poseStack.pushPose();
        try {
            RenderUtils.translateToPivotPoint(poseStack, (CoreGeoBone) bone);
            HairRenderer.render(poseStack, bufferSource, hair, hair, 0f, null, null, null, rgb, rgb,
                    force, force, partialTick, packedLight, OverlayTexture.NO_OVERLAY, 1f, 0f, 0f);
            bufferSource.getBuffer(renderType); // re-select body render type (see SduHairLayer)
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Fighter hair render failed: {}", DmzNpc.MODID, t.toString());
        }
        poseStack.popPose();
    }
}
