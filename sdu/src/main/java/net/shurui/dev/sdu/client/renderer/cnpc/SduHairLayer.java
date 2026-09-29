package net.shurui.dev.sdu.client.renderer.cnpc;

import com.dragonminez.client.render.hair.HairRenderer;
import com.dragonminez.common.hair.CustomHair;
import com.goodbird.cnpcgeckoaddon.entity.EntityCustomModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzHair;
import net.shurui.dev.sdu.compat.cnpc.SduHairModelHolder;
import net.minecraft.client.renderer.texture.OverlayTexture;
import com.mojang.blaze3d.vertex.VertexConsumer;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.util.RenderUtils;

/**
 * Renders a DMZ hairstyle on a Custom NPC that carries a hair code, by mirroring DMZ's own
 * {@code DMZHairLayer}: a GeckoLib render layer that, at the {@code head} bone, translates to the bone pivot
 * and calls {@link HairRenderer#render}. The hair code lives on the NPC's display (see {@link SduHairHolder});
 * we decode it to a {@link CustomHair} and draw it statically - passing {@code null} for DMZ's
 * character/stats/player context (all null-guarded in {@code HairRenderer}, which only skips hair physics).
 *
 * <p>Attached to the addon's {@code RenderCustomModel} by {@code RenderCustomModelMixin}. Client-only.</p>
 */
public class SduHairLayer extends GeoRenderLayer<EntityCustomModel> {

    public SduHairLayer(GeoRenderer<EntityCustomModel> renderer) {
        super(renderer);
    }

    @Override
    public void renderForBone(PoseStack poseStack, EntityCustomModel animatable, GeoBone bone, RenderType renderType,
                              MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
                              int packedLight, int packedOverlay) {
        if (bone == null || !"head".equals(bone.getName()) || !(animatable instanceof SduHairModelHolder holder)) {
            return;
        }
        CustomHair hair;
        String code = holder.sdu$getModelHairCode();
        try {
            hair = (code == null || code.isEmpty()) ? null : DmzHair.decode(code);
        } catch (Throwable t) {
            return;
        }
        if (hair == null || hair.isEmpty()) {
            return;
        }

        // Colour: an explicit override (from the tab's colour picker) recolours every strand (forceColor);
        // otherwise fall back to the hair's own global colour and keep each strand's baked colour.
        float[] override = DmzHair.parseHex(holder.sdu$getModelHairColor());
        boolean force = override != null;
        float[] rgb = force ? override : DmzHair.globalRgb(hair);

        poseStack.pushPose();
        try {
            RenderUtils.translateToPivotPoint(poseStack, (CoreGeoBone) bone);
            // Static hair (no physics): null character/stats/player, physicsLodMultiplier 0. HairRenderer
            // null-guards all three, so only the physics pass is skipped; the strands still draw.
            HairRenderer.render(poseStack, bufferSource, hair, hair, 0f, null, null, null, rgb, rgb,
                    force, force, partialTick, packedLight, OverlayTexture.NO_OVERLAY, 1f, 0f, 0f);
            // Re-select the body's render type as the active buffer. The hair drew into its own
            // (hair-texture) RenderType; without this, the rest of the model's geometry keeps writing into
            // the hair buffer, corrupting the body texture. Mirrors DMZ's own DMZHairLayer.
            bufferSource.getBuffer(renderType);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Hair render failed: {}", DmzNpc.MODID, t.toString());
        }
        poseStack.popPose();
    }
}
