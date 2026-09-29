package net.shurui.dev.shuruis_dmz_dungeons.client.render;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import net.shurui.dev.shuruis_dmz_dungeons.block.CrateBlock;
import net.shurui.dev.shuruis_dmz_dungeons.block.CrateBlockEntity;
import net.shurui.dev.shuruis_dmz_dungeons.client.ClientCrateRolls;

import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * Draws the animated crates.
 *
 * <p>Rotation is applied here rather than left to GeckoLib's block facing, because a crate turns in sixteen steps
 * not four: it is furniture and should sit at an angle into a corner. The model is spun about its block's centre
 * so the turn does not walk it off position.
 *
 * <p>Crates are shrunk to fit their block. The models are furniture sized, 1.4 to 1.7 blocks across, so at built
 * size they reach into whatever stands beside them. {@link CrateFit} measures each model once and this draws it
 * at that factor: uniform (proportions kept) and a drawing transform only, no model file touched.
 */
public class CrateRenderer extends GeoBlockRenderer<CrateBlockEntity> {

    public CrateRenderer() {
        super(new CrateGeoModel());
    }

    @Override
    public void preRender(PoseStack poseStack, CrateBlockEntity crate,
                          software.bernie.geckolib.cache.object.BakedGeoModel model, MultiBufferSource buffer,
                          com.mojang.blaze3d.vertex.VertexConsumer vertexConsumer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, float red, float green, float blue,
                          float alpha) {
        if (!isReRender) {
            poseStack.translate(0.5D, 0.0D, 0.5D);
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(
                    CrateBlock.rotationDegrees(crate.getBlockState())));
            // shrink to fit the block, about its centre line and floor, so a crate never reaches into what is
            // beside or above it. Uniform and drawing-time only; the models are untouched.
            float fit = CrateFit.scaleFor(model);
            if (fit < 1.0f) {
                poseStack.scale(fit, fit, fit);
            }
            poseStack.translate(-0.5D, 0.0D, -0.5D);
        }
        super.preRender(poseStack, crate, model, buffer, vertexConsumer, isReRender, partialTick, packedLight,
                packedOverlay, red, green, blue, alpha);
    }

    // GeckoLib's default block rotation is the four way one derived from a facing property. The crates carry their
    // own sixteen step ROTATION and apply it above, so this must not turn them a second time.
    @Override
    protected void rotateBlock(net.minecraft.core.Direction facing, PoseStack poseStack) {
    }

    /**
     * The crate, and then whatever is spinning above it.
     *
     * <p>Hooked on {@code renderFinal} rather than {@code render}, which cannot be overridden here: GeckoLib
     * declares render with a raw BlockEntity while BlockEntityRenderer declares it with the type parameter, and the
     * two erase to the same signature, so a subclass declaring either clashes with the other. renderFinal runs once
     * per crate after its model is drawn.
     *
     * <p>The pose here is the BLOCK's, before the sixteen-step turn preRender applies, so the floating item does
     * not inherit the crate's angle.
     */
    @Override
    public void renderFinal(PoseStack poseStack, CrateBlockEntity crate,
                            software.bernie.geckolib.cache.object.BakedGeoModel model, MultiBufferSource buffer,
                            com.mojang.blaze3d.vertex.VertexConsumer vertexConsumer, float partialTick,
                            int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        super.renderFinal(poseStack, crate, model, buffer, vertexConsumer, partialTick, packedLight, packedOverlay,
                red, green, blue, alpha);
        ClientCrateRolls.Roll roll = ClientCrateRolls.at(crate.getBlockPos());
        if (roll != null) {
            renderRoll(roll, partialTick, poseStack, buffer, packedLight);
        }
    }

    // one item, floating above the crate, turning on the spot. It rises as it settles and hangs there for a moment
    // on the real reward, which the player already has.
    private void renderRoll(ClientCrateRolls.Roll roll, float partialTick, PoseStack poseStack,
                            MultiBufferSource buffer, int packedLight) {
        net.minecraft.world.item.ItemStack stack = roll.current(partialTick);
        if (stack.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float t = roll.progress(partialTick);
        long time = mc.level == null ? 0L : mc.level.getGameTime();
        float bob = (float) Math.sin((time + partialTick) * 0.12f) * 0.03f;

        poseStack.pushPose();
        // rises through the roll and keeps clear of the crate lid, which the open animation throws upward.
        poseStack.translate(0.5D, 1.35D + 0.35D * t + bob, 0.5D);

        poseStack.pushPose();
        // spins fast while it is cycling and eases to a slow drift once it has landed, so the stop reads as a stop.
        float spin = (time + partialTick) * (12.0f - 9.0f * t);
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(spin));
        poseStack.scale(0.75f, 0.75f, 0.75f);
        mc.getItemRenderer().renderStatic(stack, net.minecraft.world.item.ItemDisplayContext.GROUND,
                packedLight, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                poseStack, buffer, mc.level, 0);
        poseStack.popPose();

        renderLabel(stack, poseStack, buffer, packedLight);
        poseStack.popPose();
    }

    /**
     * The reward's name, and its count when more than one, over the floating item. Drawn OUTSIDE the item's spin
     * so it stays readable rather than turning edge on, and billboarded to the camera like a nameplate. The pose is
     * already at the item, so this only has to rise above it.
     */
    private void renderLabel(net.minecraft.world.item.ItemStack stack, PoseStack poseStack,
                             MultiBufferSource buffer, int packedLight) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.network.chat.Component name = stack.getHoverName();
        if (stack.getCount() > 1) {
            name = net.minecraft.network.chat.Component.empty().append(name)
                    .append(net.minecraft.network.chat.Component.literal(" x" + stack.getCount()));
        }

        poseStack.pushPose();
        poseStack.translate(0.0D, 0.4D, 0.0D);
        poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        // the font draws at one unit per pixel, so it has to come down to world scale, and Y is flipped.
        poseStack.scale(-0.02f, -0.02f, 0.02f);

        var font = mc.font;
        float width = -font.width(name) / 2.0f;
        // the shadowed pass sits over a faint backdrop, exactly as vanilla nameplates do, so a pale reward name
        // stays legible against a pale wall.
        int backdrop = (int) (mc.options.getBackgroundOpacity(0.25f) * 255.0f) << 24;
        font.drawInBatch(name, width, 0.0f, 0x20FFFFFF, false, poseStack.last().pose(), buffer,
                net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, backdrop, packedLight);
        font.drawInBatch(name, width, 0.0f, -1, false, poseStack.last().pose(), buffer,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, packedLight);
        poseStack.popPose();
    }

    @Override
    public RenderType getRenderType(CrateBlockEntity crate, net.minecraft.resources.ResourceLocation texture,
                                    MultiBufferSource buffer, float partialTick) {
        // TRANSLUCENT, not cutout. Cutout tests alpha against a threshold and discards everything below it, so a
        // half-alpha pixel comes out fully opaque. The crate needs both: its trim and chains are cut out (alpha 0,
        // either type handles that), but the glow planes inside the chest are drawn at partial alpha (measured: 64,
        // 97, 165, 205 of 255), which cutout painted solid, so an open crate's inside read as a painted board
        // rather than a light. Translucent blends them as authored.
        return RenderType.entityTranslucent(texture);
    }
}
